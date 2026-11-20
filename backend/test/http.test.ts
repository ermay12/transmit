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

describe("pages", () => {
  async function get(path: string, method = "GET") {
    const { handleRequest } = await import("../src/index");
    const world = new FakeWorld();
    return handleRequest(new Request(`https://transmit.test${path}`, { method }), await world.env(), world.deps());
  }

  it("serves the privacy policy as HTML rendered from docs/launch/privacy-policy.md", async () => {
    const r = await get("/privacy");
    expect(r.status).toBe(200);
    expect(r.headers.get("content-type")).toBe("text/html; charset=utf-8");
    expect(r.headers.get("content-security-policy")).toContain("default-src 'none'");
    const page = await r.text();
    expect(page).toMatch(/^<!doctype html>/);
    expect(page).toContain('<meta name="viewport" content="width=device-width, initial-scale=1">');
    expect(page).toContain("<title>Transmit privacy policy</title>");
    expect(page).toContain('<h2 id="7-your-choices-and-rights">7. Your choices and rights</h2>');
    expect(page).toContain('<div class="table"><table>');
    // The developer's details are filled in (a placeholder would show highlighted); drafting notes don't show.
    expect(page).not.toContain("<mark>");
    expect(page).toContain('<a href="mailto:ermay12@gmail.com">ermay12@gmail.com</a>');
    expect(page).not.toContain("<!--");
    expect(page).not.toMatch(/<script|<link|src=/);
  });

  it("links to the policy from the root, and answers HEAD without a body", async () => {
    const home = await get("/");
    expect(home.status).toBe(200);
    expect(await home.text()).toContain('<a href="/privacy">Privacy policy</a>');
    const head = await get("/privacy", "HEAD");
    expect(head.status).toBe(200);
    expect(head.headers.get("content-type")).toBe("text/html; charset=utf-8");
    expect(await head.text()).toBe("");
    const post = await get("/privacy", "POST");
    expect(post.status).toBe(405);
    expect(await post.json()).toMatchObject({ error: "method_not_allowed" });
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
