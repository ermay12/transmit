# Transmit

An Android texting app for language learners. Transmit replaces your SMS/MMS app: messages from friends arrive translated into the language you're practicing, and you can reply in that language while friends receive your native language. Translation and tutoring use your own AI API key.

## Features

- **Full SMS/MMS app:** conversations, group chats, photos and media, reactions, delivery reports, notifications, search, pin, mute, and swipe to archive.
- **Translated inbox:** incoming messages are translated in the background as they arrive. Until the translation is ready, the text is hidden, so you don't read it in your native language first. Tap any bubble to switch between the original and the translation.
- **Write in either language:**
  - Write in your practice language: Transmit prepares the native-language version your friend receives.
  - Write in your native language: Transmit shows how to say it in your practice language.
  - **Check my translation:** grades your attempt, suggests corrections and shows a diff.
- **Explain:** gives a phrase-by-phrase breakdown of any translation, with notes on grammar, idioms and slang.
- **Pinyin and romaji:** for Chinese and Japanese, choose how messages show: characters only (tap a message to add its pinyin or romaji, tap again for the original), characters with the romanization underneath, or romanization only. Whatever you choose, the box you write in shows its romanization as you type. In romanization-only mode you can write in pinyin (tone numbers like `dui4` work) or romaji, and corrections stay in it. Otherwise, pinyin or romaji you type is corrected into characters. In a mode with characters, messages you sent in pinyin or romaji are rewritten in characters, matched to what you wrote, with your romanization kept underneath: the newest ones (as many as Translate on open covers) when a chat opens or you switch modes, and older ones when you tap them. The AI provides readings that fit the context (银行 → yínháng); pinyin typed with tone numbers gets tone marks on the device.
- **Per-chat settings:** language, auto-translate, and monthly budget.
- **Budgets and usage tracking:** default budgets are $5 a month overall, $1 per contact and $0.10 per unknown number. Unknown numbers aren't auto-translated, so verification codes and spam never reach the AI.
- **Verification codes:** a notification with a one-time code gets a "Copy 482913" button that copies the code, marks the chat read and dismisses the notification. In the chat, the code is bold and tappable, and the newest code (under an hour old) has a Copy code chip. Messages with codes appear at once and are never auto-translated.
- **Smart links:** addresses open in Google Maps; dates and times ("Friday at 7", "Nov 28 from 6-9pm", "mañana a las 3", "金曜日の夜7時") open a prefilled calendar event with the message as its title and any address in the message as its location; phone numbers and email addresses are tappable too. These are found on the device, in both the original and the translation, after the message appears, so they never delay it.
- **AI providers:** Anthropic, OpenAI, Google Gemini, OpenRouter, or any OpenAI-compatible endpoint. Translation and tutoring can each use their own model. API keys are encrypted with an Android Keystore key and never leave the device except in requests to your provider.

Transmit is SMS/MMS only, not RCS. Before switching, turn off chat features (RCS) in your current messaging app so that friends' messages arrive as SMS.

## Build and install

Requirements: JDK 17 and the Android SDK (compile SDK 36). The app runs on Android 12 (API 31) or later.

```sh
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the debug key, so they install directly for personal use. On first launch, onboarding asks for your languages and level, makes Transmit the default SMS app, and connects an AI provider. You can skip the AI step and add a key later in Settings.

Unit tests:

```sh
./gradlew :app:testDebugUnitTest
```

## Project layout

```
app/src/main/java/app/transmit/
  telephony/   SMS/MMS provider access, sending, MMS download, PDU encoding (pdu/)
  ai/          provider clients, prompts, translation manager, tutor, budgets, pricing
  data/        Room database, settings (DataStore), contacts, encrypted key store
  notify/      incoming-message worker (background translation) and notifications
  smart/       on-device detection of verification codes, dates, addresses, phones and emails
  ui/          Jetpack Compose screens: home, chat, new chat, settings, onboarding
```

The platform's SMS/MMS store is the source of truth for messages. Transmit's own Room database (schemas in `app/schemas/`) holds only translations, drafts, per-chat settings and the usage ledger. Dependencies are wired by hand in `AppContainer`.
