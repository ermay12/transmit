// Row types and the few queries shared between the endpoints and the job.

import { log } from "./http";

export interface TrialRow {
  device_hash: string;
  created_at: number;
  expires_at: number;
  allowance_usd: number;
  key_hash: string | null;
  ended_at: number | null;
  converted_at: number | null;
}

export interface SubscriptionRow {
  token_hash: string;
  token_enc: string;
  product_id: string;
  base_plan_id: string | null;
  state: string;
  expiry_time: number;
  auto_renewing: number;
  order_id: string | null;
  allowance_usd: number;
  key_hash: string | null;
  key_disabled: number;
  limit_usd: number | null;
  voided_orders: string;
  acknowledged: number;
  replaced_by: string | null;
  next_check_at: number | null;
  created_at: number;
  updated_at: number;
  checked_at: number;
}

export function trialStmt(db: D1Database, deviceHash: string): D1PreparedStatement {
  return db.prepare("SELECT * FROM trials WHERE device_hash = ?1").bind(deviceHash);
}

export function subscriptionStmt(db: D1Database, tokenHash: string): D1PreparedStatement {
  return db.prepare("SELECT * FROM subscriptions WHERE token_hash = ?1").bind(tokenHash);
}

export function getTrial(db: D1Database, deviceHash: string): Promise<TrialRow | null> {
  return trialStmt(db, deviceHash).first<TrialRow>();
}

export function getSubscription(db: D1Database, tokenHash: string): Promise<SubscriptionRow | null> {
  return subscriptionStmt(db, tokenHash).first<SubscriptionRow>();
}

/** First row of one statement's result in a batch. */
export function firstRow<T>(result: D1Result | undefined): T | null {
  return (result?.results[0] as T | undefined) ?? null;
}

/** Full-row upsert. `expectedUpdatedAt` makes it conditional (optimistic concurrency) for the job. */
export function putSubscription(db: D1Database, row: SubscriptionRow, expectedUpdatedAt?: number): D1PreparedStatement {
  const cols = [
    "token_hash", "token_enc", "product_id", "base_plan_id", "state", "expiry_time", "auto_renewing", "order_id",
    "allowance_usd", "key_hash", "key_disabled", "limit_usd", "voided_orders", "acknowledged", "replaced_by",
    "next_check_at", "created_at", "updated_at", "checked_at",
  ] as const;
  const values = cols.map((c) => row[c]);
  if (expectedUpdatedAt !== undefined) {
    const sets = cols.slice(1).map((c, i) => `${c} = ?${i + 2}`).join(", ");
    return db
      .prepare(`UPDATE subscriptions SET ${sets} WHERE token_hash = ?1 AND updated_at = ?${cols.length + 1}`)
      .bind(...values, expectedUpdatedAt);
  }
  const placeholders = cols.map((_, i) => `?${i + 1}`).join(", ");
  const updates = cols.slice(1).map((c) => `${c} = excluded.${c}`).join(", ");
  return db
    .prepare(`INSERT INTO subscriptions (${cols.join(", ")}) VALUES (${placeholders}) ON CONFLICT(token_hash) DO UPDATE SET ${updates}`)
    .bind(...values);
}

const LOCK_MS = 30_000;

/**
 * A short lease on `name`, so two calls for the same device or purchase don't race. D1 round trips
 * cost CPU (the Free plan allows 10 ms per request), so the lease is taken in the request's first
 * batch, next to its reads, and released in the batch that carries its last writes.
 */
export class Lease {
  private held = false;
  private readonly until: number;

  constructor(
    private readonly db: D1Database,
    private readonly name: string,
    private readonly now: number,
    /** More writes for the final batch, e.g. sharing a fresh Google token. */
    private readonly extra: () => Promise<D1PreparedStatement[]> = async () => [],
  ) {
    this.until = now + LOCK_MS;
  }

  /** Put this in the request's first batch and pass its result to `took`. */
  acquire(): D1PreparedStatement {
    return this.db
      .prepare("INSERT INTO locks (name, until) VALUES (?1, ?2) ON CONFLICT(name) DO UPDATE SET until = excluded.until WHERE locks.until < ?3")
      .bind(this.name, this.until, this.now);
  }

  /** Whether the lease was ours to take (false: another call holds it). */
  took(result: D1Result | undefined): boolean {
    this.held = Boolean(result?.meta.changes);
    return this.held;
  }

  /** Runs `writes`, the extra writes and the release in one batch. */
  async finish(writes: D1PreparedStatement[] = []): Promise<void> {
    const statements = [...writes, ...(await this.extra())];
    if (this.held) statements.push(this.db.prepare("DELETE FROM locks WHERE name = ?1 AND until = ?2").bind(this.name, this.until));
    if (statements.length) await this.db.batch(statements);
    this.held = false;
  }

  /** For `finally`: releases the lease if `finish` didn't. Never throws; the lease runs out after 30 s anyway. */
  async end(): Promise<void> {
    try {
      await this.finish();
    } catch (err) {
      log("lock_release_failed", { error: String(err) });
    }
  }
}

export function utcDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

export function secondsToNextUtcMidnight(now: number): number {
  const d = new Date(now);
  const next = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1);
  return Math.max(1, Math.ceil((next - now) / 1000));
}
