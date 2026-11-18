# Phase 2a handoff (Play Billing, Integrity, Worker client)

Written 2026-11-18 when phase 2a was stopped early to save usage. Branch `play-launch`, at 595b35f.

## What's done

Nothing in code yet. The stop came while I was still reading, so this phase has no commits under `app/` or `gradle/` and the tree is the same as phase 1 left it. I read these and checked them against the scope:

- `docs/product/release-1.0.md`, `backend/API.md`, and `app/src/main/java/app/transmit/access/*`.
- The debug fake (`app/src/debug/java/app/transmit/access/AccessBuild.kt`) and the release stub (`app/src/release/java/app/transmit/access/AccessBuild.kt`, `UnavailableBackend`).
- The placeholder `ui/access/PlansScreen.kt`, `SettingsViewModel` (`placeholderPurchase`, `restore`) and `MainActivity` (the `pendingEnd` → PLANS navigation).
- `play-launch-plan.md` in memory.

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

- [ ] `AccessManager.listener` is never set in `AppContainer`, so `onEndedInBackground` posts nothing. Wire it to a Notifier method, on a new "Plan" channel, that deep-links to PLANS.
- [ ] `AccessManager.warningDismissed` is in memory only. "Small decisions" says a dismissal lasts until the period resets or the allowance reaches 100%. Persist it in `StoredAccess`, for example as the `resetsAt`/period it was dismissed for.
- [ ] `onSubscriptionKeyRejected` has no handler. API.md says to ask "Your subscription is in use on another device. Use it here?" before restoring without a matching keyHash.

## What's left (phase 2a checklist)

- [ ] **Dependencies:** add `com.google.android.play:integrity` to `gradle/libs.versions.toml`; check the latest version first. Billing 9.1.0 (not ktx) is already there. Write your own suspend wrappers with `suspendCancellableCoroutine`.
- [ ] **Worker client** (`WorkerAccessBackend`, OkHttp, base URL `https://transmit.ermay12.workers.dev`, timeout about 30 s):
  - Implements `/v1/trial` and `/v1/subscription` as in API.md, and parses JSON with kotlinx `JsonObject`; the serialization plugin isn't applied.
  - Branches on the `error` field, not the HTTP status, and passes `reason`, `state` and `retryAfterSeconds` (or the `Retry-After` header) to `resultFor`.
  - IOException → NETWORK.
  - Sends `androidId` on the first call after a purchase.
  - Parses ISO `expiresAt`/`periodEndsAt` into epoch millis.
  - Uses `release/AccessBuild` in release builds; debug gets a switch (prefs) between the fake and the real client.
- [ ] **Integrity:**
  - Use `StandardIntegrityManager` with project number `36162671718`.
  - requestHash = lowercase hex SHA-256 of `"transmit-trial-v1:" + ANDROID_ID`.
  - Prepare the token provider lazily, and warm it when onboarding step 3 or the plans screen opens. Request the token right before `/v1/trial`.
  - If the provider fails, prepare it again once.
- [ ] **Billing:**
  - A connection wrapper with reconnect.
  - `queryProductDetails` for `transmit_monthly` and `transmit_plus`, base plan `monthly` (pick that offer's token).
  - `launchBillingFlow`. An upgrade uses `SubscriptionUpdateParams` with the old token and `ReplacementMode.CHARGE_FULL_PRICE`; put the choice in a pure function and unit-test it.
  - `onPurchasesUpdated`: PURCHASED goes to the Worker; PENDING waits.
  - `queryPurchasesAsync(SUBS)` on launch, and for restore.
  - Never acknowledge purchases in the app; the Worker does that.
- [ ] **Purchase-token delivery:**
  - Every PURCHASED token that isn't acknowledged or isn't known to the Worker goes into a persisted queue and is sent by a unique WorkManager job (network constraint, exponential backoff). Each launch enqueues it again.
  - Retry `busy` and `upstream_error`. Stop on `purchase_invalid` and on any 200.
  - On `purchase_not_active` with state `replaced`, query purchases again and send the newer token. `resultFor` currently maps `replaced` to SERVICE, so handle it before calling `resultFor`.
- [ ] **Not installed from Play:** use `packageManager.getInstallSourceInfo(packageName).installingPackageName == "com.android.vending"`. When it isn't, hide Subscribe and show only "Use my own AI key" (Small decisions). Trial start returns NOT_FROM_PLAY without calling the Worker.
- [ ] **Plans screen:**
  - Replace the placeholder: two plans with `formattedPrice` from ProductDetails, Subscribe, Upgrade (Standard only), Restore, and Manage subscription (`https://play.google.com/store/account/subscriptions?sku=<id>&package=com.ermay12.transmit`).
  - Put the plan text in `strings.xml`.
  - Remove `placeholderPurchase`.
- [ ] **AllowanceSource:** `GET https://openrouter.ai/api/v1/key` with the managed key, reading `data.limit_remaining`. Set `at` to now − 3 min (lag).
  - 403 "Key limit exceeded" means used up.
  - 401 "API key expired" means a trial ended, or a refresh for a subscription.
  - Any other 4xx/5xx returns null (a service problem, never a paywall).
- [ ] **Lifecycle:**
  - A daily `PeriodicWorkRequest` that calls `access.refresh(force = true)` and `reconcile()`.
  - The refresh at 90% is already in `afterManagedCall`.
- [ ] **Notifications** (each one opens PLANS):
  - Day 6, at `expiresAt` − 24 h: "Your free trial ends tomorrow". Use a OneTimeWork with a delay, scheduled when the trial is granted.
  - The trial's AI is used up.
  - The trial or subscription ended while the app was in the background (the listener above).
  - Also check for an end when the app returns to the foreground.
- [ ] **Unit tests** (MockWebServer and coroutines-test are already test deps):
  - Worker error mapping for every `error`/`reason`/`state` in API.md.
  - The purchase-token retry decisions.
  - The replacement-mode choice.
  - OpenRouter allowance error mapping.
- [ ] **Release prep:** set versionName to `1.0.0-beta1` in `app/build.gradle.kts`. After the last commit, build the signed AAB (`set -a; . ~/.config/transmit/upload-key.env; set +a; ./gradlew :app:bundleRelease`) and copy it to `~/Downloads/transmit-1.0.0-beta1-<versionCode>.aab`.

## API.md notes for the backend

- `purchase_invalid` covers three different causes. `token_unknown` is fine to show as "no purchase". `product_mismatch` and `unknown_product` are app bugs and shouldn't read as "no subscription found". The client can split these itself; no Worker change is needed.
- `integrity_failed` with `token_expired` should get one retry with a fresh token on the client. API.md could say so.
- API.md doesn't say what the Worker returns for a second `/v1/trial` call while a trial key is being issued, apart from `busy`. I didn't verify this.
- The backend agent may still be editing API.md. Re-read it before you finalize the client.

## How to verify

- `./gradlew :app:testDebugUnitTest :app:assembleDebug`. I didn't run it in this phase because no code changed since 595b35f.
- UI smoke test on the API 36 emulator only (see `memory/test-device.md`), not the physical phones.
- curl `/v1/health`, then `/v1/trial` and `/v1/subscription` with junk input, to see the real error codes.
- These need a Play-installed internal-testing build on a Pixel, as a device test: the Integrity verdicts, real purchases, upgrades, restore, and acknowledgement within 3 days.
