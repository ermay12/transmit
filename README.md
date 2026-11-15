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
- **Per-chat settings:** language, auto-translate, and monthly budget.
- **Budgets and usage tracking:** default budgets are $5 a month overall, $1 per contact and $0.10 per unknown number. Unknown numbers aren't auto-translated, so verification codes and spam never reach the AI.
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
  ui/          Jetpack Compose screens: home, chat, new chat, settings, onboarding
```

The platform's SMS/MMS store is the source of truth for messages. Transmit's own Room database (schemas in `app/schemas/`) holds only translations, drafts, per-chat settings and the usage ledger. Dependencies are wired by hand in `AppContainer`.
