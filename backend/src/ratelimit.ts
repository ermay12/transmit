// Per-client limits on the two POST endpoints, so junk calls can't use up
// Google's quotas (Play Integrity allows 10,000 checks a day) or the Worker's
// free allowance. A client is an IPv4 address or an IPv6 /64 (one phone or
// one home network gets a whole /64).
//
// Both limits count by networkKey(): a keyed hash of the client and the UTC
// day. The address itself is never stored or logged, and a network's hash
// changes every day, so counts can't be linked across days.
//
// - Every call to /v1/trial and /v1/subscription first passes a Workers Rate
//   Limiting binding: a few dozen calls per minute, counted per Cloudflare
//   location, with no storage or subrequest cost (limits in wrangler.jsonc).
// - /v1/trial also counts calls per client per UTC day in D1 (trial.ts),
//   because a per-minute limit alone still lets one address make thousands of
//   Integrity checks a day. The scheduled job deletes those counts once their
//   day is over.

import { type DataKeys, keyedHash } from "./crypto";
import { utcDay } from "./db";
import { ApiError, log } from "./http";

export function rateLimited(retryAfterSeconds: number): ApiError {
  return new ApiError(
    "rate_limited",
    "Too many requests from this network. Try again later.",
    { retryAfterSeconds },
    { "retry-after": String(retryAfterSeconds) },
  );
}

/** The client's address, IPv6 cut to its /64; null without the header (local tests, wrangler dev). */
export function clientKey(request: Request): string | null {
  const ip = request.headers.get("cf-connecting-ip")?.trim();
  if (!ip) return null;
  if (!ip.includes(":")) return ip;
  const [head = "", tail] = ip.toLowerCase().split("::");
  const h = head ? head.split(":") : [];
  const t = tail ? tail.split(":") : [];
  const groups = tail === undefined ? h : [...h, ...Array<string>(Math.max(0, 8 - h.length - t.length)).fill("0"), ...t];
  return `${groups.slice(0, 4).map((g) => (parseInt(g, 16) || 0).toString(16)).join(":")}::/64`;
}

/** What the limits count by: HMAC("ip:" + UTC day + "|" + clientKey); null without a client address. */
export async function networkKey(request: Request, keys: DataKeys, now: number): Promise<string | null> {
  const client = clientKey(request);
  return client ? keyedHash(keys, "ip", `${utcDay(now)}|${client}`) : null;
}

/** Throws rate_limited if the network has used up this minute's calls. Fails open if the binding is missing or errors. */
export async function checkPerMinute(binding: RateLimit | undefined, path: string, network: string | null): Promise<void> {
  if (!binding || !network) return;
  let allowed = true;
  try {
    allowed = (await binding.limit({ key: network })).success;
  } catch (err) {
    log("rate_limit_unavailable", { path, error: String(err) });
    return;
  }
  if (!allowed) {
    log("rate_limited", { path, window: "minute" });
    throw rateLimited(60);
  }
}
