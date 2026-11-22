// Transmit backend: trial keys, Play subscription keys, and the job that keeps
// them in step with Google Play. See API.md for the contract with the app.

import { ALERT_CRON, JOB_RUN_ROW, recordEvent, runAlerts, type AlertReport } from "./alerts";
import { type Env, loadConfig } from "./config";
import { makeCtx, defaultDeps, type Deps } from "./context";
import { Budget, runScheduled, type CronReport } from "./cron";
import { dataKeys } from "./crypto";
import { HOME_HTML, PRIVACY_HTML, TERMS_HTML } from "./generated/pages";
import { ApiError, errorResponse, html, json, log } from "./http";
import { checkPerMinute, networkKey } from "./ratelimit";
import { handleSubscription } from "./subscription";
import { handleTrial } from "./trial";

export async function handleRequest(request: Request, env: Env, deps: Deps): Promise<Response> {
  const { pathname } = new URL(request.url);
  try {
    const route = ROUTES[pathname];
    if (!route) throw new ApiError("not_found", "No such endpoint.");
    const head = request.method === "HEAD";
    const handler = route[request.method] ?? (head ? route.GET : undefined);
    if (!handler) throw new ApiError("method_not_allowed", `Use ${Object.keys(route).join(" or ")} for ${pathname}.`);
    const response = await handler(request, env, deps);
    return head ? new Response(null, response) : response;
  } catch (err) {
    if (err instanceof ApiError) {
      if (err.code === "upstream_error") {
        log("upstream_error", { path: pathname, message: err.message });
        await recordEvent(env.DB, "upstream_error", `${pathname}: ${err.message}`, deps.now());
      }
      return errorResponse(err);
    }
    const error = err instanceof Error ? `${err.name}: ${err.message}` : String(err);
    log("internal_error", { path: pathname, error });
    await recordEvent(env.DB, "internal_error", `${pathname}: ${error}`, deps.now());
    return errorResponse(new ApiError("internal_error", "Something went wrong on our side."));
  }
}

type Handler = (request: Request, env: Env, deps: Deps) => Promise<Response>;

/** The rate limits' key for this request's network: a keyed hash, never the address. */
async function network(req: Request, env: Env, deps: Deps): Promise<string | null> {
  return networkKey(req, await dataKeys(env.DATA_SECRET), deps.now());
}

const ROUTES: Record<string, Record<string, Handler>> = {
  // Pages rendered at build time (scripts/build-pages.mjs); the privacy policy is linked from the app and Play,
  // the terms from Play's trader details.
  "/": { GET: async () => html(HOME_HTML) },
  "/privacy": { GET: async () => html(PRIVACY_HTML) },
  "/terms": { GET: async () => html(TERMS_HTML) },
  "/v1/health": { GET: async () => json({ ok: true }) },
  // For the outside watchdog (.github/workflows/watchdog.yml): 503 when a scheduled job hasn't run lately.
  "/v1/health/jobs": { GET: async (_req, env, deps) => jobsHealth(env, deps.now()) },
  "/v1/trial": {
    POST: async (req, env, deps) => {
      const net = await network(req, env, deps);
      await checkPerMinute(env.TRIAL_RATE_LIMIT, "/v1/trial", net);
      return handleTrial(await makeCtx(env, deps), req, net);
    },
  },
  "/v1/subscription": {
    POST: async (req, env, deps) => {
      await checkPerMinute(env.SUBSCRIPTION_RATE_LIMIT, "/v1/subscription", await network(req, env, deps));
      return handleSubscription(await makeCtx(env, deps), req);
    },
  },
};

/** The main job runs every 10 minutes and the alert job hourly; older than these, something is wrong. */
const JOB_MAX_AGE_MS = 30 * 60_000;
const ALERTS_MAX_AGE_MS = 150 * 60_000;

async function jobsHealth(env: Env, now: number): Promise<Response> {
  const rows = await env.DB.prepare("SELECT name, value FROM job_state WHERE name IN (?1, 'alerts')").bind(JOB_RUN_ROW).all<{ name: string; value: string }>();
  const values = new Map(rows.results.map((r) => [r.name, r.value]));
  const job = values.has(JOB_RUN_ROW) ? Number(values.get(JOB_RUN_ROW)) : undefined;
  const alerts = values.has("alerts") ? (JSON.parse(values.get("alerts")!) as { lastRun?: number }).lastRun : undefined;
  const minutes = (t: number | undefined) => (t === undefined ? null : Math.round((now - t) / 60_000));
  const ok = job !== undefined && now - job <= JOB_MAX_AGE_MS && alerts !== undefined && now - alerts <= ALERTS_MAX_AGE_MS;
  return json({ ok, jobMinutesAgo: minutes(job), alertsMinutesAgo: minutes(alerts) }, ok ? 200 : 503);
}

export async function handleAlerts(env: Env, deps: Deps): Promise<AlertReport> {
  return runAlerts(env, deps);
}

export async function handleScheduled(env: Env, deps: Deps): Promise<CronReport> {
  const budget = new Budget(loadConfig(env).cronSubrequestBudget);
  const ctx = await makeCtx(env, { ...deps, fetch: budget.wrap(deps.fetch) });
  return runScheduled(ctx, budget);
}

export default {
  fetch(request: Request, env: Env): Promise<Response> {
    return handleRequest(request, env, defaultDeps());
  },
  async scheduled(controller: ScheduledController, env: Env): Promise<void> {
    if (controller.cron === ALERT_CRON) await handleAlerts(env, defaultDeps());
    else await handleScheduled(env, defaultDeps());
  },
} satisfies ExportedHandler<Env>;
