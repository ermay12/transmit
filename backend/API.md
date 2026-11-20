# Transmit backend API (v1)

The backend is a Cloudflare Worker. It hands out OpenRouter keys for the free
trial and for Play subscribers, and keeps each key's spending limit in step with
Google Play. The phone sends every AI request straight to OpenRouter with the
key it gets here; no message text ever reaches the Worker.

- **Base URL:** `https://transmit.ermay12.workers.dev`
- **Package:** `com.ermay12.transmit`
- **Google Cloud project number** (for `StandardIntegrityManager`): `36162671718`
  (project `transmit-515111`)
- Requests and responses are JSON (`Content-Type: application/json`), UTF-8.
- There is no login. A trial is tied to the device (ANDROID_ID + Play
  Integrity); a subscription is tied to its Play purchase token.
- Allow about 30 s per call: the Worker calls Google and OpenRouter in turn.

## Endpoints

| Method and path | Purpose |
| --- | --- |
| `POST /v1/trial` | Start the free trial on this device, or re-issue its key after a reinstall during the trial |
| `POST /v1/subscription` | Verify a Play subscription and return or refresh its key: after a purchase, an upgrade, a switch to Transmit, a restore, daily, and when the allowance runs low |
| `GET /v1/health` | Liveness check, returns `{"ok": true}` |
| `GET /privacy` | The privacy policy, as an HTML page (the link in the app and in Play Console) |
| `GET /` | A short HTML page that links to `/privacy` |

There is no status endpoint. For the remaining allowance, call OpenRouter with
the app's own key: `GET https://openrouter.ai/api/v1/key` and read
`data.limit_remaining` (see [Allowance](#allowance)).

## Errors

Every error has the same shape, and the app should branch on `error`, not on
the HTTP status:

```json
{ "error": "trial_already_used", "message": "This device has already used its free trial." }
```

Some errors add fields (`reason`, `state`, `retryAfterSeconds`, `service`),
listed below. `message` is English text for logs, not for users.

| `error` | HTTP | Meaning | What the app does |
| --- | --- | --- | --- |
| `bad_request` | 400 | Missing or malformed field | Bug in the app; log it |
| `integrity_failed` | 403 | The Play Integrity check failed; `reason` says why (below) | "Free trial needs the app installed from Google Play" (or "this device can't start a trial"); offer Subscribe or BYOK |
| `trial_already_used` | 409 | This device already had a trial (expired, used up, or converted to a subscription) | "This device has already used its free trial"; offer Subscribe or BYOK |
| `trial_capacity_reached` | 429 | Today's trial quota is used up; `retryAfterSeconds` = seconds to the next UTC midnight (also in the `Retry-After` header) | "Free trials are full today, try again tomorrow"; offer Subscribe or BYOK |
| `purchase_invalid` | 400 | Google doesn't know the token, or it's not a Transmit subscription; `reason` below | Don't retry with that token |
| `purchase_not_active` | 402 | The purchase exists but gives no AI right now; `state` below | `pending`: wait for Play's purchase update. `on_hold`, `paused`: "Fix your payment in Google Play" + Manage subscription. `expired`, `canceled_pending`, `refunded`: ENDED. `replaced`: call again with the newer purchase token from `queryPurchasesAsync` (the key keeps working meanwhile; see [Switch to Transmit](#app-flows)) |
| `busy` | 409 | Another call for the same device or purchase is in progress | Retry after 2–5 s (for `/v1/trial`, see [Concurrent calls](#concurrent-calls)) |
| `key_mismatch` | 409 | `/v1/subscription` with `"rotate": false`: the `keyHash` sent is missing or isn't this purchase's current key, so the key is in use on another device. Nothing was changed | Background call: stop and keep the app's state. In the foreground: "Your subscription is in use on another device. Use it here?" On yes, call again without `rotate` |
| `rate_limited` | 429 | Too many calls from this network; `retryAfterSeconds` = seconds to wait (also in the `Retry-After` header). See [Rate limits](#rate-limits) | Wait `retryAfterSeconds`, then retry once; don't retry in a loop. For a trial: "Too many attempts from this network, try again later"; offer Subscribe or BYOK |
| `upstream_error` | 502 | Google or OpenRouter failed; `service` is `google` or `openrouter` | Retry with backoff; nothing was charged or lost |
| `not_found` | 404 | Unknown path | |
| `method_not_allowed` | 405 | Wrong HTTP method for the path | Bug in the app |
| `internal_error` | 500 | Bug in the Worker | Retry later |

`integrity_failed` reasons:

| `reason` | Cause |
| --- | --- |
| `token_invalid` | Google couldn't decode the token (garbage, wrong project, or a token from another app) |
| `wrong_package` | The token was requested by a different package |
| `request_hash_mismatch` | `requestHash` isn't the hash of this `androidId` (see below) |
| `token_expired` | The token is older than 10 minutes, or its timestamp is in the future |
| `app_not_recognized` | `appRecognitionVerdict` isn't `PLAY_RECOGNIZED` (sideloaded, GitHub APK, debug build, modified app) |
| `unlicensed` | `appLicensingVerdict` isn't `LICENSED` (not installed from Play, or no Play account on the device) |
| `device_integrity` | `deviceRecognitionVerdict` lacks `MEETS_DEVICE_INTEGRITY` (emulator, rooted, unlocked bootloader) |

On `token_expired`, request a fresh Integrity token and call `/v1/trial` once
more. If the retry gets `token_expired` again, the phone's clock is probably
wrong: handle it like the other `integrity_failed` reasons.

`purchase_invalid` reasons: `token_unknown` (Google rejects the token),
`unknown_product` (not `transmit_monthly` or `transmit_plus`),
`product_mismatch` (the `productId` you sent differs from Google's).

## POST /v1/trial

Request:

```json
{
  "androidId": "9774d56d682e549c",
  "integrityToken": "<token from StandardIntegrityTokenProvider.request>"
}
```

- `androidId`: `Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)`,
  sent as is (1–64 letters and digits). The Worker stores only a keyed hash of it.
- `integrityToken`: a Play Integrity **standard** request token whose
  `requestHash` is computed as below.

### Computing the requestHash

```
requestHash = lowercase hex( SHA-256( UTF-8( "transmit-trial-v1:" + androidId ) ) )
```

That is 64 hex characters. Kotlin:

```kotlin
val requestHash = MessageDigest.getInstance("SHA-256")
    .digest("transmit-trial-v1:$androidId".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

val manager = IntegrityManagerFactory.createStandard(context)
val provider = manager.prepareIntegrityToken(
    PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(36162671718L).build()
).await()                       // can be prepared ahead of time, e.g. when step 3 opens
val token = provider.request(
    StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()
).await().token()
```

Request the token right before calling `/v1/trial`: the Worker rejects tokens
older than 10 minutes. Integrity only passes on a build installed from a Play
track on a real, certified phone; the emulator and GitHub APKs get
`integrity_failed`.

Response `200`:

```json
{
  "kind": "trial",
  "key": "sk-or-v1-…",
  "keyHash": "f01d52606dc8f0a8303a7b5cc3fa07109c2e346cec7c0a16b40de462992ce943",
  "allowanceUsd": 0.5,
  "remainingUsd": 0.5,
  "expiresAt": "2026-11-25T19:30:00Z",
  "restored": false
}
```

- `key` is the OpenRouter key. Store it (encrypted) with `keyHash`; the Worker
  can't show it again.
- `expiresAt`: the key stops working then (OpenRouter returns 401 "API key
  expired"). Day 6 reminder = `expiresAt` − 24 h.
- `restored: true` means this device already had a trial that is still running
  (for example after a reinstall). The old key is deleted and the new one keeps
  the same `expiresAt` and only what was left of the allowance
  (`remainingUsd`, which can be below `allowanceUsd`).
- One trial per device: once it has expired, been used up, or the device has
  subscribed, the answer is `trial_already_used`.

Errors: `bad_request`, `integrity_failed`, `trial_already_used`,
`trial_capacity_reached`, `busy`, `rate_limited`, `upstream_error`.

### Concurrent calls

The Worker handles one `/v1/trial` call per `androidId` at a time. While one is
in progress, another call with the same `androidId` gets, right away:

```
HTTP/1.1 409 Conflict
Content-Type: application/json; charset=utf-8

{ "error": "busy", "message": "A trial request for this device is already in progress." }
```

- No other fields and no `Retry-After` header.
- `busy` is checked before the Integrity token, so the second call uses no
  Play Integrity check. It changes nothing: the first call finishes normally.
  Only `bad_request` and `rate_limited` come before `busy`.
- A call holds the device for at most 30 s, even if it dies halfway.
- Wait 2–5 s and retry with a fresh Integrity token. The retry gets this
  device's normal answer. If the first call started the trial, the retry gets
  `200` with `restored: true` and a **new** key, and the first call's key is
  deleted. So keep only the key from the latest `200`. Better still, never send
  two `/v1/trial` calls at once.

## POST /v1/subscription

One call for every subscription case. The Worker checks the token with Google,
acknowledges new purchases, and makes sure the purchase has exactly one working
key with the right limit.

Request:

```json
{
  "purchaseToken": "<Purchase.getPurchaseToken()>",
  "productId": "transmit_monthly",
  "keyHash": "f01d52606dc8…",
  "androidId": "9774d56d682e549c"
}
```

- `purchaseToken` (required).
- `productId` (optional): the product the app thinks it bought. If sent, it must
  match Google's record. For the purchase token of a switch to Transmit
  (DEFERRED), both `transmit_plus` and `transmit_monthly` are accepted until the
  switch takes effect (Play reports the old product until then).
- `keyHash` (optional): the `keyHash` of the subscription key the app holds now.
  - If it matches the key on this purchase (or on the purchase this one
    upgraded), the key is **kept**: the response has no `key`, and its limit and
    enabled state are updated in place.
  - If it's missing or doesn't match (fresh install, restore, cleared data, or a
    second device), the Worker **rotates**: it deletes the old key and returns a
    new one in `key`. The old key stops working at once.
  - Never send the trial key's hash here; it never matches.
- `androidId` (optional): send it on the first call after a purchase. If this
  device has a trial key, the Worker deletes it (no carry-over); the
  subscription key replaces it.
- `rotate` (optional, `true` or `false`, default `true`): `false` means "never
  take the key from another device". Where the Worker would rotate (a missing
  or non-matching `keyHash` on a purchase that has a key, including the one an
  upgrade links to), it answers `key_mismatch` instead and changes nothing.
  `purchase_invalid` and `purchase_not_active` still come first. A purchase
  with no key yet still gets one. Send `"rotate": false` on background calls
  (daily refresh, near the limit); leave it out for purchases, upgrades and
  "Restore purchase".

Do **not** acknowledge purchases in the app. The Worker acknowledges after it has
issued the key. If the Worker can't be reached, retry; Play refunds purchases that
stay unacknowledged for 3 days, so a user never pays for a key they didn't get.

Response `200`:

```json
{
  "kind": "subscription",
  "productId": "transmit_plus",
  "basePlanId": "monthly",
  "state": "active",
  "key": "sk-or-v1-…",
  "keyHash": "0b9e…",
  "newKey": true,
  "allowanceUsd": 9,
  "remainingUsd": 9,
  "allowanceRenewed": true,
  "periodEndsAt": "2026-12-18T19:30:00Z",
  "autoRenewing": true
}
```

While a switch to Transmit is scheduled (see [Switch to Transmit](#app-flows)),
the response also has:

```json
{
  "pendingProductId": "transmit_monthly",
  "pendingAllowanceUsd": 4.5,
  "pendingStartsAt": "2026-12-18T19:30:00Z"
}
```

- `key` is present only when `newKey` is `true`. Store it with `keyHash`,
  replacing any previous subscription key.
- `state`: `active`, `canceled` (the user turned off renewal; the AI keeps
  working until `periodEndsAt`), or `grace` (a renewal payment failed; the AI
  keeps working during Play's grace period, but no new allowance until the
  payment succeeds).
- `allowanceUsd`: AI allowance per billing period for this plan ($4.50 Transmit,
  $9.00 Plus).
- `remainingUsd`: what's left of this period's allowance, as OpenRouter reports
  it now.
- `allowanceRenewed`: `true` when this call credited a new Play order: a new
  purchase, an upgrade, or a renewal the Worker hadn't seen yet. Not for a
  purchase that carries on a period already credited (a switch to Transmit
  before its renewal, a resubscribe before expiry, a cancelled switch).
- `periodEndsAt`: end of the paid period. If `autoRenewing`, that's both
  "Renews <date>" and "Resets <date>". If not, the subscription ends then.
- `pendingProductId`, `pendingAllowanceUsd`, `pendingStartsAt` (optional, added
  for the downgrade): present only while a plan change is scheduled for the
  next renewal and renewal is on. From `pendingStartsAt` (always equal to
  `periodEndsAt`) the plan is `pendingProductId` with `pendingAllowanceUsd` per
  period. Until then `productId`, `allowanceUsd` and `remainingUsd` are still
  those of the current plan. Show "Switches to Transmit on <pendingStartsAt>".
  If the fields are absent, nothing is scheduled (or the switch was cancelled,
  or renewal was turned off, in which case the subscription simply ends at
  `periodEndsAt`). `autoRenewing` is `true` while a switch is scheduled, since
  the subscription renews, as the new plan.

Errors: `bad_request`, `purchase_invalid`, `purchase_not_active`, `busy`,
`key_mismatch` (only with `"rotate": false`), `rate_limited`, `upstream_error`. On `purchase_not_active` (except `replaced`, below) the Worker has also disabled the
purchase's key, if it had one; it is re-enabled automatically if the
subscription recovers (for example `on_hold` → paid).

`state` values in `purchase_not_active`: `pending` (payment not completed yet),
`on_hold` (payment failed after the grace period), `paused`, `expired`,
`canceled_pending` (a pending purchase was canceled), `refunded` (the current
order was refunded or charged back, or a refund ended the purchase: Play's
refund with "Remove entitlement", or a revoke), `replaced` (this token was superseded by an
upgrade, a switch to Transmit, a resubscribe or a cancelled switch; use the newer token).
A refund that ends the purchase shows in Google's purchase record only as
expired, so the first answer after it can be `expired`. The Worker then finds
the refund in Google's voided purchases, at its next job run (within about 20
minutes) or at the latest at the daily refund scan, and answers `refunded` from
then on. Both are ENDED to the app, and the key is off either way.
`replaced` is the one `purchase_not_active` that does not turn the key off: Play
ends the old token the moment the user changes plan, and the period moves to
the new token. The key keeps working until the app sends the new token (or, if
it never does, until 3 days after the period ends).

## Rate limits

The Worker counts calls per network: an IPv4 address, or an IPv6 `/64` (one
phone or one home network). It counts under a keyed hash of the network and the
UTC day, and never stores the address. Every limit answers `rate_limited` (429) with
`retryAfterSeconds` and a `Retry-After` header. A rate-limited call does
nothing and uses no Google check.

| Endpoint | Limit | `retryAfterSeconds` |
| --- | --- | --- |
| `POST /v1/trial` | 10 calls per minute | `60` |
| `POST /v1/trial` | 30 calls per UTC day (well-formed calls only) | seconds to the next UTC midnight |
| `POST /v1/subscription` | 30 calls per minute | `60` |

The per-minute counts are kept per Cloudflare location and are approximate.
The app's normal flows stay far below these limits, even with retries and a
few phones behind one address. Hitting one usually means a retry loop.

## Allowance

The app shows "percent of this period's AI used" from two numbers:

```
percentUsed = 1 − limit_remaining / allowanceUsd
```

- `allowanceUsd` comes from the last `/v1/trial` or `/v1/subscription` response.
- `limit_remaining` comes from `GET https://openrouter.ai/api/v1/key` with the
  managed key (or `remainingUsd` from the last Worker response).

This formula stays right across renewals, upgrades and key rotations, because
the Worker always sets the key's limit to "spent so far + what's left of this
period".

OpenRouter books spending about 3 minutes late, so `limit_remaining` lags. For
the "used up" check after each AI call, also add up each response's `usage.cost`
locally since the last Worker or `/api/v1/key` reading.

OpenRouter errors with the managed key:

| OpenRouter response | Meaning |
| --- | --- |
| 403 with `"Key limit exceeded"` | This period's allowance is used up |
| 401 `"API key expired."` | Trial key past `expiresAt` |
| 401 otherwise, e.g. `"User not found."` (key deleted or disabled) | Key rotated on another device, or the subscription lapsed or was refunded: call `/v1/subscription` (or show ENDED for a trial) |
| 404 `"Model blocked by guardrail"` | The app asked for a model other than `google/gemma-4-31b-it` |
| other 4xx/5xx | Service problem, not "allowance used up"; show the message in its original language and retry later |

## App flows

**Start the trial** (onboarding step 3, or "Start free trial" on the plan card)

1. Read ANDROID_ID, compute `requestHash`, get a standard Integrity token.
2. `POST /v1/trial`. On `200`, save `key`, `keyHash`, `allowanceUsd`,
   `expiresAt`, and set the mode to TRIAL. Schedule the day-6 notification.
3. On `integrity_failed`, `trial_already_used` or `trial_capacity_reached`, show
   the matching plain-language reason and offer Subscribe or BYOK. On
   `integrity_failed` with `reason: "token_expired"`, first retry once with a
   fresh token. On `busy`, see [Concurrent calls](#concurrent-calls); on
   `rate_limited`, wait `retryAfterSeconds`.

**Trial runs out** (401 expired, 403 key limit, or the local tally reaches the
allowance): set ENDED and show the paywall (a notification if in the
background).

**Subscribe** (from the paywall)

1. Launch the Play billing flow for `transmit_monthly` or `transmit_plus` (base
   plan `monthly`).
2. In `onPurchasesUpdated`, for a `PURCHASED` purchase:
   `POST /v1/subscription {purchaseToken, productId, androidId}`. For `PENDING`,
   wait for the next update.
3. On `200`, save `key`/`keyHash`, `allowanceUsd`, `periodEndsAt`, and set the
   mode to SUBSCRIBED. Drop the trial key.
4. On `upstream_error` or a network failure, retry with backoff, and again on
   the next app start (find the purchase with `queryPurchasesAsync`).

**Upgrade to Plus**

1. Launch the billing flow for `transmit_plus` with
   `SubscriptionUpdateParams(oldPurchaseToken = <current token>, ReplacementMode.CHARGE_FULL_PRICE)`.
2. `POST /v1/subscription {purchaseToken: <new token>, productId: "transmit_plus", keyHash: <current keyHash>}`.
   The key is kept (no `key` in the response), and its remaining allowance
   becomes the full $9.00 at once (`allowanceRenewed: true`).
3. From then on, use the new purchase token. The old one answers
   `purchase_not_active` with `state: "replaced"` or `"expired"`.

**Switch to Transmit** (downgrade from Plus; takes effect at the next renewal)

1. Launch the billing flow for `transmit_monthly` (base plan `monthly`) with
   `SubscriptionUpdateParams(oldPurchaseToken = <current Plus token>, ReplacementMode.DEFERRED)`.
2. Play returns a **new** purchase token right away (in `onPurchasesUpdated` and
   from then on in `queryPurchasesAsync`). Until the renewal, Play still shows
   it as `transmit_plus`. For a `PURCHASED` purchase, call at once:
   `POST /v1/subscription {purchaseToken: <new token>, keyHash: <current keyHash>}`
   (`productId` optional; either plan is accepted). The Worker acknowledges it
   now; don't wait for the renewal (Play refunds it after 3 days otherwise).
3. Response: the same key (no `key`), still `productId: "transmit_plus"`,
   `allowanceUsd: 9` and the `remainingUsd` left this period,
   `allowanceRenewed: false`, `autoRenewing: true`, plus `pendingProductId:
   "transmit_monthly"`, `pendingAllowanceUsd: 4.5` and `pendingStartsAt`. Nothing
   about the key changes: same limit, no gap, no rotation. Save the new token in
   place of the old one and show "Switches to Transmit on <pendingStartsAt>".
4. At the renewal the usual refresh (daily, near the limit, or the Worker's
   job) reports `productId: "transmit_monthly"`, `allowanceUsd: 4.5`,
   `allowanceRenewed: true`, no `pending…` fields. The key's limit becomes usage
   + $4.50, as at every renewal.
5. The old Plus token answers `purchase_not_active` with `state: "replaced"`
   from the moment of the switch. If a call with it gets there first, the key
   stays on; call again with the new token.

**Keep Plus** (cancel a scheduled switch): Google's docs name no separate
cancel call; they say the user can "revert to the original plan" with another
plan change. So launch the billing flow for `transmit_plus` with
`SubscriptionUpdateParams(oldPurchaseToken = <the switch's token>, …)`. Which
replacement mode Play accepts here is still to be confirmed on a device; try
`DEFERRED` first, since it charges nothing now (`CHARGE_FULL_PRICE` would
charge a new full month, which the Worker then credits as a new period). Then
`POST /v1/subscription {purchaseToken: <newest token>, keyHash}` as for any
plan change. The Worker treats a purchase that carries on the current period
(same period end) as no new order: same key and limit, `allowanceRenewed:
false`, no `pending…` fields. If Play instead drops the scheduled change from
the token, the next call simply has no `pending…` fields. If the user turns off
renewal while a switch is scheduled, the `pending…` fields go away too, `state`
is `canceled`, and the subscription ends at `periodEndsAt`.

**Restore purchase** (fresh install, cleared data, the "Restore purchase" link,
or a 401 on the managed key)

1. `queryPurchasesAsync(SUBS)` and take the purchase for `transmit_monthly` or
   `transmit_plus`.
2. `POST /v1/subscription {purchaseToken}` with no `keyHash` (or the stale one).
   The response has a new `key`; the old key stops working.
3. A subscription has one working key at a time, so two phones on one Google
   account would keep taking the key from each other. Only call without a
   matching `keyHash` when there's no key or the user tapped "Restore purchase";
   on a 401 for a key that used to work, ask before taking the key back
   ("Your subscription is in use on another device. Use it here?").
   Calls the user didn't ask for send `"rotate": false`, so they get
   `key_mismatch` instead of taking the key.

**Daily refresh**: once a day (WorkManager) and when the local tally passes
about 90% of the allowance, call
`POST /v1/subscription {purchaseToken, keyHash}`. That picks up renewals within
a day even if the Worker's own job hasn't, and keeps `state` and `periodEndsAt`
fresh for the plan card. Add `"rotate": false` to these background calls: a
stale `keyHash` (the key moved to another phone) then gets `key_mismatch`, not a
new key.

**Allowance used up**

1. Call `POST /v1/subscription {purchaseToken, keyHash}` once, in case a renewal
   happened. If `remainingUsd` > 0 now, carry on.
2. Otherwise: Standard → "You've used this month's AI" + Upgrade to Plus, or
   "Resets <periodEndsAt>". Plus → "Resets <periodEndsAt>". If `autoRenewing`
   is `false`, there's no reset date to show.

**Renewal, lapse and refund without the app**: the Worker's scheduled job checks
every subscription after its `periodEndsAt`. A renewal raises the key's limit by
the plan's allowance; a lapse (on hold, paused, expired) or a refund disables the
key. The next `/v1/subscription` call reports what happened. A scheduled switch to
Transmit takes effect the same way: at the renewal the limit becomes usage +
$4.50. An old token that a plan change replaced is not a lapse: its key stays on
until the app sends the new token, and goes off only if the app hasn't sent it
3 days after the period ended.
