import { describe, expect, it } from "vitest";
import { loadConfig } from "../src/config";
import { dataKeys, decrypt, encrypt, keyedHash, trialRequestHash } from "../src/crypto";
import { call, FakeWorld } from "./fakes";

describe("routing and errors", () => {
  it("answers health checks", async () => {
    const r = await call(new FakeWorld(), "/v1/health");
    expect(r).toMatchObject({ status: 200, body: { ok: true } });
    expect(r.headers.get("cache-control")).toBe("no-store");
  });

  it("uses machine-readable errors for unknown paths and methods", async () => {
    const world = new FakeWorld();
    expect(await call(world, "/v1/nope")).toMatchObject({ status: 404, body: { error: "not_found" } });
    expect(await call(world, "/v1/trial")).toMatchObject({ status: 405, body: { error: "method_not_allowed" } });
  });

  it("turns a broken config into internal_error without leaking details", async () => {
    const world = new FakeWorld();
    world.addPurchase("t");
    const r = await call(world, "/v1/subscription", { purchaseToken: "t" }, { env: { PLAN_ALLOWANCES_USD: "{" } });
    expect(r.status).toBe(500);
    expect(r.body).toEqual({ error: "internal_error", message: "Something went wrong on our side." });
  });

  it("rejects oversized bodies", async () => {
    const r = await call(new FakeWorld(), "/v1/subscription", { purchaseToken: "x".repeat(20_000) });
    expect(r.body.error).toBe("bad_request");
  });
});

describe("config", () => {
  it("reads every amount from vars", async () => {
    const world = new FakeWorld();
    const base = await world.env();
    const c = loadConfig(base);
    expect(c).toMatchObject({
      trialAllowanceUsd: 0.5,
      trialDurationMs: 7 * 86_400_000,
      trialDailyBudgetUsd: 30,
      planAllowances: { transmit_monthly: 4.5, transmit_plus: 9 },
      cronSubrequestBudget: 10,
    });
    expect(loadConfig({ ...base, PLAN_ALLOWANCES_USD: { "transmit_plus/yearly": 100, transmit_plus: 9 } }).planAllowances).toEqual({
      "transmit_plus/yearly": 100,
      transmit_plus: 9,
    });
    expect(() => loadConfig({ ...base, TRIAL_ALLOWANCE_USD: "lots" })).toThrow();
  });
});

describe("crypto", () => {
  it("computes the documented Integrity requestHash", async () => {
    expect(await trialRequestHash("9774d56d682e549c")).toBe("abef3ce8ef9b46415cdb18f7df04ddd97a1f12e4cbf32efebc1ea31fda1417fd");
  });

  it("hashes with purpose separation and round-trips encryption", async () => {
    const keys = await dataKeys("x".repeat(40));
    expect(await keyedHash(keys, "device", "abc")).not.toBe(await keyedHash(keys, "purchase", "abc"));
    expect(await keyedHash(keys, "device", "abc")).toBe(await keyedHash(keys, "device", "abc"));
    const sealed = await encrypt(keys, "token-value");
    expect(sealed).not.toContain("token-value");
    expect(await decrypt(keys, sealed)).toBe("token-value");
    const other = await dataKeys("y".repeat(40));
    await expect(decrypt(other, sealed)).rejects.toThrow();
  });
});
