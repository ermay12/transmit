# Phase 2b notes (polish and first-run review)

Written 2026-11-18 on branch `play-launch`. Commits: 590402b, ed41e4d, 3eda6f7, 65065d7, 2708ddd, then this file. `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes. Screenshots are in `/tmp/transmit-screens/phase2b/`, with earlier and in-between shots in `review/`.

## What changed

- **Chat strip in NONE.** A slim "Translation is off · Start your free week" strip sits at the top of every chat.
  - A copy not installed from Play shows "Use my own AI key" instead, which opens the key dialog.
  - Starting the trial from the strip translates the chat once AI is on. If the trial fails, the usual "Translation is off" dialog explains why.
  - Dismissing it hides it in every chat, across restarts (`StoredAccess.offStripDismissed`, tested in `AccessManagerTest`).
  - The Home and Settings CTAs stay. The Home NONE banner now has its own "Start your free week" button (or "Use my own AI key" when not from Play) instead of sending people to Plans.
- **Plans screen.**
  - A plain Material 3 screen. The headline follows the mode: "Translate every chat", "Keep translating after your free week", "Keep translating", or "Your plan".
  - Two selectable plan cards show the Play price with "/month" and the approved allowance lines. Below them are one Subscribe button and "Cancel anytime in Google Play".
  - Subscribers' plan is marked "Current plan". Standard subscribers get Plus preselected and "Upgrade to Plus"; Plus subscribers see only their plan.
  - Restore purchase and Manage subscription are small links at the bottom.
- **No-rotate refresh.** API.md now documents `"rotate": false`.
  - Background refreshes send it: the daily refresh, the one near the limit, the one when the AI is used up, and the one after a refused key with Play unsure.
  - A `key_mismatch` (new `AccessErrorCode.KEY_IN_USE_ELSEWHERE`) changes nothing. It marks the subscription in use elsewhere, shows a notification if the app is in the background, and the app then asks "Use it here?".
  - Restore, purchases, upgrades and "Use it here" still rotate.
  - The OpenRouter key check before a refresh stays. It is cheap, and it covers a Worker that predates `rotate`.
- **First-run fixes.**
  - Onboarding step 1 now says "Texts from friends arrive in the language you're practicing. Your replies reach them in the language you speak." It used to say "…Your friends get yours."
  - Pairing drops "so RCS chats keep working" and says "Nothing changes for the people you text."
  - Unpaired Home says "Your chats show up here once you pair" instead of "No conversations yet".
  - Advanced describes "Use my own AI key" as "Pay an AI provider such as OpenAI or Google yourself", without "Bring a key from OpenRouter…".
  - The Settings NONE card centres "Restore purchase" under its centred buttons.
  - A Home banner with both an action and a dismiss button stacks the button under the text, so long titles no longer squeeze into a narrow column.
  - Not installed from Play: an ended trial's Home banner offers "Use my own AI key". It used to be a dead end with no button.
- **Wording checks.**
  - No API keys, models or dollars show in managed or NONE mode. Error text goes through `AiMessages.describe`, the cost and model in the info sheets are BYOK-only, and the budget wording only appears for BYOK.
  - "Start your free week" and "Use my own AI key" are the same on Home, in chats, in Settings and on the onboarding screens.
- **Debug.** The fake backend has an "Installed from Play" switch (`--ez fromPlay false`), so the not-from-Play paths can be checked on the emulator.

## Bigger issues for the product owner (ranked)

1. **The Home NONE banner never goes away.** Following the spec, Home keeps "Translation is off · Start your free week" until AI is set up. Someone who only wants Transmit as a messaging app sees it forever, on top of the Pair banner on first run. Should it be dismissible, for example coming back after 30 days?
2. **Settings and chats look "on" while translation is off.** In NONE, the auto-translate switches are on, bubbles show the translate icon, and the composer shows both language boxes with a translate button. Tapping any of them leads to the "Translation is off" dialog. A dimmed or single-box state for NONE might be clearer.
3. **It hasn't been tested with real Play.** The Plans layout was checked only with the fake backend's prices. The long localized prices, the "Prices are shown in Google Play" fallback, a real Play purchase sheet and real trials all still need a Play-installed build on a Pixel. The list from phase 2a still applies.
4. **`rotate: false` needs the deployed Worker to support it.** A Worker that ignores the field would still rotate on a stale `keyHash`. The OpenRouter pre-check covers most of these cases, but not a key that is still valid when the check runs.
5. **A Plus subscriber can't move down to Transmit in the app.** Plans shows only the Plus card. Downgrading means going to Manage subscription in Play, and the app only picks the change up when it next refreshes.
6. **A failed trial reads as an error.** "Free trials are full today. Try again tomorrow." uses the red error style, though nothing is wrong with the user's phone. A neutral style may fit better for full trials and rate limits.
7. **There are two wordings for the end of a trial.** "Your free trial's AI is used up" and "Your free trial has ended" lead to the same Subscribe. That is fine if intended, but worth a look.
8. **The new copy needs your sign-off.** The onboarding step 1 subtitle, the pairing intro and the Advanced own-key subtitle were rewritten for clarity (see above).

## Notes

- The emulator's `com.ermay12.transmit` was uninstalled, reinstalled as a debug build and had its data cleared during the review. It is left on the fake backend, in NONE, marked installed from Play, with one sample chat. The physical phones weren't touched.
- The screenshots are `onboarding-1-practice`, `onboarding-2-connect`, `onboarding-3-translation`, `pairing`, `home-none`, `home-trial-85`, `home-ended-not-from-play`, `plans-nonsubscriber`, `plans-standard-subscriber`, `plans-plus-subscriber`, `settings-none`, `settings-trial`, `settings-subscribed`, `settings-subscribed-advanced`, `chat-none-strip`, and `chat-none-strip-not-from-play`.
