// POST /v1/subscription, plus the per-purchase re-check used by the scheduled job.
//
// Allowance rule (no carry-over): whenever a new Play order is credited, the
// key's OpenRouter limit becomes (usage so far + the plan's allowance). A key
// that is rotated (restore, second device) starts at usage 0 with whatever was
// left of the current period, so "limit − usage" always means "left this period".

import { planAllowance } from "./config";
import type { Ctx } from "./context";
import { encrypt, keyedHash } from "./crypto";
import { acquireLock, getSubscription, getTrial, putSubscription, type SubscriptionRow } from "./db";
import type { SubscriptionPurchaseV2 } from "./google";
import { ApiError, json, log, optionalString, readJson, requiredString } from "./http";
import { isoSeconds, type KeyInfo, money } from "./openrouter";

export type SubState =
  | "active"
  | "canceled"
  | "grace"
  | "on_hold"
  | "paused"
  | "pending"
  | "expired"
  | "canceled_pending"
  | "refunded"
  | "replaced";

const GOOGLE_STATES: Record<string, SubState> = {
  SUBSCRIPTION_STATE_ACTIVE: "active",
  SUBSCRIPTION_STATE_CANCELED: "canceled",
  SUBSCRIPTION_STATE_IN_GRACE_PERIOD: "grace",
  SUBSCRIPTION_STATE_ON_HOLD: "on_hold",
  SUBSCRIPTION_STATE_PAUSED: "paused",
  SUBSCRIPTION_STATE_PENDING: "pending",
  SUBSCRIPTION_STATE_EXPIRED: "expired",
  SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED: "canceled_pending",
};

export interface Purchase {
  productId: string;
  basePlanId: string | null;
  googleState: string;
  expiryTime: number;
  autoRenewing: boolean;
  orderId: string | null;
  linkedPurchaseToken: string | null;
  acknowledged: boolean;
}

export function normalizePurchase(raw: SubscriptionPurchaseV2): Purchase {
  // Transmit's subscriptions have one line item; if there are more, use the one that runs longest.
  const items = raw.lineItems ?? [];
  let item = items[0];
  for (const it of items) if (Date.parse(it.expiryTime ?? "") > Date.parse(item?.expiryTime ?? "")) item = it;
  const expiry = Date.parse(item?.expiryTime ?? "");
  return {
    productId: item?.productId ?? "",
    basePlanId: item?.offerDetails?.basePlanId ?? null,
    googleState: raw.subscriptionState ?? "SUBSCRIPTION_STATE_UNSPECIFIED",
    expiryTime: Number.isFinite(expiry) ? expiry : 0,
    autoRenewing: Boolean(item?.autoRenewingPlan?.autoRenewEnabled),
    orderId: item?.latestSuccessfulOrderId ?? raw.latestOrderId ?? null,
    linkedPurchaseToken: raw.linkedPurchaseToken ?? null,
    acknowledged: raw.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
  };
}

/** Our view of a purchase: its simplified state and whether it gets AI right now. */
export function assess(p: Purchase | null, voidedOrders: string[], now: number): { state: SubState; entitled: boolean } {
  if (!p) return { state: "expired", entitled: false };
  const state = GOOGLE_STATES[p.googleState] ?? "expired";
  if (state === "expired" || state === "canceled_pending") return { state, entitled: false };
  if (p.orderId && voidedOrders.includes(p.orderId)) return { state: "refunded", entitled: false };
  const entitled = state === "active" || state === "grace" || (state === "canceled" && p.expiryTime > now);
  return { state, entitled };
}

const MIN = 60_000;
const HOUR = 60 * MIN;

/** When the scheduled job should ask Google about this purchase again (null = never). */
export function nextCheckAt(state: SubState, expiry: number, now: number, ackPending: boolean): number | null {
  let t: number | null;
  switch (state) {
    case "active":
    case "canceled":
    case "refunded":
      // Renewal or expiry happens at the end of the period; if Google hasn't settled it yet, look again hourly.
      t = expiry > now ? expiry + 5 * MIN : now + HOUR;
      break;
    case "grace":
    case "pending":
      t = now + 6 * HOUR;
      break;
    case "on_hold":
    case "paused":
      t = now + 24 * HOUR;
      break;
    default:
      t = null; // expired, canceled_pending, replaced: final
  }
  if (ackPending && t !== null) t = Math.min(t, now + HOUR);
  return t;
}

export function splitOrders(s: string | null | undefined): string[] {
  return (s ?? "").split(" ").filter(Boolean);
}

const PURCHASE_TOKEN = /^[A-Za-z0-9._\-:+/=]+$/;
const PRODUCT_ID = /^[a-z0-9][a-z0-9._]*$/;
const KEY_HASH = /^[A-Za-z0-9_-]+$/;
const ANDROID_ID = /^[A-Za-z0-9]+$/;

function notActive(state: SubState): ApiError {
  return new ApiError("purchase_not_active", `This purchase gives no AI right now (${state}).`, { state });
}

function invalid(reason: string, message: string): ApiError {
  return new ApiError("purchase_invalid", message, { reason });
}

export async function handleSubscription(ctx: Ctx, request: Request): Promise<Response> {
  const body = await readJson(request);
  const purchaseToken = requiredString(body, "purchaseToken", PURCHASE_TOKEN, 4096);
  const productId = optionalString(body, "productId", PRODUCT_ID, 200);
  const keyHash = optionalString(body, "keyHash", KEY_HASH, 200);
  const androidId = optionalString(body, "androidId", ANDROID_ID, 64);
  const now = ctx.now();

  const tokenHash = await keyedHash(ctx.keys, "purchase", purchaseToken);
  const release = await acquireLock(ctx.db, `sub:${tokenHash}`, now);
  if (!release) throw new ApiError("busy", "A request for this purchase is already in progress.");
  try {
    return await sync(ctx, { purchaseToken, tokenHash, productId, keyHash, androidId, now });
  } finally {
    await release();
  }
}

interface SyncArgs {
  purchaseToken: string;
  tokenHash: string;
  productId?: string;
  keyHash?: string;
  androidId?: string;
  now: number;
}

interface KeyPlan {
  hash: string;
  key?: string;
  limit: number;
  usage: number;
  /** Keys to delete once the database points at the new one (already disabled). */
  retire: string[];
}

async function sync(ctx: Ctx, a: SyncArgs): Promise<Response> {
  const lookup = await ctx.google.getSubscription(a.purchaseToken);
  if (lookup.kind === "invalid") throw invalid("token_unknown", "Google does not recognize this purchase token.");
  const row = await getSubscription(ctx.db, a.tokenHash);
  if (row?.replaced_by) throw notActive("replaced");
  if (lookup.kind === "gone") {
    if (row) await lapse(ctx, row, null, "expired", a.now);
    throw notActive("expired");
  }

  const p = normalizePurchase(lookup.purchase);
  const allowance = planAllowance(ctx.config, p.productId, p.basePlanId);
  if (allowance === undefined) throw invalid("unknown_product", "This purchase is not a Transmit subscription.");
  if (a.productId && a.productId !== p.productId) {
    throw invalid("product_mismatch", "The productId does not match Google's record of this purchase.");
  }

  const { state, entitled } = assess(p, splitOrders(row?.voided_orders), a.now);
  if (!entitled) {
    if (row) await lapse(ctx, row, p, state, a.now);
    log("subscription_not_active", { state });
    throw notActive(state);
  }

  const statements: D1PreparedStatement[] = [];
  let newOrder = !row || row.order_id !== p.orderId;
  let plan: KeyPlan;

  if (row?.key_hash) {
    plan =
      a.keyHash === row.key_hash
        ? await keepKey(ctx, row.key_hash, newOrder, allowance, a.tokenHash)
        : await rotateKey(ctx, row.key_hash, newOrder, allowance, a.tokenHash);
  } else {
    // First time we see this token. An upgrade (or resubscribe) links to the purchase it replaces.
    newOrder = true;
    const linked = p.linkedPurchaseToken
      ? await getSubscription(ctx.db, await keyedHash(ctx.keys, "purchase", p.linkedPurchaseToken))
      : null;
    const prevKey = linked && !linked.replaced_by ? linked.key_hash : null;
    if (prevKey && a.keyHash === prevKey) plan = await keepKey(ctx, prevKey, true, allowance, a.tokenHash);
    else if (prevKey) plan = await rotateKey(ctx, prevKey, true, allowance, a.tokenHash);
    else plan = await newKey(ctx, allowance, a.tokenHash);
    if (linked && !linked.replaced_by) {
      statements.push(
        putSubscription(ctx.db, {
          ...linked,
          state: "replaced",
          key_hash: null,
          replaced_by: a.tokenHash,
          next_check_at: null,
          updated_at: a.now,
        }),
      );
    }
  }

  // A trial on this device ends when it subscribes (no carry-over).
  if (a.androidId) {
    const deviceHash = await keyedHash(ctx.keys, "device", a.androidId);
    const trial = await getTrial(ctx.db, deviceHash);
    if (trial && !trial.converted_at) {
      let deleted = true;
      if (trial.key_hash) {
        try {
          await ctx.openrouter.deleteKey(trial.key_hash);
        } catch (err) {
          deleted = false; // the job deletes it when it expires
          log("trial_key_delete_failed", { error: String(err) });
        }
      }
      statements.push(
        ctx.db
          .prepare(
            deleted
              ? "UPDATE trials SET converted_at = ?2, key_hash = NULL, ended_at = COALESCE(ended_at, ?2) WHERE device_hash = ?1"
              : "UPDATE trials SET converted_at = ?2 WHERE device_hash = ?1",
          )
          .bind(deviceHash, a.now),
      );
    }
  }

  // Acknowledge only after the key exists. If Google refuses now, the job retries within the hour.
  let acknowledged = p.acknowledged;
  if (!acknowledged) {
    try {
      acknowledged = await ctx.google.acknowledge(p.productId, a.purchaseToken);
    } catch (err) {
      log("acknowledge_failed", { error: String(err) });
    }
  }

  statements.unshift(
    putSubscription(ctx.db, {
      token_hash: a.tokenHash,
      token_enc: row?.token_enc ?? (await encrypt(ctx.keys, a.purchaseToken)),
      product_id: p.productId,
      base_plan_id: p.basePlanId,
      state,
      expiry_time: p.expiryTime,
      auto_renewing: p.autoRenewing ? 1 : 0,
      order_id: p.orderId,
      allowance_usd: allowance,
      key_hash: plan.hash,
      key_disabled: 0,
      limit_usd: plan.limit,
      voided_orders: row?.voided_orders ?? "",
      acknowledged: acknowledged ? 1 : 0,
      replaced_by: null,
      next_check_at: nextCheckAt(state, p.expiryTime, a.now, !acknowledged),
      created_at: row?.created_at ?? a.now,
      updated_at: a.now,
      checked_at: a.now,
    }),
  );
  await ctx.db.batch(statements);
  for (const hash of plan.retire) await ctx.openrouter.deleteQuietly(hash);

  log("subscription_synced", { product: p.productId, state, newKey: Boolean(plan.key), renewed: newOrder, key: plan.hash.slice(0, 12) });
  return json({
    kind: "subscription",
    productId: p.productId,
    basePlanId: p.basePlanId,
    state,
    ...(plan.key ? { key: plan.key } : {}),
    keyHash: plan.hash,
    newKey: Boolean(plan.key),
    allowanceUsd: allowance,
    remainingUsd: Math.max(0, money(plan.limit - plan.usage)),
    allowanceRenewed: newOrder,
    periodEndsAt: p.expiryTime ? isoSeconds(p.expiryTime) : null,
    autoRenewing: p.autoRenewing,
  });
}

function subKeyName(ctx: Ctx, tokenHash: string): string {
  return `${ctx.config.keyNamePrefix}-sub-${tokenHash.slice(0, 12)}`;
}

async function newKey(ctx: Ctx, limit: number, tokenHash: string, retire: string[] = []): Promise<KeyPlan> {
  const created = await ctx.openrouter.createKey({ name: subKeyName(ctx, tokenHash), limit });
  return { hash: created.hash, key: created.key, limit: money(limit), usage: 0, retire };
}

/** Keep the app's key: credit a new order if there is one, and make sure it's enabled. */
async function keepKey(ctx: Ctx, hash: string, newOrder: boolean, allowance: number, tokenHash: string): Promise<KeyPlan> {
  const info = await ctx.openrouter.getKey(hash);
  if (!info) return newKey(ctx, allowance, tokenHash); // deleted outside the Worker
  const limit = newOrder || info.limit === null ? money(info.usage + allowance) : info.limit;
  if (info.disabled || info.limit !== limit) {
    const updated = await ctx.openrouter.updateKey(hash, { limit, disabled: false });
    if (!updated) return newKey(ctx, allowance, tokenHash);
  }
  return { hash, limit, usage: info.usage, retire: [] };
}

/** Replace the key: disable the old one (which also tells us its usage), then issue a new one. */
async function rotateKey(ctx: Ctx, oldHash: string, newOrder: boolean, allowance: number, tokenHash: string): Promise<KeyPlan> {
  const old: KeyInfo | null = await ctx.openrouter.updateKey(oldHash, { disabled: true });
  const remaining = newOrder || !old ? allowance : Math.min(allowance, Math.max(0, (old.limit ?? 0) - old.usage));
  return newKey(ctx, remaining, tokenHash, [oldHash]);
}

/** The purchase gives no AI now: disable its key and record the state. */
async function lapse(ctx: Ctx, row: SubscriptionRow, p: Purchase | null, state: SubState, now: number): Promise<void> {
  if (row.key_hash && !row.key_disabled) await ctx.openrouter.updateKey(row.key_hash, { disabled: true });
  const expiry = p?.expiryTime || row.expiry_time;
  await putSubscription(ctx.db, {
    ...row,
    state,
    expiry_time: expiry,
    auto_renewing: p ? (p.autoRenewing ? 1 : 0) : 0,
    key_disabled: row.key_hash ? 1 : 0,
    next_check_at: nextCheckAt(state, expiry, now, false),
    updated_at: now,
    checked_at: now,
  }).run();
  log("subscription_lapsed", { state, key: row.key_hash?.slice(0, 12) ?? null });
}

/**
 * The scheduled job's re-check of one stored purchase. Never rotates keys (only the app can
 * receive a new one); it credits renewals, disables lapsed purchases and retries acknowledgements.
 * Returns a conditional write, so a concurrent request from the app wins.
 * Costs at most 4 subrequests (Google get, OpenRouter get + update or update, acknowledge).
 */
export async function recheck(ctx: Ctx, row: SubscriptionRow, purchaseToken: string, now: number): Promise<{ stmt: D1PreparedStatement; outcome: string }> {
  const lookup = await ctx.google.getSubscription(purchaseToken);
  const p = lookup.kind === "found" ? normalizePurchase(lookup.purchase) : null;
  const { state, entitled } = assess(p, splitOrders(row.voided_orders), now);
  const next: SubscriptionRow = { ...row, state, checked_at: now, updated_at: now };
  if (p) {
    next.expiry_time = p.expiryTime || row.expiry_time;
    next.auto_renewing = p.autoRenewing ? 1 : 0;
  } else next.auto_renewing = 0;
  let outcome = "unchanged";

  if (entitled && p) {
    const allowance = planAllowance(ctx.config, p.productId, p.basePlanId) ?? row.allowance_usd;
    if (p.orderId !== row.order_id && row.key_hash) {
      const info = await ctx.openrouter.getKey(row.key_hash);
      if (info) {
        const limit = money(info.usage + allowance);
        await ctx.openrouter.updateKey(row.key_hash, { limit, disabled: false });
        Object.assign(next, { limit_usd: limit, key_disabled: 0 });
        outcome = "renewed";
      } else {
        next.key_hash = null; // gone; the app's next call issues a new key
        outcome = "key_missing";
      }
      Object.assign(next, { order_id: p.orderId, allowance_usd: allowance, product_id: p.productId, base_plan_id: p.basePlanId });
    } else if (row.key_disabled && row.key_hash) {
      await ctx.openrouter.updateKey(row.key_hash, { disabled: false });
      next.key_disabled = 0;
      outcome = "reenabled";
    }
    if (!p.acknowledged) {
      const ok = await ctx.google.acknowledge(p.productId, purchaseToken).catch(() => false);
      next.acknowledged = ok ? 1 : 0;
      if (ok) outcome += "+acknowledged";
    } else next.acknowledged = 1;
  } else if (row.key_hash && !row.key_disabled) {
    await ctx.openrouter.updateKey(row.key_hash, { disabled: true });
    next.key_disabled = 1;
    outcome = "disabled";
  }

  next.next_check_at = nextCheckAt(state, next.expiry_time, now, entitled && !next.acknowledged);
  return { stmt: putSubscription(ctx.db, next, row.updated_at), outcome: `${state}:${outcome}` };
}
