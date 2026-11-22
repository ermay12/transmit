import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { countZdrHosts, recordEvent } from "../src/alerts";
import { alerts, call, cron, DAY, FakeWorld } from "./fakes";

const HOUR = 3_600_000;

/** A world whose first alert run (the "alerts are on" email) is done, at 13:07 UTC. */
async function welcomed(o: { credits?: FakeWorld["credits"] } = {}): Promise<FakeWorld> {
  const world = new FakeWorld();
  if (o.credits) world.credits = o.credits;
  world.now += HOUR + 7 * 60_000;
  await alerts(world);
  world.emails = [];
  return world;
}

async function failedTrial(world: FakeWorld, n: number) {
  const androidId = `a${String(n).padStart(15, "0")}`;
  await world.addIntegrity(`itok-${n}`, androidId);
  world.failCreates = 1;
  const r = await call(world, "/v1/trial", { androidId, integrityToken: `itok-${n}` });
  expect(r.body.error).toBe("upstream_error");
}

describe("alert job", () => {
  it("says alerts are on once, with the status, then stays quiet while nothing is wrong", async () => {
    const world = new FakeWorld();
    const report = await alerts(world);
    expect(report).toMatchObject({ emailed: true, firing: [], subject: "Transmit alerts are on" });
    expect(world.emails).toHaveLength(1);
    const mail = world.emails[0]!;
    expect(mail).toMatchObject({ from: "Transmit alerts <onboarding@resend.dev>", to: ["ermay12@gmail.com"] });
    expect(mail.text).toContain("Alerts are on.");
    expect(mail.text).toContain("Status now: OpenRouter balance $90.00; spend today $0.00; free weeks today 0 ($0.00 of $30.00)");
    expect(mail.text).toContain("google/gemma-4-31b-it hosts up: 1 of 2");
    // Two OpenRouter reads and one email.
    expect(world.calls.map((c) => `${c.method} ${new URL(c.url).pathname}`)).toEqual(["GET /api/v1/credits", "GET /api/v1/endpoints/zdr", "POST /emails"]);

    world.now += HOUR;
    expect(await alerts(world)).toMatchObject({ emailed: false, firing: [] });
    expect(world.emails).toHaveLength(1);
  });

  it("emails low credits at once, again after a day, and once more when they're topped up", async () => {
    const world = new FakeWorld();
    world.credits = { total: 30, usage: 12.5 };
    const first = await alerts(world);
    expect(first.subject).toBe("Transmit alerts are on: OpenRouter credits are low");
    expect(world.emails[0]!.text).toContain("NEW: OpenRouter credits are low\nThe balance is $17.50 (alert below $25.00).");

    world.now += HOUR;
    expect((await alerts(world)).emailed).toBe(false);

    world.now += DAY;
    const reminder = await alerts(world);
    expect(reminder.subject).toBe("Transmit: OpenRouter credits are low");
    expect(world.emails[1]!.text).toContain("STILL HAPPENING (since 2026-11-18 12:00 UTC): OpenRouter credits are low");

    world.credits.total = 130;
    world.now += HOUR;
    const cleared = await alerts(world);
    expect(cleared.subject).toBe("Transmit: resolved: OpenRouter credits are low");
    expect(world.emails[2]!.text).toContain("RESOLVED: OpenRouter credits are low");
    world.now += HOUR;
    expect((await alerts(world)).emailed).toBe(false);
  });

  it("alerts when the model has no zero-data-retention host up", async () => {
    const world = await welcomed();
    world.zdr = [{ model: "google/gemma-4-31b-it", status: -2 }, { model: "other/model", status: 0 }];
    world.now += HOUR;
    const r = await alerts(world);
    expect(r.subject).toBe("Transmit: The app's AI model has no working host");
    expect(world.emails[0]!.text).toContain("OpenRouter lists 1 zero-data-retention host for google/gemma-4-31b-it, and none is up.");

    world.zdr = [];
    world.now += DAY;
    await alerts(world);
    expect(world.emails[1]!.text).toContain("lists 0 zero-data-retention hosts");
  });

  it("finds the model's hosts in the list text, compact or spaced", () => {
    const list = (space: string) =>
      JSON.stringify(
        {
          data: [
            { name: "A | google/gemma-4-31b-it", model_id: "google/gemma-4-31b-it", pricing: { prompt: "1" }, status: -1 },
            { name: "B | google/gemma-4-31b-it-x", model_id: "google/gemma-4-31b-it-x", status: 0 },
            { name: "C | google/gemma-4-31b-it", model_id: "google/gemma-4-31b-it", perf: { p50: 1 }, status: 0 },
            { name: "D | google/gemma-4-31b-it", model_id: "google/gemma-4-31b-it" },
            { name: "E | other", model_id: "other", status: 0 },
          ],
        },
        null,
        space,
      );
    expect(countZdrHosts(list(""), "google/gemma-4-31b-it")).toEqual({ listed: 3, up: 1 });
    expect(countZdrHosts(list("  "), "google/gemma-4-31b-it")).toEqual({ listed: 3, up: 1 });
    expect(countZdrHosts(list(""), "missing/model")).toEqual({ listed: 0, up: 0 });
  });

  it("alerts on today's spend from the day's first check, and starts again the next day", async () => {
    const world = await welcomed({ credits: { total: 200, usage: 10 } });
    world.credits.usage = 85.5;
    world.now += HOUR;
    const r = await alerts(world);
    expect(r.firing).toEqual(["spend_high"]);
    expect(world.emails[0]!.text).toContain("OpenRouter spend since the first check after midnight UTC is $75.50 (alert above $70.00).");

    // At the next day's first run the count starts over, so the alert clears.
    world.now += DAY;
    const next = await alerts(world);
    expect(next.subject).toBe("Transmit: resolved: AI spend is high today");
  });

  it("alerts only when OpenRouter can't be read twice in a row", async () => {
    const world = await welcomed();
    world.failOpenRouterReads = true;
    world.now += HOUR;
    expect(await alerts(world)).toMatchObject({ emailed: false, firing: [] });
    world.now += HOUR;
    const r = await alerts(world);
    expect(r.firing).toEqual(["openrouter_unreadable"]);
    expect(world.emails[0]!.text).toContain("2 runs in a row failed: Error: credits: HTTP 503; Error: zdr endpoints: HTTP 503.");
  });

  it("alerts on the daily free-week cap and on Play Integrity use", async () => {
    const world = await welcomed();
    await env.DB.batch([
      env.DB.prepare("INSERT INTO trial_days (day, granted_usd, trials) VALUES ('2026-11-18', 29.75, 59)"),
      env.DB.prepare("INSERT INTO trial_calls (day, ip_hash, calls) VALUES ('2026-11-18', 'a', 4000), ('2026-11-18', 'b', 3000), ('2026-11-17', 'c', 9000)"),
    ]);
    world.now += HOUR;
    const r = await alerts(world);
    expect(r.firing.sort()).toEqual(["integrity_high", "trial_cap"]);
    expect(r.subject).toBe("Transmit: Today's free weeks are used up (+1 more)");
    const text = world.emails[0]!.text;
    expect(text).toContain("59 free weeks started today (UTC), $29.75 of the $30.00 daily cap.");
    expect(text).toContain("7000 free-week requests today (UTC)");
  });

  it("alerts on a purchase still unacknowledged after a day", async () => {
    const world = await welcomed();
    world.failAcks = true;
    world.addPurchase("t1");
    expect((await call(world, "/v1/subscription", { purchaseToken: "t1" })).status).toBe(200);

    world.now += 12 * HOUR;
    expect((await alerts(world)).firing).toEqual([]);
    world.now += 13 * HOUR;
    const r = await alerts(world);
    expect(r.firing).toEqual(["unacknowledged"]);
    expect(world.emails[0]!.text).toContain("1 active purchase is still unacknowledged after a day.");
  });

  it("counts failing calls to Google or OpenRouter, and emails a burst at most every 6 hours", async () => {
    const world = await welcomed();
    // 13:07: four failures this hour are under the threshold of five.
    for (let i = 0; i < 4; i++) await failedTrial(world, i);
    world.now += HOUR;
    expect((await alerts(world)).firing).toEqual([]);

    // 14:07: a fifth in one hour is a burst.
    for (let i = 4; i < 9; i++) await failedTrial(world, i);
    world.now += HOUR;
    const r = await alerts(world);
    expect(r.firing).toEqual(["upstream_error"]);
    expect(world.emails[0]!.text).toContain("NEW: Calls to Google Play or OpenRouter are failing\n9 failed calls since 2026-11-18 13:00 UTC, 5 in the worst hour.");
    expect(world.emails[0]!.text).toContain("The latest: /v1/trial: openrouter returned 500");

    // Another burst within 6 hours waits; after that, it's emailed with everything since the last email.
    for (let i = 9; i < 15; i++) await failedTrial(world, i);
    world.now += HOUR;
    expect(await alerts(world)).toMatchObject({ emailed: false, firing: ["upstream_error"] });
    world.now += 5 * HOUR;
    await alerts(world);
    expect(world.emails[1]!.text).toContain("6 failed calls since 2026-11-18 15:00 UTC");
    // No "resolved" for errors, and the reported ones aren't counted again.
    for (let h = 0; h < 30; h++) {
      world.now += HOUR;
      await alerts(world);
    }
    expect(world.emails).toHaveLength(2);
  });

  it("emails any internal error", async () => {
    const world = await welcomed();
    await recordEvent(env.DB, "internal_error", "/v1/subscription: TypeError: x is undefined", world.now);
    world.now += HOUR;
    const r = await alerts(world);
    expect(r.subject).toBe("Transmit: The backend has internal errors");
    expect(world.emails[0]!.text).toContain("1 internal error since 2026-11-18 13:00 UTC. The latest: /v1/subscription: TypeError: x is undefined");
  });

  it("alerts when the main job stops, or keeps failing steps", async () => {
    const world = await welcomed();
    await cron(world);
    world.now += 20 * 60_000;
    expect((await alerts(world)).firing).toEqual([]);
    world.now += 20 * 60_000;
    const r = await alerts(world);
    expect(r.firing).toEqual(["job_stalled"]);
    expect(world.emails[0]!.text).toContain("It last finished 40 minutes ago");

    // Runs with failed steps: two in one hour alert.
    await env.DB.prepare("INSERT OR REPLACE INTO job_state (name, value) VALUES ('voided', 'not json')").run();
    world.now = Math.floor(world.now / HOUR) * HOUR + HOUR + 3 * 60_000;
    for (let i = 0; i < 6; i++) {
      await cron(world);
      world.now += 10 * 60_000;
    }
    world.now += 4 * 60_000;
    const failing = await alerts(world);
    expect(failing.firing).toEqual(["job_error"]);
    expect(world.emails.at(-1)!.text).toContain("6 runs with failed steps since");
    expect(world.emails.at(-1)!.text).toContain("The latest: cron_voided_failed: SyntaxError");
    expect(world.emails.at(-1)!.text).toContain("RESOLVED: The scheduled job has stopped");
  });

  it("keeps an email that couldn't be sent for the next run", async () => {
    const world = new FakeWorld();
    world.credits = { total: 20, usage: 0 };
    const unset = await alerts(world, { RESEND_API_KEY: "" });
    expect(unset).toMatchObject({ emailed: false, firing: ["credits_low"] });
    world.failEmails = true;
    world.now += HOUR;
    expect((await alerts(world)).emailed).toBe(false);
    world.failEmails = false;
    world.now += HOUR;
    const sent = await alerts(world);
    expect(sent).toMatchObject({ emailed: true, subject: "Transmit alerts are on: OpenRouter credits are low" });
    expect(world.emails).toHaveLength(1);
  });
});

describe("GET /v1/health/jobs", () => {
  it("is 503 until both jobs have run lately", async () => {
    const world = new FakeWorld();
    expect(await call(world, "/v1/health/jobs")).toMatchObject({ status: 503, body: { ok: false, jobMinutesAgo: null, alertsMinutesAgo: null } });
    await cron(world);
    await alerts(world);
    world.now += 5 * 60_000;
    expect(await call(world, "/v1/health/jobs")).toMatchObject({ status: 200, body: { ok: true, jobMinutesAgo: 5, alertsMinutesAgo: 5 } });
    world.now += 40 * 60_000;
    expect(await call(world, "/v1/health/jobs")).toMatchObject({ status: 503, body: { ok: false, jobMinutesAgo: 45 } });
  });
});
