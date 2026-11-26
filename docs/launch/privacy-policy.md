<!--
This file is the privacy policy page. The Worker serves it at https://transmit.ermay12.workers.dev/privacy:
backend/scripts/build-pages.mjs renders it to HTML when the Worker is built, so editing this file and redeploying the
Worker updates the page (backend/README.md, "Policy pages").
The developer's name, address and email, the country and the effective date were filled in on 2026-11-19, with the
developer's approval to publish them. A new [BRACKETED] placeholder would show on the page, highlighted. When the policy
changes, set a new effective date, and re-check the facts against the build you ship. Comments like this one don't
show on the page.
Facts were taken from the code on branch play-launch at 189aed3 (2026-11-18). The server parts (IP addresses, logs,
retention) were re-checked on 2026-11-19 against the Worker after its privacy changes, and the AI context labels
against the app change that replaces contact names with "Me", "Them" and "Person 1, 2, …". See
docs/launch/data-safety.md for the code references behind each statement.
Google Analytics (section 5, and the short version) was added on 2026-11-26 for app version 1.0.3, from branch
analytics: app/src/main/java/app/transmit/analytics/Analytics.kt and the manifest's Firebase settings. Set the effective
date to the day this version of the page goes live, before 1.0.3 reaches Play.
-->

# Transmit privacy policy

**Effective date:** November 26, 2026

Transmit is an Android app for language learners. It shows your Google Messages chats with AI translation and a tutor. It's made by Eric Maynard, an individual developer ("I", "me"). This policy explains what Transmit does with your data, in plain language. If anything is unclear, write to me at ermay12@gmail.com. The free week, the plans and the rules for using them are in the [terms of service](https://transmit.ermay12.workers.dev/terms).

## The short version

- Transmit has no accounts, no ads and no crash reporting. I don't sell your data, and it's never used to show you ads.
- The Google Play version counts the steps of setting it up (such as signing in, pairing and starting the free week) with Google Analytics, so I can see where people get stuck and which of my ads work. Those counts never include your messages, contacts, phone number, email address or Google account.
- Your chats are stored on your phone. Transmit's server never receives your messages.
- To translate or explain a message, Transmit sends its text, and a few earlier messages from the same chat, to an AI service:
  - With the free week or a Transmit plan, that's OpenRouter. Transmit's OpenRouter account only allows model hosts that have a zero-data-retention policy.
  - With your own API key, it's the provider you chose, under that provider's terms.
- Transmit's server keeps a few records to run the free week and subscriptions. They hold hashed or encrypted identifiers, never your name, phone number, email address, IP address or Google account. Subscription records are deleted 90 days after the subscription ends or is refunded.

## 1. What stays on your phone

Transmit stores these only on your device:

- **Your chats:** the conversations, messages, contact names and photos it gets from Google Messages, and the photos, videos and files in them (downloaded and decrypted when you open them).
- **What Transmit adds:** translations, explanations, drafts, per-chat settings, and a record of how much AI each chat used.
- **Your settings:** your languages, your level and your app preferences.
- **Secrets, encrypted with a key that never leaves your phone's Android Keystore:**
  - the keys that link Transmit to Google Messages;
  - for Google-account pairing, the Google sign-in cookies that Messages for Web uses;
  - your own AI API keys, if you add any;
  - the AI key Transmit's server gives you for the free week or a plan;
  - your Google Play purchase token, if you subscribe.
- **Your contacts:** with your permission, Transmit reads your contacts to show names and photos, and to let you pick who to text. It doesn't upload your contact list, and it never sends contact names to the AI service (see section 3).

Transmit also finds verification codes, addresses, dates, phone numbers and email addresses in messages on the device. It creates its notifications on the device too, with no push service.

Transmit opts out of Android's cloud backup. Unpairing from Google Messages deletes the pairing and the chats and media cached from it. Clearing Transmit's storage or uninstalling it deletes everything listed above, including the Google sign-in inside Transmit's sign-in page (that sign-in can stay active after you unpair, until you clear storage or uninstall).

## 2. Google Messages

Transmit links to Google Messages on your phone the same way Messages for Web does. Google Messages stays your phone's texting app: it still sends and receives your SMS, MMS and RCS messages.

- Messages travel between Google Messages and Transmit through Google's servers. They're encrypted with keys that Google Messages and Transmit create when you pair. Photos and other attachments are encrypted too, each with its own key.
- If you pair by signing in with Google, you sign in on Google's own sign-in page, inside Transmit. Transmit then keeps the resulting Google sign-in cookies on your phone, encrypted, and sends them only to Google, to connect to Google Messages. I never receive them.
- Google processes what it receives under [Google's Privacy Policy](https://policies.google.com/privacy).

Transmit is an independent app. It isn't made, endorsed or supported by Google, and it uses the same unofficial connection as Messages for Web.

## 3. AI translation and the tutor

### What is sent

When Transmit translates or explains something, it sends the AI service:

- the text of the messages to translate, write in characters, or add pinyin or romaji to;
- for context, up to 8 earlier messages from the same chat (each shortened to 400 characters). Each is labelled only "Me" or "Them", or in a group chat "Person 1", "Person 2" and so on. Names and phone numbers from your contacts or Google Messages are never sent;
- for **Check my translation**, your draft and, if you wrote one, what you meant in your own language;
- for **Explain**, the message and its translation;
- your own language, the language you're practicing, and your level.

Transmit doesn't send the AI service contact names, phone numbers, photos, videos, audio, other attachments, your contact list or your Google account. Message text is sent as written, so a name that someone typed in a message goes with it.

### When it is sent

- **Automatically,** if auto-translation is on: as messages arrive, and for the newest few messages when you open a chat. It's on by default for chats with your contacts, chats with numbers that aren't in your contacts, and your own sent messages. You can turn each one off in **Settings → Translation**, or for one chat in its settings.
- **When you ask:** when you tap to translate, explain or check a message.
- **Never** for messages that contain a verification code. Those are never translated automatically.
- **Never** if you don't set up AI. Without the free week, a plan or your own key, translation stays off.

### Where it goes

**With the free week or a Transmit plan (Transmit, Transmit Plus):** your phone sends the request straight to [OpenRouter](https://openrouter.ai) (OpenRouter, Inc., in the United States). OpenRouter passes it to a host of the model Transmit uses (Google's Gemma 4 31B).

- The requests use an AI key that Transmit's server created for your free week or plan. OpenRouter doesn't know your name, phone number or Google account.
- Transmit's OpenRouter account only allows model hosts with a zero-data-retention policy, which means they don't store prompts or responses after answering. Hosts that keep requests for abuse monitoring are excluded too.
- I haven't turned on OpenRouter's optional prompt logging, or OpenRouter's use of inputs and outputs. So under [OpenRouter's data policy](https://openrouter.ai/docs/guides/privacy/data-collection), OpenRouter doesn't store your messages or Transmit's replies, with the two exceptions below.
- Even with zero data retention, OpenRouter may sample a small number of prompts to sort them into categories for its usage reports and model rankings. It says the categories are stored anonymously and never linked to an account, and that the sorting is done by a model with a zero-data-retention policy.
- OpenRouter also screens every request, zero-data-retention requests included, for child sexual abuse material as it passes through. Content flagged as possible child sexual abuse material may be kept for human review, for reporting, and where the law requires it to be preserved ([zero data retention](https://openrouter.ai/docs/guides/features/zdr)).
- OpenRouter does keep metadata for each request, such as the number of tokens, the cost and how long it took. It doesn't keep the text.
- Like any web service, OpenRouter receives your phone's IP address with each request. OpenRouter's own terms and [privacy policy](https://openrouter.ai/privacy) apply to its service.

<!--
OpenRouter facts checked 2026-11-19 against:
- https://openrouter.ai/docs/guides/privacy/data-collection ("Data Collection"): prompts and responses aren't stored
  unless you opt in to logging or the content is flagged as potential CSAM; "OpenRouter samples a small number of
  prompts for categorization to power our reporting and model ranking", stored "completely anonymously and never
  associated with your account or user ID", and "done by model with a zero-data-retention policy"; "OpenRouter will
  screen inference requests, including ZDR traffic, for child sexual abuse material (CSAM) in flight"; per-request
  metadata (token counts, latency) is stored. The page doesn't say whether sampling skips ZDR traffic, hence "may".
- https://openrouter.ai/docs/guides/features/zdr ("Zero Data Retention"): ZDR means "no routine storage of prompts or
  responses after processing"; all inference requests, including ZDR traffic, are screened for CSAM, and "Content
  flagged as potential CSAM may be retained for human review, reporting, and preservation"; the "ZDR only" setting
  excludes Safety Retention endpoints (launch-checklist.md, step 4).
- https://openrouter.ai/privacy (last updated August 31, 2026): collects IP addresses and log files; doesn't use
  inputs or outputs for model training.
Re-check these pages before publishing and whenever OpenRouter announces a policy change.
The account settings behind "I haven't turned on" were confirmed by the developer on 2026-11-19: prompt logging (Input &
Output Logging) is off, and "OpenRouter use of inputs/outputs" is off.
-->

**With your own API key:** your phone sends the request straight to the provider you chose: Anthropic, OpenAI, Google (Gemini API), OpenRouter, or the OpenAI-compatible service at the address you entered. That provider handles it under your account with them, and under their terms and privacy policy, including whether they store it or train on it. I never see these requests. Your key is stored encrypted on your phone and sent only to that provider.

AI can make mistakes. Check anything important.

## 4. Transmit's server

Transmit has a small server (a Cloudflare Worker with a Cloudflare D1 database, at `transmit.ermay12.workers.dev`). It hands out AI keys for the free week and for Google Play subscriptions, and keeps each key's allowance in step with Google Play. It never receives your messages, contacts, phone number, name, email address or Google account.

**When you start the free week**, the app sends the server:

- your device's Android ID, an identifier Android gives Transmit on your phone;
- a Google Play Integrity token. The server asks Google to check it, to confirm that this is the real Transmit from Google Play, on a genuine device.

The server stores a keyed hash of the Android ID (never the ID itself), when the free week started and ends, its AI allowance, and the hash of its AI key. It doesn't store the Integrity token or Google's answer. This record is how each device gets one free week.

**When you subscribe**, the app sends the server your Google Play purchase token, the plan, and once, your device's Android ID (so the free week's key can be turned off). The server checks the purchase with Google Play and stores:

- a keyed hash of the purchase token, and the token itself, encrypted, so the server can check the purchase with Google again later;
- the plan, its state (for example active, cancelled or on hold), when the current period ends, and whether it renews;
- the latest Google Play order number, and the order numbers of any refunded orders;
- the AI allowance, the hash of the AI key, its spending limit, and timestamps.

The server deletes this record, and the subscription's AI key, 90 days after the subscription ends or is refunded. A purchase that a plan change replaced (for example, by an upgrade to Transmit Plus) counts as ended on the day of the change; the plan you changed to has a record of its own.

**AI keys.** For each free week or subscription, the server creates an AI key at OpenRouter, sends it to your phone once, and keeps only its hash. It reads how much the key has spent, to set its limit.

**Network address.** To stop one network from starting many free weeks, the server limits how often each network can call it. It counts calls under a keyed hash of your IP address (for IPv6, of its /64 block) and the date, never under the address itself. The hash changes every day, so one day's counts can't be linked to another's.

- Free-week calls are counted per day in the server's database. Each day's counts are deleted shortly after the day ends (midnight UTC).
- All calls are also counted per minute by Cloudflare's rate limiter, under the same hash, for the current minute only.

**Hosting and logs.** Cloudflare runs the server, so it receives your IP address and other request details to deliver each request, and handles them under [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/).

- I've turned off Cloudflare's per-request logs for Transmit's server. So the server keeps no record of each request with its IP address, headers or location.
- The server writes a short log line when something happens, such as "free week started" or "subscription checked". These lines never contain IP addresses, Android IDs, purchase tokens or AI keys, and Cloudflare keeps them for 3 days.
- Cloudflare also counts the server's requests, errors and processing time, in totals that don't include IP addresses.
- When I'm fixing a problem, I can watch requests live, including their IP address. That live view isn't stored.

## 5. Google Play and Google Analytics

- **Payments:** Google Play handles subscription payments under [Google's Privacy Policy](https://policies.google.com/privacy). I don't receive your card or payment details. Google Play tells me about orders, refunds and subscription states, and shows me order details in its developer console.
- **Play Integrity:** when you start the free week, Google Play checks the app and the device. Google handles that data under the Google Play Terms of Service.

### Google Analytics

From version 1.0.3, the copy of Transmit from Google Play uses Google Analytics for Firebase, a Google service, to count how people set the app up. I use it to see where new users get stuck, and to measure Transmit's own ads on Google Ads (how many installs, free weeks and subscriptions they lead to). Copies from GitHub never use it.

**What is counted:**

- setup steps: choosing your languages, opening Google's sign-in page, signing in, seeing the emoji to confirm, pairing (by Google sign-in or QR code), starting the free week or the reason it couldn't start, and finishing setup;
- if you leave Google's sign-in page, the step you left it on, as a word such as "password", and that page's address without the part that can include your email address;
- Google Analytics' own events: opening the app for the first time, sessions and time in the app, app updates and uninstalls, and Google Play purchases (the plan and its price, never your payment details).

With these, Google receives an ID that Google Analytics creates for this installation of Transmit, your phone's model, its Android version, the app version and its language. Google derives an approximate location (such as your country or city) from your masked IP address.

**What is never counted:** your messages, contacts or contact names, phone numbers, email address or Google account. Transmit doesn't collect your phone's Advertising ID or Android ID for Google Analytics. Ad personalization and remarketing are turned off, so this data isn't used to show you ads.

Google Analytics keeps this data for 14 months, then deletes it. Google handles it under [Google's Privacy Policy](https://policies.google.com/privacy), and [how Google uses information from apps that use its services](https://policies.google.com/technologies/partner-sites) explains more.

## 6. How long data is kept

| Data | Where | How long |
| --- | --- | --- |
| Chats, translations, settings, keys | Your phone | Until you unpair (chats and media), clear Transmit's storage, or uninstall |
| Message text sent for translation | OpenRouter and its model hosts (free week and plans) | Not stored after the reply, except as described in section 3 |
| Message text sent for translation | Your own provider (own key) | Under that provider's terms |
| Free-week record (hashed Android ID, dates, allowance) | Transmit's server | As long as Transmit's server runs, so each device gets one free week |
| Free-week AI key | OpenRouter | Deleted when the free week ends |
| Subscription record | Transmit's server | Until 90 days after the subscription ends or is refunded, then deleted automatically |
| Subscription AI key | OpenRouter | Turned off when the subscription ends or is refunded, and deleted with the subscription record |
| Free-week call counts per network (keyed hash) | Transmit's server | Until the day ends (midnight UTC) |
| Per-minute call counts per network (keyed hash) | Cloudflare's rate limiter | The current minute |
| The server's log lines (no IP addresses or identifiers) | Cloudflare | 3 days |
| Setup steps and app events (Google Play version) | Google Analytics | 14 months |

## 7. Your choices and rights

- **Use Transmit without AI.** Don't start the free week or add a key, and translation stays off.
- **Limit what is sent.** Turn off auto-translation in **Settings → Translation**, or for one chat.
- **Choose your provider.** Use your own API key in **Settings → Advanced**.
- **Contacts and notifications** are optional. You can turn either permission off in Android's settings.
- **Delete data on your phone.** Unpair, clear Transmit's storage, or uninstall.
- **Delete data on Transmit's server.** Subscription records are deleted automatically 90 days after the subscription ends or is refunded. To have yours deleted sooner, email ermay12@gmail.com with your Google Play order number (it starts with "GPA." and is in your Google Play receipt). I'll delete your subscription record and its AI key within 30 days. If the subscription is still active, the app will set it up again the next time it checks, so cancel it in Google Play first if you want it gone for good.
  - Free-week records can't be matched to you, because they only hold a keyed hash of an identifier on your phone. I keep them to prevent abuse of the free week.

If you are in the European Economic Area, the United Kingdom or another region with similar laws, you can also ask to access or correct your data, object to how it's used, or complain to your local data protection authority. I handle the data above to provide the app you asked for (translation, the free week, your subscription), and for my legitimate interests in preventing abuse, keeping the service secure, and improving setup and measuring my ads (section 5).

## 8. Children

Transmit isn't meant for children. It isn't directed at children under 13, and I don't knowingly collect data from them. If you believe a child has used Transmit and want their server records deleted, contact me.

## 9. Security

Every connection Transmit makes uses HTTPS. On your phone, secrets are encrypted with a key kept in the Android Keystore. On the server, identifiers are stored as keyed hashes, purchase tokens are encrypted, and IP addresses aren't stored at all. No system is perfectly secure. If a breach affects your data, I'll post a notice on this page and in the app's release notes.

## 10. Where data is processed

I'm based in the United States. Cloudflare, OpenRouter and the model hosts it uses, and Google (including Google Analytics) may process the data described here in the United States and in other countries.

## 11. Open source

Transmit is open source under the GNU Affero General Public License v3. You can read exactly what the app and its server do at [github.com/ermay12/transmit](https://github.com/ermay12/transmit).

## 12. Changes to this policy

If this policy changes, I'll post the new version on this page with a new effective date. If a change affects what Transmit collects or where it sends it, I'll also say so in the app or in the release notes before it takes effect.

## 13. Contact

Eric Maynard\
7320 Austin St APT 5H\
Forest Hills, NY 11375\
United States\
ermay12@gmail.com
