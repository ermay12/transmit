# Launch checklist: from now to production

Ordered steps to get Transmit 1.0 onto Google Play production. As of 2026-11-18:

- Branch `play-launch` is 48 commits ahead of `main`, at versionName `1.0.0-beta3` and versionCode 72.
- The Worker is deployed. Both subscriptions are active in Play Console, and a first bundle has been uploaded.
- Nothing has been tested end to end with a real Play install.

The companion drafts are in this folder: `privacy-policy.md`, `data-safety.md`, `store-listing.md`, and `content-rating-and-declarations.md`.

Items marked **(you)** need your decision or your account. Items marked **(code)** are app or Worker changes; the ones done are ticked.

## 1. Decisions only you can make (you)

- [ ] **Legal name, contact email, postal address and country** for the privacy policy, and the store listing's public email. Also decide whether the email is a dedicated address (recommended).
- [ ] **Developer name** on the listing. It mustn't suggest a tie to Google ([Impersonation policy](https://support.google.com/googleplay/android-developer/answer/9888374)).
- [ ] **Trader status** ([Trader status declaration](https://support.google.com/googleplay/android-developer/answer/17594752)). Selling subscriptions for profit points to "trader".
  - Existing accounts must declare by **February 1, 2027**, at Manage account → Account details → About you. Declaring falsely can get the account removed.
  - Traders on personal accounts get their **phone number and address shown publicly**, everywhere from **March 1, 2027**. Traders also give a **terms of service link for each app**, so Transmit would need a terms page.
  - Separately, once a personal account sells, Google Play already shows its address publicly. Both apply account-wide, so your other apps' listings show them too.
  - Options: an LLC, or a business or virtual-office address (no PO boxes). Your call.
- [ ] **Target audience:** 18+ (recommended) or 13+ (`content-rating-and-declarations.md` §2).
- [x] **Retention period** for the Worker's subscription rows: 90 days after the subscription ends or is refunded (`docs/product/release-1.0.md`, Play compliance). Built and deployed on 2026-11-19 (step 2.9).
- [ ] **Deletion turnaround:** the draft promises 30 days.
- [ ] **App name:** "Transmit: Learn by Texting" is recommended. Check the name against existing "Transmit" apps and marks first (`store-listing.md`).
- [x] **Sender names in AI context:** replaced with neutral labels (`docs/product/release-1.0.md`). `data-safety.md` and the privacy policy assume that app change (step 2.5).

## 2. App and Worker changes before the closed test (code)

The closed track is reviewed against the same policies as production, so fix these first.

1. [ ] **A privacy policy link in the app.** Add it to the Settings footer next to Source code, and to onboarding step 3. The User Data policy requires one "within the app itself".
2. [ ] **AI disclosure + consent.** One line on onboarding step 3 and on the Turn on translation sheet, above the buttons. Copy is in `content-rating-and-declarations.md` §8.
3. [ ] **Auto-renewal line on the Plans screen:** "Renews automatically every month until you cancel." (Subscriptions policy).
4. [ ] **Recommended:** a **demo mode**, so reviewers and curious users can try it without pairing. App access is otherwise a likely rejection (§4 of the declarations file).
5. [ ] **Recommended:** neutral speaker labels in AI context (`ai/TranslationManager.kt:303`, `ui/chat/ChatViewModel.kt:960`). This removes Contacts from Data safety.
6. [ ] **Optional:** a "Report translation" action in the long-press sheet (AI-Generated Content policy).
7. [ ] **Optional:** clear the WebView's Google cookies on unpair (`GmBridge.forget` doesn't clear `CookieManager` today).
8. [x] **Worker logs.** Invocation logs stored each request's `cf-connecting-ip`, headers and location (city, postal code, coordinates) for 3 days. They're off since 2026-11-19 (`observability.logs.invocation_logs: false`). The Worker's own log lines stay, and never contain IP addresses. The rate limits count by a keyed hash of the network and the day. See `backend/README.md`, "Privacy".
9. [x] **Worker retention:** the job deletes subscription rows and their OpenRouter keys 90 days after they end (2026-11-19, migration `0004`).
10. [ ] **Docs:**
    - Fix `README.md`. It still says "Unknown numbers aren't auto-translated", but since 13d9dc4 they are, by default.
    - Its BYOK-only intro ("Translation and tutoring use your own AI API key") also predates the free week and plans.
11. [ ] Set `versionName` to `1.0.0` for the production build.

## 3. Host the privacy policy (you + code)

Play needs "an active, publicly accessible and non-geofenced URL (no PDFs)" ([User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)). Pick one:

- **A route on the existing Worker** (chosen, live since 2026-11-19): `https://transmit.ermay12.workers.dev/privacy`.
  - It works now while the repo is private, uses the domain the policy already names, and its URL won't change if the repo moves.
  - The page is rendered from `privacy-policy.md` whenever the Worker is deployed. To update it, edit the Markdown and redeploy (`backend/README.md`, "Privacy policy page").
- **GitHub Pages**, once the repo is public, serving `main` / `docs`: `https://ermay12.github.io/transmit/launch/privacy-policy.html`.
  - No code, and edits are versioned.
  - Pages on a private repo needs a paid GitHub plan. The URL changes if you rename the repo.
  - The drafting notes in HTML comments don't show when rendered.
- A Cloudflare Pages site, or a Google Sites page, if you want it separate from both.

Either way:

- [ ] Fill the placeholders and the effective date in `privacy-policy.md`, redeploy the Worker, and open the URL in a private window to check it.
- [ ] Put the URL in Play Console (App content → Privacy policy) and in the app (step 2.1).

## 4. Account and service checks (you)

- [ ] **OpenRouter** (account and the Transmit Customers workspace):
  - Input & Output Logging is **off**, and "OpenRouter use of inputs/outputs" is **off**. The privacy policy says so.
  - The workspace guardrail still enforces ZDR on every model group, with safety retention off and only Gemma 4 31B allowed.
  - Decide on the $100/day workspace budget.
  - Recommended: swap the account-wide management key for a workspace-scoped one (`backend/README.md`, Secrets).
- [ ] **Back up the Worker's `DATA_SECRET`.** Without it, every hashed record is useless (`backend/README.md`).
- [ ] **Payments profile** linked and verified. The subscriptions are active, so it probably is; confirm in Play Console → Setup → Payments profile.
- [ ] **Trader declaration** submitted (step 1).

## 5. Play Console: App content and store listing (you)

You can do this alongside step 6. It must all be done before the closed-test release goes to review.

- [ ] Privacy policy URL.
- [ ] App access: the instructions, the review OpenRouter key (`transmit-review`, $3 limit, in the Transmit Customers workspace), and the video link (`content-rating-and-declarations.md` §4).
- [ ] Ads: No. Advertising ID: No.
- [ ] Content rating (IARC questionnaire, §1).
- [ ] Target audience and content (§2).
- [ ] Data safety (`data-safety.md`). Keep it consistent with the privacy policy.
- [ ] Financial features: "My app doesn't provide any financial features."
- [ ] Health (if shown), Government, News: no.
- [ ] Foreground service declaration: remote messaging, with a video (§7). Record it during the internal test.
- [ ] Store listing (`store-listing.md`): name, short and full description, the 512 px icon, the 1024 × 500 feature graphic, 2–8 phone screenshots, category Communication, tags, contact email.
- [ ] Countries and regions. Match the 174 regions the subscriptions are on, or start smaller.

## 6. Internal test (you + me)

1. [ ] Build the bundle with the upload key:
   ```sh
   set -a; . ~/.config/transmit/upload-key.env; set +a; ./gradlew :app:bundleRelease
   ```
   Its versionCode must be higher than any bundle uploaded so far. It's the commit count, so committing is enough.
2. [ ] Upload it to **Internal testing**. Add yourself and your license testers.
3. [ ] Install it from the Play opt-in link on a Pixel, and test everything that isn't proven yet (`backend/README.md` "Not done yet"; `docs/product/phase2b-notes.md` §3):
   - a real free week (Integrity passes; `restored: true` after a reinstall; `trial_already_used` the second time);
   - a license-tester purchase of Transmit; the 5-minute test renewals; the allowance bar;
   - upgrade to Plus (CHARGE_FULL_PRICE); switch to Transmit (DEFERRED) and Keep Plus, noting which replacement mode Play accepts;
   - restore on a second device; "in use on another device";
   - a refund in Play Console → the key turns off within a day;
   - BYOK; and the not-from-Play paths with a sideloaded build.
4. [ ] Read the **pre-launch report** for crashes and accessibility problems. Its robot can't pair, which is expected.
5. [ ] Record the foreground service video, and the reviewer video if you have no demo mode.

## 7. Merge `play-launch` into `main` (you approve)

- [ ] Merge only after the internal test passes. **Every push to `main` runs `.github/workflows/release.yml`.** It runs the unit tests, builds an APK signed with the shared debug key, and publishes a GitHub release `v<versionName>-<versionCode>`.
  - That APK can't start the free week or subscribe (not Play-recognized). It works with your own key only, and can't update a Play install, or the reverse.
  - Decide whether you still want these public APK releases once the repo is public.
- [ ] Tag the commit of each Play release (for example `play-1.0.0-<versionCode>`). The source of every build you distribute is then easy to point to, as the AGPL requires.

## 8. Make the repo public (needs your explicit OK)

Do this before anyone outside your own accounts gets a build: before the closed test.

- The app's Settings → Source code link (`https://github.com/ermay12/transmit`) must work for testers.
- Transmit includes a port of AGPL-licensed libgm, so you must offer the source to everyone you distribute to.

Before flipping:

- [ ] Re-run a secret scan over commits since 2026-11-18. The last clean scan was 2c13272. Look at `backend/`, `docs/` and the workflow.
- [ ] Remember that the full history becomes public, with your personal email as the commit author (you said that's fine), along with every existing GitHub release and its APK.
- [ ] Check the docs you'd be publishing: `docs/product/*` (internal notes) and `docs/launch/*` (this folder).
  - They hold nothing secret, but they do show the Worker URL, the GCP project number and the OpenRouter workspace ID.
  - None of those are credentials. Remove or rewrite anything you'd rather not publish.
- [ ] GitHub → Settings → General → Danger zone → Change visibility → Public.

## 9. Closed test: 14 days, at least 12 testers (you)

[Google's rule](https://support.google.com/googleplay/android-developer/answer/14151465): personal accounts created after November 13, 2023 "must run a closed test for their app with a minimum of 12 testers who have been opted in continuously for at least 14 days".

- If your account predates that, Play Console won't show the requirement and you can skip to step 10.
- Check the app's Dashboard.

- [ ] Create a **Closed testing** track. Add testers through a Google Group or an email list, and send them the opt-in link.
- [ ] Recruit more than 12 (aim for 15–20). Anyone who opts out before 14 consecutive days doesn't count. Testers need an Android phone with Google Messages.
- [ ] Tell testers:
  - to stay opted in for 14 days and actually use it (Google asks about engagement);
  - that the free week works for them because they install from Play;
  - that **testers who aren't license testers pay real prices** if they subscribe.
- [ ] Push fixes to the closed track as needed. Updating the build is normal during the test; testers just need to stay opted in.
- [ ] Keep notes of the feedback and what you fixed, for the production application.

## 10. Apply for production, then release (you)

- [ ] Dashboard → **Apply for production**. Answer the three sections: about your closed test, about your app, and about your production readiness. "Review usually takes seven days or less."
- [ ] After approval, create the production release from the tested bundle. Use a **staged rollout** (for example 10–20%), and add release notes.
- [ ] The production release goes through app review. App access (step 5) and Device and Network Abuse are the likely sticking points.

## 11. After launch

- [ ] Watch:
  - the Worker's Observability tab (`trial_capacity_reached`, `exceededCpu`, `upstream_error`);
  - OpenRouter workspace spending against its budget;
  - Play vitals, reviews and the subscription numbers.
- [ ] Handle data deletion requests as the privacy policy promises: find the row by order ID, delete it, and delete the key.
- [ ] Keep `data-safety.md` and the privacy policy in step with the code. Update them when anything sent off the device changes.
- [ ] Before you widen the rollout to 100%, re-check the trial daily budget (`TRIAL_DAILY_BUDGET_USD`, $30) against real abuse.
