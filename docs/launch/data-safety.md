# Data safety form: proposed answers

Proposed answers for Play Console → Monitor and improve → Policy and programs → App content → **Data safety**, for Transmit 1.0 (`com.ermay12.transmit`). They're based on the code on branch `play-launch` at 189aed3 (2026-11-18). Re-checked on 2026-11-19 for:

- the Worker's privacy changes: no IP addresses in logs or storage, and subscription rows deleted 90 days after they end;
- the app change that replaces contact names in AI context with neutral labels, so **Contacts** is no longer declared.

**Updated 2026-11-26 for 1.0.3 (branch `analytics`):** Google Analytics for Firebase counts the setup funnel in Play installs (`analytics/Analytics.kt`). That adds **App activity → App interactions** and **Location → Approximate location**, makes **Device or other IDs** required, and adds the Analytics and Advertising or marketing purposes. Submit these answers before 1.0.3 goes out: Play compares them with the SDKs in each release.

If the code changes, re-check this file (see [What would change these answers](#what-would-change-these-answers)).

The answers must match the privacy policy (`docs/launch/privacy-policy.md`). Google compares them.

## Google's definitions used here

From [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469), checked 2026-11-18:

- **Collect:** "transmitting data from your app off a user's device." Data "only processed locally on the user's device and not sent off device does not need to be disclosed."
- **Share:** "transferring user data collected from your app to a third party". These transfers don't count as sharing:
  - to a **service provider**, "an entity that processes user data on behalf of the developer";
  - for specific legal purposes;
  - "based on a specific user-initiated action, where the user reasonably expects the data to be shared", or after a prominent in-app disclosure and consent;
  - data that is fully anonymized.
- **End-to-end encryption:** data sent off the device that is "unreadable by you or anyone other than the sender and recipient" doesn't need to be declared.
- **Ephemeral:** the data is "only stored in memory and retained for no longer than necessary" to serve the request.
- **Optional:** users can choose whether the data is collected.
- **Payment services:** apps using "Google Play's billing system" "don't need to declare collection of the data that the payment service collects". They may still need to declare purchase history, or other data they receive from the payment service.
- **IP addresses:** disclose them "based on their particular usage". If you use them to infer location, declare location.

## Section 1: Data collection and security

| Question | Answer | Why |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** | Message text goes to the AI service, the Worker receives ANDROID_ID and purchase tokens, and Play installs send setup events to Google Analytics (below). |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | Every endpoint is HTTPS (Google Analytics too: "All of the user data collected by the SDK is encrypted in transit using the Transport Layer Security (TLS) protocol", [Google Analytics' Play disclosure page](https://support.google.com/analytics/answer/11582702)): OpenRouter and the BYOK providers (`ai/AiService.kt:44-50`), the Worker (`access/WorkerApi.kt:115`), and Google (`gm/GmClient.kt:131-146`). The app targets SDK 36 and has no network security config, so cleartext HTTP is blocked even for a custom BYOK base URL. |
| Which of the following methods of account creation does your app support? | **My app does not allow users to create an account** | No login anywhere. The free week is tied to the device and the subscription to its Play purchase token (`backend/API.md`, "There is no login"). |
| Do you provide a way for users to request that their data is deleted? | **Yes** | Section 7 of the privacy policy: email with the Google Play order number, and the Worker's subscription row and OpenRouter key are deleted by hand. Without a request, the job deletes subscription rows and their keys 90 days after the subscription ends or is refunded (`backend/src/cron.ts`, `purgeEndedSubscriptions`). The per-network counts go at the first job run after UTC midnight (`cron.ts:174`), and trial keys when the trial ends (`deleteExpiredTrialKeys`). Hashed trial rows are kept for abuse prevention, which the form allows ("even if you need to retain certain data for legitimate reasons such as ... abuse prevention"). |
| Delete data URL (if shown; optional for apps without accounts) | `https://transmit.ermay12.workers.dev/privacy#7-your-choices-and-rights` (section 7 of the policy) | |

Paths without a prefix are under `app/src/main/java/app/transmit/`.

## Section 2: Data types

Declare these six types and nothing else.

### Messages → SMS or MMS

| Question | Answer |
| --- | --- |
| Collected | **Yes** |
| Shared | **No** |
| Processed ephemerally | **No** (conservative; see note) |
| Required or optional | **Optional** (users can choose whether this data is collected) |
| Purposes | **App functionality** |

- **Why collected:** the text of messages (and of up to 8 earlier messages for context) leaves the device in every AI request. See `ai/Prompts.kt:64` (`translateUser`), `:122` (`charactersUser`), `:143` (`explainUser`), `:185` (`feedbackUser`), sent by `ai/HttpAiClients.kt:115` (`send`). Incoming messages are sent automatically (`ai/TranslationManager.kt:106`, `translateIncoming`).
- **Why not shared:**
  - Free week and plans: OpenRouter, and the model hosts behind it, process the text on the developer's behalf, through the developer's OpenRouter workspace and keys. That makes them service providers (`ai/AiService.kt:58`, `managedClient`).
  - BYOK: the user picks the provider and pastes their own key, so the transfer follows "a specific user-initiated action, where the user reasonably expects the data to be shared".
  - The Google Messages relay carries message payloads encrypted with keys only the phone and the app hold (`gm/GmCrypto.kt:29`, `RequestCrypto`). That's covered by the end-to-end encryption exemption, and it's the user's own Google Messages anyway.
- **Why optional:** AI is off unless the user starts the free week, subscribes or adds a key (`AiAccess` NONE mode; onboarding "Not now"). Auto-translation can also be turned off per category and per chat.
- **Note on "ephemeral":** the managed path arguably qualifies. Hosts are limited to ZDR (the workspace guardrail, `backend/wrangler.jsonc`, `OPENROUTER_WORKSPACE_ID`), and OpenRouter doesn't store prompts with logging off. But even with ZDR, OpenRouter may sample prompts for anonymous categorization, and it screens every request for CSAM and may keep flagged content ([Data Collection](https://openrouter.ai/docs/guides/privacy/data-collection), [Zero Data Retention](https://openrouter.ai/docs/guides/features/zdr), checked 2026-11-19). BYOK providers may keep data too. "No" is the safe answer. If you choose "Yes", base it on the managed path only.

### Messages → Other in-app messages

Same answers as SMS or MMS: collected yes, shared no, ephemeral no, optional, App functionality.

- **Why:** RCS chats are "chat content", which Google lists under Other in-app messages rather than SMS or MMS. The **Check my translation** draft (`ai/Prompts.kt:185`) is also message text the user writes in the app.

### Device or other IDs

| Question | Answer |
| --- | --- |
| Collected | **Yes** |
| Shared | **No** |
| Processed ephemerally | **No** (a keyed hash is stored) |
| Required or optional | **Required** (since 1.0.3: Google Analytics' app-instance ID) |
| Purposes | **App functionality**, **Fraud prevention, security, and compliance**, **Analytics**, **Advertising or marketing** |

- **Google Analytics (1.0.3):** the SDK assigns an app-instance ID to each installation and sends it with every event, in copies installed from Play (`TransmitApp.onCreate`, `Analytics.start`). It's required: there's no opt-out in the app. Purposes: the setup funnel (Analytics), and conversions for Transmit's Google Ads (Advertising or marketing, which Play defines to include measuring ad performance). The manifest turns off the Advertising ID (`google_analytics_adid_collection_enabled`, and both AD_ID permissions removed) and ANDROID_ID (`google_analytics_ssaid_collection_enabled`).
- **Why collected:** ANDROID_ID (`access/PlayAccessBackend.kt:125`) goes to the Worker in `/v1/trial` (`access/WorkerApi.kt:79`), and once in `/v1/subscription` after a purchase (`WorkerApi.kt:100`).
  - The Worker stores `HMAC("device:" + id)` in `trials.device_hash` (`backend/src/trial.ts:29`, `backend/migrations/0001_init.sql`), to give each device one free week.
  - This type also covers the Play Integrity token bound to the same ID (`access/PlayIntegrity.kt:31,68`; checked, not stored, in `backend/src/trial.ts:65`).
  - It also covers the IP address, used only for rate limits. The Worker never stores or logs it. Both limits count by `HMAC("ip:" + UTC day + "|" + IPv4 address or IPv6 /64)` (`networkKey`, `backend/src/ratelimit.ts:44`), so a network's hash changes daily:
    - the per-minute Workers Rate Limiting binding, which keeps counts for the current minute;
    - the daily `/v1/trial` counter in D1 (`trial.ts:36`, `0002_trial_calls.sql`), deleted at the first job run after UTC midnight (`cron.ts:174`).
  - The Worker doesn't use IP addresses for location. Google Analytics does (see **Approximate location**).
- **Why not shared:** Google decodes the Integrity token as the API provider, Cloudflare hosts the Worker, and Google Analytics processes events for the developer. All three are service providers. Analytics' link to the developer's own Google Ads account measures the developer's own ads; Google signals, ad personalization and remarketing audiences are off. If you'd rather be as conservative as possible, answer **Shared: Yes** (with Google, for Advertising or marketing) for this type and App interactions.
- **ANDROID_ID itself is still optional:** only the free week and the first call after a purchase send it. BYOK and no-AI users never do.

### Financial info → Purchase history

| Question | Answer |
| --- | --- |
| Collected | **Yes** |
| Shared | **No** |
| Processed ephemerally | **No** |
| Required or optional | **Optional** |
| Purposes | **App functionality**, **Fraud prevention, security, and compliance**, **Analytics**, **Advertising or marketing** |

- **Google Analytics (1.0.3):** the SDK logs `in_app_purchase` for Play subscriptions automatically, with the product ID, name and price ("Collects in-app purchase and subscription events with parameters that include product ID, product name, and price"). Subscriptions are also Google Ads conversions, through Analytics and the Play link.
- **Why collected:** the purchase token and product ID go to the Worker (`access/WorkerApi.kt:97-98`). The Worker stores them in the `subscriptions` table (`0001_init.sql`, `subscription.ts:403`): the token encrypted, its keyed hash, the product, state, expiry, auto-renew, the latest Play order ID, and voided order IDs.
- **Retention:** the job deletes the row, and its OpenRouter key, 90 days after the purchase ended (expired, pending purchase cancelled, or replaced by a plan change) or was refunded (`subscriptions.ended_at`, `0004_subscription_retention.sql`; `SUBSCRIPTION_RETENTION_MS` and `purgeEndedSubscriptions` in `backend/src/cron.ts`).
- **Purposes:** to deliver the plan's AI allowance (App functionality), and to turn off keys after refunds and chargebacks (`backend/src/cron.ts`, voided purchases), which is Fraud prevention.
- **Why not shared:** the token goes back only to Google Play's Developer API, to verify the purchase.
- **Why optional:** only subscribers.
- **Payment info is not declared:** Google Play Billing collects the payment details itself, and the app never sees them (Google's payment-service exemption above).

### App activity → App interactions (1.0.3)

| Question | Answer |
| --- | --- |
| Collected | **Yes** |
| Shared | **No** (see Device or other IDs) |
| Processed ephemerally | **No** (Analytics keeps events 14 months) |
| Required or optional | **Required** |
| Purposes | **Analytics**, **Advertising or marketing** |

- **Why collected:** `analytics/Analytics.kt` logs the setup steps: `languages_chosen`, `pairing_skipped`, `signin_opened`, `signin_left` (a step word and the page's host and path, never its query), `signin_done`, `qr_pairing_opened`, `pairing_emoji_shown`, `paired`, `onboarding_finished`, `trial_started` (the Google Ads conversion) and `trial_failed` (an error code). The SDK adds first_open, session_start, user_engagement, app_update, os_update and app_remove, with device model, OS version, app version and language. Parameters are fixed words; no message text, names, numbers, email addresses or contacts.
- **Advertising or marketing:** `trial_started`, first opens and purchases are imported into Google Ads as conversions, to measure Transmit's campaigns.

### Location → Approximate location (1.0.3)

| Question | Answer |
| --- | --- |
| Collected | **Yes** |
| Shared | **No** |
| Processed ephemerally | **No** |
| Required or optional | **Required** |
| Purposes | **Analytics**, **Advertising or marketing** |

- **Why:** Google Analytics "Derives coarse location data from the masked IP addresses of the device" ([Google Analytics' Play disclosure page](https://support.google.com/analytics/answer/11582702)). The app has no location permission.

## Considered and not declared

| Data | Decision | Why |
| --- | --- | --- |
| Personal info (name, email, phone number, user IDs) | Not collected | Never sent to the Worker or the AI (`access/WorkerApi.kt`, `ai/Prompts.kt`). |
| Contacts (contact names) | Not collected | AI context lines name no one: "Me", "Them", or "Person 1/2/…" in groups (`ContextLine.speaker` in `ai/Prompts.kt`, built by `PromptContext`). Names and numbers from the address book or Google Messages never go to the AI or the Worker; `data/ContactsRepository.kt` reads contacts only for on-device display and the recipient picker. A name a person typed into a message is message text, covered by Messages. |
| IP addresses in server logs | Not collected | Workers invocation logs are off (`observability.logs.invocation_logs: false` in `backend/wrangler.jsonc`); they stored each request's `cf-connecting-ip` and location. The Worker's own log lines carry no IP addresses or identifiers (`log()` in `backend/src/http.ts`). Cloudflare processes the IP to deliver requests as the hosting service provider. |
| Google sign-in cookies (Google-account pairing) | Not declared | No Data safety type fits. They're read from the user's own sign-in on Google's page (`ui/pairing/GoogleSignIn.kt`) and sent only to Google's Messages servers (`gm/GmClient.kt:1164`). The privacy policy discloses them (section 2). |
| Recipient numbers, message payloads and media to Google's relay | Not declared | End-to-end encrypted between the phone and the app: `RequestCrypto` (`gm/GmCrypto.kt:29`), and media with a per-file key (`gm/GmClient.kt:1048`, `MediaCrypto`). |
| Photos, videos, audio, files | Not collected | They only travel through Google Messages, encrypted. The AI only gets text. |
| Precise location | Not collected | No location permission. Addresses open Maps through an on-device intent (`smart/SmartActions.kt:30`). The Worker uses the IP only for rate limiting; Google Analytics' coarse location is declared above. |
| App activity: in-app search, other UGC | Not declared | Searches and drafts stay on the device. The usage ledger is a local Room table (`data/db/Database.kt:62`, `UsageEntity`). OpenRouter keeps per-request metadata (token counts, cost) against a pseudonymous key, but that's part of processing the declared Messages data. App interactions are declared above, for Google Analytics. |
| Learner level and languages | Not declared | They go in the AI system prompt (`ai/Prompts.kt`), but they're app settings, not a listed data type. |
| App info and performance (crash logs, diagnostics) | Not collected | No crash reporting or performance SDK; Firebase Analytics is the only Firebase product (`app/build.gradle.kts`), and it sends no crash logs or performance data. Logs go only to the device's logcat. |
| Web browsing | Not collected | The only WebView is Google's sign-in page, part of the app's own pairing flow. `signin_left` records which sign-in step was open (host and path only), which is an app interaction, not browsing history. |
| BYOK API keys | Not declared | They're the user's own credentials, stored encrypted (`data/SecretStore.kt`) and sent only to the provider they belong to. |
| Notifications, share targets | Not collected | Created on the device (`notify/Notifier.kt`, `notify/ConversationShortcuts.kt`). There's no push service. |
| Backups | n/a | `android:allowBackup="false"` (`app/src/main/AndroidManifest.xml:33`). |

## Section 3: Security practices shown on the listing

- Data is encrypted in transit: **Yes**.
- You can request that data be deleted: **Yes**.
- Committed to the Play Families Policy: **No** (not a Families app; see `content-rating-and-declarations.md`).
- Independently validated against a global security standard: **No**. This is an optional, paid review.

## What the listing will roughly say

- **Data shared:** "No data shared with third parties".
- **Data collected:** Messages, Device or other IDs, Financial info, App activity, Location (approximate).
- **Security practices:** "Data is encrypted in transit", "You can request that data be deleted".

## What would change these answers

| Change | Update |
| --- | --- |
| Contact names or numbers sent to the AI again | Add **Contacts** (collected, App functionality) |
| Workers invocation logs turned back on | They store IP addresses and location for 3 days: say so in privacy policy section 4, and re-check Device or other IDs |
| An in-app privacy disclosure + consent screen before the first AI use | No change, but it supports "not shared" and the User Data policy |
| Crash reporting (or Firebase Performance) added | Add **App info and performance** |
| A new Analytics event or parameter, a user ID, or any free text in an event | Re-check App interactions and Personal info, and the privacy policy's section 5 |
| Advertising ID collection turned on, or an AD_ID permission back in the merged manifest | Device or other IDs for Advertising; the App content **Advertising ID** declaration becomes Yes |
| Google signals, ad personalization or remarketing audiences turned on | Re-check Shared, and the privacy policy |
| Managed mode adds another provider, or a host without ZDR | Re-check "not shared" and the privacy policy |
| The 90-day subscription retention changes | Update privacy policy sections 4, 6 and 7; the answers stay |
| An in-app "delete my server data" button | The answers stay; mention it in the privacy policy |
| Accounts or sign-in added | Account creation answers, and the account deletion requirement |
