import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { call, DAY, FakeWorld } from "./fakes";

const TOKEN = "purchase-token-monthly-1";

async function subscribe(world: FakeWorld, body: Record<string, unknown> = {}) {
  return call(world, "/v1/subscription", { purchaseToken: TOKEN, productId: "transmit_monthly", ...body });
}

describe("POST /v1/subscription", () => {
  it("verifies a new purchase, issues a $4.50 key and acknowledges after the key exists", async () => {
    const world = new FakeWorld();
    world.addPurchase(TOKEN);
    const r = await subscribe(world);
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({
      kind: "subscription",
      productId: "transmit_monthly",
      basePlanId: "monthly",
      state: "active",
      newKey: true,
      allowanceUsd: 4.5,
      remainingUsd: 4.5,
      allowanceRenewed: true,
      periodEndsAt: "2026-12-18T12:00:00Z",
      autoRenewing: true,
    });
    const key = world.keys.get(r.body.keyHash as string)!;
    expect(r.body.key).toBe(key.key);
    expect(key).toMatchObject({ limit: 4.5, expires_at: null, workspace_id: "ab9a85f5-3ee7-4578-a4a9-2600970bf7a6" });
    expect(key.name).toMatch(/^transmit-sub-[0-9a-f]{12}$/);
    expect(world.purchases.get(TOKEN)!.acknowledgementState).toBe("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED");
    const create = world.calls.findIndex((c) => c.method === "POST" && c.url.endsWith("/keys"));
    const ack = world.calls.findIndex((c) => c.url.includes(":acknowledge"));
    expect(create).toBeLessThan(ack);

    // The token is stored only hashed and encrypted.
    const row = await env.DB.prepare("SELECT * FROM subscriptions").first<Record<string, unknown>>();
    expect(JSON.stringify(row)).not.toContain(TOKEN);
    expect(row).toMatchObject({ key_hash: key.hash, limit_usd: 4.5, acknowledged: 1, order_id: "GPA.0000-0000-0000-00001" });
  });

  it("keeps the key on a refresh with the matching keyHash, and credits a renewal once", async () => {
    const world = new FakeWorld();
    world.addPurchase(TOKEN);
    const first = await subscribe(world);
    const hash = first.body.keyHash as string;
    world.spend(hash, 3);

    const same = await subscribe(world, { keyHash: hash });
    expect(same.body).toMatchObject({ newKey: false, keyHash: hash, allowanceRenewed: false, remainingUsd: 1.5 });
    expect(same.body.key).toBeUndefined();
    expect(world.keys.get(hash)!.limit).toBe(4.5);

    world.now += 31 * DAY;
    world.renew(TOKEN, "GPA.0000-0000-0000-00001..0", world.now + 30 * DAY);
    const renewed = await subscribe(world, { keyHash: hash });
    expect(renewed.body).toMatchObject({ newKey: false, allowanceRenewed: true, remainingUsd: 4.5 });
    expect(world.keys.get(hash)!.limit).toBe(7.5); // usage 3 + 4.50, no carry-over

    const again = await subscribe(world, { keyHash: hash });
    expect(again.body).toMatchObject({ allowanceRenewed: false, remainingUsd: 4.5 });
    expect(world.keys.get(hash)!.limit).toBe(7.5);
  });

  it("rotates the key on a restore without keyHash, keeping only what's left this period", async () => {
    const world = new FakeWorld();
    world.addPurchase(TOKEN, { acknowledged: true });
    const first = await subscribe(world);
    world.spend(first.body.keyHash as string, 1.25);

    const restored = await call(world, "/v1/subscription", { purchaseToken: TOKEN });
    expect(restored.status).toBe(200);
    expect(restored.body).toMatchObject({ newKey: true, allowanceRenewed: false, remainingUsd: 3.25 });
    expect(restored.body.key).not.toBe(first.body.key);
    expect(world.keys.has(first.body.keyHash as string)).toBe(false);
    expect(world.keys.get(restored.body.keyHash as string)!.limit).toBe(3.25);
    expect(world.count("POST", ":acknowledge")).toBe(0); // already acknowledged

    // A stale keyHash rotates too.
    const stale = await call(world, "/v1/subscription", { purchaseToken: TOKEN, keyHash: first.body.keyHash });
    expect(stale.body.newKey).toBe(true);
    expect(world.keys.size).toBe(1);
  });

  it("upgrades to Plus with CHARGE_FULL_PRICE: same key, full $9.00 at once, old token replaced", async () => {
    const world = new FakeWorld();
    world.addPurchase(TOKEN);
    const monthly = await subscribe(world);
    const hash = monthly.body.keyHash as string;
    world.spend(hash, 4.6); // over the limit (OpenRouter books spending late)

    world.now += 10 * DAY;
    world.setState(TOKEN, "SUBSCRIPTION_STATE_EXPIRED");
    world.addPurchase("plus-token", { productId: "transmit_plus", orderId: "GPA.1111-1111-1111-11111", linked: TOKEN });
    const plus = await call(world, "/v1/subscription", { purchaseToken: "plus-token", productId: "transmit_plus", keyHash: hash });
    expect(plus.status).toBe(200);
    expect(plus.body).toMatchObject({ productId: "transmit_plus", newKey: false, keyHash: hash, allowanceUsd: 9, remainingUsd: 9, allowanceRenewed: true });
    expect(world.keys.get(hash)).toMatchObject({ limit: 13.6, disabled: false });

    const old = await subscribe(world, { keyHash: hash });
    expect(old.status).toBe(402);
    expect(old.body).toMatchObject({ error: "purchase_not_active", state: "replaced" });
    expect(world.keys.get(hash)!.disabled).toBe(false);
  });

  it("an upgrade without the old keyHash gets a new key and the old one is deleted", async () => {
    const world = new FakeWorld();
    world.addPurchase(TOKEN);
    const monthly = await subscribe(world);
    world.addPurchase("plus-token", { productId: "transmit_plus", orderId: "GPA.2", linked: TOKEN });
    const plus = await call(world, "/v1/subscription", { purchaseToken: "plus-token" });
    expect(plus.body).toMatchObject({ newKey: true, remainingUsd: 9 });
    expect(world.keys.has(monthly.body.keyHash as string)).toBe(false);
    expect(world.keys.size).toBe(1);
  });

  it("ends the device's trial when it subscribes (trial key deleted, no carry-over)", async () => {
    const world = new FakeWorld();
    await world.addIntegrity("itok", "abc123");
    const trial = await call(world, "/v1/trial", { androidId: "abc123", integrityToken: "itok" });
    world.addPurchase(TOKEN);
    const r = await subscribe(world, { androidId: "abc123" });
    expect(r.body).toMatchObject({ newKey: true, remainingUsd: 4.5 });
    expect(world.keys.has(trial.body.keyHash as string)).toBe(false);
    const row = await env.DB.prepare("SELECT key_hash, converted_at FROM trials").first();
    expect(row).toMatchObject({ key_hash: null, converted_at: world.now });

    await world.addIntegrity("itok2", "abc123");
    const again = await call(world, "/v1/trial", { androidId: "abc123", integrityToken: "itok2" });
    expect(again.body.error).toBe("trial_already_used");
  });

  it("disables the key when the purchase lapses and re-enables it when payment recovers", async () => {
    const world = new FakeWorld();
    world.addPurchase(TOKEN);
    const first = await subscribe(world);
    const hash = first.body.keyHash as string;

    world.setState(TOKEN, "SUBSCRIPTION_STATE_ON_HOLD");
    const hold = await subscribe(world, { keyHash: hash });
    expect(hold.status).toBe(402);
    expect(hold.body).toMatchObject({ error: "purchase_not_active", state: "on_hold" });
    expect(world.keys.get(hash)!.disabled).toBe(true);

    world.setState(TOKEN, "SUBSCRIPTION_STATE_ACTIVE");
    world.renew(TOKEN, "GPA.0000-0000-0000-00001..0", world.now + 30 * DAY);
    const back = await subscribe(world, { keyHash: hash });
    expect(back.body).toMatchObject({ newKey: false, allowanceRenewed: true, state: "active" });
    expect(world.keys.get(hash)!.disabled).toBe(false);
  });

  it("keeps the AI on for canceled-but-paid and grace-period purchases", async () => {
    const world = new FakeWorld();
    world.addPurchase(TOKEN, { state: "SUBSCRIPTION_STATE_CANCELED", autoRenew: false });
    const canceled = await subscribe(world);
    expect(canceled.body).toMatchObject({ state: "canceled", autoRenewing: false, newKey: true });
    world.setState(TOKEN, "SUBSCRIPTION_STATE_IN_GRACE_PERIOD");
    const grace = await subscribe(world, { keyHash: canceled.body.keyHash });
    expect(grace.body).toMatchObject({ state: "grace", newKey: false, allowanceRenewed: false });
  });

  it.each([
    ["SUBSCRIPTION_STATE_PENDING", "pending"],
    ["SUBSCRIPTION_STATE_PAUSED", "paused"],
    ["SUBSCRIPTION_STATE_EXPIRED", "expired"],
    ["SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED", "canceled_pending"],
  ])("refuses %s without creating anything", async (googleState, state) => {
    const world = new FakeWorld();
    world.addPurchase(TOKEN, { state: googleState });
    const r = await subscribe(world);
    expect(r.status).toBe(402);
    expect(r.body).toMatchObject({ error: "purchase_not_active", state });
    expect(world.keys.size).toBe(0);
    expect(world.count("POST", ":acknowledge")).toBe(0);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM subscriptions").first("n")).toBe(0);
  });

  it("rejects unknown tokens, foreign products and mismatched productIds", async () => {
    const world = new FakeWorld();
    expect((await subscribe(world)).body).toMatchObject({ error: "purchase_invalid", reason: "token_unknown" });
    world.addPurchase("other", { productId: "some_other_sub" });
    expect((await call(world, "/v1/subscription", { purchaseToken: "other" })).body).toMatchObject({
      error: "purchase_invalid",
      reason: "unknown_product",
    });
    world.addPurchase(TOKEN, { productId: "transmit_plus" });
    expect((await subscribe(world)).body).toMatchObject({ error: "purchase_invalid", reason: "product_mismatch" });
    expect(world.keys.size).toBe(0);
  });

  it("still returns the key if acknowledging fails, and schedules a retry within the hour", async () => {
    const world = new FakeWorld();
    world.addPurchase(TOKEN);
    world.failAcks = true;
    const r = await subscribe(world);
    expect(r.status).toBe(200);
    expect(r.body.newKey).toBe(true);
    const row = await env.DB.prepare("SELECT acknowledged, next_check_at FROM subscriptions").first<{ acknowledged: number; next_check_at: number }>();
    expect(row!.acknowledged).toBe(0);
    expect(row!.next_check_at).toBe(world.now + 3_600_000);
  });

  it("reports upstream failures without creating anything", async () => {
    const world = new FakeWorld();
    world.addPurchase(TOKEN);
    world.failCreates = 1;
    const r = await subscribe(world);
    expect(r.status).toBe(502);
    expect(r.body).toMatchObject({ error: "upstream_error", service: "openrouter" });
    expect(world.count("POST", ":acknowledge")).toBe(0);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM subscriptions").first("n")).toBe(0);
  });
});
