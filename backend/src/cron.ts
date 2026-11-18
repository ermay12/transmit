// The scheduled job (hourly). Each run stays inside a fixed subrequest budget
// (fetches and D1 calls together; the Workers Free plan allows 50 per
// invocation) and leaves the rest for the next run:
//   1. refunds: Google's voided purchases, scanned once a day, page by page;
//   2. purchases past their period end (renewal or lapse), on hold, or unacknowledged;
//   3. expired trial keys, which are deleted.

import type { Ctx } from "./context";
import { decrypt, keyedHash } from "./crypto";
import { putSubscription, type SubscriptionRow } from "./db";
import type { VoidedPurchase } from "./google";
import { log } from "./http";
import { nextCheckAt, recheck, splitOrders, type SubState } from "./subscription";

/** Counts subrequests. The fetch wrapper charges network calls; D1 calls are charged explicitly. */
export class Budget {
  used = 0;
  constructor(readonly limit: number) {}
  get left(): number {
    return this.limit - this.used;
  }
  spend(n = 1): void {
    this.used += n;
  }
  /** Can we afford `n` more and still make the final database write? */
  allows(n: number): boolean {
    return this.left >= n + 1;
  }
  wrap(fetcher: typeof fetch): typeof fetch {
    return (input, init) => {
      this.spend(1);
      return fetcher(input, init);
    };
  }
}

type Touched = Map<string, { next: SubscriptionRow; expectedUpdatedAt: number }>;

interface VoidedState {
  /** endTime of the last complete scan. */
  lastEnd?: number;
  /** A scan in progress, resumed by the next run. */
  page?: { startTime: number; endTime: number; token?: string };
}

export interface CronReport {
  subrequests: number;
  voidedScanned: number;
  refundsApplied: number;
  subscriptionsChecked: number;
  outcomes: string[];
  trialKeysDeleted: number;
  errors: number;
}

const DAY = 86_400_000;
/** Google's voided-purchases API looks back at most 30 days. */
const VOIDED_MAX_LOOKBACK = 29 * DAY;
const VOIDED_OVERLAP = DAY;
/** Small enough that one page's tokens fit D1's 100 bound parameters. */
const VOIDED_PAGE_SIZE = 50;
/** Worst case for one purchase in recheck(), plus a possible Google token exchange. */
const RECHECK_COST = 5;
/** Typical cost, used to size the query; the loop stops at the real budget. */
const RECHECK_TYPICAL_COST = 2;

export async function runScheduled(ctx: Ctx, budget: Budget): Promise<CronReport> {
  const now = ctx.now();
  const report: CronReport = {
    subrequests: 0,
    voidedScanned: 0,
    refundsApplied: 0,
    subscriptionsChecked: 0,
    outcomes: [],
    trialKeysDeleted: 0,
    errors: 0,
  };
  const writes: D1PreparedStatement[] = [];
  /** Refund updates of this run, by token hash, merged across pages; the re-check skips these purchases. */
  const touched: Touched = new Map();

  try {
    await scanVoided(ctx, budget, now, writes, report, touched);
  } catch (err) {
    report.errors++;
    log("cron_voided_failed", { error: String(err) });
  }
  try {
    await recheckDue(ctx, budget, now, writes, report, touched);
  } catch (err) {
    report.errors++;
    log("cron_recheck_failed", { error: String(err) });
  }
  try {
    await deleteExpiredTrialKeys(ctx, budget, now, writes, report);
  } catch (err) {
    report.errors++;
    log("cron_trials_failed", { error: String(err) });
  }

  writes.push(ctx.db.prepare("DELETE FROM locks WHERE until < ?1").bind(now));
  budget.spend();
  await ctx.db.batch(writes);
  report.subrequests = budget.used;
  log("cron_done", { ...report, outcomes: report.outcomes.length ? report.outcomes : undefined });
  return report;
}

async function scanVoided(
  ctx: Ctx,
  budget: Budget,
  now: number,
  writes: D1PreparedStatement[],
  report: CronReport,
  touched: Touched,
): Promise<void> {
  try {
    await scanVoidedPages(ctx, budget, now, writes, report, touched);
  } finally {
    for (const { next, expectedUpdatedAt } of touched.values()) writes.push(putSubscription(ctx.db, next, expectedUpdatedAt));
  }
}

async function scanVoidedPages(
  ctx: Ctx,
  budget: Budget,
  now: number,
  writes: D1PreparedStatement[],
  report: CronReport,
  touched: Touched,
): Promise<void> {
  budget.spend();
  const stored = await ctx.db.prepare("SELECT value FROM job_state WHERE name = 'voided'").first<{ value: string }>();
  const state: VoidedState = stored ? (JSON.parse(stored.value) as VoidedState) : {};
  let page = state.page;
  if (!page) {
    if (state.lastEnd !== undefined && now - state.lastEnd < ctx.config.voidedCheckIntervalMs) return;
    const startTime = Math.max(now - VOIDED_MAX_LOOKBACK, (state.lastEnd ?? 0) - VOIDED_OVERLAP);
    page = { startTime, endTime: now };
  }

  // Each page: 1 Google call + 1 D1 lookup + up to one OpenRouter call per refund that applies.
  while (budget.allows(3)) {
    let res;
    try {
      res = await ctx.google.listVoided({ ...page, pageToken: page.token, maxResults: VOIDED_PAGE_SIZE });
    } catch (err) {
      // A stale page token must not wedge the scan: start over from the last complete scan next time.
      if (page.token) writes.push(saveVoidedState(ctx, { lastEnd: state.lastEnd }));
      throw err;
    }
    report.voidedScanned += res.items.length;
    const done = await applyRefunds(ctx, budget, now, res.items, writes, report, touched);
    if (!done) break; // out of budget mid-page: redo this page next run (idempotent)
    if (res.nextPageToken) {
      page = { ...page, token: res.nextPageToken };
      continue;
    }
    writes.push(saveVoidedState(ctx, { lastEnd: page.endTime }));
    return;
  }
  writes.push(saveVoidedState(ctx, { ...state, page }));
}

function saveVoidedState(ctx: Ctx, state: VoidedState): D1PreparedStatement {
  return ctx.db
    .prepare("INSERT INTO job_state (name, value) VALUES ('voided', ?1) ON CONFLICT(name) DO UPDATE SET value = excluded.value")
    .bind(JSON.stringify(state));
}

/** Returns false if the budget ran out before every refund on the page was applied. */
async function applyRefunds(
  ctx: Ctx,
  budget: Budget,
  now: number,
  items: VoidedPurchase[],
  writes: D1PreparedStatement[],
  report: CronReport,
  touched: Touched,
): Promise<boolean> {
  const byHash = new Map<string, string[]>();
  for (const v of items) {
    if (!v.purchaseToken || !v.orderId) continue;
    const h = await keyedHash(ctx.keys, "purchase", v.purchaseToken);
    byHash.set(h, [...(byHash.get(h) ?? []), v.orderId]);
  }
  if (byHash.size === 0) return true;
  const hashes = [...byHash.keys()];
  budget.spend();
  const rows = await ctx.db
    .prepare(`SELECT * FROM subscriptions WHERE token_hash IN (${hashes.map((_, i) => `?${i + 1}`).join(", ")})`)
    .bind(...hashes)
    .all<SubscriptionRow>();

  for (const stored of rows.results) {
    const seen = touched.get(stored.token_hash);
    const row = seen?.next ?? stored;
    const voided = splitOrders(row.voided_orders);
    const fresh = (byHash.get(row.token_hash) ?? []).filter((o) => !voided.includes(o));
    if (fresh.length === 0) continue;
    const hitsCurrent = row.order_id !== null && fresh.includes(row.order_id) && !row.replaced_by;
    const next: SubscriptionRow = { ...row, voided_orders: [...voided, ...fresh].join(" "), updated_at: now };
    if (hitsCurrent) {
      if (row.key_hash && !row.key_disabled) {
        if (!budget.allows(1)) return false;
        await ctx.openrouter.updateKey(row.key_hash, { disabled: true });
        next.key_disabled = 1;
      }
      const state: SubState = "refunded";
      next.state = state;
      next.next_check_at = nextCheckAt(state, row.expiry_time, now, false);
      report.refundsApplied++;
      log("refund_applied", { key: row.key_hash?.slice(0, 12) ?? null });
    }
    touched.set(row.token_hash, { next, expectedUpdatedAt: seen?.expectedUpdatedAt ?? stored.updated_at });
  }
  return true;
}

async function recheckDue(
  ctx: Ctx,
  budget: Budget,
  now: number,
  writes: D1PreparedStatement[],
  report: CronReport,
  touched: Touched,
): Promise<void> {
  if (!budget.allows(RECHECK_COST + 1)) return;
  const room = Math.floor((budget.left - 2) / RECHECK_TYPICAL_COST);
  budget.spend();
  const due = await ctx.db
    .prepare("SELECT * FROM subscriptions WHERE next_check_at IS NOT NULL AND next_check_at <= ?1 ORDER BY next_check_at LIMIT ?2")
    .bind(now, room)
    .all<SubscriptionRow>();

  for (const row of due.results) {
    if (!budget.allows(RECHECK_COST)) break;
    if (touched.has(row.token_hash)) continue;
    try {
      const token = await decrypt(ctx.keys, row.token_enc);
      const { stmt, outcome } = await recheck(ctx, row, token, now);
      writes.push(stmt);
      report.subscriptionsChecked++;
      report.outcomes.push(outcome);
    } catch (err) {
      // Push it back an hour so one broken row can't use up every run.
      report.errors++;
      log("cron_recheck_row_failed", { error: String(err) });
      writes.push(putSubscription(ctx.db, { ...row, next_check_at: now + 3_600_000, updated_at: now }, row.updated_at));
    }
  }
}

async function deleteExpiredTrialKeys(
  ctx: Ctx,
  budget: Budget,
  now: number,
  writes: D1PreparedStatement[],
  report: CronReport,
): Promise<void> {
  const room = budget.left - 2;
  if (room < 1) return;
  budget.spend();
  const rows = await ctx.db
    .prepare("SELECT device_hash, key_hash FROM trials WHERE key_hash IS NOT NULL AND expires_at <= ?1 ORDER BY expires_at LIMIT ?2")
    .bind(now, room)
    .all<{ device_hash: string; key_hash: string }>();
  for (const t of rows.results) {
    if (!budget.allows(1)) break;
    await ctx.openrouter.deleteKey(t.key_hash);
    writes.push(
      ctx.db
        .prepare("UPDATE trials SET key_hash = NULL, ended_at = ?3 WHERE device_hash = ?1 AND key_hash = ?2")
        .bind(t.device_hash, t.key_hash, now),
    );
    report.trialKeysDeleted++;
  }
}
