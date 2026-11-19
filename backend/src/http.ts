// JSON responses and the machine-readable error codes listed in API.md.

export type ErrorCode =
  | "bad_request"
  | "integrity_failed"
  | "trial_already_used"
  | "trial_capacity_reached"
  | "purchase_invalid"
  | "purchase_not_active"
  | "busy"
  | "key_mismatch"
  | "rate_limited"
  | "upstream_error"
  | "not_found"
  | "method_not_allowed"
  | "internal_error";

const STATUS: Record<ErrorCode, number> = {
  bad_request: 400,
  integrity_failed: 403,
  trial_already_used: 409,
  trial_capacity_reached: 429,
  purchase_invalid: 400,
  purchase_not_active: 402,
  busy: 409,
  key_mismatch: 409,
  rate_limited: 429,
  upstream_error: 502,
  not_found: 404,
  method_not_allowed: 405,
  internal_error: 500,
};

/** An error that becomes a JSON error response. */
export class ApiError extends Error {
  constructor(
    readonly code: ErrorCode,
    message: string,
    readonly extra: Record<string, string | number> = {},
    readonly headers: Record<string, string> = {},
  ) {
    super(message);
  }
}

/** Google or OpenRouter failed in a way the app can retry. */
export class UpstreamError extends ApiError {
  constructor(
    readonly service: "google" | "openrouter",
    readonly status: number,
    detail: string,
  ) {
    super("upstream_error", `${service} returned ${status}: ${detail}`.slice(0, 300), { service });
  }
}

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
  });
}

export function errorResponse(err: ApiError): Response {
  return json({ error: err.code, message: err.message, ...err.extra }, STATUS[err.code], err.headers);
}

const MAX_BODY_BYTES = 16 * 1024;

export async function readJson(request: Request): Promise<Record<string, unknown>> {
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) throw new ApiError("bad_request", "Request body is too large.");
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    throw new ApiError("bad_request", "Request body must be JSON.");
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new ApiError("bad_request", "Request body must be a JSON object.");
  return body as Record<string, unknown>;
}

export function requiredString(body: Record<string, unknown>, field: string, pattern: RegExp, maxLength: number): string {
  const v = body[field];
  if (typeof v !== "string" || v.length === 0 || v.length > maxLength || !pattern.test(v)) {
    throw new ApiError("bad_request", `Field "${field}" is missing or malformed.`);
  }
  return v;
}

export function optionalString(body: Record<string, unknown>, field: string, pattern: RegExp, maxLength: number): string | undefined {
  const v = body[field];
  if (v === undefined || v === null || v === "") return undefined;
  return requiredString(body, field, pattern, maxLength);
}

export function optionalBoolean(body: Record<string, unknown>, field: string): boolean | undefined {
  const v = body[field];
  if (v === undefined || v === null) return undefined;
  if (typeof v !== "boolean") throw new ApiError("bad_request", `Field "${field}" must be true or false.`);
  return v;
}

/** Structured log line. Never pass keys, tokens or device IDs here. */
export function log(event: string, fields: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({ event, ...fields }));
}
