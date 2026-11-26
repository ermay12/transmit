# Transmit backend

A Cloudflare Worker with a D1 database, on the Workers Free plan, at
`https://transmit.ermay12.workers.dev`. It grants free trials, verifies Play
subscriptions, creates and adjusts one OpenRouter key per trial or subscription,
and disables keys whose subscription lapsed or was refunded. The app talks to
OpenRouter directly with that key. The contract with the app is in
[API.md](API.md). It also serves the privacy policy at
[`/privacy`](https://transmit.ermay12.workers.dev/privacy) and the terms of
service at [`/terms`](https://transmit.ermay12.workers.dev/terms).

## Layout

| Path | What it is |
| --- | --- |
| `src/trial.ts` | `POST /v1/trial`: Play Integrity check, one trial per ANDROID_ID, daily cap |
| `src/subscription.ts` | `POST /v1/subscription` and the per-purchase re-check, including plan changes (upgrade, DEFERRED switch to Transmit) |
| `src/cron.ts` | Job every 10 minutes: refunds, renewals and lapses, expired trial keys, retention (ended subscriptions, past days' network counts) |
| `src/alerts.ts` | Hourly alert job: emails the developer through Resend (see [Alerts](#alerts)) |
| `src/customers.ts` | The alert job's customer news email: free weeks and subscriptions (see [Customer emails](#customer-emails)) |
| `src/google.ts`, `src/openrouter.ts` | API clients |
| `src/shared-token.ts` | Shares the Google access token between isolates through D1 |
| `src/ratelimit.ts` | Per-network call limits (Workers Rate Limiting binding), keyed by a daily keyed hash of the network |
| `src/crypto.ts` | Keyed hashes (device, purchase token, network) and AES-GCM for stored secrets |
| `scripts/build-pages.mjs` | Renders `GET /privacy` (from `docs/launch/privacy-policy.md`), `GET /terms` (from `docs/launch/terms-of-service.md`) and `GET /` into `src/generated/pages.ts`, which isn't committed |
| `migrations/` | D1 schema |
| `test/` | Vitest suite in the Workers runtime, with fake Google and OpenRouter |

## What is stored

Only keyed hashes of ANDROID_ID and purchase tokens, the purchase token
encrypted (the job must show it to Google again), and OpenRouter key hashes.
Never a key string, and never an IP address. Also the current Google access
token, encrypted, and per-day counts of `/v1/trial` calls under a keyed hash of
the network and the day. See `migrations/`.

How long:

| Table | Kept |
| --- | --- |
| `trials` | For good: the hashed ANDROID_ID keeps each device to one free trial. The trial's key is deleted when it expires |
| `subscriptions` | Until 90 days after `ended_at`: when the purchase expired, its pending purchase was cancelled, a plan change replaced it, or it was refunded. The job then deletes the row and its OpenRouter key (`SUBSCRIPTION_RETENTION_MS` in `src/cron.ts`). `putSubscription()` sets `ended_at` and clears it if the purchase recovers |
| `trial_calls` | Until the first job run after UTC midnight |
| `trial_days` | For good (daily totals, nothing per device) |
| `locks`, `job_state` | Leases expire in 30 s; job state is overwritten |
| `alert_events` | Error counts per hour, for a day |
| `alerts_sent` | Until the alert clears (error alerts: a day after their last email) |

## Privacy: IP addresses and logs

What the Worker and Cloudflare keep about a request, as of 2026-11-19:

- **IP addresses are never stored or logged by the Worker.** The rate limits
  count by `networkKey()`: `HMAC("ip:" + UTC day + "|" + IPv4 address or IPv6 /64)`.
  The hash changes every day, so a network can't be followed across days.
  - The Workers Rate Limiting bindings keep it for the current 60 s window.
  - `trial_calls` keeps it in D1 until the first job run after UTC midnight.
- **Workers Logs keep only the Worker's own log lines** (`log()` in
  `src/http.ts`), for 3 days on the Free plan. They carry no IP addresses,
  keys, tokens or device IDs. Cloudflare adds the request's method and URL to
  each line (`redact_query_string` cuts query strings from it).
- **Invocation logs are off** (`observability.logs.invocation_logs: false`).
  When they were on, every request's log held its headers (including
  `cf-connecting-ip` and `x-real-ip`) and `request.cf` location: city, postal
  code, coordinates, ASN. Traces are off too.
- **Still available for debugging:** the Worker's Metrics tab (requests,
  errors by type, CPU and wall time, aggregated, no IP addresses), the log
  lines above, and `npx wrangler tail`, which streams live requests with their
  headers and IP but stores nothing.
- **The trade-off:** the Observability tab no longer lists each request with
  its status, outcome and CPU time. To find `exceededCpu` (error 1102) or a
  slow endpoint, use Metrics, or `wrangler tail` while reproducing. For a short
  investigation you can set `invocation_logs` back to `true` and deploy; that
  stores IP addresses for 3 days, which the privacy policy doesn't allow for,
  so turn it off again and keep it short.
- **Cloudflare itself** processes the IP to deliver each request, under its
  own privacy policy.

The privacy policy (`docs/launch/privacy-policy.md`, sections 4 and 6) and
`docs/launch/data-safety.md` describe exactly this. Change them together.

## Policy pages

`GET /privacy` is `docs/launch/privacy-policy.md` and `GET /terms` is
`docs/launch/terms-of-service.md` (the link Play's trader details give for
Transmit), each rendered to one self-contained HTML page (no scripts, no
external requests) when the Worker is built. `GET /` is a short page that links
to both. The Markdown is the only source:

1. Edit the Markdown. Placeholders like `[LEGAL NAME]` show on the page,
   highlighted, until you replace them. HTML comments (drafting notes) are left
   out of the page. Set a new effective date when the content changes.
2. Optional preview: `npm run build:pages`, then open the `PRIVACY_HTML` or
   `TERMS_HTML` string in `src/generated/pages.ts`, or run `npx wrangler dev`
   and open `http://localhost:8787/privacy` or `/terms`.
3. Deploy (below). `wrangler deploy` runs `scripts/build-pages.mjs` first (the
   `build` section of `wrangler.jsonc`), so the deploy carries the new page.
   The tests and `npm run typecheck` run it too.

Headings get GitHub-style anchors, for example `/privacy#7-your-choices-and-rights`
or `/terms#5-subscriptions`.
Browsers may cache the page for 5 minutes.

## Config

Everything about money and limits is a var in `wrangler.jsonc`. Change it there
and run `npm run deploy`.

| Var | Default | Meaning |
| --- | --- | --- |
| `TRIAL_ALLOWANCE_USD` | `0.50` | AI allowance of one trial |
| `TRIAL_DAYS` | `7` | Trial length (the key's `expires_at`) |
| `TRIAL_DAILY_BUDGET_USD` | `100000` | New trial allowance per UTC day. Off since 2026-11-25 (100000 is the maximum); the OpenRouter workspace's daily budget caps spend instead |
| `TRIAL_CALLS_PER_IP_PER_DAY` | `30` | `/v1/trial` calls per network per UTC day (protects the 10,000 Play Integrity checks a day) |
| `PLAN_ALLOWANCES_USD` | `{"transmit_monthly": 4.50, "transmit_plus": 9.00}` | Allowance per billing period, keyed by `productId` or `productId/basePlanId` |
| `INTEGRITY_MAX_AGE_SECONDS` | `600` | Oldest Integrity token accepted |
| `CRON_SUBREQUEST_BUDGET` | `10` | Fetches + D1 calls per job run. Set by CPU (about 0.5 ms each), not by the Free plan's 50 |
| `VOIDED_CHECK_INTERVAL_HOURS` | `24` | How often the job scans voided purchases |
| `PACKAGE_NAME`, `OPENROUTER_WORKSPACE_ID`, `KEY_NAME_PREFIX` | | App package, the "Transmit Customers" workspace, key name prefix (`transmit`) |
| `ALERT_EMAIL` | `ermay12@gmail.com` | Where alerts go. Resend's test sender only delivers to the Resend account's own address |
| `ALERT_MIN_CREDITS_USD` | `25` | Alert when the OpenRouter balance is below this |
| `ALERT_DAILY_SPEND_USD` | `70` | Alert when OpenRouter spend since the day's first check (UTC) is above this |
| `ALERT_INTEGRITY_CHECKS` | `7000` | Alert when today's `/v1/trial` calls reach this (Google allows 10,000 Integrity checks a day) |
| `ALERT_MODEL` | `google/gemma-4-31b-it` | The model the workspace allows; the alert job checks it has a working zero-data-retention host |

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
( set -a; . ~/.config/transmit/resend.env; set +a; printf '%s' "$RESEND_API_KEY" | npx wrangler secret put RESEND_API_KEY )
python3 -c 'import json,os,sys; sys.stdout.write(json.dumps(json.load(open(os.path.expanduser("~/.config/transmit/play-service-account.json")))))' \
  | npx wrangler secret put GOOGLE_SERVICE_ACCOUNT_JSON
```

| Secret | Source |
| --- | --- |
| `OPENROUTER_MANAGEMENT_KEY` | `openrouter.env`. Swap in a key scoped to the Transmit Customers workspace when you make one, then run the first command again |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | `play-service-account.json` (`transmit-backend@transmit-515111`) |
| `RESEND_API_KEY` | `resend.env`: a Resend key with sending access, on the account of `ALERT_EMAIL`. Without it, alerts are only logged (`alert_not_sent`) |
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

Check it: `curl https://transmit.ermay12.workers.dev/v1/health`, and open
`https://transmit.ermay12.workers.dev/privacy` and `/terms`. `/v1/health/jobs`
should return 200 within an hour (the alert job runs at minute 7).

## Alerts

An hourly job (`src/alerts.ts`, cron `7 * * * *`) emails `ALERT_EMAIL` through
[Resend](https://resend.com), from Resend's test sender `onboarding@resend.dev`,
so no domain is needed. Live since 2026-11-22. It sends one email per run at
most, with every problem that's new, still happening, or resolved, and the
status (balance, today's spend, free weeks, model hosts). The first run with
working email sends "Transmit alerts are on".

| Alert | Fires when | Why it matters |
| --- | --- | --- |
| OpenRouter credits are low | Balance under `ALERT_MIN_CREDITS_USD` | At $0, the free week and both plans stop translating |
| AI spend is high today | Spend since the day's first check (UTC) over `ALERT_DAILY_SPEND_USD` (the whole account) | Abuse, or costs heading for the workspace's daily budget |
| The app's AI model has no working host | No zero-data-retention host for `ALERT_MODEL` with status 0 | The workspace's guardrail only allows those hosts |
| The alert job can't read OpenRouter | `/credits` or `/endpoints/zdr` fails 2 runs in a row | The checks above aren't running |
| Today's free weeks are used up | Less than one trial's allowance left under `TRIAL_DAILY_BUDGET_USD` | New free weeks are refused until midnight UTC |
| Play Integrity checks are near Google's daily limit | Today's `/v1/trial` calls reach `ALERT_INTEGRITY_CHECKS` | Past 10,000, new free weeks fail |
| Purchases aren't being acknowledged | An entitled purchase unacknowledged after a day | Google refunds it after 3 days |
| The scheduled job has stopped | The main job last finished over 30 minutes ago | Renewals, refunds and expired keys wait |
| The backend has internal errors | Any `internal_error` | A bug |
| Calls to Google Play or OpenRouter are failing | 5 `upstream_error`s in one hour | Free weeks and purchases can't be checked |
| The scheduled job is hitting errors | 2 main-job runs with failed steps in one hour | See `cron_*_failed` in the logs |

- **Repeats:** a lasting problem is emailed again every 24 hours, and once
  more when it clears. Error alerts go out at most every 6 hours, counting
  everything since the last one, and don't send "resolved".
- **Errors are counted** in `alert_events` as they happen (`recordEvent()` in
  the request error path; the main job adds one per run with failed steps to its
  write batch). The alert job reads each complete hour once.
- **If an email can't be sent** (Resend down, or the secret missing), nothing
  is marked, and the next run sends it.
- **Cost:** one D1 read, two OpenRouter reads (the ZDR list is over 1 MB, so it's
  scanned as text, about 1 ms of CPU), at most two emails (problems, and
  [customer news](#customer-emails)), one D1 write.
- **From outside:** `.github/workflows/watchdog.yml` calls `GET /v1/health/jobs`
  every hour at minute 37. It returns 503 when the main job hasn't finished in
  30 minutes or the alert job in 150, and the failed run is emailed by GitHub.
  That covers the Worker being down and the alert job's own trigger stopping.
  GitHub turns schedules off in public repos after 60 days without activity.
- **Privacy:** alert emails hold counts and error messages, as the logs do: no
  keys, tokens, device IDs or IP addresses.

### Customer emails

Each run of the alert job also emails what customers did since its last
customer email, when anything happened (`src/customers.ts`, since 2026-11-23).
The subject sums it up, for example `Transmit: 1 new subscriber, 2 free weeks
started`, and each line has its time:

| News | When the Worker learns it |
| --- | --- |
| A free week started; turned into a subscription; ended without one | At once; an ended one when the job deletes its key, after the 7 days |
| New subscriber; returning subscriber; plan change (an upgrade) | When the app sends the new purchase |
| Switch to Transmit scheduled, called off, and taking effect | When the app or the job next checks the purchase |
| Renewal | When the app or the job checks after the period ends |
| Auto-renew turned off or back on | When the app next checks (about once a day), or at the period's end |
| Payment failed (grace period, on hold); fixed; paused | When the app or the job checks |
| Refund (with or without the AI turned off); subscription ended | The job's daily refund scan, or the check at the period's end |

- **How it finds them:** each purchase row keeps `notified`, what the last
  email said about it (product, state, order, pending switch, refunded orders).
  A row that differs is news; only the alert job writes the column. Free weeks
  are found by their timestamps since the last email (`customersThrough` in the
  `alerts` row of `job_state`), read 5 minutes late so a request still in flight
  isn't missed. The trial and purchase code isn't involved, apart from storing
  Google's `testPurchase` flag (`test_purchase`).
- **Changes within an hour fold together:** auto-renew turned off and back on
  between two runs shows nothing.
- **Test purchases** (license testers) are marked `[test]`, counted apart in the
  status line, and an email with only tests is titled `Transmit test purchases: …`.
  Their 5-minute renewals show up as renewals. Rows from before 2026-11-23 get
  the flag at their next check.
- **Purchases from before these emails** (`notified = ''`, set by migration
  0006) were recorded quietly at the first run, not reported.
- **Not sent** (Resend down, or the secret missing): nothing is marked, and the
  next run sends the same news. At most 100 purchases per run; more wait.
- **Volume:** one customer email an hour at most, so with the problems email at
  most 48 a day, under Resend's free 100 a day.
- **Privacy:** plans, states and times only. No order numbers, keys, tokens or
  device IDs. To find a purchase in Play Console (Order management), go by its
  time.

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
  `cron_done`, `rate_limited`, `upstream_error`, …). Log lines never contain keys,
  tokens, device IDs or IP addresses, and invocation logs are off (see
  [Privacy](#privacy-ip-addresses-and-logs)).
- **The job** runs every 10 minutes (minutes 3, 13, …, 53). Each run handles what fits in
  `CRON_SUBREQUEST_BUDGET` (one or two purchases) and leaves the rest for the
  next run. The refund scan runs once a day. When Google ends a purchase
  (expired), the job also looks it up in the voided purchases once, so a refund
  with revoke is recorded as `refunded`, not `expired` (if the app saw the end
  first, the job looks 10 minutes later). Retention goes last: up to 20
  ended subscriptions a run, each with a key costing one subrequest (about 8 a
  run when nothing else is due). `cron_done` reports what a run did
  (`subscriptionsDeleted` among it) and how many subrequests it used.
- **CPU:** the Free plan allows 10 ms of CPU per request and per job run, with
  some leeway for occasional overruns. Waiting on Google or OpenRouter doesn't
  count, but each fetch or D1 call still costs about 0.5 ms (measured
  2026-11-18), signing the Google token exchange 1–3 ms, and a cold isolate a few
  ms more. So the code batches D1 calls (one read batch and one write batch per
  request) and shares the Google token. Measured with the real code and a real D1:
  every endpoint path has a median of 3–5 ms, and about 6% of calls go over 10 ms
  on a cold isolate (up to 19 ms). Every call succeeded. If Metrics shows
  CPU-limit errors (`exceededCpu`, error 1102), lower `CRON_SUBREQUEST_BUDGET` (job) or
  move to the paid plan ($5/month, 30 s of CPU).
- **Alerts:** see [Alerts](#alerts).
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
2026-11-18. Switching Plus → Transmit (ReplacementMode.DEFERRED) went live on
2026-11-18 with migration `0003` (see API.md, "Switch to Transmit"). On
2026-11-19 (version `a5d97641`): the `/privacy` and `/` pages, invocation logs
off, rate limits keyed by a daily network hash, and the 90-day subscription
retention with migration `0004`. Later on 2026-11-19 (version `fabd25d9`): a
refund with revoke is stored as `refunded`, not `expired` (no migration). Then
(version `f633abc6`): when a DEFERRED switch's purchase lists both items with
the same expiry and order after the renewal, the Worker takes the item with the
billed plan (`autoRenewingPlan` with a price: Transmit), in either order, not
the first listed (no migration). On 2026-11-20 (version `43eccc92`): the
`/terms` page, linked from `/privacy` and `/` (no migration). Not done yet:

- **No real trial or purchase tested end to end.** That needs a Play-installed
  build (internal testing track) on a Pixel, and a license-tester purchase
  (test renewals come every 5 minutes, so the app's refresh call picks them up).
- **Cold-isolate CPU outliers** (above 10 ms on about 6% of calls in testing)
  remain. Cloudflare tolerated them, but watch the Metrics tab for CPU-limit
  errors once real users arrive.
- **Rate limits are per network.** Someone with many addresses can still use up
  the Play Integrity quota (Google then returns errors, so trials fail with
  `upstream_error`). If that happens, add sign-in or ask Google for more quota.
- **The management key is account-wide**, not scoped to the Transmit Customers workspace. That's
  deliberate (2026-11-22): the OpenRouter account is used only for Transmit.
- **The DEFERRED switch is built from Google's documented shapes, not a real
  purchase.** On a device (test renewals every 5 minutes, so about 10 minutes
  end to end), confirm: the new token's two line items and that its
  acknowledgement works with the old product ID (the Worker falls back to the
  other); that the old token shows `canceledStateContext.replacementCancellation`;
  that the response shows `pendingProductId` and keeps $9.00 until the renewal,
  then usage + $4.50; and which replacement mode Play accepts for "Keep Plus".
