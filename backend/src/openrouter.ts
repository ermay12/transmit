// OpenRouter key provisioning with the management key. Every key is created
// in the Transmit Customers workspace, whose default guardrail allows only the
// app's model on zero-data-retention hosts.

import { log, UpstreamError } from "./http";

const BASE = "https://openrouter.ai/api/v1";

export interface KeyInfo {
  hash: string;
  limit: number | null;
  usage: number;
  disabled: boolean;
}

interface RawKey {
  hash?: string;
  limit?: number | null;
  usage?: number;
  disabled?: boolean;
}

function info(raw: RawKey | undefined): KeyInfo {
  if (!raw?.hash) throw new UpstreamError("openrouter", 200, "response without a key hash");
  return { hash: raw.hash, limit: raw.limit ?? null, usage: Number(raw.usage ?? 0), disabled: Boolean(raw.disabled) };
}

/** Limits are dollars; keep them to whole micro-dollars so equality checks are stable. */
export function money(n: number): number {
  return Math.round(n * 1e6) / 1e6;
}

/** ISO 8601 with whole seconds, which OpenRouter requires for expires_at. */
export function isoSeconds(ms: number): string {
  return new Date(Math.floor(ms / 1000) * 1000).toISOString().replace(/\.\d{3}Z$/, "Z");
}

export class OpenRouter {
  constructor(
    private readonly managementKey: string,
    private readonly workspaceId: string,
    private readonly fetcher: typeof fetch,
  ) {}

  private async call(method: string, path: string, body?: unknown): Promise<{ status: number; json: Record<string, unknown> }> {
    const res = await this.fetcher(`${BASE}${path}`, {
      method,
      headers: { authorization: `Bearer ${this.managementKey}`, "content-type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    let json: Record<string, unknown> = {};
    try {
      json = text ? (JSON.parse(text) as Record<string, unknown>) : {};
    } catch {
      json = {};
    }
    return { status: res.status, json };
  }

  private static fail(status: number, json: Record<string, unknown>): never {
    const message = (json.error as { message?: string } | undefined)?.message ?? "unexpected response";
    throw new UpstreamError("openrouter", status, message);
  }

  async createKey(opts: { name: string; limit: number; expiresAt?: number }): Promise<KeyInfo & { key: string }> {
    const body: Record<string, unknown> = { name: opts.name, limit: money(opts.limit), workspace_id: this.workspaceId };
    if (opts.expiresAt !== undefined) body.expires_at = isoSeconds(opts.expiresAt);
    const r = await this.call("POST", "/keys", body);
    if (r.status !== 200 && r.status !== 201) OpenRouter.fail(r.status, r.json);
    const key = r.json.key;
    if (typeof key !== "string" || !key) OpenRouter.fail(r.status, { error: { message: "response without a key" } });
    return { ...info(r.json.data as RawKey), key };
  }

  /** null if the key doesn't exist (any more). */
  async getKey(hash: string): Promise<KeyInfo | null> {
    const r = await this.call("GET", `/keys/${encodeURIComponent(hash)}`);
    if (r.status === 404) return null;
    if (r.status !== 200) OpenRouter.fail(r.status, r.json);
    return info(r.json.data as RawKey);
  }

  /** null if the key doesn't exist (any more). */
  async updateKey(hash: string, patch: { limit?: number; disabled?: boolean }): Promise<KeyInfo | null> {
    const body: Record<string, unknown> = {};
    if (patch.limit !== undefined) body.limit = money(patch.limit);
    if (patch.disabled !== undefined) body.disabled = patch.disabled;
    const r = await this.call("PATCH", `/keys/${encodeURIComponent(hash)}`, body);
    if (r.status === 404) return null;
    if (r.status !== 200) OpenRouter.fail(r.status, r.json);
    return info(r.json.data as RawKey);
  }

  /** true if deleted or already gone. */
  async deleteKey(hash: string): Promise<boolean> {
    const r = await this.call("DELETE", `/keys/${encodeURIComponent(hash)}`);
    if (r.status === 200 || r.status === 204 || r.status === 404) return true;
    OpenRouter.fail(r.status, r.json);
  }

  /** Deletes a key that is already disabled or unused; a failure only leaves a dead key behind. */
  async deleteQuietly(hash: string): Promise<void> {
    try {
      await this.deleteKey(hash);
    } catch (err) {
      log("key_delete_failed", { key: hash.slice(0, 12), error: String(err) });
    }
  }
}
