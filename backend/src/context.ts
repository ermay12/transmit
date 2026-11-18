import { type Config, type Env, loadConfig } from "./config";
import { type DataKeys, dataKeys } from "./crypto";
import { Google, isolateTokenCache, type TokenCache } from "./google";
import { OpenRouter } from "./openrouter";

/** Things tests replace: the network, the clock, and the token cache. */
export interface Deps {
  fetch: typeof fetch;
  now: () => number;
  tokenCache: TokenCache;
}

export interface Ctx {
  db: D1Database;
  config: Config;
  keys: DataKeys;
  google: Google;
  openrouter: OpenRouter;
  now: () => number;
}

export function defaultDeps(): Deps {
  return { fetch: (input, init) => fetch(input, init), now: () => Date.now(), tokenCache: isolateTokenCache };
}

export async function makeCtx(env: Env, deps: Deps): Promise<Ctx> {
  const config = loadConfig(env);
  return {
    db: env.DB,
    config,
    keys: await dataKeys(env.DATA_SECRET),
    google: new Google(env.GOOGLE_SERVICE_ACCOUNT_JSON, config.packageName, deps.fetch, deps.now, deps.tokenCache),
    openrouter: new OpenRouter(env.OPENROUTER_MANAGEMENT_KEY, config.workspaceId, deps.fetch),
    now: deps.now,
  };
}
