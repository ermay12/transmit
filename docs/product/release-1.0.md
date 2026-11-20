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

## Small decisions (after phase 1)

- The 80% Home banner can be dismissed. A dismissal lasts until the allowance period resets or the allowance reaches 100%, not just until the app restarts.
- The ENDED Home banner stays, and can't be dismissed.
- When the app isn't installed from Google Play, hide Subscribe and offer only "Use my own AI key".
- In NONE mode, chats show a slim "Translation is off · Start your free week" strip. Dismissing it hides it in every chat; the Home and Settings CTAs remain.
- A trial whose AI is used up becomes ENDED.
- (After phase 2b) The Home NONE banner can be dismissed, and stays dismissed across restarts; the Settings plan card is always there.
- (After phase 2b) In NONE and ENDED, translation must not *look* on:
  - The composer is a plain single box.
  - Translate icons and actions open a small "Turn on translation" sheet: Start your free week, or Use my own AI key when not installed from Play, plus Subscribe when ENDED.
  - The Settings → Translation switches stay editable, with the caption "These apply once translation is on."
- (After phase 2b) A trial that can't start is information, not an error: use a neutral or tonal style, never red.
- (After phase 2b) Use one name for the end of a trial everywhere: "Your free week has ended". Call the trial "free week" in all user-facing copy.
- (User, after phase 2b) **Downgrade is IN scope for 1.0.** Plus subscribers can switch to Transmit in the app, with replacement mode DEFERRED: they keep Plus and its allowance until the next renewal, then move to Transmit. The Plans screen shows it to Plus subscribers ("Switch to Transmit: takes effect on <date>"), and it also appears next to Manage subscription as the alternative to cancelling. The goal is to keep the customer rather than lose them.
- (User, after phase 2b) **Conversion first.** When the free week ends on a Play install, the only CTA is **Subscribe** (Home, plan card, chat sheet, dialogs, notifications). "Use my own AI key" appears only in Settings → Advanced. For copies not installed from Play, the primary CTA is **Get Transmit on Google Play** (the Play listing), and "Use my own AI key" is a secondary link.
- (User, after phase 2b) **Paying customers don't get a plan card at the top of Settings.** For SUBSCRIBED, Settings starts with Languages. A compact **Subscription** section near the bottom, above Advanced, has one row: the plan, "Renews <date>" and the percent of AI used. Tapping it opens the Plans screen, which holds upgrade, downgrade, restore and Manage in Play. This keeps account management out of the way, so subscribers aren't nudged to change it. TRIAL, NONE and ENDED keep the plan card at the top.

- (After the beta5 device test) Every Home access banner (NONE, free week, 80%, 100%, ENDED) sits in a fixed slot above the chat list, outside it, so one that appears while Home is open is never scrolled out of sight. The dismissal rules above are unchanged.
- (After the beta5 device test) When an AI call takes a subscription's managed use to 100% or more (the local ledger after the call, or a 403 "Key limit exceeded"), the app asks the Worker once, at once, past the refresh throttle, in case it renewed. A new period or a higher limit clears the used-up state silently. Otherwise the chat shows the budget-reached dialog, once per allowance period (persisted, like the banner dismissal), not for every blocked call. The slim in-chat strip carries the next step: Transmit → "Upgrade to Plus" (opens Plans); Plus → "Resets <date>". A used-up free week is ENDED, as before.
- (After the beta5 device test) Google Play refusing a new subscription says "Your purchase didn't go through. You haven't been charged." Upgrades and switches keep "Google Play couldn't make that change. Please try again later."
- (After the beta5 device test) A purchase, upgrade or switch makes one `POST /v1/subscription` call: the new token. Nothing else asks the Worker while Play's sheet is open or that token is on its way, and its answer counts as the plan refresh. The new token is still retried until the Worker takes it.
- (After the beta6 device test) Only the Worker says a subscription is in use on another device: its `key_mismatch` answer to a `rotate: false` call. Play's purchase list is only a hint about which token to send. A 401 on the managed key asks the Worker. The "in use elsewhere" state is checked again on every refresh and never stops one. A token the Worker reports expired or refunded → ENDED. A `replaced` token that Play names no newer token for → ENDED once the key is refused too. While the key still works, it's a plan change that Play hasn't listed yet, so nothing changes.
- (After the beta6 device test) While the app is in the foreground, the Worker is asked about 2 minutes after the paid period's known end (a renewal or an expiry), and once more 5 minutes later if the plan still looks the same. Coming back after the end has passed asks at once. Every return to the app (launcher, Recents, a notification) runs the same check.
- (After the beta6 device test) Each figure for the allowance carries when its limit and its spending were seen, and an older figure never replaces a newer one. The Worker's answer sees the limit as it is now. OpenRouter's figures, the limit included, count as a few minutes old, since `/key` was seen answering with a limit from before a change. A newer figure with room clears the used-up state, on relaunch or resume too.
- (After the beta6 device test) When Google Play says a plan is already owned, the app runs Restore purchase. If that finds an active subscription, the plan shows with no message. Otherwise: "Google Play is still updating your subscription. Please try again in a few minutes." (or the payment problem, if Play has it on hold).

## Play compliance (decided after the launch-docs review, see docs/launch/)

- **Privacy policy link** in the Settings footer, on onboarding step 3, on the Plans screen and in the own-key dialog. It's hosted on the Worker at `https://transmit.ermay12.workers.dev/privacy`.
- **AI disclosure:**
  - Onboarding step 3 (managed) says, under the CTA: "Messages you translate or ask the tutor about are sent to our AI provider to process them. They aren't stored or used for training. Privacy policy".
  - The own-key dialog says the same about "the provider you choose".
- **Auto-renew disclosure** on the Plans screen, near Subscribe: "Renews automatically each month at <price> until you cancel in Google Play."
- **Contact names never go to the AI.** Prompts use neutral labels ("Me" / "Them", or "Person 1/2/…" in groups), so Data safety needs no Contacts sharing.
- **Reviewer demo mode:** a hidden entry, long-pressing the "Connect your messages" heading on onboarding step 2, opens sample conversations so Play reviewers can try translation and the tutor without pairing. It's documented in the App access notes. Demo chats are clearly labelled and can be exited.
- **Retention:** subscription rows are deleted 90 days after the subscription ends or is refunded. Trial device hashes are kept, because they enforce one free week per device.
- **Target audience:** 18+.

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
