# Transmit 1.0 (Google Play release): product spec

Owner: product (decisions below are made; flag problems rather than silently changing them).
Backend contract: `backend/API.md` (written by the backend agent).
Business plan and verified facts: the Claude Doc "Transmit: Google Play launch plan".

## Goals

1. Getting started is very easy: a new user can be translating real chats within a couple of minutes, without knowing what an API key, model or OpenRouter is.
2. Subscription and trial users never see AI plumbing (provider, key, model, effort, budgets in dollars, cost per message).
3. Bring-your-own-key (BYOK) stays fully supported, but every AI control lives under **Settings → Advanced**.

## AI access modes

One source of truth, `AiAccess` (name is up to the implementer):

| Mode | Meaning | Who pays |
|---|---|---|
| `NONE` | No AI set up yet (skipped, or trial unavailable) | – |
| `TRIAL` | 7-day free trial, $0.50 allowance, key from our backend | us |
| `SUBSCRIBED` | `transmit_monthly` (Standard) or `transmit_plus` (Plus), key from our backend | user via Play |
| `ENDED` | Trial ran out (time or allowance) or subscription lapsed | – |
| `BYOK` | The user's own key and provider, today's behaviour | user |

- Managed modes (TRIAL, SUBSCRIBED) always use OpenRouter with `google/gemma-4-31b-it` for translation and tutoring, effort Auto, context 8, translate-on-open 4. None of this is shown.
- The managed key is stored separately from the user's own key, so switching BYOK ↔ subscription never loses either.
- Upgrading from 0.3 with a saved key → `BYOK`. No key → `NONE`.
- Builds not installed from Play (GitHub APK, emulator) can't start a trial or buy; they must still work fully in BYOK.

## Onboarding (3 steps, progress dots, step survives navigation and rotation)

1. **"What are you practicing?"** "I'm practicing" (prominent), "I speak" (prefilled from the device), Level. Continue.
2. **"Connect your messages"**: one big **Connect Google Messages** button (the existing Google sign-in pairing). The QR fallback stays on the pairing screen. When pairing succeeds, return here, auto-advance, and request contacts and notification permissions with one sentence explaining why. A small "Do this later" link.
3. **"Turn on translation"**: the benefit in one line, plus 2–3 short bullets (translations as you chat, a tutor for replies).
   - Primary button: **Start your free week**, with the subtext "7 days free. No card needed."
   - Text button: **Use my own AI key** → the existing provider + key dialog, then → `BYOK`.
   - Text button: **Not now** → `NONE`.
   - If the trial can't start, give a plain-language reason (already used on this device / this device isn't supported (failed device integrity) / not installed from Google Play / trials are full today, try tomorrow) and offer Subscribe or Use my own API key.

Finish → Home.

## Settings layout

1. **Plan card** (top), varies by mode:
   - NONE: "Translation is off" + **Start your free week** + "Use my own AI key" link.
   - TRIAL: "Free trial · N days left" + AI-used bar + **Subscribe**.
   - SUBSCRIBED: "Transmit" / "Transmit Plus" + "Renews <date>" when known + AI-used-this-month bar + **Upgrade to Plus** (Standard only) + **Manage subscription** (Play subscription deep link).
   - ENDED: "Your free trial has ended" / "Your subscription has ended" + **Subscribe**.
   - BYOK: "Using your own API key" + link to Advanced.
   - Always: a small "Restore purchase" link (managed and NONE/ENDED).
2. **Languages**: I speak, Practicing, Level, the zh/ja script option.
3. **Translation**: auto-translate contacts, auto-translate unknown numbers, translate my sent messages.
4. **Messages**: Google Messages pairing, notification display.
5. **Advanced** (collapsed by default):
   - Managed modes: only **Use my own AI key**.
   - BYOK: provider, key, translation model + effort, tutor model + effort, budgets, context messages, translate on open, Usage screen, and **Switch to a Transmit subscription**.
6. Footer: version, plus a **Source code** link to the GitHub repo (AGPL requirement).

## The allowance as users see it

- Managed users never see dollars. Show **percent of this month's AI used** (trial: of the trial's AI).
- Hide the Home spend pill for managed users. Show a Home banner at ≥80% used and when it's used up.
- Chat "budget reached" dialog in managed modes: no "Allow once".
  - TRIAL → "Your free trial's AI is used up" → Subscribe.
  - Standard → "You've used this month's AI" → Upgrade to Plus, or "Resets <date>".
  - Plus → "Resets <date>".
- In managed modes, hide the per-chat budget override (chat info sheet) and the model and cost in the message info sheet. Keep both for BYOK.
- In managed and NONE modes, errors never mention API keys, models or Settings. In NONE, the CTA is "Start your free week".
- A failed translation never hides a message: the original always shows.

## Paywall / plans screen

- Two plans with localized Play prices: **Transmit** (`transmit_monthly`) and **Transmit Plus** (`transmit_plus`), the latter described as "twice the AI of Transmit".
- Describe the allowance in user terms (an estimate of translations or tutor replies per month), not dollars. Use a conservative estimate and flag the numbers to product for review.
- Shown from: the plan card, the trial-ended state, the budget-reached dialog, and onboarding step 3's failure path.
- Upgrade Standard → Plus uses replacement mode CHARGE_FULL_PRICE.

## Trial lifecycle

- Day 6: a notification, "Your free trial ends tomorrow", that opens the paywall.
- Trial AI used up (checked after each AI call): a notification and an in-app prompt to subscribe right away.
- Trial expired (time or AI), or a subscription ended: if the app is open, the plans screen appears right away. If it's in the background, a notification links to the plans screen. Subscribing during the trial is allowed at any time.

## Technical requirements from the launch plan (mostly phase 2)

- **Spend tracking:** count spending in the app from each response's `usage.cost`. OpenRouter reports usage about 3 minutes late, so its own figure lags. The percent bar and the after-each-call allowance check use the local ledger, reconciled with `GET /api/v1/key`.
- **Error mapping for managed keys:**
  - 403 "Key limit exceeded" → the allowance is used up.
  - 401 "API key expired" → the trial has ended, or the key was turned off.
  - Any unfamiliar 4xx or 5xx → a service problem ("Translation is having trouble, try again later"). It must never show a paywall. This covers the workspace's daily cap.
- **Renewals and refunds:** the app asks the Worker once a day, and whenever the key nears its limit, to pick up renewals, upgrades and turned-off keys. A key that the Worker reports inactive (refund or lapse) → ENDED.
- **Purchase tokens:** the app always sends a new purchase token to the Worker and retries until it succeeds, including on the next launch. The Worker acknowledges the purchase, and Play refunds purchases that aren't acknowledged within 3 days.
- **Integrity:** a standard request whose requestHash binds ANDROID_ID, as described in `backend/API.md`.

## Out of scope for 1.0

Accounts or login, Stripe, sync across devices, more AI models in managed mode.
