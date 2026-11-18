// POST /v1/trial: one free trial per device, gated by Play Integrity.

import type { Ctx } from "./context";
import { keyedHash, trialRequestHash } from "./crypto";
import { acquireLock, getTrial, utcDay } from "./db";
import type { IntegrityPayload } from "./google";
import { ApiError, json, log, readJson, requiredString } from "./http";
import { isoSeconds, money } from "./openrouter";

const ANDROID_ID = /^[A-Za-z0-9]+$/;
const INTEGRITY_TOKEN = /^[A-Za-z0-9._\-+/=]+$/;
/** Clock skew we tolerate for a token timestamp in the future. */
const FUTURE_SKEW_MS = 5 * 60_000;
/** A restored trial needs at least this much time and money left to be worth a key. */
const MIN_REMAINING_MS = 60_000;
const MIN_REMAINING_USD = 0.001;

export async function handleTrial(ctx: Ctx, request: Request): Promise<Response> {
  const body = await readJson(request);
  const androidId = requiredString(body, "androidId", ANDROID_ID, 64);
  const integrityToken = requiredString(body, "integrityToken", INTEGRITY_TOKEN, 20_000);
  const now = ctx.now();

  await verifyIntegrity(ctx, integrityToken, await trialRequestHash(androidId), now);

  const deviceHash = await keyedHash(ctx.keys, "device", androidId);
  const release = await acquireLock(ctx.db, `trial:${deviceHash}`, now);
  if (!release) throw new ApiError("busy", "A trial request for this device is already in progress.");
  try {
    const existing = await getTrial(ctx.db, deviceHash);
    if (existing) return await restoreTrial(ctx, existing.device_hash, existing, now);
    return await startTrial(ctx, deviceHash, now);
  } finally {
    await release();
  }
}

function integrityFailed(reason: string, message: string): ApiError {
  return new ApiError("integrity_failed", message, { reason });
}

async function verifyIntegrity(ctx: Ctx, token: string, expectedHash: string, now: number): Promise<void> {
  const payload: IntegrityPayload | null = await ctx.google.decodeIntegrityToken(token);
  if (!payload) throw integrityFailed("token_invalid", "Google could not decode the integrity token.");
  const req = payload.requestDetails ?? {};
  if (req.requestPackageName !== ctx.config.packageName) {
    throw integrityFailed("wrong_package", "The integrity token was requested by another package.");
  }
  if (req.requestHash !== expectedHash) {
    throw integrityFailed("request_hash_mismatch", "The integrity token's requestHash does not match this androidId.");
  }
  const ts = Number(req.timestampMillis);
  if (!Number.isFinite(ts) || now - ts > ctx.config.integrityMaxAgeMs || ts - now > FUTURE_SKEW_MS) {
    throw integrityFailed("token_expired", "The integrity token is too old; request a new one.");
  }
  const app = payload.appIntegrity ?? {};
  if (app.appRecognitionVerdict !== "PLAY_RECOGNIZED" || (app.packageName && app.packageName !== ctx.config.packageName)) {
    throw integrityFailed("app_not_recognized", "This copy of the app was not installed from Google Play.");
  }
  if (payload.accountDetails?.appLicensingVerdict !== "LICENSED") {
    throw integrityFailed("unlicensed", "The app is not licensed to this Google Play account.");
  }
  if (!(payload.deviceIntegrity?.deviceRecognitionVerdict ?? []).includes("MEETS_DEVICE_INTEGRITY")) {
    throw integrityFailed("device_integrity", "This device does not meet Play device integrity.");
  }
}

function secondsToNextUtcMidnight(now: number): number {
  const d = new Date(now);
  const next = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1);
  return Math.max(1, Math.ceil((next - now) / 1000));
}

async function startTrial(ctx: Ctx, deviceHash: string, now: number): Promise<Response> {
  const { trialAllowanceUsd: allowance, trialDailyBudgetUsd: budget } = ctx.config;
  const day = utcDay(now);

  // Reserve this trial's allowance in today's budget, atomically.
  const reserved = await ctx.db
    .prepare(
      `INSERT INTO trial_days (day, granted_usd, trials) SELECT ?1, ?2, 1 WHERE ?2 <= ?3 + 0.000001
       ON CONFLICT(day) DO UPDATE SET granted_usd = granted_usd + excluded.granted_usd, trials = trials + 1
       WHERE trial_days.granted_usd + excluded.granted_usd <= ?3 + 0.000001`,
    )
    .bind(day, allowance, budget)
    .run();
  if (!reserved.meta.changes) {
    const retryAfter = secondsToNextUtcMidnight(now);
    log("trial_capacity_reached", { day });
    throw new ApiError(
      "trial_capacity_reached",
      "Today's free trials are used up. Try again after midnight UTC.",
      { retryAfterSeconds: retryAfter },
      { "retry-after": String(retryAfter) },
    );
  }

  const expiresAt = Math.floor((now + ctx.config.trialDurationMs) / 1000) * 1000;
  let created;
  try {
    created = await ctx.openrouter.createKey({
      name: `${ctx.config.keyNamePrefix}-trial-${deviceHash.slice(0, 12)}`,
      limit: allowance,
      expiresAt,
    });
  } catch (err) {
    await ctx.db
      .prepare("UPDATE trial_days SET granted_usd = MAX(0, granted_usd - ?2), trials = MAX(0, trials - 1) WHERE day = ?1")
      .bind(day, allowance)
      .run();
    throw err;
  }
  await ctx.db
    .prepare("INSERT INTO trials (device_hash, created_at, expires_at, allowance_usd, key_hash) VALUES (?1, ?2, ?3, ?4, ?5)")
    .bind(deviceHash, now, expiresAt, allowance, created.hash)
    .run();
  log("trial_started", { key: created.hash.slice(0, 12) });
  return json({
    kind: "trial",
    key: created.key,
    keyHash: created.hash,
    allowanceUsd: allowance,
    remainingUsd: money(allowance),
    expiresAt: isoSeconds(expiresAt),
    restored: false,
  });
}

/** The device already had a trial. If it's still running, re-issue its key (reinstall); otherwise refuse. */
async function restoreTrial(
  ctx: Ctx,
  deviceHash: string,
  trial: { key_hash: string | null; expires_at: number; allowance_usd: number; converted_at: number | null },
  now: number,
): Promise<Response> {
  const used = () => new ApiError("trial_already_used", "This device has already used its free trial.");
  if (!trial.key_hash || trial.converted_at || trial.expires_at - now < MIN_REMAINING_MS) throw used();
  // Disable the old key first: that stops it at once and tells us what's left on it.
  const old = await ctx.openrouter.updateKey(trial.key_hash, { disabled: true });
  const remaining = old ? money(Math.min(trial.allowance_usd, (old.limit ?? 0) - old.usage)) : 0;
  if (remaining < MIN_REMAINING_USD) throw used();

  const created = await ctx.openrouter.createKey({
    name: `${ctx.config.keyNamePrefix}-trial-${deviceHash.slice(0, 12)}`,
    limit: remaining,
    expiresAt: trial.expires_at,
  });
  await ctx.db.prepare("UPDATE trials SET key_hash = ?2 WHERE device_hash = ?1").bind(deviceHash, created.hash).run();
  await ctx.openrouter.deleteQuietly(trial.key_hash);
  log("trial_restored", { key: created.hash.slice(0, 12) });
  return json({
    kind: "trial",
    key: created.key,
    keyHash: created.hash,
    allowanceUsd: trial.allowance_usd,
    remainingUsd: remaining,
    expiresAt: isoSeconds(trial.expires_at),
    restored: true,
  });
}

