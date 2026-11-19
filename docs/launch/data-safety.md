# Data safety form: proposed answers

Proposed answers for Play Console → Monitor and improve → Policy and programs → App content → **Data safety**, for Transmit 1.0 (`com.ermay12.transmit`). They're based on the code on branch `play-launch` at 189aed3 (2026-11-18). If the code changes, re-check this file (see [What would change these answers](#what-would-change-these-answers)).

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
| Does your app collect or share any of the required user data types? | **Yes** | Message text goes to the AI service, and the Worker receives ANDROID_ID and purchase tokens (below). |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | Every endpoint is HTTPS: OpenRouter and the BYOK providers (`ai/AiService.kt:44-50`), the Worker (`access/WorkerApi.kt:115`), and Google (`gm/GmClient.kt:131-146`). The app targets SDK 36 and has no network security config, so cleartext HTTP is blocked even for a custom BYOK base URL. |
| Which of the following methods of account creation does your app support? | **My app does not allow users to create an account** | No login anywhere. The free week is tied to the device and the subscription to its Play purchase token (`backend/API.md`, "There is no login"). |
| Do you provide a way for users to request that their data is deleted? | **Yes** | Section 7 of the privacy policy: email with the Google Play order number, and the Worker's subscription row and OpenRouter key are deleted by hand. The IP counts are deleted automatically after about a day (`backend/src/cron.ts:143`), and trial keys when the trial ends (`cron.ts:289`). Hashed trial rows are kept for abuse prevention, which the form allows ("even if you need to retain certain data for legitimate reasons such as ... abuse prevention"). |
| Delete data URL (if shown; optional for apps without accounts) | The privacy policy URL, anchored at section 7 | |

Paths without a prefix are under `app/src/main/java/app/transmit/`.

## Section 2: Data types

Declare these five types and nothing else.

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
- **Note on "ephemeral":** the managed path arguably qualifies. Hosts are limited to ZDR (the workspace guardrail, `backend/wrangler.jsonc`, `OPENROUTER_WORKSPACE_ID`), and OpenRouter doesn't store prompts with logging off. But OpenRouter samples some prompts for anonymous categorization and screens requests for CSAM, and BYOK providers may keep data. "No" is the safe answer. If you choose "Yes", base it on the managed path only.

### Messages → Other in-app messages

Same answers as SMS or MMS: collected yes, shared no, ephemeral no, optional, App functionality.

- **Why:** RCS chats are "chat content", which Google lists under Other in-app messages rather than SMS or MMS. The **Check my translation** draft (`ai/Prompts.kt:185`) is also message text the user writes in the app.

### Contacts

| Question | Answer |
| --- | --- |
| Collected | **Yes** |
| Shared | **No** |
| Processed ephemerally | **No** |
| Required or optional | **Optional** |
| Purposes | **App functionality** |

- **Why collected:** Google's Contacts type covers "contact names". Each context line sent to the AI is labelled with the sender's display name from the address book or from Google Messages, not "Them". See `ai/TranslationManager.kt:295-304` (`contextFor` → `contacts.lookup(a).name`), `ui/chat/ChatViewModel.kt:955-961` (`contextLines`) and `ai/Prompts.kt:68`.
- **Not collected:** the contact list itself. `data/ContactsRepository.kt` reads contacts only for on-device display and the recipient picker.
- **Why optional:** same as Messages. Even without the contacts permission, names can still come from Google Messages (`fallbackNames`, `ContactsRepository.kt:125`).
- **Recommended code change:** replace sender names in context lines with neutral labels ("Them", or "Person 1/2" in groups). Contacts then leaves this form, and the privacy policy gets simpler.

### Device or other IDs

| Question | Answer |
| --- | --- |
| Collected | **Yes** |
| Shared | **No** |
| Processed ephemerally | **No** (a keyed hash is stored) |
| Required or optional | **Optional** |
| Purposes | **App functionality**, **Fraud prevention, security, and compliance** |

- **Why collected:** ANDROID_ID (`access/PlayAccessBackend.kt:125`) goes to the Worker in `/v1/trial` (`access/WorkerApi.kt:79`), and once in `/v1/subscription` after a purchase (`WorkerApi.kt:100`).
  - The Worker stores `HMAC("device:" + id)` in `trials.device_hash` (`backend/src/trial.ts:28`, `backend/migrations/0001_init.sql`), to give each device one free week.
  - This type also covers the Play Integrity token bound to the same ID (`access/PlayIntegrity.kt:31,68`; checked, not stored, in `backend/src/trial.ts:64`).
  - It also covers the per-network trial counter: a keyed hash of the IPv4 address or IPv6 /64, deleted after about a day (`backend/src/ratelimit.ts`, `trial.ts:38`, `0002_trial_calls.sql`). IP addresses aren't used for location, so no location type.
- **Why not shared:** Google decodes the Integrity token as the API provider, and Cloudflare hosts the Worker. Both are service providers.
- **Why optional:** only the free week and the first call after a purchase send it. BYOK and no-AI users never do.

### Financial info → Purchase history

| Question | Answer |
| --- | --- |
| Collected | **Yes** |
| Shared | **No** |
| Processed ephemerally | **No** |
| Required or optional | **Optional** |
| Purposes | **App functionality**, **Fraud prevention, security, and compliance** |

- **Why collected:** the purchase token and product ID go to the Worker (`access/WorkerApi.kt:97-98`). The Worker stores them in the `subscriptions` table (`0001_init.sql`, `subscription.ts:412`): the token encrypted, its keyed hash, the product, state, expiry, auto-renew, the latest Play order ID, and voided order IDs.
- **Purposes:** to deliver the plan's AI allowance (App functionality), and to turn off keys after refunds and chargebacks (`backend/src/cron.ts`, voided purchases), which is Fraud prevention.
- **Why not shared:** the token goes back only to Google Play's Developer API, to verify the purchase.
- **Why optional:** only subscribers.
- **Payment info is not declared:** Google Play Billing collects the payment details itself, and the app never sees them (Google's payment-service exemption above).

## Considered and not declared

| Data | Decision | Why |
| --- | --- | --- |
| Personal info (name, email, phone number, user IDs) | Not collected | Never sent to the Worker or the AI (`access/WorkerApi.kt`, `ai/Prompts.kt`). |
| Google sign-in cookies (Google-account pairing) | Not declared | No Data safety type fits. They're read from the user's own sign-in on Google's page (`ui/pairing/GoogleSignIn.kt`) and sent only to Google's Messages servers (`gm/GmClient.kt:1164`). The privacy policy discloses them (section 2). |
| Recipient numbers, message payloads and media to Google's relay | Not declared | End-to-end encrypted between the phone and the app: `RequestCrypto` (`gm/GmCrypto.kt:29`), and media with a per-file key (`gm/GmClient.kt:1048`, `MediaCrypto`). |
| Photos, videos, audio, files | Not collected | They only travel through Google Messages, encrypted. The AI only gets text. |
| Location | Not collected | No location permission. Addresses open Maps through an on-device intent (`smart/SmartActions.kt:30`). The IP is used only for rate limiting. |
| App activity (app interactions, in-app search, other UGC) | Not declared | The usage ledger is a local Room table (`data/db/Database.kt:62`, `UsageEntity`). OpenRouter keeps per-request metadata (token counts, cost) against a pseudonymous key, but that's part of processing the declared Messages data. If you want to be as conservative as possible, add **App activity → App interactions** for App functionality. |
| Learner level and languages | Not declared | They go in the AI system prompt (`ai/Prompts.kt`), but they're app settings, not a listed data type. |
| App info and performance (crash logs, diagnostics) | Not collected | No crash, analytics or ads SDK in `gradle/libs.versions.toml`. Logs go only to the device's logcat. |
| Web browsing | Not collected | The only WebView is Google's sign-in page. |
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
- **Data collected:** Messages, Contacts, Device or other IDs, Financial info.
- **Security practices:** "Data is encrypted in transit", "You can request that data be deleted".

## What would change these answers

| Change | Update |
| --- | --- |
| Sender names in AI context replaced with neutral labels | Remove **Contacts** |
| An in-app privacy disclosure + consent screen before the first AI use | No change, but it supports "not shared" and the User Data policy |
| Crash reporting or analytics added | Add **App info and performance** (and maybe App activity) |
| Managed mode adds another provider, or a host without ZDR | Re-check "not shared" and the privacy policy |
| Subscription rows deleted after a set period | Update the privacy policy retention table; the answers stay |
| An in-app "delete my server data" button | The answers stay; mention it in the privacy policy |
| Accounts or sign-in added | Account creation answers, and the account deletion requirement |
