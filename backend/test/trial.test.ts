import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { call, DAY, FakeWorld } from "./fakes";

const ANDROID_ID = "9774d56d682e549c";

async function startTrial(world: FakeWorld, androidId = ANDROID_ID, token = `itok-${androidId}`) {
  await world.addIntegrity(token, androidId);
  return call(world, "/v1/trial", { androidId, integrityToken: token });
}

describe("POST /v1/trial", () => {
  it("issues a 7-day $0.50 key in the customer workspace", async () => {
    const world = new FakeWorld();
    const r = await startTrial(world);
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ kind: "trial", allowanceUsd: 0.5, remainingUsd: 0.5, restored: false, expiresAt: "2026-11-25T12:00:00Z" });
    const key = world.keys.get(r.body.keyHash as string)!;
    expect(r.body.key).toBe(key.key);
    expect(key).toMatchObject({ limit: 0.5, expires_at: "2026-11-25T12:00:00Z", workspace_id: "ab9a85f5-3ee7-4578-a4a9-2600970bf7a6" });
    expect(key.name).toMatch(/^transmit-trial-[0-9a-f]{12}$/);

    // Only a keyed hash of the device ID and the key's hash are stored.
    const rows = await env.DB.prepare("SELECT * FROM trials").all();
    expect(rows.results).toHaveLength(1);
    const stored = JSON.stringify(rows.results);
    expect(stored).not.toContain(ANDROID_ID);
    expect(stored).not.toContain(key.key);
    expect(rows.results[0]).toMatchObject({ key_hash: key.hash, allowance_usd: 0.5 });
  });

  it("gives one trial per device: a reinstall during the trial rotates the key, keeping what's left", async () => {
    const world = new FakeWorld();
    const first = await startTrial(world);
    world.spend(first.body.keyHash as string, 0.2);
    world.now += 2 * DAY;

    const again = await startTrial(world, ANDROID_ID, "itok-again");
    expect(again.status).toBe(200);
    expect(again.body).toMatchObject({ restored: true, allowanceUsd: 0.5, remainingUsd: 0.3, expiresAt: first.body.expiresAt });
    expect(again.body.key).not.toBe(first.body.key);
    expect(world.keys.has(first.body.keyHash as string)).toBe(false);
    expect(world.keys.get(again.body.keyHash as string)).toMatchObject({ limit: 0.3, expires_at: "2026-11-25T12:00:00Z" });
  });

  it("refuses a device whose trial has expired or been used up", async () => {
    const world = new FakeWorld();
    const first = await startTrial(world);
    world.spend(first.body.keyHash as string, 0.5);
    const usedUp = await startTrial(world, ANDROID_ID, "itok-2");
    expect(usedUp.status).toBe(409);
    expect(usedUp.body.error).toBe("trial_already_used");

    const other = new FakeWorld();
    await startTrial(other, "aaaa1111bbbb2222");
    other.now += 8 * DAY;
    const expired = await startTrial(other, "aaaa1111bbbb2222", "itok-3");
    expect(expired.body.error).toBe("trial_already_used");
  });

  it.each([
    ["token_invalid", null],
    ["wrong_package", (p: any) => (p.requestDetails.requestPackageName = "com.evil.app")],
    ["request_hash_mismatch", (p: any) => (p.requestDetails.requestHash = "0".repeat(64))],
    ["token_expired", (p: any) => (p.requestDetails.timestampMillis = String(Date.UTC(2026, 10, 18, 11, 0, 0)))],
    ["app_not_recognized", (p: any) => (p.appIntegrity.appRecognitionVerdict = "UNRECOGNIZED_VERSION")],
    ["unlicensed", (p: any) => (p.accountDetails.appLicensingVerdict = "UNLICENSED")],
    ["device_integrity", (p: any) => (p.deviceIntegrity = {})],
  ])("rejects integrity failure %s", async (reason, tweak) => {
    const world = new FakeWorld();
    if (tweak) await world.addIntegrity("tok", ANDROID_ID, tweak);
    const r = await call(world, "/v1/trial", { androidId: ANDROID_ID, integrityToken: "tok" });
    expect(r.status).toBe(403);
    expect(r.body).toMatchObject({ error: "integrity_failed", reason });
    expect(world.keys.size).toBe(0);
  });

  it("binds the token to the androidId it was requested for", async () => {
    const world = new FakeWorld();
    await world.addIntegrity("tok", "1111222233334444");
    const r = await call(world, "/v1/trial", { androidId: ANDROID_ID, integrityToken: "tok" });
    expect(r.body).toMatchObject({ error: "integrity_failed", reason: "request_hash_mismatch" });
  });

  it("caps new trial allowance per UTC day and refunds the slot if key creation fails", async () => {
    const world = new FakeWorld();
    const env = { TRIAL_DAILY_BUDGET_USD: "1.00" };
    for (const id of ["a1", "a2"]) {
      await world.addIntegrity(id, id);
      expect((await call(world, "/v1/trial", { androidId: id, integrityToken: id }, { env })).status).toBe(200);
    }
    await world.addIntegrity("a3", "a3");
    const full = await call(world, "/v1/trial", { androidId: "a3", integrityToken: "a3" }, { env });
    expect(full.status).toBe(429);
    expect(full.body).toMatchObject({ error: "trial_capacity_reached", retryAfterSeconds: 12 * 3600 });
    expect(full.headers.get("retry-after")).toBe(String(12 * 3600));

    world.now += 12 * 3600 * 1000; // next UTC day
    await world.addIntegrity("a3", "a3"); // a fresh token
    world.failCreates = 1;
    const failed = await call(world, "/v1/trial", { androidId: "a3", integrityToken: "a3" }, { env });
    expect(failed.status).toBe(502);
    expect(failed.body).toMatchObject({ error: "upstream_error", service: "openrouter" });
    const day = await env_DB_day("2026-11-19");
    expect(day).toMatchObject({ granted_usd: 0, trials: 0 });

    const ok = await call(world, "/v1/trial", { androidId: "a3", integrityToken: "a3" }, { env });
    expect(ok.status).toBe(200);
    expect(await env_DB_day("2026-11-19")).toMatchObject({ granted_usd: 0.5, trials: 1 });
  });

  it("validates input", async () => {
    const world = new FakeWorld();
    expect((await call(world, "/v1/trial", { androidId: "not valid!", integrityToken: "x" })).body.error).toBe("bad_request");
    expect((await call(world, "/v1/trial", { androidId: ANDROID_ID })).body.error).toBe("bad_request");
    expect((await call(world, "/v1/trial", undefined, { raw: "{not json" })).body.error).toBe("bad_request");
  });

  it("answers busy, without an Integrity check, while another request for the same device holds the lock", async () => {
    const world = new FakeWorld();
    await world.addIntegrity("tok", ANDROID_ID);
    const { keyedHash, dataKeys } = await import("../src/crypto");
    const deviceHash = await keyedHash(await dataKeys(env.DATA_SECRET), "device", ANDROID_ID);
    await env.DB.prepare("INSERT INTO locks (name, until) VALUES (?1, ?2)").bind(`trial:${deviceHash}`, world.now + 10_000).run();
    const r = await call(world, "/v1/trial", { androidId: ANDROID_ID, integrityToken: "tok" });
    expect(r.status).toBe(409);
    expect(r.body.error).toBe("busy");
    expect(world.count("POST", ":decodeIntegrityToken")).toBe(0);
    // The other request's lease is left alone.
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM locks").first("n")).toBe(1);
  });
});

function env_DB_day(day: string) {
  return env.DB.prepare("SELECT granted_usd, trials FROM trial_days WHERE day = ?1").bind(day).first();
}
