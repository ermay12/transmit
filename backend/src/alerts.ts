// Email alerts for problems that need the developer. The alert job runs hourly on its own cron
// trigger (ALERT_CRON, also in wrangler.jsonc), so it never takes from the main job's budget. Each run:
// one D1 read, two OpenRouter calls, at most one email through Resend, one D1 write.
//
// What it checks:
//   - OpenRouter: the balance, today's spend, and a working zero-data-retention host for the model
//     the Transmit Customers workspace allows (without one, the free week and plans can't translate);
//   - D1: the daily free-week cap, today's /v1/trial calls (Play Integrity's 10,000 a day), purchases
//     still unacknowledged after a day (Google refunds them after 3), and when the main job last ran;
//   - errors counted as they happen (recordEvent): internal errors, failing calls to Google or
//     OpenRouter, and job runs with failed steps.
//
// A new problem is emailed at once, one that lasts again every 24 hours, and a cleared one once, as
// resolved. Error alerts go out at most every 6 hours, with everything counted since the last one.
// Emails carry counts and error messages only: no keys, tokens, device IDs or IP addresses.

import { type AlertConfig, type Env, loadAlertConfig, loadConfig } from "./config";
import type { Deps } from "./context";
import { utcDay } from "./db";
import { log } from "./http";

/** Must match the second entry of `triggers.crons` in wrangler.jsonc. */
export const ALERT_CRON = "7 * * * *";
/** job_state row where the main job records its last finished run (cron.ts). */
export const JOB_RUN_ROW = "cron_last_run";
const STATE_ROW = "alerts";

const OPENROUTER = "https://openrouter.ai/api/v1";
const RESEND = "https://api.resend.com/emails";
/** Resend's test sender, which can email only the Resend account's own address. No domain needed. */
const FROM = "Transmit alerts <onboarding@resend.dev>";

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const REMIND_MS = DAY;
const ERROR_REPEAT_MS = 6 * HOUR;
const UNACKED_AFTER_MS = DAY;
/** The main job runs every 10 minutes. */
const JOB_STALE_MS = 30 * MINUTE;
/** OpenRouter failing this many runs in a row is a problem; once is a blip. */
const OPENROUTER_FAILURES_TO_ALERT = 2;

export type EventKind = "internal_error" | "upstream_error" | "job_error";

/** An error kind alerts when one UTC hour has at least this many. */
const EVENT_THRESHOLDS: Record<EventKind, number> = { internal_error: 1, upstream_error: 5, job_error: 2 };
const EVENT_KINDS = Object.keys(EVENT_THRESHOLDS) as EventKind[];

/** Each alert's title, by kind: in the subject, the body and the resolved notice. */
const TITLES = {
  credits_low: "OpenRouter credits are low",
  spend_high: "AI spend is high today",
  openrouter_unreadable: "The alert job can't read OpenRouter",
  model_unavailable: "The app's AI model has no working host",
  trial_cap: "Today's free weeks are used up",
  integrity_high: "Play Integrity checks are near Google's daily limit",
  unacknowledged: "Purchases aren't being acknowledged",
  job_stalled: "The scheduled job has stopped",
  internal_error: "The backend has internal errors",
  upstream_error: "Calls to Google Play or OpenRouter are failing",
  job_error: "The scheduled job is hitting errors",
} as const;

function hourOf(ms: number): number {
  return Math.floor(ms / HOUR) * HOUR;
}

/** Counts `n` errors of `kind` in the current hour, keeping the latest message. */
export function eventStatement(db: D1Database, kind: EventKind, detail: string, now: number, n = 1): D1PreparedStatement {
  return db
    .prepare(
      `INSERT INTO alert_events (hour, kind, count, detail) VALUES (?1, ?2, ?4, ?3)
       ON CONFLICT(hour, kind) DO UPDATE SET count = count + excluded.count, detail = excluded.detail`,
    )
    .bind(hourOf(now), kind, detail.slice(0, 300), n);
}

/** Counts an error for the alert job. Never throws: counting mustn't change the response. */
export async function recordEvent(db: D1Database, kind: EventKind, detail: string, now: number): Promise<void> {
  try {
    await eventStatement(db, kind, detail, now).run();
  } catch (err) {
    log("alert_event_failed", { kind, error: String(err) });
  }
}

interface AlertState {
  lastRun?: number;
  /** The "alerts are on" email went out. */
  welcomed?: boolean;
  /** UTC day of `usageAtDayStart`. */
  day?: string;
  /** OpenRouter's total_usage at the day's first run, for today's spend. */
  usageAtDayStart?: number;
  /** Runs in a row that couldn't read OpenRouter. */
  openrouterFailures?: number;
}

interface Problem {
  kind: string;
  title: string;
  detail: string;
  action: string;
  /** Error alerts are counted per window; the rest are states that last until they clear. */
  event: boolean;
}

interface Sent {
  kind: string;
  first_at: number;
  sent_at: number;
}

export interface AlertReport {
  firing: string[];
  emailed: boolean;
  subject?: string;
}

/** Zero-data-retention hosts OpenRouter lists for `model`, and how many are up (status 0). */
export function countZdrHosts(text: string, model: string): { listed: number; up: number } {
  // The list is over 1 MB. Scanning the text costs about 1 ms of CPU; parsing it all would cost several,
  // and a run gets 10 ms on the Free plan. In each endpoint object, "status" comes after "model_id".
  const escaped = model.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&");
  const ids = new RegExp(`"model_id"\\s*:\\s*"${escaped}"`, "g");
  const nextId = /"model_id"\s*:/g;
  const status = /"status"\s*:\s*(-?\d+)/g;
  let listed = 0;
  let up = 0;
  for (let m = ids.exec(text); m; m = ids.exec(text)) {
    listed++;
    nextId.lastIndex = ids.lastIndex;
    const end = nextId.exec(text)?.index ?? text.length;
    status.lastIndex = ids.lastIndex;
    const s = status.exec(text);
    if (s && s.index < end && Number(s[1]) === 0) up++;
  }
  return { listed, up };
}

async function openrouterCredits(fetcher: typeof fetch, key: string): Promise<{ total: number; usage: number }> {
  const r = await fetcher(`${OPENROUTER}/credits`, { headers: { authorization: `Bearer ${key}` } });
  if (!r.ok) throw new Error(`credits: HTTP ${r.status}`);
  const d = ((await r.json()) as { data?: { total_credits?: unknown; total_usage?: unknown } }).data;
  if (typeof d?.total_credits !== "number" || typeof d.total_usage !== "number") throw new Error("credits: unexpected response");
  return { total: d.total_credits, usage: d.total_usage };
}

async function openrouterZdr(fetcher: typeof fetch, key: string, model: string): Promise<{ listed: number; up: number }> {
  const r = await fetcher(`${OPENROUTER}/endpoints/zdr`, { headers: { authorization: `Bearer ${key}` } });
  if (!r.ok) throw new Error(`zdr endpoints: HTTP ${r.status}`);
  return countZdrHosts(await r.text(), model);
}

async function sendEmail(fetcher: typeof fetch, cfg: AlertConfig, subject: string, text: string): Promise<boolean> {
  if (!cfg.email || !cfg.resendKey) {
    log("alert_not_sent", { reason: cfg.email ? "no RESEND_API_KEY" : "no ALERT_EMAIL", subject });
    return false;
  }
  try {
    const r = await fetcher(RESEND, {
      method: "POST",
      headers: { authorization: `Bearer ${cfg.resendKey}`, "content-type": "application/json" },
      body: JSON.stringify({ from: FROM, to: [cfg.email], subject, text }),
    });
    if (!r.ok) throw new Error(`HTTP ${r.status}: ${(await r.text()).slice(0, 200)}`);
    return true;
  } catch (err) {
    log("alert_send_failed", { error: String(err), subject });
    return false;
  }
}

const usd = (n: number) => `$${n.toFixed(2)}`;
const when = (ms: number) => new Date(ms).toISOString().slice(0, 16).replace("T", " ") + " UTC";
const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
const LOGS = "Cloudflare dashboard → Workers & Pages → transmit → Logs, or `npx wrangler tail` in backend/";

export async function runAlerts(env: Env, deps: Deps): Promise<AlertReport> {
  const now = deps.now();
  const config = loadConfig(env);
  const cfg = loadAlertConfig(env);
  const db = env.DB;
  const day = utcDay(now);
  const thisHour = hourOf(now);

  const [stateRows, sentRows, eventRows, unackedRows, trialRows, callRows] = await db.batch([
    db.prepare("SELECT name, value FROM job_state WHERE name IN (?1, ?2)").bind(STATE_ROW, JOB_RUN_ROW),
    db.prepare("SELECT kind, first_at, sent_at FROM alerts_sent"),
    db.prepare("SELECT hour, kind, count, detail FROM alert_events WHERE hour >= ?1 AND hour < ?2 ORDER BY hour").bind(thisHour - DAY, thisHour),
    db
      .prepare(
        `SELECT COUNT(*) AS n FROM subscriptions WHERE acknowledged = 0 AND created_at <= ?2
         AND (state IN ('active', 'grace') OR (state = 'canceled' AND expiry_time > ?1))`,
      )
      .bind(now, now - UNACKED_AFTER_MS),
    db.prepare("SELECT granted_usd, trials FROM trial_days WHERE day = ?1").bind(day),
    db.prepare("SELECT COALESCE(SUM(calls), 0) AS n FROM trial_calls WHERE day = ?1").bind(day),
  ]);
  const rows = new Map((stateRows!.results as { name: string; value: string }[]).map((r) => [r.name, r.value]));
  const state: AlertState = rows.has(STATE_ROW) ? (JSON.parse(rows.get(STATE_ROW)!) as AlertState) : {};
  const first = !state.welcomed;
  const jobRun = rows.has(JOB_RUN_ROW) ? Number(rows.get(JOB_RUN_ROW)) : undefined;
  const sent = new Map((sentRows!.results as unknown as Sent[]).map((r) => [r.kind, r]));
  const events = eventRows!.results as unknown as { hour: number; kind: EventKind; count: number; detail: string | null }[];
  const unacked = Number((unackedRows!.results[0] as { n: number }).n);
  const trialDay = trialRows!.results[0] as { granted_usd: number; trials: number } | undefined;
  const trialCalls = Number((callRows!.results[0] as { n: number }).n);

  const [creditsResult, zdrResult] = await Promise.allSettled([
    openrouterCredits(deps.fetch, env.OPENROUTER_MANAGEMENT_KEY),
    openrouterZdr(deps.fetch, env.OPENROUTER_MANAGEMENT_KEY, cfg.model),
  ]);
  const credits = creditsResult.status === "fulfilled" ? creditsResult.value : undefined;
  const zdr = zdrResult.status === "fulfilled" ? zdrResult.value : undefined;
  const failures = [creditsResult, zdrResult].flatMap((r) => (r.status === "rejected" ? [String(r.reason)] : []));

  const next: AlertState = { ...state, lastRun: now, openrouterFailures: failures.length ? (state.openrouterFailures ?? 0) + 1 : 0 };
  if (credits && next.day !== day) {
    next.day = day;
    next.usageAtDayStart = credits.usage;
  }
  const spendToday = credits && next.day === day && next.usageAtDayStart !== undefined ? Math.max(0, credits.usage - next.usageAtDayStart) : undefined;
  const balance = credits ? credits.total - credits.usage : undefined;

  const problems: Problem[] = [];
  const add = (kind: keyof typeof TITLES, detail: string, action: string) =>
    problems.push({ kind, title: TITLES[kind], detail, action, event: EVENT_KINDS.includes(kind as EventKind) });

  if (balance !== undefined && balance < cfg.minCreditsUsd) {
    add(
      "credits_low",
      `The balance is ${usd(balance)} (alert below ${usd(cfg.minCreditsUsd)}). At $0, the free week and both plans stop translating.`,
      "Add credits, or turn on auto top-up: https://openrouter.ai/settings/credits",
    );
  }
  if (spendToday !== undefined && spendToday > cfg.dailySpendUsd) {
    add(
      "spend_high",
      `OpenRouter spend since the first check after midnight UTC is ${usd(spendToday)} (alert above ${usd(cfg.dailySpendUsd)}). It's the whole account's spend, and the Transmit Customers workspace stops at its own daily budget.`,
      "Look for abuse at https://openrouter.ai/activity. To move the alert, change ALERT_DAILY_SPEND_USD in backend/wrangler.jsonc.",
    );
  }
  if ((next.openrouterFailures ?? 0) >= OPENROUTER_FAILURES_TO_ALERT) {
    add(
      "openrouter_unreadable",
      `${plural(next.openrouterFailures!, "run")} in a row failed: ${failures.join("; ")}. Credits and the model's hosts aren't being checked.`,
      "Check https://status.openrouter.ai, and that the OPENROUTER_MANAGEMENT_KEY secret still works (it also creates every key).",
    );
  }
  if (zdr && zdr.up === 0) {
    add(
      "model_unavailable",
      `OpenRouter lists ${plural(zdr.listed, "zero-data-retention host")} for ${cfg.model}, and none is up. The free week and plans translate only through these hosts.`,
      `Check https://openrouter.ai/${cfg.model}/providers. If it lasts, allow another model in the Transmit Customers workspace's guardrail, and in the app.`,
    );
  }
  if (trialDay && trialDay.granted_usd + config.trialAllowanceUsd > config.trialDailyBudgetUsd + 1e-6) {
    add(
      "trial_cap",
      `${plural(trialDay.trials, "free week")} started today (UTC), ${usd(trialDay.granted_usd)} of the ${usd(config.trialDailyBudgetUsd)} daily cap. New ones are refused until midnight UTC.`,
      "If it's real demand, raise TRIAL_DAILY_BUDGET_USD in backend/wrangler.jsonc and deploy. If it looks like abuse, leave it.",
    );
  }
  if (trialCalls >= cfg.integrityChecks) {
    add(
      "integrity_high",
      `${trialCalls} free-week requests today (UTC), and each can use a Play Integrity check. Google allows 10,000 a day; past that, new free weeks fail.`,
      "Look for abuse in the Worker's logs, or ask Google for more quota (Play Console → Test and release → App integrity).",
    );
  }
  if (unacked > 0) {
    add(
      "unacknowledged",
      `${plural(unacked, "active purchase is", "active purchases are")} still unacknowledged after a day. Google refunds a purchase that isn't acknowledged within 3 days.`,
      `Look for acknowledge_failed in the Worker's logs (${LOGS}).`,
    );
  }
  if (jobRun !== undefined && now - jobRun > JOB_STALE_MS) {
    add(
      "job_stalled",
      `It last finished ${Math.round((now - jobRun) / MINUTE)} minutes ago, and it runs every 10 minutes. Renewals, refunds and expired keys wait until it runs again.`,
      `Check the Worker's logs and Metrics (${LOGS}).`,
    );
  }

  // Errors: every complete hour since this kind's last email (at most a day back).
  for (const kind of EVENT_KINDS) {
    const s = sent.get(kind);
    const from = s ? hourOf(s.sent_at) : thisHour - DAY;
    const mine = events.filter((e) => e.kind === kind && e.hour >= from);
    const peak = Math.max(0, ...mine.map((e) => e.count));
    if (peak < EVENT_THRESHOLDS[kind]) continue;
    const total = mine.reduce((n, e) => n + e.count, 0);
    const latest = mine[mine.length - 1]!.detail ?? "";
    const since = `since ${when(mine[0]!.hour)}`;
    if (kind === "internal_error") {
      add(kind, `${plural(total, "internal error")} ${since}. The latest: ${latest}`, `Check the Worker's logs (${LOGS}).`);
    } else if (kind === "upstream_error") {
      add(
        kind,
        `${plural(total, "failed call")} ${since}, ${peak} in the worst hour. The latest: ${latest}. Free weeks and purchases can't be checked while it lasts.`,
        "Check https://status.openrouter.ai and https://status.cloud.google.com, then the Worker's logs.",
      );
    } else {
      add(kind, `${plural(total, "run")} with failed steps ${since}. The latest: ${latest}`, `Check the Worker's logs (${LOGS}).`);
    }
  }

  // What to email: new problems, lasting ones due a reminder, and ones that cleared.
  const firing = new Set(problems.map((p) => p.kind));
  const due = problems.filter((p) => {
    const s = sent.get(p.kind);
    return !s || now - s.sent_at >= (p.event ? ERROR_REPEAT_MS : REMIND_MS);
  });
  const resolved = [...sent.values()].filter((s) => !firing.has(s.kind) && !EVENT_KINDS.includes(s.kind as EventKind));
  // An error alert's row spaces out its emails and marks where its next count starts. It goes quietly once
  // the day the count looks back over has passed it, so the errors it reported can't be counted again.
  const expired = [...sent.values()].filter((s) => !firing.has(s.kind) && EVENT_KINDS.includes(s.kind as EventKind) && now - s.sent_at >= DAY);

  const status = [
    balance !== undefined ? `OpenRouter balance ${usd(balance)}` : "OpenRouter balance unknown",
    spendToday !== undefined ? `spend today ${usd(spendToday)}` : undefined,
    `free weeks today ${trialDay?.trials ?? 0} (${usd(trialDay?.granted_usd ?? 0)} of ${usd(config.trialDailyBudgetUsd)})`,
    `free-week requests today ${trialCalls}`,
    zdr ? `${cfg.model} hosts up: ${zdr.up} of ${zdr.listed}` : undefined,
    jobRun !== undefined ? `main job last ran ${when(jobRun)}` : undefined,
  ].filter(Boolean).join("; ");

  const report: AlertReport = { firing: [...firing], emailed: false };
  const writes: D1PreparedStatement[] = [
    db.prepare("DELETE FROM alert_events WHERE hour < ?1").bind(thisHour - DAY),
    ...expired.map((s) => db.prepare("DELETE FROM alerts_sent WHERE kind = ?1").bind(s.kind)),
  ];

  if (first || due.length || resolved.length) {
    const lines: string[] = [`Transmit alerts, ${when(now)}`, ""];
    if (first) lines.push("Alerts are on. This job checks the backend every hour and emails this address when something needs you.", "");
    for (const p of due) {
      const s = sent.get(p.kind);
      lines.push(!s || p.event ? `NEW: ${p.title}` : `STILL HAPPENING (since ${when(s.first_at)}): ${p.title}`, p.detail, `What to do: ${p.action}`, "");
    }
    for (const s of resolved) lines.push(`RESOLVED: ${(TITLES as Record<string, string>)[s.kind] ?? s.kind}`, "");
    if (problems.length > due.length) lines.push(`Also still happening, already emailed: ${problems.filter((p) => !due.includes(p)).map((p) => p.title).join("; ")}.`, "");
    lines.push(`Status now: ${status}.`, "", "--", "Sent by the Transmit Worker's hourly alert job (backend/src/alerts.ts). A problem that lasts is emailed again every 24 hours, and errors at most every 6 hours.");

    const subject = first
      ? due.length ? `Transmit alerts are on: ${due[0]!.title}` : "Transmit alerts are on"
      : due.length
        ? `Transmit: ${due[0]!.title}${due.length > 1 ? ` (+${due.length - 1} more)` : ""}`
        : `Transmit: resolved: ${(TITLES as Record<string, string>)[resolved[0]!.kind] ?? resolved[0]!.kind}${resolved.length > 1 ? ` (+${resolved.length - 1} more)` : ""}`;
    report.subject = subject;
    report.emailed = await sendEmail(deps.fetch, cfg, subject, lines.join("\n"));
    if (report.emailed) {
      for (const p of due) {
        const s = sent.get(p.kind);
        const firstAt = s && !p.event ? s.first_at : now;
        writes.push(
          db.prepare("INSERT INTO alerts_sent (kind, first_at, sent_at) VALUES (?1, ?2, ?3) ON CONFLICT(kind) DO UPDATE SET first_at = excluded.first_at, sent_at = excluded.sent_at").bind(p.kind, firstAt, now),
        );
      }
      for (const s of resolved) writes.push(db.prepare("DELETE FROM alerts_sent WHERE kind = ?1").bind(s.kind));
      next.welcomed = true;
    }
    // Not sent: nothing is marked, so the same email goes out at the next run that can send.
  }
  writes.push(
    db.prepare("INSERT INTO job_state (name, value) VALUES (?1, ?2) ON CONFLICT(name) DO UPDATE SET value = excluded.value").bind(STATE_ROW, JSON.stringify(next)),
  );
  await db.batch(writes);
  log("alerts_done", { firing: report.firing, emailed: report.emailed, subject: report.subject });
  return report;
}
