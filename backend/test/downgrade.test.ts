// Switching Transmit Plus -> Transmit with ReplacementMode.DEFERRED. The fake's purchase shapes follow
// Google's docs: developer.android.com/google/play/billing/subscriptions#handle-deferred-replacement
// and codelabs.developers.google.com/codelabs/play-billing-subs-replacement (see FakeWorld.deferredSwitch).

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { call, cron, DAY, FakeWorld } from "./fakes";

const A = "plus-token-a"; // the Plus purchase
const B = "switch-token-b"; // the DEFERRED switch to Transmit
const HOUR = 3_600_000;

function iso(ms: number): string {
  return new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z");
}

/** Buys Plus; returns the key hash and the end of its period. */
async function buyPlus(world: FakeWorld): Promise<{ hash: string; plusEnds: number }> {
  const plusEnds = world.now + 30 * DAY;
  world.addPurchase(A, { productId: "transmit_plus", orderId: "GPA.1111-1111-1111-11111", expiry: plusEnds });
  const r = await call(world, "/v1/subscription", { purchaseToken: A, productId: "transmit_plus" });
  expect(r.body).toMatchObject({ newKey: true, allowanceUsd: 9 });
  return { hash: r.body.keyHash as string, plusEnds };
}

async function row(token: string) {
  const { keyedHash, dataKeys } = await import("../src/crypto");
  const keys = await dataKeys(env.DATA_SECRET);
  return env.DB.prepare("SELECT * FROM subscriptions WHERE token_hash = ?1")
    .bind(await keyedHash(keys, "purchase", token))
    .first<Record<string, unknown>>();
}

describe("switch to Transmit (DEFERRED)", () => {
  it("keeps Plus, the key and its limit until the renewal, then gives $4.50 a month", async () => {
    const world = new FakeWorld();
    const { hash, plusEnds } = await buyPlus(world);
    world.spend(hash, 2);
    world.now += 10 * DAY;
    world.deferredSwitch(A, B);

    const r = await call(world, "/v1/subscription", { purchaseToken: B, productId: "transmit_monthly", keyHash: hash });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({
      kind: "subscription",
      productId: "transmit_plus",
      basePlanId: "monthly",
      state: "active",
      keyHash: hash,
      newKey: false,
      allowanceUsd: 9,
      remainingUsd: 7,
      allowanceRenewed: false,
      periodEndsAt: iso(plusEnds),
      autoRenewing: true,
      pendingProductId: "transmit_monthly",
      pendingAllowanceUsd: 4.5,
      pendingStartsAt: iso(plusEnds),
    });
    expect(world.keys.get(hash)).toMatchObject({ limit: 9, disabled: false });
    expect(world.keys.size).toBe(1);
    // Acknowledged now (not at the renewal), with the product Play reports for it until then.
    expect(world.purchases.get(B)!.acknowledgementState).toBe("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED");
    expect(world.calls.filter((c) => c.url.includes(":acknowledge")).at(-1)!.url).toContain("/subscriptions/transmit_plus/tokens/");
    expect(await row(B)).toMatchObject({ product_id: "transmit_plus", allowance_usd: 9, pending_product_id: "transmit_monthly", limit_usd: 9 });
    expect(await row(A)).toMatchObject({ state: "replaced", key_hash: null, next_check_at: null });

    // The old token answers "replaced"; the key stays on.
    const old = await call(world, "/v1/subscription", { purchaseToken: A, keyHash: hash, rotate: false });
    expect(old.body).toMatchObject({ error: "purchase_not_active", state: "replaced" });
    expect(world.keys.get(hash)!.disabled).toBe(false);

    // Refreshes before the renewal change nothing; either productId is accepted.
    for (const productId of ["transmit_plus", "transmit_monthly", undefined]) {
      const again = await call(world, "/v1/subscription", { purchaseToken: B, productId, keyHash: hash, rotate: false });
      expect(again.body).toMatchObject({ allowanceRenewed: false, remainingUsd: 7, pendingProductId: "transmit_monthly" });
    }
    expect(world.keys.get(hash)!.limit).toBe(9);

    // The renewal: Transmit starts, and the limit becomes usage + $4.50.
    world.spend(hash, 1);
    world.now = plusEnds + 60_000;
    world.completeDeferred(B, "GPA.2222-2222-2222-22222..0", world.now + 30 * DAY);
    const renewed = await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash, rotate: false });
    expect(renewed.body).toMatchObject({
      productId: "transmit_monthly",
      state: "active",
      newKey: false,
      allowanceUsd: 4.5,
      remainingUsd: 4.5,
      allowanceRenewed: true,
      autoRenewing: true,
      periodEndsAt: iso(world.now + 30 * DAY),
    });
    expect(renewed.body).not.toHaveProperty("pendingProductId");
    expect(world.keys.get(hash)).toMatchObject({ limit: 7.5, disabled: false }); // usage 3 + 4.50
    expect(await row(B)).toMatchObject({ product_id: "transmit_monthly", allowance_usd: 4.5, pending_product_id: null });

    const after = await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });
    expect(after.body).toMatchObject({ allowanceRenewed: false, remainingUsd: 4.5 });
  });

  it("the job credits the switch at the first Transmit renewal", async () => {
    const world = new FakeWorld();
    const { hash, plusEnds } = await buyPlus(world);
    world.now += 10 * DAY;
    world.deferredSwitch(A, B);
    await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });
    world.spend(hash, 5);

    world.now += HOUR;
    expect((await cron(world)).subscriptionsChecked).toBe(0); // nothing due before the period ends

    world.now = plusEnds + 10 * 60_000;
    world.completeDeferred(B, "GPA.2222-2222-2222-22222..0", world.now + 30 * DAY);
    const report = await cron(world);
    expect(report.outcomes).toEqual(["active:renewed"]);
    expect(world.keys.get(hash)).toMatchObject({ limit: 9.5, disabled: false }); // usage 5 + 4.50
    expect(await row(B)).toMatchObject({ product_id: "transmit_monthly", allowance_usd: 4.5, pending_product_id: null, auto_renewing: 1 });
  });

  it("the old token showing the replacement before the new one arrives keeps the key on", async () => {
    const world = new FakeWorld();
    const { hash, plusEnds } = await buyPlus(world);
    world.spend(hash, 1);
    world.now += 10 * DAY;
    world.deferredSwitch(A, B);

    // A background refresh with the old token reaches the Worker first.
    const old = await call(world, "/v1/subscription", { purchaseToken: A, keyHash: hash, rotate: false });
    expect(old.status).toBe(402);
    expect(old.body).toMatchObject({ error: "purchase_not_active", state: "replaced" });
    expect(world.keys.get(hash)).toMatchObject({ limit: 9, disabled: false });
    expect(await row(A)).toMatchObject({
      state: "replaced",
      replaced_by: null,
      key_hash: hash,
      key_disabled: 0,
      expiry_time: plusEnds,
      next_check_at: plusEnds + 72 * HOUR,
    });
    expect((await cron(world)).subscriptionsChecked).toBe(0);

    const r = await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });
    expect(r.body).toMatchObject({ newKey: false, keyHash: hash, remainingUsd: 8, allowanceRenewed: false, pendingProductId: "transmit_monthly" });
    expect(await row(A)).toMatchObject({ state: "replaced", key_hash: null, next_check_at: null });
  });

  it("the job sees the old token's replacement first, keeps the key on, and a late new token is credited", async () => {
    const world = new FakeWorld();
    const { hash, plusEnds } = await buyPlus(world);
    world.spend(hash, 6);
    world.now = plusEnds - HOUR;
    world.deferredSwitch(A, B);
    world.now = plusEnds + 10 * 60_000;
    world.completeDeferred(B, "GPA.2222-2222-2222-22222..0", world.now + 30 * DAY);

    expect((await cron(world)).outcomes).toEqual(["replaced:awaiting_successor"]);
    expect(world.keys.get(hash)).toMatchObject({ limit: 9, disabled: false });

    // The app sends the new token only after the renewal: same key, and this month's $4.50.
    const r = await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });
    expect(r.body).toMatchObject({ productId: "transmit_monthly", newKey: false, allowanceRenewed: true, remainingUsd: 4.5, allowanceUsd: 4.5 });
    expect(world.keys.get(hash)!.limit).toBe(10.5); // usage 6 + 4.50
  });

  it("turns the key off if the new token never arrives", async () => {
    const world = new FakeWorld();
    const { hash, plusEnds } = await buyPlus(world);
    world.now += 10 * DAY;
    world.deferredSwitch(A, B);
    world.now = plusEnds + 10 * 60_000;
    expect((await cron(world)).outcomes).toEqual(["replaced:awaiting_successor"]);
    world.now += 71 * HOUR;
    expect((await cron(world)).subscriptionsChecked).toBe(0);
    expect(world.keys.get(hash)!.disabled).toBe(false);

    world.now += 2 * HOUR;
    expect((await cron(world)).outcomes).toEqual(["replaced:disabled"]);
    expect(world.keys.get(hash)!.disabled).toBe(true);
    expect(await row(A)).toMatchObject({ state: "replaced", key_disabled: 1, next_check_at: null });
    expect((await call(world, "/v1/subscription", { purchaseToken: A, keyHash: hash })).body).toMatchObject({ state: "replaced" });
  });

  it("switching back to Plus before the renewal carries on the period: no second allowance", async () => {
    const world = new FakeWorld();
    const { hash, plusEnds } = await buyPlus(world);
    world.spend(hash, 2);
    world.now += 10 * DAY;
    world.deferredSwitch(A, B);
    await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });

    // The user cancels the pending switch by buying Plus again over the new token (no charge now).
    world.now += DAY;
    world.addPurchase("revert-c", { productId: "transmit_plus", orderId: "GPA.3333-3333-3333-33333", expiry: plusEnds, linked: B, replacementMode: "DEFERRED" });
    world.setState(B, "SUBSCRIPTION_STATE_EXPIRED");
    world.purchases.get(B)!.canceledStateContext = { replacementCancellation: {} };
    const c = await call(world, "/v1/subscription", { purchaseToken: "revert-c", productId: "transmit_plus", keyHash: hash });
    expect(c.body).toMatchObject({ productId: "transmit_plus", newKey: false, keyHash: hash, allowanceRenewed: false, remainingUsd: 7, autoRenewing: true });
    expect(c.body).not.toHaveProperty("pendingProductId");
    expect(world.keys.get(hash)!.limit).toBe(9);
    expect((await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash })).body).toMatchObject({ state: "replaced" });

    // Plus renews as Plus.
    world.now = plusEnds + 60_000;
    world.renew("revert-c", "GPA.3333-3333-3333-33333..0", world.now + 30 * DAY);
    const renewed = await call(world, "/v1/subscription", { purchaseToken: "revert-c", keyHash: hash });
    expect(renewed.body).toMatchObject({ allowanceRenewed: true, remainingUsd: 9, allowanceUsd: 9 });
  });

  it("keeps Plus current even if Google gives the not-yet-started item an expiryTime", async () => {
    const world = new FakeWorld();
    const { hash, plusEnds } = await buyPlus(world);
    world.now += 10 * DAY;
    const p = world.deferredSwitch(A, B);
    p.lineItems![0]!.expiryTime = new Date(plusEnds + 30 * DAY).toISOString(); // not documented, but harmless
    const r = await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });
    expect(r.body).toMatchObject({ productId: "transmit_plus", allowanceUsd: 9, remainingUsd: 9, allowanceRenewed: false, pendingProductId: "transmit_monthly" });
    expect(world.keys.get(hash)!.limit).toBe(9);
  });

  it("a pending switch that Google drops from the same token disappears from the response", async () => {
    const world = new FakeWorld();
    const { hash } = await buyPlus(world);
    world.now += 10 * DAY;
    const p = world.deferredSwitch(A, B);
    await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });

    const plus = p.lineItems![1]!;
    delete plus.deferredItemReplacement;
    plus.autoRenewingPlan = { autoRenewEnabled: true };
    p.lineItems = [plus];
    const r = await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });
    expect(r.body).toMatchObject({ productId: "transmit_plus", allowanceRenewed: false, remainingUsd: 9, autoRenewing: true });
    expect(r.body).not.toHaveProperty("pendingProductId");
    expect(await row(B)).toMatchObject({ pending_product_id: null });
  });

  it("cancelling during a pending switch keeps the AI until the period ends, with no switch shown", async () => {
    const world = new FakeWorld();
    const { hash, plusEnds } = await buyPlus(world);
    world.now += 10 * DAY;
    const p = world.deferredSwitch(A, B);
    await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });

    world.setState(B, "SUBSCRIPTION_STATE_CANCELED");
    p.lineItems![0]!.autoRenewingPlan = { autoRenewEnabled: false };
    const r = await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });
    expect(r.body).toMatchObject({ state: "canceled", autoRenewing: false, productId: "transmit_plus", periodEndsAt: iso(plusEnds) });
    expect(r.body).not.toHaveProperty("pendingProductId");
    expect(world.keys.get(hash)!.disabled).toBe(false);

    world.now = plusEnds + 60_000;
    world.setState(B, "SUBSCRIPTION_STATE_EXPIRED");
    const ended = await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });
    expect(ended.body).toMatchObject({ error: "purchase_not_active", state: "expired" });
    expect(world.keys.get(hash)!.disabled).toBe(true);
  });

  it("acknowledges with the new plan's productId if Google refuses the old one", async () => {
    const world = new FakeWorld();
    const { hash } = await buyPlus(world);
    world.deferredSwitch(A, B);
    world.ackOnly.set(B, "transmit_monthly");
    const before = world.count("POST", ":acknowledge");
    const r = await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });
    expect(r.status).toBe(200);
    expect(world.count("POST", ":acknowledge") - before).toBe(2);
    expect(world.purchases.get(B)!.acknowledgementState).toBe("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED");
    expect(await row(B)).toMatchObject({ acknowledged: 1 });
  });

  it("credits Plus's own renewal if it happened before the switch reached the Worker", async () => {
    const world = new FakeWorld();
    const { hash, plusEnds } = await buyPlus(world);
    world.spend(hash, 8);
    world.now = plusEnds + 60_000;
    world.renew(A, "GPA.1111-1111-1111-11111..0", plusEnds + 30 * DAY); // not seen by the app or the job
    world.deferredSwitch(A, B);
    const r = await call(world, "/v1/subscription", { purchaseToken: B, keyHash: hash });
    expect(r.body).toMatchObject({ productId: "transmit_plus", allowanceRenewed: true, remainingUsd: 9, pendingProductId: "transmit_monthly" });
    expect(world.keys.get(hash)!.limit).toBe(17); // usage 8 + 9.00
  });

  it("a resubscribe before expiry doesn't credit the period again", async () => {
    const world = new FakeWorld();
    const expiry = world.now + 30 * DAY;
    world.addPurchase("t1", { state: "SUBSCRIPTION_STATE_CANCELED", autoRenew: false, expiry });
    const first = await call(world, "/v1/subscription", { purchaseToken: "t1" });
    const hash = first.body.keyHash as string;
    world.spend(hash, 1);
    world.now += 5 * DAY;
    world.addPurchase("t2", { linked: "t1", expiry, orderId: "GPA.5555-5555-5555-55555", replacementMode: null });
    world.setState("t1", "SUBSCRIPTION_STATE_EXPIRED");
    const r = await call(world, "/v1/subscription", { purchaseToken: "t2", keyHash: hash });
    expect(r.body).toMatchObject({ newKey: false, allowanceRenewed: false, remainingUsd: 3.5, autoRenewing: true });
  });
});
