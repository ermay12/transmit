// POST /v1/subscription, plus the per-purchase re-check used by the scheduled job.
//
// Allowance rule (no carry-over): whenever a new Play order is credited, the
// key's OpenRouter limit becomes (usage so far + the plan's allowance). A key
// that is rotated (restore, second device) starts at usage 0 with whatever was
// left of the current period, so "limit − usage" always means "left this period".

import { planAllowance } from "./config";
import type { Ctx } from "./context";
import { encrypt, keyedHash } from "./crypto";
import { firstRow, getSubscription, Lease, putSubscription, subscriptionStmt, type SubscriptionRow, trialStmt, type TrialRow } from "./db";
import type { PurchaseLineItem, SubscriptionPurchaseV2 } from "./google";
import { ApiError, json, log, optionalBoolean, optionalString, readJson, requiredString } from "./http";
import { isoSeconds, type KeyInfo, money } from "./openrouter";
import { adoptSharedToken, readSharedToken, shareTokenWrites } from "./shared-token";

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
  /** The current item: the plan that gives AI now. */
  productId: string;
  basePlanId: string | null;
  googleState: string;
  expiryTime: number;
  /** Whether the subscription renews, as the current plan or as the one a pending change switches to. */
  autoRenewing: boolean;
  orderId: string | null;
  linkedPurchaseToken: string | null;
  acknowledged: boolean;
  /** Every line item's product, for the productId check and the acknowledgement. */
  productIds: string[];
  /** The current item's deferredItemReplacement: a DEFERRED change to this product is scheduled. */
  deferredTo: string | null;
  /** The plan a DEFERRED change switches to at expiryTime; null if none, or if renewal is off (it never happens). */
  pendingProductId: string | null;
  pendingBasePlanId: string | null;
  /** How the current item replaced an item of an older purchase (itemReplacement.replacementMode), if it did. */
  replacementMode: string | null;
  /** Why Google ended the purchase (canceledStateContext). */
  endReason: "replacement" | "user" | "system" | "developer" | null;
}

/** An item's expiryTime; -Infinity if it has none (the item a DEFERRED change switches to, until it starts). */
function itemEnd(item: PurchaseLineItem | undefined): number {
  const t = Date.parse(item?.expiryTime ?? "");
  return Number.isFinite(t) ? t : -Infinity;
}

export function normalizePurchase(raw: SubscriptionPurchaseV2): Purchase {
  // The current item is the one that runs longest. Transmit's subscriptions have one item, except
  // after a DEFERRED plan change (Plus -> Transmit): until the renewal, the old item is current (it has
  // the expiryTime and a deferredItemReplacement) and the new item has no expiryTime; from the
  // renewal on, the new item runs longest.
  // Google marks the item that hasn't started three ways (no expiryTime, no latestSuccessfulOrderId,
  // named by the other item's deferredItemReplacement); skip it on any of them, so the old plan's
  // allowance can't drop early.
  const items = raw.lineItems ?? [];
  const targets = new Set(items.map((it) => it.deferredItemReplacement?.productId).filter(Boolean));
  const started = items.filter((it) => !targets.has(it.productId) && it.latestSuccessfulOrderId);
  const pool = started.length ? started : items;
  let item = pool[0];
  for (const it of pool) if (itemEnd(it) > itemEnd(item)) item = it;
  const expiry = itemEnd(item);
  const googleState = raw.subscriptionState ?? "SUBSCRIPTION_STATE_UNSPECIFIED";
  const deferredTo = item?.deferredItemReplacement?.productId || null;
  // While a change is pending the current item has renewal off; the subscription renews as the new
  // item. ACTIVE means some item renews (CANCELED: none does).
  const autoRenewing =
    items.some((it) => it.autoRenewingPlan?.autoRenewEnabled === true) ||
    (deferredTo !== null && googleState === "SUBSCRIPTION_STATE_ACTIVE");
  const pendingItem = deferredTo ? items.find((it) => it !== item && it.productId === deferredTo) : undefined;
  const ended = raw.canceledStateContext;
  return {
    productId: item?.productId ?? "",
    basePlanId: item?.offerDetails?.basePlanId ?? null,
    googleState,
    expiryTime: Number.isFinite(expiry) ? expiry : 0,
    autoRenewing,
    orderId: item?.latestSuccessfulOrderId ?? raw.latestOrderId ?? null,
    linkedPurchaseToken: raw.linkedPurchaseToken ?? null,
    acknowledged: raw.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    productIds: [...new Set(items.map((it) => it.productId).filter((id): id is string => Boolean(id)))],
    deferredTo,
    pendingProductId: deferredTo && autoRenewing ? deferredTo : null,
    pendingBasePlanId: deferredTo && autoRenewing ? (pendingItem?.offerDetails?.basePlanId ?? null) : null,
    replacementMode: item?.itemReplacement?.replacementMode ?? null,
    endReason: ended?.replacementCancellation
      ? "replacement"
      : ended?.userInitiatedCancellation
        ? "user"
        : ended?.systemInitiatedCancellation
          ? "system"
          : ended?.developerInitiatedCancellation
            ? "developer"
            : null,
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

/**
 * Two period ends closer than this are the same period. Google copies the old item's expiryTime
 * into a DEFERRED purchase; a renewal moves it by a whole period (5 minutes for test purchases).
 */
const SAME_PERIOD_SLACK_MS = MIN;

/**
 * How long a replaced purchase's key stays on after its period while the app hasn't sent the
 * newer token. Play refunds a purchase that nobody acknowledges within 3 days.
 */
export const SUCCESSOR_WAIT_MS = 72 * HOUR;

/**
 * Whether a purchase that replaces `linked` starts a paid period of its own (credit a full
 * allowance), or carries on the period already credited on `linked`: a DEFERRED plan change before
 * its renewal, a resubscribe before expiry, or a switch back from a pending change.
 */
export function startsNewPeriod(p: Purchase, linked: SubscriptionRow): boolean {
  if (!p.deferredTo && (p.replacementMode === "CHARGE_FULL_PRICE" || p.replacementMode === "CHARGE_PRORATED_PRICE")) return true;
  // Otherwise only a later period end means a new paid period (for example, the app sends a DEFERRED
  // purchase for the first time after its renewal, or the old purchase renewed before we saw it).
  return p.expiryTime > linked.expiry_time + SAME_PERIOD_SLACK_MS;
}

/**
 * Google ended this purchase because a newer one replaced it (an upgrade, a DEFERRED plan change, a
 * resubscribe), and the app hasn't sent us the newer token yet. That is not a lapse: the user's
 * period moved to the newer purchase, so the key must keep working until the app sends it.
 */
export function replacedBeforeLinked(p: Purchase | null, row: SubscriptionRow, voidedOrders: string[]): boolean {
  if (!p || row.replaced_by || p.googleState !== "SUBSCRIPTION_STATE_EXPIRED") return false;
  if (p.orderId && voidedOrders.includes(p.orderId)) return false;
  if (p.endReason === "replacement") return true;
  // No reason given, but it ended before the period we credited: most likely replaced as well.
  return p.endReason === null && p.expiryTime < row.expiry_time - SAME_PERIOD_SLACK_MS;
}

/** The row of a purchase that replacedBeforeLinked(), on first sight: wait for the newer token. */
function awaitingSuccessor(row: SubscriptionRow, now: number): SubscriptionRow {
  return {
    ...row,
    state: "replaced",
    auto_renewing: 0,
    pending_product_id: null,
    pending_base_plan_id: null,
    // expiry_time stays the end of the period we credited: startsNewPeriod() compares against it.
    next_check_at: Math.max(row.expiry_time, now) + SUCCESSOR_WAIT_MS,
    updated_at: now,
    checked_at: now,
  };
}

/** Product IDs to acknowledge a purchase with: the current item's first (a DEFERRED purchase reports the old product). */
function ackCandidates(p: Purchase): string[] {
  return [...new Set([p.productId, ...p.productIds].filter(Boolean))];
}

const PURCHASE_TOKEN = /^[A-Za-z0-9._\-:+/=]+$/;
const PRODUCT_ID = /^[a-z0-9][a-z0-9._]*$/;
const KEY_HASH = /^[A-Za-z0-9_-]+$/;
const ANDROID_ID = /^[A-Za-z0-9]+$/;

function notActive(state: SubState): ApiError {
  return new ApiError("purchase_not_active", `This purchase gives no AI right now (${state}).`, { state });
}

function keyMismatch(): ApiError {
  return new ApiError("key_mismatch", "This purchase's key is held elsewhere; keyHash does not match it.");
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
  const rotate = optionalBoolean(body, "rotate") ?? true;
  const now = ctx.now();

  const tokenHash = await keyedHash(ctx.keys, "purchase", purchaseToken);
  const deviceHash = androidId ? await keyedHash(ctx.keys, "device", androidId) : undefined;

  // One D1 round trip: take the purchase's lease and read everything the sync needs up front.
  const lease = new Lease(ctx.db, `sub:${tokenHash}`, now, () => shareTokenWrites(ctx));
  const first = [lease.acquire(), subscriptionStmt(ctx.db, tokenHash), readSharedToken(ctx.db)];
  if (deviceHash) first.push(trialStmt(ctx.db, deviceHash));
  const [locked, row, token, trial] = await ctx.db.batch(first);
  if (!lease.took(locked)) throw new ApiError("busy", "A request for this purchase is already in progress.");
  try {
    await adoptSharedToken(ctx, firstRow<{ value: string }>(token)?.value);
    return await sync(ctx, lease, {
      purchaseToken,
      tokenHash,
      productId,
      keyHash,
      rotate,
      row: firstRow<SubscriptionRow>(row),
      deviceHash,
      trial: firstRow<TrialRow>(trial),
      now,
    });
  } finally {
    await lease.end();
  }
}

interface SyncArgs {
  purchaseToken: string;
  tokenHash: string;
  productId?: string;
  keyHash?: string;
  /** false: answer key_mismatch instead of taking the key from whoever holds it. */
  rotate: boolean;
  /** This purchase's row, read under the lease. */
  row: SubscriptionRow | null;
  deviceHash?: string;
  /** The trial of the device in `androidId`, if any. */
  trial: TrialRow | null;
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

async function sync(ctx: Ctx, lease: Lease, a: SyncArgs): Promise<Response> {
  const lookup = await ctx.google.getSubscription(a.purchaseToken);
  if (lookup.kind === "invalid") throw invalid("token_unknown", "Google does not recognize this purchase token.");
  const row = a.row;
  if (row?.replaced_by) throw notActive("replaced");
  if (lookup.kind === "gone") {
    if (row) await lapse(ctx, lease, row, null, "expired", a.now);
    throw notActive("expired");
  }

  const p = normalizePurchase(lookup.purchase);
  const allowance = planAllowance(ctx.config, p.productId, p.basePlanId);
  if (allowance === undefined) throw invalid("unknown_product", "This purchase is not a Transmit subscription.");
  // After a DEFERRED change the app may name either plan: Play reports the old one until the renewal.
  if (a.productId && a.productId !== p.productId && a.productId !== p.deferredTo) {
    throw invalid("product_mismatch", "The productId does not match Google's record of this purchase.");
  }

  const voided = splitOrders(row?.voided_orders);
  if (row && replacedBeforeLinked(p, row, voided)) {
    // The old token of a plan change, before the app sent the new one: keep the key on, and point the app at the new token.
    if (row.state !== "replaced") await lease.finish([putSubscription(ctx.db, awaitingSuccessor(row, a.now))]);
    log("subscription_replaced_unlinked", { key: row.key_hash?.slice(0, 12) ?? null });
    throw notActive("replaced");
  }
  const { state, entitled } = assess(p, voided, a.now);
  if (!entitled) {
    if (row) await lapse(ctx, lease, row, p, state, a.now);
    log("subscription_not_active", { state });
    throw notActive(state);
  }

  const statements: D1PreparedStatement[] = [];
  let newOrder = !row || row.order_id !== p.orderId;
  let plan: KeyPlan;

  if (row?.key_hash) {
    if (a.keyHash === row.key_hash) plan = await keepKey(ctx, row.key_hash, newOrder, allowance, a.tokenHash);
    else if (!a.rotate) throw keyMismatch();
    else plan = await rotateKey(ctx, row.key_hash, newOrder, allowance, a.tokenHash);
  } else {
    // First time we see this token. An upgrade, a plan change or a resubscribe links to the purchase it
    // replaces; it takes over that purchase's key. A full allowance is credited only if it starts a
    // paid period of its own: a DEFERRED switch to Transmit keeps the Plus period (and its limit) until the renewal.
    const found = p.linkedPurchaseToken
      ? await getSubscription(ctx.db, await keyedHash(ctx.keys, "purchase", p.linkedPurchaseToken))
      : null;
    const linked = found && !found.replaced_by ? found : null;
    newOrder = !linked || startsNewPeriod(p, linked);
    const prevKey = linked?.key_hash ?? null;
    if (prevKey && a.keyHash === prevKey) plan = await keepKey(ctx, prevKey, newOrder, allowance, a.tokenHash);
    else if (prevKey && !a.rotate) throw keyMismatch();
    else if (prevKey) plan = await rotateKey(ctx, prevKey, newOrder, allowance, a.tokenHash);
    else plan = await newKey(ctx, allowance, a.tokenHash);
    if (linked) {
      statements.push(
        putSubscription(ctx.db, {
          ...linked,
          state: "replaced",
          key_hash: null,
          replaced_by: a.tokenHash,
          next_check_at: null,
          pending_product_id: null,
          pending_base_plan_id: null,
          updated_at: a.now,
        }),
      );
    }
  }

  // A trial on this device ends when it subscribes (no carry-over).
  const trial = a.trial;
  if (a.deviceHash && trial && !trial.converted_at) {
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
        .bind(a.deviceHash, a.now),
    );
  }

  // Acknowledge only after the key exists (for a DEFERRED change, right away, not at the renewal).
  // If Google refuses now, the job retries within the hour.
  let acknowledged = p.acknowledged;
  for (const id of acknowledged ? [] : ackCandidates(p)) {
    try {
      acknowledged = await ctx.google.acknowledge(id, a.purchaseToken);
    } catch (err) {
      log("acknowledge_failed", { error: String(err) });
      break;
    }
    if (acknowledged) break;
  }
  if (!acknowledged) log("acknowledge_refused", { products: ackCandidates(p).length });

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
      pending_product_id: p.pendingProductId,
      pending_base_plan_id: p.pendingBasePlanId,
    }),
  );
  await lease.finish(statements);
  for (const hash of plan.retire) await ctx.openrouter.deleteQuietly(hash);

  const pending = pendingPlan(ctx, p);
  log("subscription_synced", {
    product: p.productId,
    state,
    newKey: Boolean(plan.key),
    renewed: newOrder,
    pending: p.pendingProductId ?? undefined,
    key: plan.hash.slice(0, 12),
  });
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
    ...(pending ?? {}),
  });
}

/** Response fields for a DEFERRED change that takes effect at the renewal (absent if none is pending). */
function pendingPlan(ctx: Ctx, p: Purchase): { pendingProductId: string; pendingAllowanceUsd: number; pendingStartsAt: string } | null {
  if (!p.pendingProductId || !p.expiryTime) return null;
  const allowance = planAllowance(ctx.config, p.pendingProductId, p.pendingBasePlanId);
  if (allowance === undefined) {
    log("pending_plan_unknown", { product: p.pendingProductId });
    return null;
  }
  return { pendingProductId: p.pendingProductId, pendingAllowanceUsd: allowance, pendingStartsAt: isoSeconds(p.expiryTime) };
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
async function lapse(ctx: Ctx, lease: Lease, row: SubscriptionRow, p: Purchase | null, state: SubState, now: number): Promise<void> {
  if (row.key_hash && !row.key_disabled) await ctx.openrouter.updateKey(row.key_hash, { disabled: true });
  const expiry = p?.expiryTime || row.expiry_time;
  const write = putSubscription(ctx.db, {
    ...row,
    state,
    expiry_time: expiry,
    auto_renewing: p ? (p.autoRenewing ? 1 : 0) : 0,
    key_disabled: row.key_hash ? 1 : 0,
    next_check_at: nextCheckAt(state, expiry, now, false),
    pending_product_id: null,
    pending_base_plan_id: null,
    updated_at: now,
    checked_at: now,
  });
  await lease.finish([write]);
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
  const voided = splitOrders(row.voided_orders);
  if (replacedBeforeLinked(p, row, voided)) return recheckReplaced(ctx, row, now);
  const { state, entitled } = assess(p, voided, now);
  const next: SubscriptionRow = { ...row, state, checked_at: now, updated_at: now };
  if (p) {
    next.expiry_time = p.expiryTime || row.expiry_time;
    next.auto_renewing = p.autoRenewing ? 1 : 0;
    next.pending_product_id = entitled ? p.pendingProductId : null;
    next.pending_base_plan_id = entitled ? p.pendingBasePlanId : null;
  } else Object.assign(next, { auto_renewing: 0, pending_product_id: null, pending_base_plan_id: null });
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
      // One attempt per run (the job's budget). With several products (a DEFERRED change), the hourly
      // retries take turns; the app's own calls try them all.
      const ids = ackCandidates(p);
      const ok = await ctx.google.acknowledge(ids[Math.floor(now / HOUR) % ids.length] ?? p.productId, purchaseToken).catch(() => false);
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

/**
 * The job found a purchase that a newer one replaced, before the app sent the newer token. Its key
 * stays on (the paid period moved to the newer purchase) until the app sends it. If it never does
 * within SUCCESSOR_WAIT_MS after the period, the key goes off: by then Play has refunded the
 * unacknowledged newer purchase.
 */
async function recheckReplaced(ctx: Ctx, row: SubscriptionRow, now: number): Promise<{ stmt: D1PreparedStatement; outcome: string }> {
  if (row.state !== "replaced") {
    return { stmt: putSubscription(ctx.db, awaitingSuccessor(row, now), row.updated_at), outcome: "replaced:awaiting_successor" };
  }
  // Already waiting and due again: the wait is over.
  const next: SubscriptionRow = { ...row, next_check_at: null, checked_at: now, updated_at: now };
  if (row.key_hash && !row.key_disabled) {
    await ctx.openrouter.updateKey(row.key_hash, { disabled: true });
    next.key_disabled = 1;
  }
  log("replaced_never_linked", { key: row.key_hash?.slice(0, 12) ?? null });
  return { stmt: putSubscription(ctx.db, next, row.updated_at), outcome: "replaced:disabled" };
}
