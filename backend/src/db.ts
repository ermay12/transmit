// Row types and the few queries shared between the endpoints and the job.

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

export function getTrial(db: D1Database, deviceHash: string): Promise<TrialRow | null> {
  return db.prepare("SELECT * FROM trials WHERE device_hash = ?1").bind(deviceHash).first<TrialRow>();
}

export function getSubscription(db: D1Database, tokenHash: string): Promise<SubscriptionRow | null> {
  return db.prepare("SELECT * FROM subscriptions WHERE token_hash = ?1").bind(tokenHash).first<SubscriptionRow>();
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

/** Takes a short lease on `name`. Returns a release function, or null if someone else holds it. */
export async function acquireLock(db: D1Database, name: string, now: number): Promise<(() => Promise<void>) | null> {
  const until = now + LOCK_MS;
  const r = await db
    .prepare("INSERT INTO locks (name, until) VALUES (?1, ?2) ON CONFLICT(name) DO UPDATE SET until = excluded.until WHERE locks.until < ?3")
    .bind(name, until, now)
    .run();
  if (!r.meta.changes) return null;
  return async () => {
    await db.prepare("DELETE FROM locks WHERE name = ?1 AND until = ?2").bind(name, until).run();
  };
}

export function utcDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}
