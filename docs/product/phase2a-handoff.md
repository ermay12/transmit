# Phase 2a handoff (Play Billing, Integrity, Worker client)

Started 2026-11-18 as a handoff when phase 2a was stopped early; completed the same day. Branch `play-launch`.

## What's done

Phase 2a is built (2026-11-18). Commits: bf5b823, abdbb6f, a7b69eb, 7da58d6, 65a0609, be3e62a, aa14cb3, 6142c7f, then the release commit that sets versionName `1.0.0-beta1`. `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes. The new tests are `WorkerApiTest`, `OpenRouterAllowanceTest`, `PlayAccessBackendTest`, `PurchasesTest`, and new cases in `AccessManagerTest`.

- `access/WorkerApi.kt`: `/v1/trial` and `/v1/subscription` (OkHttp, kotlinx `JsonObject`, about 30 s). It branches on `error`, reads `retryAfterSeconds` or the `Retry-After` header, and an IOException becomes NETWORK. `WorkerReply.delivery` says whether to retry a purchase token, give up, or swap to the newer token. `purchase_invalid` maps to NO_PURCHASE only for `token_unknown`; the other reasons are SERVICE. `rate_limited` maps to RATE_LIMITED.
- `access/OpenRouterAllowance.kt`: `GET /api/v1/key`. Every 401 is `KeyRejected` (mapped by status, never wording). Only 403 "Key limit exceeded" means `UsedUp`. Anything else is `Unavailable`, never a paywall. `at` is now − 3 min.
- `access/PlayIntegrity.kt`: standard provider prepared lazily or by `warm()` (onboarding step 3, plans screen), re-prepared once on failure; `TrialAttestation.requestHash`.
- `access/Storefront.kt`, `PlayBilling.kt`: billing 9.1.0 (not ktx), own suspend wrappers, auto-reconnect, the base plan `monthly` offer and its `formattedPrice`. It doesn't touch Billing at all when not installed from Play. `PurchasePlan.choose` is the pure replacement choice: Standard → Plus is an `Upgrade` using the item-level `SubscriptionProductReplacementParams` (CHARGE_FULL_PRICE = 4) plus `SubscriptionUpdateParams.setOldPurchaseToken`. The purchase-level `setSubscriptionReplacementMode` is deprecated in 9.x. Plus → Standard isn't offered.
- `access/PlayAccessBackend.kt`: a trial or restore when not installed from Play returns NOT_FROM_PLAY without calling the Worker. Only one `/v1/trial` runs at a time. Each trial attempt gets a fresh Integrity token, with up to two `busy` retries after 3 s and one `token_expired` retry. `busy` on `/v1/subscription` is retried twice after 3 s. A `replaced` token is swapped for Play's newer one. A restore prefers paid-up purchases, then Plus, and sends the key hash held now.
- `access/Purchases.kt`: encrypted `PurchaseQueue` (in `SecretStore`), plus delivery. A retryable failure stays queued and schedules the job; anything else is dropped. Each launch, foreground and daily job re-queue unacknowledged PURCHASED purchases. PENDING waits. Nothing is acknowledged in the app.
- `access/AccessWork.kt`: unique delivery job (network, exponential backoff from 30 s), daily `AccessRefreshWorker` (sync, forced refresh, reconcile), and day-6 `TrialReminderWorker` (scheduled when a trial key is stored, cancelled when a subscription key is).
- Gap (a): the listener is set in `AppContainer` before transitions are watched (`autoStart = false` + `start()`), and it posts to the new "Plan" channel. Notifications deep-link to PLANS (`MainActivity.ACTION_OPEN_PLANS`); the plans screen cancels them. `AppContainer.checkAccess()` (reconcile + purchase sync) runs on every foreground.
- Gap (b): `StoredAccess.warningDismissedFor` holds the allowance period (trial end, or tier + `periodEndsAt`). It survives restarts and daily refreshes, and is cleared at 100%.
- Gap (c): see the API.md notes below. A 401 on the subscription key asks Play first. If Play shows the subscription paid up, the app sets `inUseElsewhere`: chats get `AiBlock.InUseElsewhere` ("Your subscription is in use on another device. Use it here?"), and Home and the plan card show "Use it here", which restores and rotates. Otherwise it does one Worker refresh, which reports the lapse or refund. The daily and 90% refreshes check the key with OpenRouter first, so they never rotate a key another device took.
- Plans screen (`ui/access/PlansScreen.kt`, text in `strings.xml`): Play prices, Subscribe, Upgrade (Standard only), Restore, Manage subscription, pending and in-progress states, and "Use it here". `placeholderPurchase` is gone.
- Not installed from Play: no free week, Subscribe, Upgrade or Restore anywhere; only "Use my own AI key".
- Debug: Settings' debug panel (or `adb shell am broadcast -n com.ermay12.transmit/app.transmit.access.DebugAccessReceiver --es backend WORKER`) switches between the fake and the live Worker after a restart.

## Verified

- Live Worker with curl and junk input: `/v1/health` 200. `/v1/trial` with `{}` → 400 `bad_request`; with a junk token → 403 `integrity_failed`/`token_invalid`. `/v1/subscription` with `{}` → 400 `bad_request`; with a junk token → 400 `purchase_invalid`/`token_unknown`. GET `/v1/trial` → 405 `method_not_allowed`; an unknown path → 404 `not_found`. `rate_limited` wasn't triggered, to avoid hammering the live Worker.
- Emulator (API 36, debug build, fake backend): Plans prices; Subscribe → queue → delivery → SUBSCRIBED; Upgrade chose `Upgrade(old token, transmit_monthly, mode 4)`. A subscription ended in the background posted the Plan notification, and tapping it opened Plans and cleared it. The 85% Home banner, once dismissed, stayed hidden after a force-stop, and 100% ended the trial.
- Emulator with the live Worker: a real Integrity token reached the Worker and came back as not Play-recognized, so the app showed "Free trials need Transmit installed from Google Play." with no Subscribe. Billing on the account-less emulator reported unavailable after about 20 s, and the screen fell back to "Price shown in Google Play".

## Needs a Play-installed build on a Pixel (device test)

- Integrity verdicts passing, and a real trial (key, day-6 reminder, expiry, used up, background notification).
- Real purchase of each plan, PENDING payments, the Worker's acknowledgement within 3 days, and the purchase queue retrying with the network off.
- Upgrade Standard → Plus (CHARGE_FULL_PRICE: the key is kept and the allowance becomes $9 at once), and `replaced` on the old token.
- Restore on a second phone, then the first phone's "Use it here?" prompt, and taking the key back.
- Lapse, refund and on-hold via the Worker's cron, and the daily refresh picking them up.
- Manage subscription deep link, and localized prices.

## Notes for product

- The plan estimates ("Around 3,000 / 6,000 translations or tutor replies a month") are a third of a cost estimate; the reasoning is in `strings.xml`. Please review.
- The chat "Translation is off · Start your free week" strip from "Small decisions" isn't in the code (phase 1 didn't build it).
- Background trial-used-up gets a notification only when the app is in the background; in the foreground the plans screen opens instead.

## What phase 1 already provides (reuse it)

- `AccessBackend` (startTrial, activatePurchase, restore, refresh), `AllowanceSource.read`, `AccessListener`, and `AccessErrorCode.resultFor(error, reason, state, retryAfterSeconds)` in `access/AccessBackend.kt`.
- `AccessManager` already does several things:
  - Stores grants: the managed key and purchase token in `SecretStore`, everything else in the `access` DataStore.
  - Counts the allowance from the `usage.cost` ledger (`dao.managedSpentSinceFlow`) against `AllowanceBaseline`.
  - `reconcile()` calls `AllowanceSource`, and `HomeViewModel` calls it.
  - `refresh()` is limited to once per 5 min unless forced.
  - `afterManagedCall()` ends a used-up trial and refreshes a subscription at 90% or more.
  - `onManagedError()` maps errors: KEY_LIMIT means used up; 401 on a trial means TRIAL_EXPIRED; 401 on a subscription calls `listener.onSubscriptionKeyRejected()`; anything else is null, so no paywall.
- `HttpAiClients.kt:51` already tells 403 "key limit exceeded" (KEY_LIMIT) apart from other 403s.
- Foreground end of access: `MainActivity` watches `access.pendingEnd` and opens PLANS. This already works; it only needs a check once the real backend is in.
- `AccessBuild.backend(context, vault)` picks the backend for each build type. Debug currently always returns the fake.

## Gaps found in phase 1 (fix in 2a)

- [x] `AccessManager.listener` is never set in `AppContainer`, so `onEndedInBackground` posts nothing. Wire it to a Notifier method, on a new "Plan" channel, that deep-links to PLANS.
- [x] `AccessManager.warningDismissed` is in memory only. "Small decisions" says a dismissal lasts until the period resets or the allowance reaches 100%. Persist it in `StoredAccess`, for example as the `resetsAt`/period it was dismissed for.
- [x] `onSubscriptionKeyRejected` has no handler. API.md says to ask "Your subscription is in use on another device. Use it here?" before restoring without a matching keyHash.

## Phase 2a checklist (all done; see "What's done" for where each piece lives)

The Worker client ended up as `WorkerApi` (HTTP) plus `PlayAccessBackend` (Integrity, Billing, retries) rather than one `WorkerAccessBackend`.

- [x] **Dependencies:** add `com.google.android.play:integrity` to `gradle/libs.versions.toml`; check the latest version first. Billing 9.1.0 (not ktx) is already there. Write your own suspend wrappers with `suspendCancellableCoroutine`.
- [x] **Worker client** (`WorkerAccessBackend`, OkHttp, base URL `https://transmit.ermay12.workers.dev`, timeout about 30 s):
  - Implements `/v1/trial` and `/v1/subscription` as in API.md, and parses JSON with kotlinx `JsonObject`; the serialization plugin isn't applied.
  - Branches on the `error` field, not the HTTP status, and passes `reason`, `state` and `retryAfterSeconds` (or the `Retry-After` header) to `resultFor`.
  - IOException → NETWORK.
  - Sends `androidId` on the first call after a purchase.
  - Parses ISO `expiresAt`/`periodEndsAt` into epoch millis.
  - Uses `release/AccessBuild` in release builds; debug gets a switch (prefs) between the fake and the real client.
- [x] **Integrity:**
  - Use `StandardIntegrityManager` with project number `36162671718`.
  - requestHash = lowercase hex SHA-256 of `"transmit-trial-v1:" + ANDROID_ID`.
  - Prepare the token provider lazily, and warm it when onboarding step 3 or the plans screen opens. Request the token right before `/v1/trial`.
  - If the provider fails, prepare it again once.
- [x] **Billing:**
  - A connection wrapper with reconnect.
  - `queryProductDetails` for `transmit_monthly` and `transmit_plus`, base plan `monthly` (pick that offer's token).
  - `launchBillingFlow`. An upgrade uses `SubscriptionUpdateParams` with the old token and `ReplacementMode.CHARGE_FULL_PRICE`; put the choice in a pure function and unit-test it.
  - `onPurchasesUpdated`: PURCHASED goes to the Worker; PENDING waits.
  - `queryPurchasesAsync(SUBS)` on launch, and for restore.
  - Never acknowledge purchases in the app; the Worker does that.
- [x] **Purchase-token delivery:**
  - Every PURCHASED token that isn't acknowledged or isn't known to the Worker goes into a persisted queue and is sent by a unique WorkManager job (network constraint, exponential backoff). Each launch enqueues it again.
  - Retry `busy` and `upstream_error`. Stop on `purchase_invalid` and on any 200.
  - On `purchase_not_active` with state `replaced`, query purchases again and send the newer token. `resultFor` currently maps `replaced` to SERVICE, so handle it before calling `resultFor`.
- [x] **Not installed from Play:** use `packageManager.getInstallSourceInfo(packageName).installingPackageName == "com.android.vending"`. When it isn't, hide Subscribe and show only "Use my own AI key" (Small decisions). Trial start returns NOT_FROM_PLAY without calling the Worker.
- [x] **Plans screen:**
  - Replace the placeholder: two plans with `formattedPrice` from ProductDetails, Subscribe, Upgrade (Standard only), Restore, and Manage subscription (`https://play.google.com/store/account/subscriptions?sku=<id>&package=com.ermay12.transmit`).
  - Put the plan text in `strings.xml`.
  - Remove `placeholderPurchase`.
- [x] **AllowanceSource:** `GET https://openrouter.ai/api/v1/key` with the managed key, reading `data.limit_remaining`. Set `at` to now − 3 min (lag).
  - 403 "Key limit exceeded" means used up.
  - 401 "API key expired" means a trial ended, or a refresh for a subscription.
  - Any other 4xx/5xx returns null (a service problem, never a paywall).
- [x] **Lifecycle:**
  - A daily `PeriodicWorkRequest` that calls `access.refresh(force = true)` and `reconcile()`.
  - The refresh at 90% is already in `afterManagedCall`.
- [x] **Notifications** (each one opens PLANS):
  - Day 6, at `expiresAt` − 24 h: "Your free trial ends tomorrow". Use a OneTimeWork with a delay, scheduled when the trial is granted.
  - The trial's AI is used up.
  - The trial or subscription ended while the app was in the background (the listener above).
  - Also check for an end when the app returns to the foreground.
- [x] **Unit tests** (MockWebServer and coroutines-test are already test deps):
  - Worker error mapping for every `error`/`reason`/`state` in API.md.
  - The purchase-token retry decisions.
  - The replacement-mode choice.
  - OpenRouter allowance error mapping.
- [x] **Release prep:** set versionName to `1.0.0-beta1` in `app/build.gradle.kts`. After the last commit, build the signed AAB (`set -a; . ~/.config/transmit/upload-key.env; set +a; ./gradlew :app:bundleRelease`) and copy it to `~/Downloads/transmit-1.0.0-beta1-<versionCode>.aab`.

## API.md notes for the backend

- **Key rotation vs. "ask first":** `/v1/subscription` rotates on any missing or stale `keyHash` (`backend/src/subscription.ts`). A client that refreshes after a 401 therefore takes the key back from the other device without asking, and two phones would trade it daily. The app works around this by checking Play first and checking the key with OpenRouter before any refresh. Suggested fix: a flag such as `"rotate": false` that returns an error like `key_mismatch` instead of rotating, so the app can refresh without stealing the key.
- `purchase_invalid` covers three different causes. The client now splits them: `token_unknown` → "no purchase"; `unknown_product` and `product_mismatch` → service problem.
- `rate_limited`, concurrent `/v1/trial` and `token_expired` are now documented and followed.

## How to verify

- `./gradlew :app:testDebugUnitTest :app:assembleDebug`. I didn't run it in this phase because no code changed since 595b35f.
- UI smoke test on the API 36 emulator only (see `memory/test-device.md`), not the physical phones.
- curl `/v1/health`, then `/v1/trial` and `/v1/subscription` with junk input, to see the real error codes.
- These need a Play-installed internal-testing build on a Pixel, as a device test: the Integrity verdicts, real purchases, upgrades, restore, and acknowledgement within 3 days.
