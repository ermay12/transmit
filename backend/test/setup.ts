import { applyD1Migrations } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { beforeEach } from "vitest";

await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);

beforeEach(async () => {
  await env.DB.batch(
    ["trials", "trial_days", "subscriptions", "locks", "job_state", "trial_calls"].map((t) => env.DB.prepare(`DELETE FROM ${t}`)),
  );
});
