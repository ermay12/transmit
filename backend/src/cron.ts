// The scheduled job (every 10 minutes). Each run stays inside a fixed
// subrequest budget (fetches and D1 calls together) and leaves the rest for the
// next run. The budget is set by CPU, not by the Free plan's 50 subrequests:
// each subrequest costs about 0.5 ms of CPU in production, and a run gets 10 ms.
// It reads everything in one D1 batch and writes everything in another, then:
//   1. refunds: Google's voided purchases, scanned once a day, page by page;
//   2. purchases past their period end (renewal or lapse), on hold, or unacknowledged;
//   3. expired trial keys, which are deleted;
//   4. retention: subscription rows (and their OpenRouter keys) 90 days after the
//      purchase ended or was refunded. Trial rows are kept: they keep each device
//      to one free trial.
// It also clears stale locks and the per-network trial counts of past days, and
// shares a fresh Google token with the request isolates (shared-token.ts).

import type { Ctx } from "./context";
import { decrypt, keyedHash } from "./crypto";
import { putSubscription, type SubscriptionRow, utcDay } from "./db";
import { VOIDED_MAX_LOOKBACK_MS, type VoidedPurchase } from "./google";
import { log } from "./http";
import { adoptSharedToken, SHARED_TOKEN_ROW, shareTokenWrites } from "./shared-token";
import { nextCheckAt, recheck, REFUND_WINDOW_MS, splitOrders, type SubState } from "./subscription";

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

interface ExpiredTrial {
  device_hash: string;
  key_hash: string;
}

interface EndedSubscription {
  token_hash: string;
  key_hash: string | null;
  updated_at: number;
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
  subscriptionsDeleted: number;
  errors: number;
}

const DAY = 86_400_000;
/** Subscription rows are deleted this long after ended_at (privacy policy, section 6). */
export const SUBSCRIPTION_RETENTION_MS = 90 * DAY;
/** Ended rows read per run. Rows without a key cost no subrequest; the rest wait for budget. */
const PURGE_BATCH = 20;
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
    subscriptionsDeleted: 0,
    errors: 0,
  };
  const writes: D1PreparedStatement[] = [];
  /** Refund updates of this run, by token hash, merged across pages; the re-check skips these purchases. */
  const touched: Touched = new Map();

  // Everything the run reads, in one D1 call: the refund scan's progress, the Google token other
  // isolates shared, the purchases due for a re-check, the expired trial keys and the ended
  // subscriptions past retention (except those due for a re-check, which this run may rewrite). The
  // loops below stop at the budget, so the limits only keep the reads small.
  let reads: { state: Map<string, string>; due: SubscriptionRow[]; trials: ExpiredTrial[]; ended: EndedSubscription[] } | undefined;
  try {
    const dueRoom = Math.max(1, Math.floor((budget.left - 2) / RECHECK_TYPICAL_COST));
    const trialRoom = Math.max(1, budget.left - 2);
    budget.spend();
    const [state, due, trials, ended] = await ctx.db.batch([
      ctx.db.prepare("SELECT name, value FROM job_state WHERE name IN ('voided', ?1)").bind(SHARED_TOKEN_ROW),
      ctx.db
        .prepare("SELECT * FROM subscriptions WHERE next_check_at IS NOT NULL AND next_check_at <= ?1 ORDER BY next_check_at LIMIT ?2")
        .bind(now, dueRoom),
      ctx.db
        .prepare("SELECT device_hash, key_hash FROM trials WHERE key_hash IS NOT NULL AND expires_at <= ?1 ORDER BY expires_at LIMIT ?2")
        .bind(now, trialRoom),
      ctx.db
        .prepare(
          `SELECT token_hash, key_hash, updated_at FROM subscriptions
           WHERE ended_at IS NOT NULL AND ended_at <= ?1 AND (next_check_at IS NULL OR next_check_at > ?2)
           ORDER BY ended_at LIMIT ?3`,
        )
        .bind(now - SUBSCRIPTION_RETENTION_MS, now, PURGE_BATCH),
    ]);
    reads = {
      state: new Map((state!.results as { name: string; value: string }[]).map((r) => [r.name, r.value])),
      due: due!.results as SubscriptionRow[],
      trials: trials!.results as ExpiredTrial[],
      ended: ended!.results as EndedSubscription[],
    };
    await adoptSharedToken(ctx, reads.state.get(SHARED_TOKEN_ROW));
  } catch (err) {
    report.errors++;
    log("cron_read_failed", { error: String(err) });
  }

  if (reads) {
    try {
      await scanVoided(ctx, budget, now, reads.state.get("voided"), writes, report, touched);
    } catch (err) {
      report.errors++;
      log("cron_voided_failed", { error: String(err) });
    }
    try {
      await recheckDue(ctx, budget, now, reads.due, writes, report, touched);
    } catch (err) {
      report.errors++;
      log("cron_recheck_failed", { error: String(err) });
    }
    try {
      await deleteExpiredTrialKeys(ctx, budget, now, reads.trials, writes, report);
    } catch (err) {
      report.errors++;
      log("cron_trials_failed", { error: String(err) });
    }
    try {
      await purgeEndedSubscriptions(ctx, budget, reads.ended, writes, report, touched);
    } catch (err) {
      report.errors++;
      log("cron_purge_failed", { error: String(err) });
    }
  }

  writes.push(...(await shareTokenWrites(ctx)));
  writes.push(ctx.db.prepare("DELETE FROM locks WHERE until < ?1").bind(now));
  // The daily trial limit only reads today's counts; earlier days go at the first run after UTC midnight.
  writes.push(ctx.db.prepare("DELETE FROM trial_calls WHERE day < ?1").bind(utcDay(now)));
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
  stored: string | undefined,
  writes: D1PreparedStatement[],
  report: CronReport,
  touched: Touched,
): Promise<void> {
  try {
    await scanVoidedPages(ctx, budget, now, stored, writes, report, touched);
  } finally {
    for (const { next, expectedUpdatedAt } of touched.values()) writes.push(putSubscription(ctx.db, next, expectedUpdatedAt));
  }
}

async function scanVoidedPages(
  ctx: Ctx,
  budget: Budget,
  now: number,
  stored: string | undefined,
  writes: D1PreparedStatement[],
  report: CronReport,
  touched: Touched,
): Promise<void> {
  const state: VoidedState = stored ? (JSON.parse(stored) as VoidedState) : {};
  let page = state.page;
  if (!page) {
    if (state.lastEnd !== undefined && now - state.lastEnd < ctx.config.voidedCheckIntervalMs) return;
    const startTime = Math.max(now - VOIDED_MAX_LOOKBACK_MS, (state.lastEnd ?? 0) - VOIDED_OVERLAP);
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
  const byHash = new Map<string, VoidedPurchase[]>();
  for (const v of items) {
    if (!v.purchaseToken || !v.orderId) continue;
    const h = await keyedHash(ctx.keys, "purchase", v.purchaseToken);
    byHash.set(h, [...(byHash.get(h) ?? []), v]);
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
    const freshItems = (byHash.get(row.token_hash) ?? []).filter((v) => !voided.includes(v.orderId!));
    const fresh = [...new Set(freshItems.map((v) => v.orderId!))];
    if (fresh.length === 0) continue;
    const hitsCurrent = row.order_id !== null && fresh.includes(row.order_id) && !row.replaced_by;
    // A purchase Google already ended (a refund with revoke ends it at once and shows only as EXPIRED):
    // a refund around its end is what ended it, whichever order it was.
    const ended = row.state === "expired";
    const endedByRefund =
      ended && freshItems.some((v) => Math.abs(Number(v.voidedTimeMillis) - row.expiry_time) <= REFUND_WINDOW_MS);
    const next: SubscriptionRow = { ...row, voided_orders: [...voided, ...fresh].join(" "), updated_at: now };
    if (hitsCurrent || endedByRefund) {
      if (row.key_hash && !row.key_disabled) {
        if (!budget.allows(1)) return false;
        await ctx.openrouter.updateKey(row.key_hash, { disabled: true });
        next.key_disabled = 1;
      }
      const state: SubState = "refunded";
      next.state = state;
      // An ended purchase can't renew, so there is nothing left to check.
      next.next_check_at = ended ? null : nextCheckAt(state, row.expiry_time, now, false);
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
  due: SubscriptionRow[],
  writes: D1PreparedStatement[],
  report: CronReport,
  touched: Touched,
): Promise<void> {
  for (const row of due) {
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
  rows: ExpiredTrial[],
  writes: D1PreparedStatement[],
  report: CronReport,
): Promise<void> {
  for (const t of rows) {
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

/**
 * Retention: deletes subscription rows 90 days after the purchase ended or was refunded, with their
 * OpenRouter key (already disabled; one subrequest each, so keys wait for budget). The delete is
 * conditional on updated_at, so a concurrent app call wins; its row keeps ended_at and goes next run.
 */
async function purgeEndedSubscriptions(
  ctx: Ctx,
  budget: Budget,
  rows: EndedSubscription[],
  writes: D1PreparedStatement[],
  report: CronReport,
  touched: Touched,
): Promise<void> {
  for (const row of rows) {
    if (touched.has(row.token_hash)) continue; // the refund scan rewrote it this run
    if (row.key_hash) {
      if (!budget.allows(1)) continue;
      await ctx.openrouter.deleteKey(row.key_hash);
    }
    writes.push(ctx.db.prepare("DELETE FROM subscriptions WHERE token_hash = ?1 AND updated_at = ?2").bind(row.token_hash, row.updated_at));
    report.subscriptionsDeleted++;
  }
}
