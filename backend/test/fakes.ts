// In-memory stand-ins for Google (OAuth, Play Developer API, Play Integrity)
// and OpenRouter's key management API, served through an injected fetch.

import { env } from "cloudflare:workers";
import type { Env } from "../src/config";
import type { Deps } from "../src/context";
import { b64, sha256Hex, trialRequestHash } from "../src/crypto";
import type { IntegrityPayload, SubscriptionPurchaseV2, VoidedPurchase } from "../src/google";

export const PKG = "com.ermay12.transmit";
const PUBLISHER = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PKG}`;
const GOOGLE_TOKEN = "ya29.fake-access-token";
export const DAY = 86_400_000;

export interface FakeKey {
  hash: string;
  key: string;
  name: string;
  limit: number | null;
  usage: number;
  disabled: boolean;
  expires_at: string | null;
  workspace_id: string;
}

interface Call {
  method: string;
  url: string;
  body?: Record<string, unknown>;
}

let serviceAccount: Promise<{ json: string; publicKey: CryptoKey }> | undefined;

/** A throwaway RSA service account, so the JWT signing path runs for real. */
export function fakeServiceAccount(): Promise<{ json: string; publicKey: CryptoKey }> {
  serviceAccount ??= (async () => {
    const kp = (await crypto.subtle.generateKey(
      { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
      true,
      ["sign", "verify"],
    )) as CryptoKeyPair;
    const pkcs8 = (await crypto.subtle.exportKey("pkcs8", kp.privateKey)) as ArrayBuffer;
    const pem = `-----BEGIN PRIVATE KEY-----\n${b64(pkcs8).match(/.{1,64}/g)!.join("\n")}\n-----END PRIVATE KEY-----\n`;
    const json = JSON.stringify({
      type: "service_account",
      client_email: "transmit-backend@test.iam.gserviceaccount.com",
      private_key: pem,
      token_uri: "https://oauth2.googleapis.com/token",
    });
    return { json, publicKey: kp.publicKey };
  })();
  return serviceAccount;
}

function b64urlDecode(s: string): Uint8Array {
  const pad = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  return Uint8Array.from(atob(pad), (c) => c.charCodeAt(0));
}

function res(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

export class FakeWorld {
  now = Date.UTC(2026, 10, 18, 12, 0, 0);
  calls: Call[] = [];
  keys = new Map<string, FakeKey>();
  purchases = new Map<string, SubscriptionPurchaseV2>();
  integrity = new Map<string, IntegrityPayload>();
  voided: VoidedPurchase[] = [];
  tokenExchanges = 0;
  /** Make the next N OpenRouter key creations fail with 500. */
  failCreates = 0;
  failAcks = false;
  /** Purchase token -> the only productId Google accepts when acknowledging it (default: any of its items). */
  ackOnly = new Map<string, string>();
  /** Shared like a warm isolate's cache; reset it to simulate a cold start. */
  tokenCache = {};
  /** Calls per client the fake rate-limit bindings allow (no time window; a test is one window). */
  rateLimits: Record<"TRIAL_RATE_LIMIT" | "SUBSCRIPTION_RATE_LIMIT", number> = { TRIAL_RATE_LIMIT: 1000, SUBSCRIPTION_RATE_LIMIT: 1000 };
  rateCounts = new Map<string, number>();
  /** OpenRouter's account credits (GET /credits). */
  credits = { total: 100, usage: 10 };
  /** OpenRouter's zero-data-retention endpoints (GET /endpoints/zdr): model and status (0 = up). */
  zdr: { model: string; status: number }[] = [
    { model: "google/gemma-4-31b-it", status: 0 },
    { model: "google/gemma-4-31b-it", status: -2 },
    { model: "other/model", status: 0 },
  ];
  /** Make OpenRouter's /credits and /endpoints/zdr fail with 503. */
  failOpenRouterReads = false;
  /** Emails sent through Resend. */
  emails: { from: string; to: string[]; subject: string; text: string }[] = [];
  failEmails = false;
  private seq = 0;
  private publicKey?: CryptoKey;

  async env(overrides: Partial<Env> = {}): Promise<Env> {
    const sa = await fakeServiceAccount();
    this.publicKey = sa.publicKey;
    return {
      ...(env as unknown as Env),
      GOOGLE_SERVICE_ACCOUNT_JSON: sa.json,
      TRIAL_RATE_LIMIT: this.limiter("TRIAL_RATE_LIMIT"),
      SUBSCRIPTION_RATE_LIMIT: this.limiter("SUBSCRIPTION_RATE_LIMIT"),
      ...overrides,
    };
  }

  private limiter(name: keyof FakeWorld["rateLimits"]): RateLimit {
    return {
      limit: async ({ key }) => {
        const k = `${name}|${key}`;
        const n = (this.rateCounts.get(k) ?? 0) + 1;
        this.rateCounts.set(k, n);
        return { success: n <= this.rateLimits[name] };
      },
    };
  }

  deps(): Deps {
    return { fetch: this.fetch, now: () => this.now, tokenCache: this.tokenCache };
  }

  count(method: string, urlPart: string): number {
    return this.calls.filter((c) => c.method === method && c.url.includes(urlPart)).length;
  }

  liveKeys(): FakeKey[] {
    return [...this.keys.values()].filter((k) => !k.disabled);
  }

  /** Spend money on a key, as OpenRouter would record it. */
  spend(hash: string, usd: number): void {
    const k = this.keys.get(hash);
    if (!k) throw new Error("no such key");
    k.usage = Math.round((k.usage + usd) * 1e6) / 1e6;
  }

  fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const req = new Request(input, init);
    const url = new URL(req.url);
    const text = req.method === "GET" ? "" : new TextDecoder().decode(await req.arrayBuffer());
    const body = text && req.headers.get("content-type")?.includes("json") ? (JSON.parse(text) as Record<string, unknown>) : undefined;
    this.calls.push({ method: req.method, url: req.url, body });
    const auth = req.headers.get("authorization");

    if (url.href === "https://oauth2.googleapis.com/token") return this.tokenExchange(new URLSearchParams(text));

    if (url.hostname.endsWith("googleapis.com")) {
      if (auth !== `Bearer ${GOOGLE_TOKEN}`) return res(401, { error: { message: "bad token" } });
      return this.google(req.method, url, body);
    }
    if (url.hostname === "api.resend.com" && url.pathname === "/emails" && req.method === "POST") {
      if (auth !== "Bearer test-resend-key") return res(401, { message: "API key is invalid" });
      if (this.failEmails) return res(500, { message: "internal_server_error" });
      this.emails.push(body as FakeWorld["emails"][number]);
      return res(200, { id: `email-${this.emails.length}` });
    }
    if (url.hostname === "openrouter.ai") {
      if (auth !== "Bearer test-management-key") return res(401, { error: { message: "bad management key" } });
      return this.openrouter(req.method, url.pathname.replace(/^\/api\/v1/, ""), body);
    }
    return res(599, { error: { message: `unexpected host ${url.hostname}` } });
  };

  private async tokenExchange(form: URLSearchParams): Promise<Response> {
    this.tokenExchanges++;
    const jwt = form.get("assertion") ?? "";
    const [h, p, s] = jwt.split(".");
    const ok =
      form.get("grant_type") === "urn:ietf:params:oauth:grant-type:jwt-bearer" &&
      h && p && s && this.publicKey &&
      (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", this.publicKey, b64urlDecode(s), new TextEncoder().encode(`${h}.${p}`)));
    if (!ok) return res(400, { error: "invalid_grant" });
    const claims = JSON.parse(new TextDecoder().decode(b64urlDecode(p))) as { scope: string };
    if (!claims.scope.includes("androidpublisher") || !claims.scope.includes("playintegrity")) return res(400, { error: "invalid_scope" });
    return res(200, { access_token: GOOGLE_TOKEN, expires_in: 3599, token_type: "Bearer" });
  }

  private google(method: string, url: URL, body?: Record<string, unknown>): Response {
    const path = decodeURIComponent(url.pathname);
    if (url.hostname === "playintegrity.googleapis.com" && method === "POST" && path === `/v1/${PKG}:decodeIntegrityToken`) {
      const payload = this.integrity.get(String(body?.integrity_token));
      return payload ? res(200, { tokenPayloadExternal: payload }) : res(400, { error: { message: "Integrity token cannot be decoded." } });
    }
    const pub = decodeURIComponent(`${url.origin}${url.pathname}`);
    const sub = pub.match(new RegExp(`^${PUBLISHER.replace(/\./g, "\\.")}/purchases/subscriptionsv2/tokens/(.+)$`));
    if (sub && method === "GET") {
      const p = this.purchases.get(sub[1]!);
      return p ? res(200, structuredClone(p)) : res(400, { error: { message: "Invalid Value", status: "INVALID_ARGUMENT" } });
    }
    const ack = pub.match(new RegExp(`^${PUBLISHER.replace(/\./g, "\\.")}/purchases/subscriptions/([^/]+)/tokens/(.+):acknowledge$`));
    if (ack && method === "POST") {
      if (this.failAcks) return res(503, { error: { message: "backend error" } });
      const p = this.purchases.get(ack[2]!);
      const only = this.ackOnly.get(ack[2]!);
      const ok = only ? only === ack[1] : p?.lineItems?.some((it) => it.productId === ack[1]);
      if (!p || !ok) return res(400, { error: { message: "Invalid Value" } });
      p.acknowledgementState = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED";
      return new Response(null, { status: 204 });
    }
    if (pub === `${PUBLISHER}/purchases/voidedpurchases` && method === "GET") {
      if (url.searchParams.get("type") !== "1") return res(400, { error: { message: "type must be 1" } });
      // Filters on voidedTimeMillis like Google (entries without one always match); the page token carries the window.
      const size = Number(url.searchParams.get("maxResults") ?? 1000);
      const [offset, from, to] = (url.searchParams.get("token") ?? `0|${url.searchParams.get("startTime") ?? ""}|${url.searchParams.get("endTime") ?? ""}`).split("|");
      const start = Number(offset);
      const matching = this.voided.filter((v) => {
        const t = Number(v.voidedTimeMillis ?? NaN);
        return !Number.isFinite(t) || ((!from || t >= Number(from)) && (!to || t <= Number(to)));
      });
      const items = matching.slice(start, start + size);
      const next = start + size < matching.length ? `${start + size}|${from}|${to}` : undefined;
      return res(200, { voidedPurchases: items, ...(next ? { tokenPagination: { nextPageToken: next } } : {}) });
    }
    return res(404, { error: { message: `unexpected Google call ${method} ${url.pathname}` } });
  }

  private async openrouter(method: string, path: string, body?: Record<string, unknown>): Promise<Response> {
    const view = (k: FakeKey) => ({
      hash: k.hash,
      name: k.name,
      label: `${k.key.slice(0, 11)}...`,
      limit: k.limit,
      limit_remaining: k.limit === null ? null : Math.max(0, k.limit - k.usage),
      usage: k.usage,
      disabled: k.disabled,
      expires_at: k.expires_at,
      workspace_id: k.workspace_id,
    });
    if ((path === "/credits" || path === "/endpoints/zdr") && method === "GET" && this.failOpenRouterReads) {
      return res(503, { error: { message: "Service Unavailable" } });
    }
    if (path === "/credits" && method === "GET") return res(200, { data: { total_credits: this.credits.total, total_usage: this.credits.usage } });
    if (path === "/endpoints/zdr" && method === "GET") {
      // Shaped like the real list: "name" first, "model_id", nested "pricing", then "status" further on.
      const data = this.zdr.map((e, i) => ({
        name: `Host${i} | ${e.model}`,
        model_id: e.model,
        model_name: e.model,
        pricing: { prompt: "0.0000001", completion: "0.0000002" },
        provider_name: `Host${i}`,
        status: e.status,
        uptime_last_30m: e.status === 0 ? 100 : 50,
        safety_retention: false,
      }));
      return res(200, { data });
    }
    if (path === "/keys" && method === "POST") {
      if (this.failCreates > 0) {
        this.failCreates--;
        return res(500, { error: { message: "Internal Server Error" } });
      }
      if (typeof body?.name !== "string") return res(400, { error: { message: "name required" } });
      if (body.expires_at !== undefined && !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(String(body.expires_at))) {
        return res(400, { error: { message: "expires_at must include seconds" } });
      }
      const n = ++this.seq;
      const hash = await sha256Hex(`key-${n}`);
      const k: FakeKey = {
        hash,
        key: `sk-or-v1-fake${n}`,
        name: body.name,
        limit: (body.limit as number | undefined) ?? null,
        usage: 0,
        disabled: false,
        expires_at: (body.expires_at as string | undefined) ?? null,
        workspace_id: String(body.workspace_id ?? "default"),
      };
      this.keys.set(hash, k);
      return res(201, { data: view(k), key: k.key });
    }
    const m = path.match(/^\/keys\/([^/]+)$/);
    const k = m ? this.keys.get(decodeURIComponent(m[1]!)) : undefined;
    if (m && !k) return res(404, { error: { message: "Not Found" } });
    if (k && method === "GET") return res(200, { data: view(k) });
    if (k && method === "PATCH") {
      if (!body || (body.limit === undefined && body.disabled === undefined)) return res(400, { error: { message: "No update fields provided" } });
      if (body.limit !== undefined) k.limit = body.limit as number;
      if (body.disabled !== undefined) k.disabled = Boolean(body.disabled);
      return res(200, { data: view(k) });
    }
    if (k && method === "DELETE") {
      this.keys.delete(k.hash);
      return res(200, { deleted: true });
    }
    return res(404, { error: { message: `unexpected OpenRouter call ${method} ${path}` } });
  }

  // ---- builders ----

  /** A Play subscription as subscriptionsv2.get returns it. */
  addPurchase(
    token: string,
    o: {
      productId?: string;
      state?: string;
      expiry?: number;
      orderId?: string;
      acknowledged?: boolean;
      linked?: string;
      /** itemReplacement.replacementMode of a purchase with `linked` (default CHARGE_FULL_PRICE, as the app's upgrades); null: none. */
      replacementMode?: string | null;
      autoRenew?: boolean;
      basePlanId?: string;
    } = {},
  ): SubscriptionPurchaseV2 {
    const mode = o.linked ? (o.replacementMode === undefined ? "CHARGE_FULL_PRICE" : o.replacementMode) : null;
    const old = o.linked ? this.purchases.get(o.linked)?.lineItems?.[0] : undefined;
    const p: SubscriptionPurchaseV2 = {
      lineItems: [
        {
          productId: o.productId ?? "transmit_monthly",
          expiryTime: new Date(o.expiry ?? this.now + 30 * DAY).toISOString(),
          latestSuccessfulOrderId: o.orderId ?? "GPA.0000-0000-0000-00001",
          autoRenewingPlan: { autoRenewEnabled: o.autoRenew ?? true },
          offerDetails: { basePlanId: o.basePlanId ?? "monthly" },
          ...(mode ? { itemReplacement: { productId: old?.productId ?? "transmit_monthly", basePlanId: "monthly", replacementMode: mode } } : {}),
        },
      ],
      subscriptionState: o.state ?? "SUBSCRIPTION_STATE_ACTIVE",
      acknowledgementState: o.acknowledged ? "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED" : "ACKNOWLEDGEMENT_STATE_PENDING",
      ...(o.linked ? { linkedPurchaseToken: o.linked } : {}),
    };
    this.purchases.set(token, p);
    return p;
  }

  /**
   * The user switches `oldToken` to `productId` with ReplacementMode.DEFERRED, as Google documents it
   * (developer.android.com/google/play/billing/subscriptions#handle-deferred-replacement and the
   * play-billing-subs-replacement codelab): a new token right away, with two line items — the new
   * plan first, with no expiryTime and no order yet, and the old plan, still current, with renewal off,
   * the old period end and a deferredItemReplacement. The old token is expired at once, replaced.
   */
  deferredSwitch(oldToken: string, newToken: string, o: { productId?: string; orderId?: string } = {}): SubscriptionPurchaseV2 {
    const prev = this.purchases.get(oldToken)!;
    const cur = prev.lineItems![0]!;
    const productId = o.productId ?? "transmit_monthly";
    const orderId = o.orderId ?? "GPA.2222-2222-2222-22222";
    const p: SubscriptionPurchaseV2 = {
      startTime: new Date(this.now).toISOString(),
      subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
      latestOrderId: orderId,
      linkedPurchaseToken: oldToken,
      acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
      lineItems: [
        {
          productId,
          autoRenewingPlan: { autoRenewEnabled: true },
          offerDetails: { basePlanId: "monthly" },
          itemReplacement: { productId: cur.productId, basePlanId: "monthly", replacementMode: "DEFERRED" },
        },
        {
          productId: cur.productId,
          expiryTime: cur.expiryTime,
          autoRenewingPlan: {},
          offerDetails: { basePlanId: "monthly" },
          deferredItemReplacement: { productId },
          latestSuccessfulOrderId: orderId,
        },
      ],
    };
    this.purchases.set(newToken, p);
    prev.subscriptionState = "SUBSCRIPTION_STATE_EXPIRED";
    prev.canceledStateContext = { replacementCancellation: {} };
    cur.expiryTime = new Date(this.now).toISOString();
    cur.autoRenewingPlan = { autoRenewEnabled: false };
    return p;
  }

  /** The renewal at which a DEFERRED switch takes effect: the new item starts (codelab step 4). */
  completeDeferred(token: string, orderId: string, expiry: number): void {
    const p = this.purchases.get(token)!;
    const [next, old] = p.lineItems!;
    next!.expiryTime = new Date(expiry).toISOString();
    next!.latestSuccessfulOrderId = orderId;
    next!.autoRenewingPlan = { autoRenewEnabled: true };
    delete old!.deferredItemReplacement;
    delete old!.autoRenewingPlan;
    old!.latestSuccessfulOrderId = orderId;
    delete p.startTime;
    p.latestOrderId = orderId;
  }

  /** Google renews a subscription: new order, new expiry. */
  renew(token: string, orderId: string, expiry: number): void {
    const item = this.purchases.get(token)!.lineItems![0]!;
    item.latestSuccessfulOrderId = orderId;
    item.expiryTime = new Date(expiry).toISOString();
  }

  setState(token: string, state: string): void {
    this.purchases.get(token)!.subscriptionState = state;
  }

  /**
   * Play Console's refund with "Remove entitlement" (or the API's revoke), as seen on a device: the
   * order is voided and the purchase ends at once, EXPIRED with developerInitiatedCancellation.
   */
  refundAndRevoke(token: string, orderId?: string): void {
    const p = this.purchases.get(token)!;
    const item = p.lineItems![0]!;
    p.subscriptionState = "SUBSCRIPTION_STATE_EXPIRED";
    p.canceledStateContext = { developerInitiatedCancellation: {} };
    item.expiryTime = new Date(this.now + 500).toISOString();
    this.voided.push({ purchaseToken: token, orderId: orderId ?? item.latestSuccessfulOrderId, voidedTimeMillis: String(this.now), voidedSource: 1, voidedReason: 0 });
  }

  /** Registers a good Integrity verdict for `androidId` under `token`; `tweak` can break parts of it. */
  async addIntegrity(token: string, androidId: string, tweak: (p: IntegrityPayload) => void = () => {}): Promise<void> {
    const payload: IntegrityPayload = {
      requestDetails: { requestPackageName: PKG, requestHash: await trialRequestHash(androidId), timestampMillis: String(this.now - 2000) },
      appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", packageName: PKG },
      deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY"] },
      accountDetails: { appLicensingVerdict: "LICENSED" },
    };
    tweak(payload);
    this.integrity.set(token, payload);
  }
}

export async function call(
  world: FakeWorld,
  path: string,
  body?: unknown,
  opts: { method?: string; env?: Partial<Env>; raw?: string; ip?: string } = {},
): Promise<{ status: number; body: Record<string, unknown>; headers: Headers }> {
  const { handleRequest } = await import("../src/index");
  const method = opts.method ?? (body === undefined && opts.raw === undefined ? "GET" : "POST");
  const request = new Request(`https://transmit.test${path}`, {
    method,
    headers: { "content-type": "application/json", "cf-connecting-ip": opts.ip ?? "192.0.2.10" },
    body: method === "GET" ? undefined : (opts.raw ?? JSON.stringify(body)),
  });
  const response = await handleRequest(request, await world.env(opts.env), world.deps());
  return { status: response.status, body: (await response.json()) as Record<string, unknown>, headers: response.headers };
}

export async function alerts(world: FakeWorld, envOverrides: Partial<Env> = {}) {
  const { handleAlerts } = await import("../src/index");
  return handleAlerts(await world.env(envOverrides), world.deps());
}

export async function cron(world: FakeWorld, envOverrides: Partial<Env> = {}) {
  const { handleScheduled } = await import("../src/index");
  return handleScheduled(await world.env(envOverrides), world.deps());
}
