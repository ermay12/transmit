// Customer news emails (src/customers.ts), sent by the hourly alert job.

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { alerts, call, cron, DAY, FakeWorld } from "./fakes";

const HOUR = 3_600_000;
const MIN = 60_000;

/** A world whose first alert run is done, at 12:00 UTC: the customer count starts here. */
async function ready(): Promise<FakeWorld> {
  const world = new FakeWorld();
  await alerts(world);
  world.emails = [];
  return world;
}

async function startTrial(world: FakeWorld, androidId: string) {
  await world.addIntegrity(`itok-${androidId}`, androidId);
  const r = await call(world, "/v1/trial", { androidId, integrityToken: `itok-${androidId}` });
  expect(r.status).toBe(200);
}

async function buy(world: FakeWorld, token: string, o: Parameters<FakeWorld["addPurchase"]>[1] = {}, body: Record<string, unknown> = {}) {
  world.addPurchase(token, o);
  const r = await call(world, "/v1/subscription", { purchaseToken: token, ...body });
  expect(r.status).toBe(200);
  return r.body.keyHash as string;
}

/** The next alert run, an hour on; returns its customer email's text ("" if none was sent). */
async function nextRun(world: FakeWorld) {
  world.now += HOUR;
  const before = world.emails.length;
  const report = await alerts(world);
  const mail = world.emails.slice(before).find((m) => m.text.startsWith("Transmit customers,"));
  return { report: report.customers, text: mail?.text ?? "" };
}

describe("customer emails", () => {
  it("reports a free week once it starts, and when it ends without a subscription", async () => {
    const world = await ready();
    world.now += MIN;
    await startTrial(world, "a1");
    // Read 5 minutes late, so a request still in flight isn't missed.
    world.now += MIN;
    expect((await alerts(world)).customers).toEqual({ lines: 0, emailed: false });

    const { report, text } = await nextRun(world);
    expect(report).toEqual({ lines: 1, emailed: true, subject: "Transmit: 1 free week started" });
    expect(text).toContain("- 2026-11-18 12:01 UTC: A free week started.");
    expect(text).toContain("Now: 0 paying subscribers; 1 free week running; 1 free week started today (UTC).");
    expect(text).not.toMatch(/a1|itok|sk-or|hash/);
    expect((await nextRun(world)).report.lines).toBe(0);

    // A week later the job deletes its key.
    world.now += 7 * DAY;
    await cron(world);
    const end = await nextRun(world);
    expect(end.report.subject).toBe("Transmit: 1 free week ended");
    expect(end.text).toContain("A free week ended without a subscription.");
    expect(end.text).toContain("0 free weeks running");
  });

  it("reports a new subscriber, and the free week it came from", async () => {
    const world = await ready();
    await startTrial(world, "a2");
    await nextRun(world);
    await buy(world, "t1", {}, { androidId: "a2" });
    const { report, text } = await nextRun(world);
    expect(report.subject).toBe("Transmit: 1 new subscriber, 1 free week converted");
    expect(text).toContain("- 2026-11-18 13:00 UTC: New subscriber: Transmit.\n- 2026-11-18 13:00 UTC: A free week turned into a subscription.");
    expect(text).toContain("Now: 1 paying subscriber (Transmit 1); 0 free weeks running;");
    expect((await nextRun(world)).report.lines).toBe(0);
  });

  it("marks test purchases, and counts them apart", async () => {
    const world = await ready();
    await buy(world, "t1", { test: true });
    const { report, text } = await nextRun(world);
    expect(report.subject).toBe("Transmit test purchases: 1 new subscriber");
    expect(text).toContain("UTC: [test] New subscriber: Transmit.");
    expect(text).toContain("Now: 0 paying subscribers, plus 1 test purchase;");

    await buy(world, "t2", { productId: "transmit_plus" });
    world.renew("t1", "GPA.1..0", world.now + 30 * DAY);
    await call(world, "/v1/subscription", { purchaseToken: "t1" });
    const mixed = await nextRun(world);
    expect(mixed.report.subject).toBe("Transmit: 1 new subscriber, plus 1 test purchase event");
    expect(mixed.text).toContain("Now: 1 paying subscriber (Transmit Plus 1), plus 1 test purchase;");
  });

  it("reports renewals, auto-renew turned off and back on, and the end", async () => {
    const world = await ready();
    const hash = await buy(world, "t1");
    await nextRun(world);

    // Play renews; the job sees it after the period ends.
    world.now += 30 * DAY;
    world.renew("t1", "GPA.1..0", world.now + 30 * DAY);
    await cron(world);
    const renewed = await nextRun(world);
    expect(renewed.report.subject).toBe("Transmit: 1 renewal");
    expect(renewed.text).toContain("UTC: Transmit: renewed.");

    const sync = () => call(world, "/v1/subscription", { purchaseToken: "t1", keyHash: hash, rotate: false });
    world.setState("t1", "SUBSCRIPTION_STATE_CANCELED");
    await sync();
    const off = await nextRun(world);
    expect(off.report.subject).toBe("Transmit: 1 cancellation");
    expect(off.text).toContain("Transmit: auto-renew turned off. The AI keeps working until 2027-01-17.");

    world.setState("t1", "SUBSCRIPTION_STATE_ACTIVE");
    await sync();
    expect((await nextRun(world)).text).toContain("Transmit: auto-renew turned back on.");

    world.setState("t1", "SUBSCRIPTION_STATE_CANCELED");
    await sync();
    await nextRun(world);
    world.now += 30 * DAY;
    world.setState("t1", "SUBSCRIPTION_STATE_EXPIRED");
    await cron(world);
    const ended = await nextRun(world);
    expect(ended.report.subject).toBe("Transmit: 1 subscription ended");
    expect(ended.text).toContain("Transmit: subscription ended (auto-renew was off).");
    expect(ended.text).toContain("Now: 0 paying subscribers;");
  });

  it("reports an upgrade as one plan change", async () => {
    const world = await ready();
    const hash = await buy(world, "t1");
    await nextRun(world);
    await buy(world, "plus-token", { productId: "transmit_plus", orderId: "GPA.2", linked: "t1" }, { keyHash: hash });
    const { report, text } = await nextRun(world);
    expect(report).toMatchObject({ lines: 1, subject: "Transmit: 1 plan change" });
    expect(text).toContain("Changed plan: Transmit → Transmit Plus.");
    expect(text).toContain("Now: 1 paying subscriber (Transmit Plus 1);");
  });

  it("reports a switch to Transmit when it's set, and when it takes effect", async () => {
    const world = await ready();
    const plusEnds = world.now + 30 * DAY;
    const hash = await buy(world, "a", { productId: "transmit_plus", expiry: plusEnds });
    await nextRun(world);
    world.deferredSwitch("a", "b");
    await call(world, "/v1/subscription", { purchaseToken: "b", productId: "transmit_monthly", keyHash: hash });
    const set = await nextRun(world);
    expect(set.report).toMatchObject({ lines: 1, subject: "Transmit: 1 plan switch scheduled" });
    expect(set.text).toContain("Transmit Plus is switching to Transmit at the renewal on 2026-12-18.");

    world.now = plusEnds + MIN;
    world.completeDeferred("b", "GPA.2..0", world.now + 30 * DAY);
    await call(world, "/v1/subscription", { purchaseToken: "b", keyHash: hash, rotate: false });
    const done = await nextRun(world);
    expect(done.report).toMatchObject({ lines: 1, subject: "Transmit: 1 plan switch" });
    expect(done.text).toContain("Switched from Transmit Plus to Transmit at the renewal.");
  });

  it("reports a refund", async () => {
    const world = await ready();
    await buy(world, "t1");
    await nextRun(world);
    world.refundAndRevoke("t1");
    world.now += 10 * MIN;
    await cron(world);
    const { report, text } = await nextRun(world);
    expect(report.subject).toBe("Transmit: 1 refund");
    expect(text).toContain("Transmit: refunded. Its AI is off.");
  });

  it("keeps the news for the next run when the email can't be sent", async () => {
    const world = await ready();
    await startTrial(world, "a3");
    await buy(world, "t1");
    world.failEmails = true;
    expect((await nextRun(world)).report).toMatchObject({ lines: 2, emailed: false });
    world.failEmails = false;
    const { report, text } = await nextRun(world);
    expect(report).toMatchObject({ lines: 2, emailed: true, subject: "Transmit: 1 new subscriber, 1 free week started" });
    expect(text).toContain("A free week started.");
    expect((await nextRun(world)).report.lines).toBe(0);
  });

  it("records purchases from before these emails quietly, then reports their changes", async () => {
    const world = await ready();
    const hash = await buy(world, "t1");
    await env.DB.prepare("UPDATE subscriptions SET notified = ''").run(); // as migration 0006 left them
    expect((await nextRun(world)).report).toEqual({ lines: 0, emailed: false });
    const row = await env.DB.prepare("SELECT notified FROM subscriptions").first<{ notified: string }>();
    expect(row!.notified).toBe("transmit_monthly|active|GPA.0000-0000-0000-00001||");

    world.setState("t1", "SUBSCRIPTION_STATE_CANCELED");
    await call(world, "/v1/subscription", { purchaseToken: "t1", keyHash: hash, rotate: false });
    expect((await nextRun(world)).report.subject).toBe("Transmit: 1 cancellation");
  });
});
