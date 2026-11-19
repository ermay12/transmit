// Transmit backend: trial keys, Play subscription keys, and the job that keeps
// them in step with Google Play. See API.md for the contract with the app.

import { type Env, loadConfig } from "./config";
import { makeCtx, defaultDeps, type Deps } from "./context";
import { Budget, runScheduled, type CronReport } from "./cron";
import { ApiError, errorResponse, json, log } from "./http";
import { checkPerMinute, clientKey } from "./ratelimit";
import { handleSubscription } from "./subscription";
import { handleTrial } from "./trial";

export async function handleRequest(request: Request, env: Env, deps: Deps): Promise<Response> {
  const { pathname } = new URL(request.url);
  try {
    const route = ROUTES[pathname];
    if (!route) throw new ApiError("not_found", "No such endpoint.");
    const handler = route[request.method];
    if (!handler) throw new ApiError("method_not_allowed", `Use ${Object.keys(route).join(" or ")} for ${pathname}.`);
    return await handler(request, env, deps);
  } catch (err) {
    if (err instanceof ApiError) {
      if (err.code === "upstream_error") log("upstream_error", { path: pathname, message: err.message });
      return errorResponse(err);
    }
    log("internal_error", { path: pathname, error: err instanceof Error ? `${err.name}: ${err.message}` : String(err) });
    return errorResponse(new ApiError("internal_error", "Something went wrong on our side."));
  }
}

type Handler = (request: Request, env: Env, deps: Deps) => Promise<Response>;

const ROUTES: Record<string, Record<string, Handler>> = {
  "/v1/health": { GET: async () => json({ ok: true }) },
  "/v1/trial": {
    POST: async (req, env, deps) => {
      const client = clientKey(req);
      await checkPerMinute(env.TRIAL_RATE_LIMIT, "/v1/trial", client);
      return handleTrial(await makeCtx(env, deps), req, client);
    },
  },
  "/v1/subscription": {
    POST: async (req, env, deps) => {
      await checkPerMinute(env.SUBSCRIPTION_RATE_LIMIT, "/v1/subscription", clientKey(req));
      return handleSubscription(await makeCtx(env, deps), req);
    },
  },
};

export async function handleScheduled(env: Env, deps: Deps): Promise<CronReport> {
  const budget = new Budget(loadConfig(env).cronSubrequestBudget);
  const ctx = await makeCtx(env, { ...deps, fetch: budget.wrap(deps.fetch) });
  return runScheduled(ctx, budget);
}

export default {
  fetch(request: Request, env: Env): Promise<Response> {
    return handleRequest(request, env, defaultDeps());
  },
  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    await handleScheduled(env, defaultDeps());
  },
} satisfies ExportedHandler<Env>;
