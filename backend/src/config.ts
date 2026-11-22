// Worker configuration. Every amount of money and every cap lives in
// wrangler.jsonc `vars`; secrets come from `wrangler secret put`.

export interface Env {
  DB: D1Database;
  /** Workers Rate Limiting bindings (calls per minute per client); limiting is skipped if one is missing. */
  TRIAL_RATE_LIMIT?: RateLimit;
  SUBSCRIPTION_RATE_LIMIT?: RateLimit;

  // vars
  PACKAGE_NAME: string;
  OPENROUTER_WORKSPACE_ID: string;
  KEY_NAME_PREFIX: string;
  TRIAL_ALLOWANCE_USD: string | number;
  TRIAL_DAYS: string | number;
  TRIAL_DAILY_BUDGET_USD: string | number;
  TRIAL_CALLS_PER_IP_PER_DAY: string | number;
  PLAN_ALLOWANCES_USD: string | Record<string, number>;
  INTEGRITY_MAX_AGE_SECONDS: string | number;
  CRON_SUBREQUEST_BUDGET: string | number;
  VOIDED_CHECK_INTERVAL_HOURS: string | number;
  // alerts (src/alerts.ts)
  ALERT_EMAIL?: string;
  ALERT_MIN_CREDITS_USD: string | number;
  ALERT_DAILY_SPEND_USD: string | number;
  ALERT_INTEGRITY_CHECKS: string | number;
  ALERT_MODEL: string;

  // secrets
  OPENROUTER_MANAGEMENT_KEY: string;
  GOOGLE_SERVICE_ACCOUNT_JSON: string;
  DATA_SECRET: string;
  /** Resend API key for alert emails; without it, alerts are only logged. */
  RESEND_API_KEY?: string;
}

export interface Config {
  packageName: string;
  workspaceId: string;
  keyNamePrefix: string;
  trialAllowanceUsd: number;
  trialDurationMs: number;
  trialDailyBudgetUsd: number;
  /** /v1/trial calls one IPv4 address or IPv6 /64 may make per UTC day. */
  trialCallsPerIpPerDay: number;
  /** Allowance per billing period, by "productId/basePlanId" or "productId". */
  planAllowances: Record<string, number>;
  integrityMaxAgeMs: number;
  cronSubrequestBudget: number;
  voidedCheckIntervalMs: number;
}

function num(name: string, value: unknown, { min = 0, max = Number.MAX_SAFE_INTEGER } = {}): number {
  const n = typeof value === "number" ? value : Number(String(value ?? "").trim());
  if (!Number.isFinite(n) || n < min || n > max || String(value ?? "").trim() === "") {
    throw new Error(`config ${name} must be a number in [${min}, ${max}]`);
  }
  return n;
}

function str(name: string, value: unknown): string {
  if (typeof value !== "string" || value.trim() === "") throw new Error(`config ${name} is missing`);
  return value.trim();
}

function plans(value: unknown): Record<string, number> {
  const raw = typeof value === "string" ? (JSON.parse(value) as unknown) : value;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) throw new Error("config PLAN_ALLOWANCES_USD must be an object");
  const out: Record<string, number> = {};
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) out[k] = num(`PLAN_ALLOWANCES_USD.${k}`, v, { max: 1000 });
  if (Object.keys(out).length === 0) throw new Error("config PLAN_ALLOWANCES_USD is empty");
  return out;
}

export function loadConfig(env: Env): Config {
  return {
    packageName: str("PACKAGE_NAME", env.PACKAGE_NAME),
    workspaceId: str("OPENROUTER_WORKSPACE_ID", env.OPENROUTER_WORKSPACE_ID),
    keyNamePrefix: str("KEY_NAME_PREFIX", env.KEY_NAME_PREFIX),
    trialAllowanceUsd: num("TRIAL_ALLOWANCE_USD", env.TRIAL_ALLOWANCE_USD, { max: 100 }),
    trialDurationMs: num("TRIAL_DAYS", env.TRIAL_DAYS, { min: 0.001, max: 365 }) * 86_400_000,
    trialDailyBudgetUsd: num("TRIAL_DAILY_BUDGET_USD", env.TRIAL_DAILY_BUDGET_USD, { max: 100_000 }),
    trialCallsPerIpPerDay: num("TRIAL_CALLS_PER_IP_PER_DAY", env.TRIAL_CALLS_PER_IP_PER_DAY, { min: 1, max: 1_000_000 }),
    planAllowances: plans(env.PLAN_ALLOWANCES_USD),
    integrityMaxAgeMs: num("INTEGRITY_MAX_AGE_SECONDS", env.INTEGRITY_MAX_AGE_SECONDS, { min: 1, max: 86_400 }) * 1000,
    cronSubrequestBudget: num("CRON_SUBREQUEST_BUDGET", env.CRON_SUBREQUEST_BUDGET, { min: 10, max: 1000 }),
    voidedCheckIntervalMs: num("VOIDED_CHECK_INTERVAL_HOURS", env.VOIDED_CHECK_INTERVAL_HOURS, { min: 1, max: 24 * 7 }) * 3_600_000,
  };
}

export interface AlertConfig {
  /** Where alerts go; with Resend's test sender, it must be the Resend account's own address. */
  email: string | null;
  resendKey: string | null;
  /** Alert when OpenRouter's balance is below this. */
  minCreditsUsd: number;
  /** Alert when OpenRouter's spend since the UTC day began is above this. */
  dailySpendUsd: number;
  /** Alert when /v1/trial calls today (each can cost a Play Integrity check) reach this. */
  integrityChecks: number;
  /** The model the Transmit Customers workspace allows, checked for a working zero-data-retention host. */
  model: string;
}

export function loadAlertConfig(env: Env): AlertConfig {
  return {
    email: env.ALERT_EMAIL?.trim() || null,
    resendKey: env.RESEND_API_KEY?.trim() || null,
    minCreditsUsd: num("ALERT_MIN_CREDITS_USD", env.ALERT_MIN_CREDITS_USD, { max: 100_000 }),
    dailySpendUsd: num("ALERT_DAILY_SPEND_USD", env.ALERT_DAILY_SPEND_USD, { max: 100_000 }),
    integrityChecks: num("ALERT_INTEGRITY_CHECKS", env.ALERT_INTEGRITY_CHECKS, { min: 1, max: 10_000_000 }),
    model: str("ALERT_MODEL", env.ALERT_MODEL),
  };
}

/** Allowance for a plan, or undefined if it isn't one of ours. */
export function planAllowance(config: Config, productId: string, basePlanId: string | null): number | undefined {
  if (basePlanId) {
    const exact = config.planAllowances[`${productId}/${basePlanId}`];
    if (exact !== undefined) return exact;
  }
  return config.planAllowances[productId];
}
