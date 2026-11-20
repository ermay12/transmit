# Google Play store listing: draft

Draft text for Play Console → Grow users → Store presence → **Main store listing**, in English (United States). Every limit and count below was checked by script. Play counts characters, whatever the type ("The numbers listed in the table are the maximum limits regardless of character type", [Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152)).

## Policy constraints this draft follows

- **[Metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842):**
  - "Your app title must be 30 characters or less."
  - No emoji, emoticons or repeated special characters, and no ALL CAPS.
  - The title, icon and developer name can't show "store performance or ranking, price or promotional information" or Play programs. So no "free" in the title or icon.
  - No unattributed testimonials, no "best" or "#1", and no keyword stuffing ("Avoid using repetitive or unrelated keywords or references").
- **[Impersonation policy](https://support.google.com/googleplay/android-developer/answer/9888374):** "Don't imply that your app is related to or authorized by someone that it isn't." The policy also names developer names that suggest "an official relationship with Google". So:
  - "Google" and "Messages" stay out of the title, icon, developer name and feature graphic;
  - Google Messages is named in the description only as what Transmit works with, next to a plain "not made, endorsed or supported by Google";
  - no Google or Google Messages logos, icons or colours anywhere, including screenshots.
- **[Subscriptions policy](https://support.google.com/googleplay/android-developer/answer/9900533):** disclose "the frequency of your billing cycle, the automatic renewal terms" and how to cancel. The description says plans renew monthly until cancelled, and that the free week doesn't convert to a paid plan.
- Prices are left out of the text: they differ by country and Play shows them. The plan allowance estimates ("around 3,000 translations a month") were left out too, while they were unapproved. The developer approved them with the rest of the app copy on 2026-11-19 (`docs/product/copy-review.md`), so they can be added if you want them here.

## App name (30 characters max)

| Option | Characters | Notes |
| --- | --- | --- |
| **Transmit: Learn by Texting** | 26 | **Chosen.** Says the benefit, with one keyword-like phrase. |
| Transmit: Texting for Learners | 30 | At the limit. |
| Transmit | 8 | Safest, but tells a searcher nothing. |

**Decision (developer, 2026-11-19):** the Play title is **Transmit: Learn by Texting**. The in-app name stays "Transmit" (`app_name`); a different store name is fine.

A naming risk to check yourself: Panic Inc. sells a well-known file-transfer app called Transmit. Their old US registrations for TRANSMIT appear cancelled in third-party trademark databases, but that isn't legal advice, and common-law rights can remain. The title is chosen, but this search isn't recorded as done: search USPTO TSDR (and EUIPO if you sell in the EU), and look for apps called "Transmit" on Play, before the production release.

## Short description (80 characters max)

**Chosen (developer, 2026-11-20):**

```text
Practice any language while texting your friends
```

- 48 characters, well under the 80 limit.
- It's benefit-led, and has no brand names, promotions or "free". It doesn't name Google Messages, so it can't be read as implying a Google relationship.
- "Any language" is broad: the language picker offers a fixed list (`app/src/main/java/app/transmit/data/Languages.kt`). If that becomes a concern, "Practice a language while texting your friends" is the drop-in fallback.
- It replaces the 2026-11-19 choice, "Text friends in a language you're learning. Two-way translation and an AI tutor." (80 characters).

Options considered before the decision:

| # | Text | Characters |
| --- | --- | --- |
| 1 | Text friends in the language you're learning, with AI translation and a tutor. | 78 |
| 2 | Practice a language in your real chats: translations, corrections, explanations. | 80 |
| 3 | Your texts, translated into the language you're practicing. Reply with help. | 76 |

- "Learn a language by texting. AI translation and a tutor. Try a free week." (73 characters) was also considered. Promotional wording in metadata draws reviewer attention, so "free week" stays in the full description and the screenshots.

## Full description (4,000 characters max)

This draft is 3,464 characters (checked by script on 2026-11-19; the bullets are "•"). Paste it as plain text.

Before pasting:

- Make sure the claims still match the build.
- Optionally, add the privacy policy URL after "Details are in the privacy policy." Play also shows the URL on the listing.
- The GitHub link at the end only works once the repo is public (`launch-checklist.md`, step 8).

```text
Transmit turns your everyday texts into language practice.

Messages from friends arrive translated into the language you're practicing, so you read them in that language first. Reply in your practice language and Transmit prepares the version your friend receives in your own language. Not sure how to say something? Write it in your own language and Transmit shows you how to say it.

Transmit works with Google Messages on your phone, the same way Messages for Web does. Your texts keep going through Google Messages, and nothing changes for the people you text. They don't need Transmit: your messages reach them as ordinary texts, in the language you speak.

What you can do
• Read every chat in your practice language. New messages are translated as they arrive, and you can tap any message to see the original.
• Write in either language. Transmit shows both versions before you send.
• Check my translation: get your attempt graded, with corrections, a more natural version and what changed.
• Explain: a phrase-by-phrase breakdown of any translation, with notes on grammar, idioms and slang.
• Chinese and Japanese: show pinyin or romaji under the characters, or on their own, and type in pinyin or romaji if you like.
• Pick a language per chat, so you can practice Spanish with one friend and Japanese with another.
• Keep your chats as they are: SMS, MMS and RCS, group chats, photos and videos, reactions, replies, read receipts, search, pin, mute and archive.
• Copy a verification code straight from the notification. Messages with codes are never sent for translation.
• Tap addresses, dates, times, phone numbers and email addresses to open them in the right app.

Free week and plans
• Start with a free week: 7 days of translation and tutoring. No card needed, and it doesn't turn into a paid subscription.
• After that, choose Transmit or Transmit Plus, which has twice the AI. Plans are billed monthly through Google Play and renew automatically until you cancel. Prices are shown in the app in your currency, and you can cancel anytime in Google Play.
• Prefer your own AI provider? Use your own API key from Anthropic, OpenAI, Google Gemini, OpenRouter or another OpenAI-compatible service, and pay them directly.
• Without a plan or key, Transmit still works for texting. Translation stays off.

What you need
• An Android phone with Google Messages as its texting app. Transmit runs on Android 12 or later, on that phone or on another device.
• Google Messages connects to one web session at a time. Connecting Transmit signs out Messages for Web elsewhere, and the reverse.
• An internet connection for translation and the tutor.

Your privacy
Your messages never reach Transmit's server. To translate, the message text and a few earlier messages from the same chat are sent to an AI service: with the free week or a plan, OpenRouter, limited to model hosts with zero-data-retention policies; with your own key, the provider you chose. No ads, no analytics. Details are in the privacy policy.

Good to know
• AI translations can be wrong. Check anything important with the person you're texting.
• Transmit is an independent app. It isn't made, endorsed or supported by Google. It uses the same unofficial connection as Messages for Web, so a change on Google's side can stop it working until Transmit is updated.
• Google Messages is a trademark of Google LLC.
• Transmit is open source under the GNU AGPL v3: github.com/ermay12/transmit
```

Fact checks behind the claims:

- "They don't need Transmit: your messages reach them as ordinary texts, in the language you speak": `ChatViewModel.send` (`ui/chat/ChatViewModel.kt:948`) sends only the native-language box through Google Messages, with nothing added. The practice-language version stays on the phone (`storeSelf`). Onboarding says the same (`onboarding_practice_body`, `pair_intro`).
- "translated as they arrive", "tap any message to see the original", Check my translation, Explain, pinyin and romaji, verification codes, smart links, SMS, MMS and RCS: `README.md` Features.
- "never sent for translation" for codes: `ai/TranslationManager.kt:78`.
- "Pick a language per chat": the per-chat settings in `ChatSettingsEntity`.
- "Without a plan or key, Transmit still works for texting": NONE mode, `docs/product/release-1.0.md`.
- "One web session at a time": `README.md`.
- "Android 12 or later": `minSdk = 31`.
- "Never reach Transmit's server": `backend/API.md`, "no message text ever reaches the Worker".
- "Zero-data-retention": the Transmit Customers workspace guardrail (`backend/wrangler.jsonc`).
- "Twice the AI": the allowances are $4.50 and $9.00 (`PLAN_ALLOWANCES_USD`), and Plus is described this way in `docs/product/copy-review.md`.

## Category and tags

- **Category: Communication.** Transmit replaces the day-to-day messaging screen. That's what users will judge it on, and where people look for messaging apps. Education is the alternative. It fits language learners better in search, but sets an expectation of lessons, which Transmit doesn't have.
- **Tags:** choose up to five from Play's list in Store settings (the list is fixed, so pick the closest matches). Candidates: Language learning, Messaging, Translation / Translator, Texting / SMS, Education.

## Store settings: contact details

Decided by the developer on 2026-11-19: Transmit is published from the existing Play developer account (a personal account, shared with LinguaRead), which will declare as a **trader**. No LLC. The developer approved publishing the name, address and email below.

- **Developer name:** the existing account's developer name, the one LinguaRead is published under (unchanged). It mustn't suggest a tie to Google (Impersonation policy).
- **Email (required, shown publicly):** ermay12@gmail.com. It's also the privacy policy's contact for data deletion requests.
- **Address (shown publicly for traders):** Eric Maynard, 7320 Austin St APT 5H, Forest Hills, NY 11375, United States. It comes from the developer account, not from this listing, and shows on every app of the account, LinguaRead included.
- **Website (optional):** the privacy policy page, or https://github.com/ermay12/transmit once it's public.
- **Phone (shown publicly for traders):** +1 484-948-5575 (chosen by the developer, 2026-11-20). It's set on the developer account and shows on every app of the account.
- **Privacy policy URL:** required. See `launch-checklist.md`, step 3.

## Graphics

Made on 2026-11-20 and kept in [`store-assets/`](store-assets/README.md), which lists every file with its size and what to review.

- **App icon:** [`store-assets/icon-512.png`](store-assets/icon-512.png), 512 × 512 32-bit PNG, rendered from the adaptive launcher icon (`ic_launcher_foreground` on `ic_bg`) as a full square; Play adds the mask and shadow. It must have no badges or "new" dots (the Metadata policy bans a "new message dot indicator when there are no new messages"). It mustn't look like the Google Messages icon (no blue speech bubble): the icon is two speech bubbles on blue, so this is on the review list in the README.
- **Feature graphic:** [`store-assets/feature-graphic-1024x500.png`](store-assets/feature-graphic-1024x500.png), 1024 × 500 PNG without alpha: the icon, the name "Transmit", the line "Learn a language by texting", and chat bubbles in Spanish and English at the edges. The icon, name and line are in the middle, so a crop of the edges loses only the bubbles. No Google marks, no "free", no prices.
- **Phone screenshots:** seven, 1080 × 1920 PNGs, in [`store-assets/screenshots/captioned/`](store-assets/screenshots/captioned/) (captions on the brand colour; upload these) and [`store-assets/screenshots/raw/`](store-assets/screenshots/raw/). They come from the reviewer demo's sample chats on the emulator, with made-up names and no numbers or photos.

## Screenshot captions

| # | Screen to capture | Caption | Characters |
| --- | --- | --- | --- |
| 1 | A chat with translated incoming bubbles | Messages from friends, in the language you're practicing | 56 |
| 2 | The same chat with one bubble flipped to the original | Tap any message to see the original | 35 |
| 3 | The composer with both language boxes filled | Reply in your practice language. They get it in yours. | 54 |
| 4 | The Check my translation sheet with a diff | Check my translation: corrections and a natural version | 55 |
| 5 | The Explain sheet | Explain: grammar, idioms and slang, phrase by phrase | 52 |
| 6 | A Chinese or Japanese chat with pinyin or romaji under the characters | Pinyin and romaji, the way you like to read | 43 |
| 7 | Onboarding step 2 ("Connect your messages") | Works with Google Messages on your phone | 40 |
| 8 | Onboarding step 3, or the Plans screen | Start with a free week. No card needed. | 39 |

- Caption 7 names Google Messages factually. Keep the screenshot free of Google's logo and the Messages icon.
- Caption 8 is promotional. Play's price and promotion ban names the title, icon and developer name, not screenshots, but drop this one if a reviewer objects. **It was dropped from the 2026-11-20 set**, which uses captions 1 to 7.
- Files: caption *n* is `store-assets/screenshots/captioned/0n-*.png`. Caption 6 has a dark version (`06-romaji-dark.png`, the one in the set) and a light one (`06-romaji-light.png`).
- Screenshots of the Plans screen will show the prices of the account they were taken on.
