# Transmit backend

A Cloudflare Worker with a D1 database, on the Workers Free plan, at
`https://transmit.ermay12.workers.dev`. It grants free trials, verifies Play
subscriptions, creates and adjusts one OpenRouter key per trial or subscription,
and disables keys whose subscription lapsed or was refunded. The app talks to
OpenRouter directly with that key. The contract with the app is in
[API.md](API.md).

## Layout

| Path | What it is |
| --- | --- |
| `src/trial.ts` | `POST /v1/trial`: Play Integrity check, one trial per ANDROID_ID, daily cap |
| `src/subscription.ts` | `POST /v1/subscription` and the per-purchase re-check |
| `src/cron.ts` | Job every 10 minutes: refunds, renewals and lapses, expired trial keys |
| `src/google.ts`, `src/openrouter.ts` | API clients |
| `src/shared-token.ts` | Shares the Google access token between isolates through D1 |
| `src/ratelimit.ts` | Per-network call limits (Workers Rate Limiting binding) |
| `src/crypto.ts` | Keyed hashes (device, purchase token, network) and AES-GCM for stored secrets |
| `migrations/` | D1 schema |
| `test/` | Vitest suite in the Workers runtime, with fake Google and OpenRouter |

## What is stored

Only keyed hashes of ANDROID_ID and purchase tokens, the purchase token
encrypted (the job must show it to Google again), and OpenRouter key hashes.
Never a key string. Also the current Google access token, encrypted, and
per-day counts of `/v1/trial` calls under a keyed hash of the network (IPv4
address or IPv6 /64). See `migrations/`.

## Config

Everything about money and limits is a var in `wrangler.jsonc`. Change it there
and run `npm run deploy`.

| Var | Default | Meaning |
| --- | --- | --- |
| `TRIAL_ALLOWANCE_USD` | `0.50` | AI allowance of one trial |
| `TRIAL_DAYS` | `7` | Trial length (the key's `expires_at`) |
| `TRIAL_DAILY_BUDGET_USD` | `30` | New trial allowance per UTC day (60 trials) |
| `TRIAL_CALLS_PER_IP_PER_DAY` | `30` | `/v1/trial` calls per network per UTC day (protects the 10,000 Play Integrity checks a day) |
| `PLAN_ALLOWANCES_USD` | `{"transmit_monthly": 4.50, "transmit_plus": 9.00}` | Allowance per billing period, keyed by `productId` or `productId/basePlanId` |
| `INTEGRITY_MAX_AGE_SECONDS` | `600` | Oldest Integrity token accepted |
| `CRON_SUBREQUEST_BUDGET` | `10` | Fetches + D1 calls per job run. Set by CPU (about 0.5 ms each), not by the Free plan's 50 |
| `VOIDED_CHECK_INTERVAL_HOURS` | `24` | How often the job scans voided purchases |
| `PACKAGE_NAME`, `OPENROUTER_WORKSPACE_ID`, `KEY_NAME_PREFIX` | | App package, the "Transmit Customers" workspace, key name prefix (`transmit`) |

The per-minute limits are the `ratelimits` bindings in `wrangler.jsonc`:
`TRIAL_RATE_LIMIT` (10 calls a minute per network) and `SUBSCRIPTION_RATE_LIMIT`
(30). The period can only be 10 or 60 s. If a binding is missing, that limit is skipped.

## Secrets

Set once per Worker with `wrangler secret put`, piped from the files in
`~/.config/transmit/` (never echo them):

```sh
cd backend
set -a; . ~/.config/transmit/cloudflare.env; set +a
( set -a; . ~/.config/transmit/openrouter.env; set +a; printf '%s' "$OPENROUTER_MANAGEMENT_KEY" | npx wrangler secret put OPENROUTER_MANAGEMENT_KEY )
( set -a; . ~/.config/transmit/worker.env; set +a; printf '%s' "$TRANSMIT_DATA_SECRET" | npx wrangler secret put DATA_SECRET )
python3 -c 'import json,os,sys; sys.stdout.write(json.dumps(json.load(open(os.path.expanduser("~/.config/transmit/play-service-account.json")))))' \
  | npx wrangler secret put GOOGLE_SERVICE_ACCOUNT_JSON
```

| Secret | Source |
| --- | --- |
| `OPENROUTER_MANAGEMENT_KEY` | `openrouter.env`. Swap in a key scoped to the Transmit Customers workspace when you make one, then run the first command again |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | `play-service-account.json` (`transmit-backend@transmit-515111`) |
| `DATA_SECRET` | `worker.env`, generated at first deploy. **Back it up.** Without it the stored hashes and tokens are useless: every device could start a new trial and the job could no longer re-check purchases. Don't rotate it |

## Deploy

The D1 database `transmit` (`fb74b4ce-efa9-4f0a-959d-2c96918ed721`) already
exists. To deploy a change:

```sh
cd backend
npm ci
npm test
set -a; . ~/.config/transmit/cloudflare.env; set +a
npx wrangler d1 migrations apply transmit --remote   # only when migrations/ changed
npx wrangler deploy
```

Check it: `curl https://transmit.ermay12.workers.dev/v1/health`.

## Tests

```sh
npm ci
npm test          # vitest in the Workers runtime (@cloudflare/vitest-plugin), local D1
npm run typecheck
```

The tests never reach the network: `test/fakes.ts` serves Google (OAuth with
a generated RSA service account, Play subscriptions, voided purchases,
Integrity) and OpenRouter keys from memory.

If `npm install` fails with "Cannot read properties of null (reading
'edgesOut')", that's a bug in npm 10; use `npx npm@11 install`. `npm ci` works.

## Operating it

- **Logs:** `npx wrangler tail`, or the Worker's Observability tab. Each line is
  JSON with an `event` (`trial_started`, `subscription_synced`, `refund_applied`,
  `cron_done`, `rate_limited`, `upstream_error`, …). Logs never contain keys,
  tokens, device IDs or IP addresses.
- **The job** runs every 10 minutes (minutes 3, 13, …, 53). Each run handles what fits in
  `CRON_SUBREQUEST_BUDGET` (one or two purchases) and leaves the rest for the
  next run. The refund scan runs once a day. `cron_done` reports what a run did
  and how many subrequests it used.
- **CPU:** the Free plan allows 10 ms of CPU per request and per job run, with
  some leeway for occasional overruns. Waiting on Google or OpenRouter doesn't
  count, but each fetch or D1 call still costs about 0.5 ms (measured
  2026-11-18), signing the Google token exchange 1–3 ms, and a cold isolate a few
  ms more. So the code batches D1 calls (one read batch and one write batch per
  request) and shares the Google token. Measured with the real code and a real D1:
  every endpoint path has a median of 3–5 ms, and about 6% of calls go over 10 ms
  on a cold isolate (up to 19 ms). Every call succeeded. If the Observability tab shows
  `exceededCpu` outcomes or error 1102, lower `CRON_SUBREQUEST_BUDGET` (job) or
  move to the paid plan ($5/month, 30 s of CPU).
- **Keys** are named `transmit-trial-<id>` and `transmit-sub-<id>` and live in
  the Transmit Customers workspace. The Worker only touches keys it created.
- **Trial abuse:** watch `trial_capacity_reached` lines; if the cap fills every
  day, lower `TRIAL_DAILY_BUDGET_USD` or add sign-in.

## Status / remaining work

As of 2026-11-18, deployed and smoke-tested (invalid inputs return the right
codes, and the Google calls work in production). The OpenRouter client was checked
live with one throwaway key, since deleted. The hourly job ran cleanly from the
first deploy on (`cron_done`, no errors); it now runs every 10 minutes with a
smaller budget, for CPU. Rate limits and the shared Google token went live on
2026-11-18. Not done yet:

- **No real trial or purchase tested end to end.** That needs a Play-installed
  build (internal testing track) on a Pixel, and a license-tester purchase
  (test renewals come every 5 minutes, so the app's refresh call picks them up).
- **Cold-isolate CPU outliers** (above 10 ms on about 6% of calls in testing)
  remain. Cloudflare tolerated them, but watch the Observability tab for
  `exceededCpu` once real users arrive.
- **Rate limits are per network.** Someone with many addresses can still use up
  the Play Integrity quota (Google then returns errors, so trials fail with
  `upstream_error`). If that happens, add sign-in or ask Google for more quota.
- **A workspace-scoped OpenRouter management key** has not replaced the account-wide one yet.
