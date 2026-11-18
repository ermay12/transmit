// Google Play Developer API and Play Integrity API, called with the service
// account in GOOGLE_SERVICE_ACCOUNT_JSON (no IAM roles; access is granted in
// Play Console for the Transmit app only).

import { b64url } from "./crypto";
import { UpstreamError } from "./http";

const SCOPES = "https://www.googleapis.com/auth/androidpublisher https://www.googleapis.com/auth/playintegrity";
const PUBLISHER = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
const INTEGRITY = "https://playintegrity.googleapis.com/v1";

interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri: string;
}

/** Access tokens are cached per isolate, so most calls skip the token exchange. */
export interface TokenCache {
  entry?: { email: string; token: string; expiresAt: number };
  signingKey?: { email: string; key: Promise<CryptoKey> };
}
export const isolateTokenCache: TokenCache = {};

export interface PurchaseLineItem {
  productId?: string;
  expiryTime?: string;
  latestSuccessfulOrderId?: string;
  autoRenewingPlan?: { autoRenewEnabled?: boolean };
  offerDetails?: { basePlanId?: string; offerId?: string };
}

export interface SubscriptionPurchaseV2 {
  lineItems?: PurchaseLineItem[];
  subscriptionState?: string;
  linkedPurchaseToken?: string;
  acknowledgementState?: string;
  testPurchase?: Record<string, never>;
  /** Deprecated top-level order ID, used only if the line item lacks one. */
  latestOrderId?: string;
}

export type SubscriptionLookup =
  | { kind: "found"; purchase: SubscriptionPurchaseV2 }
  /** Google rejects the token (400 Invalid Value, 404). */
  | { kind: "invalid" }
  /** The token is too old to look up (410): the subscription ended long ago. */
  | { kind: "gone" };

export interface VoidedPurchase {
  purchaseToken?: string;
  orderId?: string;
  voidedTimeMillis?: string;
  voidedReason?: number;
  voidedSource?: number;
}

export interface IntegrityPayload {
  requestDetails?: { requestPackageName?: string; requestHash?: string; timestampMillis?: string | number };
  appIntegrity?: { appRecognitionVerdict?: string; packageName?: string };
  deviceIntegrity?: { deviceRecognitionVerdict?: string[] };
  accountDetails?: { appLicensingVerdict?: string };
}

export class Google {
  private readonly sa: ServiceAccount;

  constructor(
    serviceAccountJson: string,
    private readonly packageName: string,
    private readonly fetcher: typeof fetch,
    private readonly now: () => number,
    private readonly cache: TokenCache,
  ) {
    const sa = JSON.parse(serviceAccountJson) as Partial<ServiceAccount>;
    if (!sa.client_email || !sa.private_key || !sa.token_uri) throw new Error("GOOGLE_SERVICE_ACCOUNT_JSON is incomplete");
    this.sa = sa as ServiceAccount;
  }

  private signingKey(): Promise<CryptoKey> {
    if (this.cache.signingKey?.email === this.sa.client_email) return this.cache.signingKey.key;
    const pem = this.sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
    const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
    const key = crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
    this.cache.signingKey = { email: this.sa.client_email, key };
    return key;
  }

  private async accessToken(): Promise<string> {
    const now = this.now();
    const hit = this.cache.entry;
    if (hit && hit.email === this.sa.client_email && hit.expiresAt - 60_000 > now) return hit.token;
    const iat = Math.floor(now / 1000);
    const unsigned = `${b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${b64url(
      JSON.stringify({ iss: this.sa.client_email, scope: SCOPES, aud: this.sa.token_uri, iat, exp: iat + 3600 }),
    )}`;
    const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", await this.signingKey(), new TextEncoder().encode(unsigned));
    const res = await this.fetcher(this.sa.token_uri, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${b64url(sig)}` }),
    });
    const body = (await res.json().catch(() => ({}))) as { access_token?: string; expires_in?: number; error?: string };
    if (!res.ok || !body.access_token) throw new UpstreamError("google", res.status, `token exchange failed (${body.error ?? "no token"})`);
    this.cache.entry = { email: this.sa.client_email, token: body.access_token, expiresAt: now + (body.expires_in ?? 3600) * 1000 };
    return body.access_token;
  }

  private async call(method: string, url: string, body?: unknown): Promise<{ status: number; json: Record<string, unknown> }> {
    const res = await this.fetcher(url, {
      method,
      headers: { authorization: `Bearer ${await this.accessToken()}`, "content-type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    let json: Record<string, unknown> = {};
    try {
      json = text ? (JSON.parse(text) as Record<string, unknown>) : {};
    } catch {
      json = { raw: text.slice(0, 200) };
    }
    return { status: res.status, json };
  }

  private static detail(json: Record<string, unknown>): string {
    const err = json.error as { message?: string; status?: string } | undefined;
    return err?.message ?? err?.status ?? "unexpected response";
  }

  async getSubscription(purchaseToken: string): Promise<SubscriptionLookup> {
    const r = await this.call("GET", `${PUBLISHER}/${this.packageName}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`);
    if (r.status === 200) return { kind: "found", purchase: r.json as SubscriptionPurchaseV2 };
    if (r.status === 400 || r.status === 404) return { kind: "invalid" };
    if (r.status === 410) return { kind: "gone" };
    throw new UpstreamError("google", r.status, Google.detail(r.json));
  }

  /** Returns false if Google refused (the purchase stays unacknowledged; retried later). */
  async acknowledge(productId: string, purchaseToken: string): Promise<boolean> {
    const r = await this.call(
      "POST",
      `${PUBLISHER}/${this.packageName}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`,
      {},
    );
    return r.status >= 200 && r.status < 300;
  }

  async listVoided(opts: { startTime?: number; endTime?: number; pageToken?: string; maxResults: number }): Promise<{
    items: VoidedPurchase[];
    nextPageToken?: string;
  }> {
    const q = new URLSearchParams({ type: "1", maxResults: String(opts.maxResults) });
    if (opts.pageToken) q.set("token", opts.pageToken);
    else {
      if (opts.startTime !== undefined) q.set("startTime", String(opts.startTime));
      if (opts.endTime !== undefined) q.set("endTime", String(opts.endTime));
    }
    const r = await this.call("GET", `${PUBLISHER}/${this.packageName}/purchases/voidedpurchases?${q}`);
    if (r.status !== 200) throw new UpstreamError("google", r.status, Google.detail(r.json));
    const pagination = r.json.tokenPagination as { nextPageToken?: string } | undefined;
    return { items: (r.json.voidedPurchases as VoidedPurchase[] | undefined) ?? [], nextPageToken: pagination?.nextPageToken || undefined };
  }

  /** Returns the verdict, or null if Google can't decode the token. */
  async decodeIntegrityToken(token: string): Promise<IntegrityPayload | null> {
    const r = await this.call("POST", `${INTEGRITY}/${this.packageName}:decodeIntegrityToken`, { integrity_token: token });
    if (r.status === 200) return (r.json.tokenPayloadExternal as IntegrityPayload | undefined) ?? null;
    if (r.status === 400) return null;
    throw new UpstreamError("google", r.status, Google.detail(r.json));
  }
}
