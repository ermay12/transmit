import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { clientKey } from "../src/ratelimit";
import { call, cron, DAY, FakeWorld } from "./fakes";

const withIp = (ip?: string) => new Request("https://transmit.test/", { headers: ip ? { "cf-connecting-ip": ip } : {} });
const junkTrial = (androidId: string) => ({ androidId, integrityToken: "junk" });

describe("rate limits", () => {
  it("counts IPv4 addresses one by one and IPv6 by /64", () => {
    expect(clientKey(withIp("203.0.113.9"))).toBe("203.0.113.9");
    expect(clientKey(withIp("2001:db8:abcd:12:1:2:3:4"))).toBe("2001:db8:abcd:12::/64");
    expect(clientKey(withIp("2001:DB8:abcd:0012::1"))).toBe("2001:db8:abcd:12::/64");
    expect(clientKey(withIp("2001:db8::1"))).toBe("2001:db8:0:0::/64");
    expect(clientKey(withIp("::1"))).toBe("0:0:0:0::/64");
    expect(clientKey(withIp())).toBeNull();
  });

  it("answers rate_limited per minute before doing any work", async () => {
    const world = new FakeWorld();
    world.rateLimits = { TRIAL_RATE_LIMIT: 2, SUBSCRIPTION_RATE_LIMIT: 1 };
    for (let i = 0; i < 2; i++) expect((await call(world, "/v1/trial", junkTrial("a1"))).body.error).toBe("integrity_failed");
    const before = world.calls.length;
    const limited = await call(world, "/v1/trial", junkTrial("a1"));
    expect(limited.status).toBe(429);
    expect(limited.body).toMatchObject({ error: "rate_limited", retryAfterSeconds: 60 });
    expect(limited.headers.get("retry-after")).toBe("60");
    expect(world.calls.length).toBe(before); // no Google call

    // Other clients, and the other endpoint, have their own counts.
    expect((await call(world, "/v1/trial", junkTrial("a1"), { ip: "198.51.100.1" })).body.error).toBe("integrity_failed");
    expect((await call(world, "/v1/subscription", { purchaseToken: "t" })).body.error).toBe("purchase_invalid");
    expect((await call(world, "/v1/subscription", { purchaseToken: "t" })).body.error).toBe("rate_limited");
  });

  it("caps /v1/trial calls per client and UTC day without spending Integrity checks", async () => {
    const world = new FakeWorld();
    const opts = { env: { TRIAL_CALLS_PER_IP_PER_DAY: "2" }, ip: "2001:db8:1:2:aaaa::1" };
    await world.addIntegrity("tok", "dev1");
    expect((await call(world, "/v1/trial", { androidId: "dev1", integrityToken: "tok" }, opts)).status).toBe(200);
    expect((await call(world, "/v1/trial", junkTrial("dev2"), opts)).body.error).toBe("integrity_failed");
    const decodes = world.count("POST", ":decodeIntegrityToken");

    // Another address in the same /64.
    const limited = await call(world, "/v1/trial", junkTrial("dev3"), { ...opts, ip: "2001:db8:1:2:bbbb::7" });
    expect(limited.status).toBe(429);
    expect(limited.body).toMatchObject({ error: "rate_limited", retryAfterSeconds: 12 * 3600 });
    expect(limited.headers.get("retry-after")).toBe(String(12 * 3600));
    expect(world.count("POST", ":decodeIntegrityToken")).toBe(decodes);

    // The limited call released the device's lease, and other networks aren't affected.
    expect((await call(world, "/v1/trial", junkTrial("dev3"), { ...opts, ip: "198.51.100.2" })).body.error).toBe("integrity_failed");
    // Only a keyed hash of the network is stored.
    const rows = await env.DB.prepare("SELECT * FROM trial_calls").all();
    expect(JSON.stringify(rows.results)).not.toMatch(/2001|198\.51/);

    world.now += 12 * 3600_000; // the next UTC day
    expect((await call(world, "/v1/trial", junkTrial("dev3"), opts)).body.error).toBe("integrity_failed");

    // The job deletes counts older than yesterday.
    world.now += 2 * DAY;
    await cron(world);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM trial_calls").first("n")).toBe(0);
  });
});

describe("Google access token", () => {
  it("is shared between isolates through D1, encrypted, until it expires", async () => {
    const world = new FakeWorld();
    world.addPurchase("t");
    expect((await call(world, "/v1/subscription", { purchaseToken: "t" })).status).toBe(200);
    expect(world.tokenExchanges).toBe(1);
    const stored = await env.DB.prepare("SELECT value FROM job_state WHERE name = 'google_token'").first<string>("value");
    expect(stored).toBeTruthy();
    expect(stored).not.toContain("ya29");

    world.tokenCache = {}; // another isolate
    await world.addIntegrity("itok", "dev1");
    expect((await call(world, "/v1/trial", { androidId: "dev1", integrityToken: "itok" })).status).toBe(200);
    world.tokenCache = {};
    await cron(world);
    expect(world.tokenExchanges).toBe(1);

    world.now += 3600_000; // expired
    world.tokenCache = {};
    expect((await call(world, "/v1/subscription", { purchaseToken: "t" })).status).toBe(200);
    expect(world.tokenExchanges).toBe(2);
  });
});
