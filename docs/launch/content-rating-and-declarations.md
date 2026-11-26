# Content rating, target audience and App content declarations

Proposed answers for Play Console → Monitor and improve → Policy and programs → **App content**, for Transmit 1.0 (`com.ermay12.transmit`, branch `play-launch` at 189aed3). The Data safety form has its own file, `data-safety.md`.

Every section on the App content page must be done before a closed or production release can go to review. Internal testing doesn't need them.

## 1. Content rating (IARC questionnaire)

[Google's steps](https://support.google.com/googleplay/android-developer/answer/9859655): enter an email for IARC, pick a category, answer the questionnaire, then submit. The questions are shown in the console and their wording changes from time to time. Answer what you see, using the facts below.

- **Email:** ermay12@gmail.com. IARC sends the certificate there.
- **Category:** "All other app types" / "Utility, Productivity, Communication, or Other", whichever your console shows. Not Game, not Social networking: Transmit has no public feed or profiles, only the user's own SMS and RCS chats.

| Topic | Answer | Why |
| --- | --- | --- |
| Violence, blood, fear, sexuality, nudity, profanity or crude humour, drugs, alcohol, tobacco, gambling, simulated gambling, hate symbols | **No** to all | Transmit has no content of its own. Messages come from the people the user texts. |
| Users can interact or exchange content (text, images, audio) with other users | **Yes** | It's a messaging app. Content from other people isn't filtered by Transmit. |
| Shares the user's personal information with third parties | **Yes** (conservative) | Message text goes to the AI service. Data safety calls OpenRouter a service provider (not sharing), but IARC's "Shares Info" notice is broader, and saying yes avoids a mismatch. "No" is defensible if you'd rather not show the notice. |
| Shares the user's current location with other users | **No** | No location permission. Users can type an address, but the app never shares location. |
| Digital purchases | **Yes** | Transmit and Transmit Plus subscriptions through Google Play Billing. |
| Unrestricted internet access / web browser | **No** | The only WebView is Google's sign-in page for pairing (`ui/pairing/GoogleSignIn.kt`). Links open in the user's browser. |
| News, educational content, or a medical app | **No** | |

Expected result: the lowest age ratings (ESRB Everyone, PEGI 3 or similar), with the interactive elements "Users Interact", "Shares Info" and "In-App Purchases". This is what messaging apps usually get.

## 2. Target audience and content

| Question | Proposed answer | Why |
| --- | --- | --- |
| Target age groups | **18 and over** only | Transmit is a paid AI service that sends message text to a third-party AI. It shows texts from any number, translated automatically. Leaving out every group under 13 keeps it out of the Families policy. Leaving out 13–17 avoids questions about minors and AI. |
| Could the store listing unintentionally appeal to children? | **No** | Plain screenshots, no cartoon characters or games. |
| Store presence | "Not designed for children" (implied) | |

**Decided: 18+** (`docs/product/release-1.0.md`, Play compliance). The reasoning: language learners include teenagers. 13+ is possible, since Google Messages itself is for 13+, and the privacy policy already says "not directed at children under 13". But the teen age bands draw extra scrutiny for apps with AI and paid features. I recommend 18+ for 1.0. You can widen it later.

## 3. Ads

**"No, my app does not contain ads."** There's no ad SDK in `gradle/libs.versions.toml`. Since 1.0.3, Firebase Analytics measures Transmit's own Google Ads campaigns, but the app shows no ads, and the manifest removes both Advertising ID permissions Firebase brings (`com.google.android.gms.permission.AD_ID` and `android.permission.ACCESS_ADSERVICES_AD_ID`). The plan upsells (Home banners, the Plans screen) are the app's own subscription prompts, not ads.

**Advertising ID declaration:** "No, my app does not use advertising ID."

## 4. App access (instructions for reviewers)

This is the hardest declaration, and **a real rejection risk**.

### Why it's a problem

Transmit does nothing useful until it's paired with Google Messages, and pairing needs the phone that runs Google Messages:

- Google-account pairing makes you tap an emoji on that phone (`pair_step_confirm`).
- QR pairing makes you scan a code with it.

So you can't give reviewers credentials, as [App access](https://support.google.com/googleplay/android-developer/answer/15748846) asks ("sign-in details must be accessible at all times, reusable, and valid regardless of user location"). A test Google account would send the pairing prompt to *your* phone, and someone would have to tap it at the moment of review. The page also asks paywalled apps to give access that lets reviewers "fully and freely access" the app.

The free week may also fail for a reviewer:

- It needs a build Play recognizes, `LICENSED` to the reviewer's account, on a device that passes integrity (`backend/src/trial.ts`).
- Each device gets one free week, so a reviewer device that tested an earlier version gets `trial_already_used`.

### What to do

**Before production, ideally before the closed test:**

1. **Best: build a demo mode.** For example, "Try a demo" on onboarding step 2, with sample chats in a few languages, so it can be reviewed without pairing.
   - It's a code change. It also helps the many store visitors who want to see the app before giving it their Google account.
   - Clearly label it as sample data, and make it available to everyone, not only reviewers.
2. **Without a demo mode, give three things:**
   - **Self-pairing instructions.** A reviewer device with Google Messages as its default SMS app, signed in to a Google account, can pair with itself through Google sign-in.
   - **A review AI key**, so the AI works whether or not the free week starts. Create an OpenRouter key named `transmit-review` in the Transmit Customers workspace: a $3 limit, no expiry. The workspace guardrail keeps it to Gemma on ZDR hosts. Watch its spending, and replace it if it leaks.
   - **A video** of the whole flow (unlisted YouTube): pairing, translated chats, Check my translation, Explain, and the Plans screen. You need a video for the foreground service declaration anyway (section 7).

### Draft "Instructions" text (English, as required)

Superseded by the demo-mode text in [App access instructions for reviewers (final, from beta4)](#app-access-instructions-for-reviewers-final-from-beta4), which needs no pairing, key or video. The `[REVIEW KEY]` and `[VIDEO URL]` placeholders below only matter if you fall back to this draft.

> Transmit is a client for Google Messages, like Messages for Web. It has no login of its own.
>
> To review it you need an Android phone where Google Messages is the default SMS app and is signed in to a Google account.
> 1. Install Transmit on that phone. Onboarding step 1: pick the languages, then Continue.
> 2. Step 2: tap Connect Google Messages, then Sign in with Google, using the same Google account Google Messages uses on this phone. Google Messages then shows a pairing request; tap the emoji Transmit shows. (Or: Pair with a QR code instead, scanned from a second device.)
> 3. Step 3: tap Start your free week (7 days, no payment). If it can't start on this device, tap Not now, then go to Settings > Advanced > Use my own AI key, choose OpenRouter and paste this key: [REVIEW KEY].
> 4. Open a chat. Incoming messages appear translated; tap one to see the original. Type a reply in the practice-language box and tap the check icon (Check my translation); type in the native-language box and tap the translate icon to see it in the practice language. Long-press a message and tap Explain.
>
> Video of the full flow: [VIDEO URL]. Subscriptions use Google Play Billing; the Plans screen is under Settings.

Choose **"All or some functionality is restricted"**, and put the text above in the instructions field. Leave username and password empty, since there's no Transmit login.

## 5. Financial features declaration

**"My app doesn't provide any financial features."**

- Every app must complete this ([Google's page](https://support.google.com/googleplay/android-developer/answer/13849271): "All developers that have an app published on Google Play must complete the Financial features declaration").
- Transmit has none of the listed features (banking, loans, payments and transfers, purchase agreements, trading, crypto, support services). Selling its own subscription through Play Billing isn't one of the categories.

## 6. Other App content items

| Item | Answer |
| --- | --- |
| Privacy policy | The hosted URL of `privacy-policy.md` (see `launch-checklist.md`, step 3) |
| Data safety | `data-safety.md` |
| Government app | No |
| News app | No |
| Health apps declaration (if shown) | "My app does not have any health features" |
| COVID-19 contact tracing and status (if still shown) | Not a COVID-19 app |
| Account deletion | Not applicable: there's no way to create an account |

## 7. Permissions

These are the merged release manifest's permissions (`app/build/intermediates/merged_manifests/release/.../AndroidManifest.xml`).

| Permission | Where it comes from | Play Console declaration? | Notes |
| --- | --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | App | No | Normal permissions. |
| `READ_CONTACTS` | App | No form | A runtime permission. The onboarding line "Next, allow contacts and notifications so your chats show names and you hear about new messages." comes right before the prompt. But contact *names* also go to the AI as context (`data-safety.md`), which that line doesn't mention. Either anonymize the speaker labels (recommended), or add it to the AI disclosure (section 8). |
| `POST_NOTIFICATIONS` | App | No form | A runtime permission, asked for at the same step. Notifications are created on the device. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_REMOTE_MESSAGING` | App (`gm/BridgeService.kt`) | **Yes: Foreground service permissions** | See below. |
| `RECEIVE_BOOT_COMPLETED` | App, plus WorkManager | No | Restarts the bridge after a reboot (`gm/BootReceiver.kt`). |
| `VIBRATE` | App | No | Notifications. |
| `WAKE_LOCK` | WorkManager | No | |
| `com.android.vending.BILLING` | Play Billing Library 9.1.0 | No | |
| `com.ermay12.transmit.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX core | No | Internal to the app. |

**Not requested, so no sensitive-permission declarations:** SMS, call log, location, photos and videos (`READ_MEDIA_*`), all-files access, `QUERY_ALL_PACKAGES`, exact alarms, accessibility, full-screen intent.

- Transmit handles `sms:` and `mms:` links, and has the `APP_MESSAGING` category.
- But it doesn't ask for the default-SMS role or any SMS permission, so the SMS and Call Log declaration doesn't apply.

### Foreground service declaration: draft

Google's requirements ([foreground service declaration](https://support.google.com/googleplay/android-developer/answer/13392821)): for each type, a description, the user impact if the task is deferred or interrupted, a demo video, and a use case.

- **Type:** Remote messaging (`FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING`).
- **Use case:** "Relay text communication to another device". This is Google's listed use case for the type. Its example is a web client paired by QR code, which is how Transmit pairs.
- **Description:**
  > Transmit is a client for the user's own Google Messages, like Messages for Web. While Transmit is paired, a foreground service keeps its connection to Google Messages open. New SMS and RCS messages that Google Messages relays from the phone then arrive right away (and are translated), and messages the user sends go out through Google Messages. The service starts once the user has paired, shows an ongoing low-priority "Receiving messages" notification, and stops when the user unpairs or Google Messages signs Transmit out.
- **Impact if deferred or interrupted:**
  > Incoming texts and their notifications would be delayed until the user opens the app, and messages the user sends might not go out. Transmit has no push channel of its own: the open connection is how messages arrive.
- **Video:** made on 2026-11-21: `~/Downloads/transmit-fgs-remote-messaging.mp4` (47 s, 1920 × 1080, captioned; how it was made: `fgs-video/README.md`). It shows pairing on the emulator (Connect Google Messages, Sign in with Google, the QR code), then on the developer's Pixel with beta18 from Play: opening Transmit starts the service, the shade shows "Connected to Google Messages · Receiving messages", and a test text from a second phone arrives as Transmit's notification while Transmit is in the background. Personal content is blurred. It was filmed with Transmit on the same phone as Google Messages, not on a second device: the emulator can't complete pairing, and the rules say never to pair or unpair. Play needs a link: upload it to YouTube as **Unlisted** (not Private, which reviewers can't open), or to Drive with "Anyone with the link".
- **Risk:** Google describes this type as relaying messages to *another* device. Most Transmit users will run it on the same phone as Google Messages. If a reviewer pushes back, explain that Transmit is the companion client (like Messages for Web) whatever device it runs on.

## 8. Policy items that need app changes before review

These aren't form answers, but reviewers check them, and the forms above assume they'll be fixed.

1. **A privacy policy link inside the app.**
   - [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311): a "privacy policy link or text within the app itself", not only in Play Console.
   - The app has none today (no "privacy" string under `app/src`). Add one to the Settings footer next to **Source code**, and to onboarding step 3.
2. **A prominent disclosure before message text first goes to the AI.**
   - The User Data policy covers "SMS and call-related data" and "contacts", and requires an in-app disclosure, followed by affirmative consent, when data use may be outside what users reasonably expect.
   - Translation is the app's purpose. But automatic translation of every incoming message, including from unknown numbers, to a third party is the kind of use reviewers want disclosed.
   - Put it on onboarding step 3, and on the "Turn on translation" sheet, right above **Start your free week** and **Use my own AI key**. Tapping either button is the consent. Suggested copy (Google's suggested pattern):
     > To translate, Transmit sends the text of your messages, and a few earlier ones from the same chat, to an AI service: OpenRouter for the free week and plans, or the provider you choose. It isn't stored on Transmit's server. Privacy policy
   - If sender names stay in the AI context, add "and the names of the people in the chat".
3. **Say that subscriptions renew automatically.**
   - The [Subscriptions policy](https://support.google.com/googleplay/android-developer/answer/9900533) lists, as a violation, "Monthly subscriptions that do not inform users they will be automatically renewed and charged every month."
   - The Plans screen shows the price, "/month" and "Cancel anytime in Google Play" (`plans_cancel_anytime`), but not that the plan renews.
   - Suggested copy: "Renews automatically every month until you cancel. Cancel anytime in Google Play."
   - Manage subscription is already linked, as the policy requires.
4. **Optional: a way to report AI output.**
   - The [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936) requires in-app reporting of offensive AI output for apps that "generate content using AI".
   - Its [scope page](https://support.google.com/googleplay/android-developer/answer/14094294) excludes "Productivity apps that use AI to improve an existing feature, such as email apps with AI-suggested email drafts". Transmit's translations and tutor arguably fit that, so this is optional.
   - Cheap insurance: a "Report translation" action in the long-press sheet (`ui/chat/Sheets.kt:163-167`, next to Explain and Redo). It could open an email to ermay12@gmail.com with the AI output filled in.

## 9. Risks to know about before submitting

1. **[Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/9888379).** Its examples include "Apps that access or use a service or API in a manner that violates its terms of service." Three things point that way:
   - Google-account pairing sends Google a `privateAPIConfirmation` field reading "This is an undocumented API. Use or access of undocumented Google APIs without express authorization is prohibited per the Google API Terms of Service" (`gm/GmClient.kt:1249`, inherited from libgm).
   - The sign-in WebView presents itself as Chrome, so Google allows the sign-in (`ui/pairing/GoogleSignIn.kt`, `configureAsChrome`).
   - Transmit keeps full Google session cookies (SID, HSID, SAPISID and others) on the device.

   You've accepted the policy risk. Just note that a closed-track release is reviewed too, and enforcement applies to the whole developer account, including your other apps on it.
2. **[Impersonation](https://support.google.com/googleplay/android-developer/answer/9888374).** Covered by keeping Google's name out of the title, icon and developer name, and by the "not made, endorsed or supported by Google" line (`store-listing.md`).
3. **User Generated Content.** Unlikely to apply. Transmit shows the user's own carrier and RCS messages, and doesn't host content. Blocking and spam reporting stay in Google Messages.

## App access instructions for reviewers (final, from beta4)

Paste into Play Console → App content → App access ("All or some functionality is restricted" → add instructions):

> No login or pairing needed. On onboarding step 2, long-press the heading "Connect your messages". A demo inbox labelled DEMO opens, with sample chats in Spanish and one group. Tap "Start your free week". If it can't start on your device, go to Settings > Advanced > Use my own AI key, choose OpenRouter, paste the key below and tap Save. Tap a bubble to see the original, long-press it for Explain, and type a reply to translate or check it. "Exit demo" or Back returns to onboarding.
>
> OpenRouter key for reviewers: [REVIEW KEY]

The review key (`transmit-review`) was created on 2026-11-21 in the Transmit Customers workspace: a $3 limit, no monthly reset and no expiry. It's in `~/.config/transmit/review-key.env`, and the text above with the key filled in is in `~/.config/transmit/app-access-text.txt`; neither is in the repo. A test translation through it worked, and the workspace guardrail refused a non-Gemma model. Watch its spending, and if it leaks, delete it and paste a new one.
