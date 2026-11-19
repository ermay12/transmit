import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";
// Writes src/generated/pages.ts (GET /privacy and GET /) from docs/launch/privacy-policy.md.
import "./scripts/build-pages.mjs";

export default defineConfig(async () => {
  const migrations = await readD1Migrations("./migrations");
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: {
            TEST_MIGRATIONS: migrations,
            // Test-only values; the tests replace GOOGLE_SERVICE_ACCOUNT_JSON with a generated key.
            DATA_SECRET: "test-data-secret-0123456789abcdef0123456789abcdef",
            OPENROUTER_MANAGEMENT_KEY: "test-management-key",
            GOOGLE_SERVICE_ACCOUNT_JSON: "{}",
          },
        },
      }),
    ],
    test: { setupFiles: ["./test/setup.ts"] },
  };
});
