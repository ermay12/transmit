import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { call, cron, DAY, FakeWorld } from "./fakes";

async function buy(world: FakeWorld, token: string, o: Parameters<FakeWorld["addPurchase"]>[1] = {}) {
  world.addPurchase(token, o);
  const r = await call(world, "/v1/subscription", { purchaseToken: token });
  expect(r.status).toBe(200);
  return r.body.keyHash as string;
}

/** Fetches the fake saw plus the D1 calls the job charged itself; must stay under the Free plan's 50. */
function expectWithinBudget(report: { subrequests: number }, world: FakeWorld, before: number) {
  expect(world.calls.length - before).toBeLessThanOrEqual(report.subrequests);
  expect(report.subrequests).toBeLessThanOrEqual(45);
}

describe("scheduled job", () => {
  it("credits a renewal that the app hasn't reported", async () => {
    const world = new FakeWorld();
    const hash = await buy(world, "t1");
    world.spend(hash, 2);
    world.now += 30 * DAY + 10 * 60_000;
    world.renew("t1", "GPA.1..0", world.now + 30 * DAY);

    const report = await cron(world);
    expect(report.outcomes).toEqual(["active:renewed"]);
    expect(world.keys.get(hash)!.limit).toBe(6.5);
    const row = await env.DB.prepare("SELECT order_id, next_check_at FROM subscriptions").first();
    expect(row).toMatchObject({ order_id: "GPA.1..0", next_check_at: world.now + 30 * DAY + 5 * 60_000 });

    // Nothing is due until the next period ends.
    const quiet = await cron(world);
    expect(quiet.subscriptionsChecked).toBe(0);
  });

  it("disables keys of lapsed subscriptions and leaves them alone afterwards", async () => {
    const world = new FakeWorld();
    const hash = await buy(world, "t1", { state: "SUBSCRIPTION_STATE_CANCELED", autoRenew: false });
    world.now += 31 * DAY;
    world.setState("t1", "SUBSCRIPTION_STATE_EXPIRED");
    const report = await cron(world);
    expect(report.outcomes).toEqual(["expired:disabled"]);
    expect(world.keys.get(hash)!.disabled).toBe(true);
    const row = await env.DB.prepare("SELECT state, key_disabled, next_check_at FROM subscriptions").first();
    expect(row).toMatchObject({ state: "expired", key_disabled: 1, next_check_at: null });
  });

  it("re-checks on-hold purchases daily and turns the key back on when Google takes the payment", async () => {
    const world = new FakeWorld();
    const hash = await buy(world, "t1");
    world.now += 30 * DAY + 10 * 60_000;
    world.setState("t1", "SUBSCRIPTION_STATE_ON_HOLD");
    expect((await cron(world)).outcomes).toEqual(["on_hold:disabled"]);
    expect(world.keys.get(hash)!.disabled).toBe(true);

    world.now += 2 * 3_600_000;
    expect((await cron(world)).subscriptionsChecked).toBe(0); // not due for a day

    world.now += DAY;
    world.setState("t1", "SUBSCRIPTION_STATE_ACTIVE");
    world.renew("t1", "GPA.1..0", world.now + 30 * DAY);
    expect((await cron(world)).outcomes).toEqual(["active:renewed"]);
    expect(world.keys.get(hash)!.disabled).toBe(false);
  });

  it("applies refunds of the current order from voided purchases, once", async () => {
    const world = new FakeWorld();
    const hash = await buy(world, "t1", { orderId: "GPA.9" });
    const other = await buy(world, "t2", { orderId: "GPA.10..3" });
    world.voided = [
      { purchaseToken: "t1", orderId: "GPA.9", voidedTimeMillis: String(world.now) },
      { purchaseToken: "t2", orderId: "GPA.10..1", voidedTimeMillis: String(world.now) }, // an older order: recorded only
      { purchaseToken: "unknown-token", orderId: "GPA.77", voidedTimeMillis: String(world.now) },
    ];
    world.now += 3_600_000;
    const report = await cron(world);
    expect(report).toMatchObject({ voidedScanned: 3, refundsApplied: 1 });
    expect(world.keys.get(hash)!.disabled).toBe(true);
    expect(world.keys.get(other)!.disabled).toBe(false);

    // The app gets "refunded" and the key stays off.
    const r = await call(world, "/v1/subscription", { purchaseToken: "t1", keyHash: hash });
    expect(r.body).toMatchObject({ error: "purchase_not_active", state: "refunded" });

    // Not scanned again until a day has passed; then re-applying is a no-op.
    world.now += 3_600_000;
    expect((await cron(world)).voidedScanned).toBe(0);
    world.now += DAY;
    const again = await cron(world);
    expect(again).toMatchObject({ voidedScanned: 3, refundsApplied: 0 });
    const call0 = world.calls.filter((c) => c.url.includes("voidedpurchases")).at(-1)!;
    expect(new URL(call0.url).searchParams.get("startTime")).toBe(String(world.now - 3_600_000 - DAY - DAY));
  });

  it("merges refunds for one purchase that land on different pages", async () => {
    const world = new FakeWorld();
    const hash = await buy(world, "t1", { orderId: "GPA.5..1" });
    world.voided = [
      { purchaseToken: "t1", orderId: "GPA.5..0" },
      ...Array.from({ length: 49 }, (_, i) => ({ purchaseToken: `filler-${i}`, orderId: `GPA.f${i}` })),
      { purchaseToken: "t1", orderId: "GPA.5..1" },
    ];
    const report = await cron(world);
    expect(report).toMatchObject({ voidedScanned: 51, refundsApplied: 1 });
    expect(world.count("GET", "voidedpurchases")).toBe(2);
    expect(world.keys.get(hash)!.disabled).toBe(true);
    const row = await env.DB.prepare("SELECT state, voided_orders FROM subscriptions").first();
    expect(row).toEqual({ state: "refunded", voided_orders: "GPA.5..0 GPA.5..1" });
  });

  it("deletes expired trial keys", async () => {
    const world = new FakeWorld();
    await world.addIntegrity("i1", "dev1");
    const trial = await call(world, "/v1/trial", { androidId: "dev1", integrityToken: "i1" });
    world.now += 6 * DAY;
    expect((await cron(world)).trialKeysDeleted).toBe(0);
    world.now += DAY + 1000;
    expect((await cron(world)).trialKeysDeleted).toBe(1);
    expect(world.keys.has(trial.body.keyHash as string)).toBe(false);
    expect(await env.DB.prepare("SELECT key_hash FROM trials").first("key_hash")).toBeNull();
  });

  it("retries a failed acknowledgement", async () => {
    const world = new FakeWorld();
    world.failAcks = true;
    await buy(world, "t1");
    world.failAcks = false;
    world.now += 3_600_000;
    const report = await cron(world);
    expect(report.outcomes).toEqual(["active:unchanged+acknowledged"]);
    expect(world.purchases.get("t1")!.acknowledgementState).toBe("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED");
  });

  it("stays within the subrequest budget and carries the backlog to the next runs", async () => {
    const world = new FakeWorld();
    const hashes: string[] = [];
    for (let i = 0; i < 30; i++) hashes.push(await buy(world, `t${i}`, { orderId: `GPA.${i}` }));
    for (let i = 0; i < 12; i++) {
      await world.addIntegrity(`i${i}`, `dev${i}`);
      await call(world, "/v1/trial", { androidId: `dev${i}`, integrityToken: `i${i}` });
    }
    world.voided = Array.from({ length: 120 }, (_, i) => ({ purchaseToken: `refunded-elsewhere-${i}`, orderId: `GPA.x${i}` }));
    // The renewal order of t29 is refunded before the job has credited it.
    world.voided.push({ purchaseToken: "t29", orderId: "GPA.29..0" });
    for (const h of hashes) world.spend(h, 1);
    world.now += 31 * DAY;
    for (let i = 0; i < 30; i++) world.renew(`t${i}`, `GPA.${i}..0`, world.now + 30 * DAY);
    world.tokenCache = {}; // cold isolate: the token exchange counts too

    let renewed = 0;
    let deleted = 0;
    let refunds = 0;
    for (let run = 0; run < 8; run++) {
      const before = world.calls.length;
      const report = await cron(world);
      expectWithinBudget(report, world, before);
      renewed += report.outcomes.filter((o) => o.endsWith(":renewed")).length;
      deleted += report.trialKeysDeleted;
      refunds += report.refundsApplied;
      world.now += 3_600_000;
    }
    expect(refunds).toBe(0); // recorded, but it wasn't the credited order yet
    expect(renewed).toBe(29); // t29's re-check sees its new order is refunded and disables instead
    expect(deleted).toBe(12);
    for (const [i, h] of hashes.entries()) {
      if (i === 29) expect(world.keys.get(h)).toMatchObject({ disabled: true, limit: 4.5 });
      else expect(world.keys.get(h)).toMatchObject({ disabled: false, limit: 5.5 }); // usage 1 + 4.50
    }
  });

  it("lets a concurrent app request win over the job's stale write", async () => {
    const world = new FakeWorld();
    const hash = await buy(world, "t1");
    world.now += 30 * DAY + 10 * 60_000;
    world.renew("t1", "GPA.1..0", world.now + 30 * DAY);
    // The app rotates the key between the job's read and its write: simulate by bumping updated_at.
    const { putSubscription, getSubscription } = await import("../src/db");
    const { makeCtx } = await import("../src/context");
    const { recheck } = await import("../src/subscription");
    const ctx = await makeCtx(await world.env(), world.deps());
    const row = (await getSubscription(env.DB, (await env.DB.prepare("SELECT token_hash FROM subscriptions").first<string>("token_hash"))!))!;
    const { stmt } = await recheck(ctx, row, "t1", world.now);
    await putSubscription(env.DB, { ...row, key_hash: "rotated", updated_at: world.now + 1 }).run();
    await stmt.run();
    expect(await env.DB.prepare("SELECT key_hash FROM subscriptions").first("key_hash")).toBe("rotated");
    expect(world.keys.get(hash)!.limit).toBe(4.5);
  });
});
