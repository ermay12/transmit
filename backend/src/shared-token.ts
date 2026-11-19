// The Google access token, shared between isolates through D1. Signing the
// RS256 token exchange costs 1–3 ms of CPU in production (the Free plan allows
// 10 ms per request), and with little traffic most requests land on a cold
// isolate. So the first isolate to get a token stores it, encrypted, and the
// others read it in their first D1 batch until it expires (an hour).

import type { Ctx } from "./context";
import { decrypt, encrypt } from "./crypto";

export const SHARED_TOKEN_ROW = "google_token";

interface Stored {
  email: string;
  expiresAt: number;
  enc: string;
}

/** Reads the shared token's job_state row; put it in the request's first batch. */
export function readSharedToken(db: D1Database): D1PreparedStatement {
  return db.prepare("SELECT value FROM job_state WHERE name = ?1").bind(SHARED_TOKEN_ROW);
}

/** Uses the stored token if this isolate has none. A bad or stale row only costs a token exchange. */
export async function adoptSharedToken(ctx: Ctx, value: unknown): Promise<void> {
  if (typeof value !== "string" || ctx.google.hasToken()) return;
  try {
    const s = JSON.parse(value) as Stored;
    if (s.email !== ctx.google.email || !ctx.google.usable(s.expiresAt)) return;
    ctx.google.adopt(await decrypt(ctx.keys, s.enc), s.expiresAt);
  } catch {
    // ignore: the next Google call exchanges a new token
  }
}

/** The write that shares a token this request got from Google, if it got one. */
export async function shareTokenWrites(ctx: Ctx): Promise<D1PreparedStatement[]> {
  const t = ctx.google.takeFresh();
  if (!t) return [];
  const value: Stored = { email: ctx.google.email, expiresAt: t.expiresAt, enc: await encrypt(ctx.keys, t.token) };
  return [
    ctx.db
      .prepare("INSERT INTO job_state (name, value) VALUES (?1, ?2) ON CONFLICT(name) DO UPDATE SET value = excluded.value")
      .bind(SHARED_TOKEN_ROW, JSON.stringify(value)),
  ];
}
