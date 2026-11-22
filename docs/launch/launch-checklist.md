# Launch checklist: from now to production

Ordered steps to get Transmit 1.0 onto Google Play production. Updated 2026-11-21:

- Branch `play-launch` is 143 commits ahead of `main`, at versionName `1.0.0-beta15` and versionCode 166. beta15 (`~/Downloads/transmit-1.0.0-beta15-166.aab`) is uploaded.
- beta15 is on the internal testing track, installed from Play on your Pixel and paired (Nov 21). It has beta12's fix for step 2.11, the double-notifications tip (step 2.12), the pairing fix (step 2.13) and the quick reconnect (step 2.14). The Worker is deployed, and both subscriptions are active.
- Every subscription flow has passed on a real Play install (beta3, beta5 and beta6 device tests, Nov 19–20). Notifications passed on two phones on Nov 21, except catch-up after being offline (step 2.11, fixed in beta12, to retest). The reviewer demo passed on a fresh install from Play on Nov 21 (beta13). What is left to test is in the remaining-work doc and in step 6 below.

The companion drafts are in this folder: `privacy-policy.md`, `data-safety.md`, `store-listing.md`, and `content-rating-and-declarations.md`. A new agent should start with `agent-handoff.md`: the rules for the phones, how to build and test, and the next tasks.

Items marked **(you)** need your decision or your account. Items marked **(code)** are app or Worker changes; the ones done are ticked.

## 1. Decisions only you can make (you)

- [x] **Legal name, contact email, postal address and country** for the privacy policy, and the store listing's public email. Decided 2026-11-19, and approved by the developer for publishing on the privacy page and the listing:
  - The legal name and postal address are in `privacy-policy.md` (Contact) and on the Play developer account.
  - Public email: ermay12@gmail.com (the existing address, not a dedicated one). It also takes data deletion requests.
  - Filled into `privacy-policy.md`, `store-listing.md` and `content-rating-and-declarations.md` (step 3).
- [x] **Developer name** on the listing: Transmit goes on the existing Play developer account, shared with LinguaRead, under its current developer name (2026-11-19). It mustn't suggest a tie to Google ([Impersonation policy](https://support.google.com/googleplay/android-developer/answer/9888374)).
- [x] **Trader status** ([Trader status declaration](https://support.google.com/googleplay/android-developer/answer/17594752)). Decided 2026-11-19: the account declares as a **trader**, with the address above shown publicly. No LLC.
  - Existing accounts must declare by **February 1, 2027**, at Manage account → Account details → About you. Declaring falsely can get the account removed. Submitting it is step 4.
  - Traders on personal accounts get their **phone number and address shown publicly**, everywhere from **March 1, 2027**. Both apply account-wide, so LinguaRead's listing shows them too.
  - Separately, once a personal account sells, Google Play already shows its address publicly.
- [x] **Public phone number:** chosen by the developer, 2026-11-20, and set on the Play developer account. Play publishes it account-wide for traders.
- [x] **Terms of service page** for Transmit, since traders give a terms link for each app: live since 2026-11-20 at `https://transmit.ermay12.workers.dev/terms`, rendered from `terms-of-service.md` like the privacy page (Worker version `43eccc92`). It links to the privacy policy, and the privacy policy links back.
  - **Review it before you submit the trader form (you).** It's a plain-language draft, not legal advice. Look hardest at the free week (§4), subscriptions and refunds (§5), the promise to cancel renewals if the service stops (§12), and the disclaimer and liability cap (§13, §14). A lawyer's read is worth it before production.
- [x] **Target audience:** 18+ (`docs/product/release-1.0.md`, Play compliance; `content-rating-and-declarations.md` §2).
- [x] **Retention period** for the Worker's subscription rows: 90 days after the subscription ends or is refunded (`docs/product/release-1.0.md`, Play compliance). Built and deployed on 2026-11-19 (step 2.9).
- [x] **Deletion turnaround:** 30 days, as the privacy policy promises (developer, 2026-11-22).
- [x] **App name:** the Play title is **"Transmit: Learn by Texting"** (2026-11-19). The in-app name stays "Transmit" (`app_name`). The trademark and Play search was done on 2026-11-22: fine to launch, with Panic's TRANSMIT (file transfer) the one mark to know about (`store-listing.md`).
- [x] **Short description:** "Practice any language while texting your friends" (48 characters, 2026-11-20; `store-listing.md`).
- [x] **App copy:** everything in `docs/product/copy-review.md` was approved by the developer on 2026-11-19.
- [x] **Sender names in AI context:** replaced with neutral labels (`docs/product/release-1.0.md`). `data-safety.md` and the privacy policy assume that app change (step 2.5).

## 2. App and Worker changes before the closed test (code)

The closed track is reviewed against the same policies as production, so fix these first.

1. [x] **A privacy policy link in the app** (beta4). Add it to the Settings footer next to Source code, and to onboarding step 3. The User Data policy requires one "within the app itself".
2. [x] **AI disclosure + consent** (beta4). One line on onboarding step 3 and on the Turn on translation sheet, above the buttons. Copy is in `content-rating-and-declarations.md` §8.
3. [x] **Auto-renewal line on the Plans screen** (beta4): "Renews automatically every month until you cancel." (Subscriptions policy).
4. [x] **Recommended:** a **demo mode** (beta4; long-press the step 2 heading), so reviewers and curious users can try it without pairing. App access is otherwise a likely rejection (§4 of the declarations file).
5. [x] **Recommended:** neutral speaker labels (beta4; Me / Them / Person N) in AI context (`ai/TranslationManager.kt:303`, `ui/chat/ChatViewModel.kt:960`). This removes Contacts from Data safety.
6. [ ] **Optional:** a "Report translation" action in the long-press sheet (AI-Generated Content policy).
7. [x] **Optional:** clear the WebView's Google cookies on unpair (beta11, 42587f2). They're also cleared once a Google pairing finishes, so they're only kept in the encrypted pairing, as the privacy policy says.
8. [x] **Worker logs.** Invocation logs stored each request's `cf-connecting-ip`, headers and location (city, postal code, coordinates) for 3 days. They're off since 2026-11-19 (`observability.logs.invocation_logs: false`). The Worker's own log lines stay, and never contain IP addresses. The rate limits count by a keyed hash of the network and the day. See `backend/README.md`, "Privacy".
9. [x] **Worker retention:** the job deletes subscription rows and their OpenRouter keys 90 days after they end (2026-11-19, migration `0004`).
10. [x] **Docs** (641a460):
    - Fix `README.md`. It still says "Unknown numbers aren't auto-translated", but since 13d9dc4 they are, by default.
    - Its BYOK-only intro ("Translation and tutoring use your own AI API key") also predates the free week and plans.
11. [x] **(code)** A text that arrives while Transmit is offline gets no notification (found Nov 21 on beta11; fixed in beta12, 7397268). With Wi-Fi and data off, a text from the second test phone showed in the chat after reconnecting, but nothing was posted in more than 5 minutes. Cause: `GmBridge` only notified live messages. The phone's replays after a reconnect (`isOld`) and messages found by the sync waited for the next live one, and a reconnect after an outage never synced (`Ready` only fires on a client's first connect). Now `ConnectionRestored` runs the sync. Replays new to the cache and still unread, and synced messages newer than anything cached before the outage, alert once per conversation after the sync has the phone's read state. Ones read on the phone meanwhile, older than a day, or in the open chat stay quiet. 5 new unit tests. Retested on your Pixel (Nov 21, beta14 from Play), with texts from another phone: (a) with Wi-Fi and data off, an RCS text waited on the server and reached the phone 36 s after reconnecting, and Transmit alerted 11 s after that; (b) with only Transmit's network blocked (`cmd connectivity set-package-networking-enabled false`), a text reached Google Messages at once, Transmit stayed quiet, and it alerted once, with sound, 32 s after it was unblocked. So the catch-up works, but slowly: see step 2.14.
12. [x] **(code)** Every text alerts twice, once from Transmit and once from Google Messages (asked for Nov 21; beta13, 17ccc46). After Transmit's first visible message notification, Home shows a sheet once: a looping clip of the switch (`res/raw/gm_notifications_off.webp`, recorded on the emulator) and a button straight to Google Messages' "Incoming messages" notifications (channel `bugle_default_channel`), which leaves its other notifications on. If Settings comes straight back because that channel was renamed, the button opens Google Messages' notification settings instead (both checked on the emulator). Settings → Messages → **Double notifications** opens the sheet any time. It isn't offered while Transmit's own alerts are off, before pairing, in the demo, or without Google Messages. 4 new unit tests. Tested on a phone by the developer (Nov 21), who found it **buggy**: see step 2.15.
13. [x] **(code)** Pairing could alert to the unread texts of the last day (found Nov 21 reading beta12's code; fixed in beta14, 92107f5). Right after pairing nothing is cached, so the phone's replays are history. But the first replay of a conversation fetches it, and if it was unread, its later replays counted as missed and alerted after the catch-up. That would also show the double-notifications tip at once. A replay now counts as missed only when it's newer than what was cached before the connection, as the sync already required. 2 new unit tests. Passed on your Pixel (Nov 21, beta14 from Play): paired again with two unread texts from today, and Transmit posted no message notification (none active, none in Android's archive of removed ones) and showed no tip. Not airtight: onboarding asks for notifications as pairing finishes, and the prompt overlapped the sync, so a blocked alert can't be fully ruled out. The offline retest exercises the same replay code.
14. [x] **(code)** Back online after an outage, Transmit took up to a minute to reconnect (found Nov 21 in the step 2.11 retest; fixed in beta15, fbb941d). The long poll retried on a timer that grows by 5 s a failure up to 60 s, and nothing told it the network was back: 32 s in the retest. Android's default-network callback now ends the wait. 5 new unit tests (`RetryWaitTest`). Passed on your Pixel (Nov 21, beta15 from Play): with only Transmit's network blocked, a text reached Google Messages; then the whole phone was offline for 3 minutes, so the retry wait had grown to 45 s. With the network back, Transmit retried 1 s later instead of 29 s later, and alerted 23 s after reconnecting. The rest was the phone's own Google services: Google Messages was silent for 9 s after the network returned, and Transmit's requests reached it by Google's push service at 16 s and 21 s. Without the fix, the same outage would have alerted after about 50 s.
15. [x] **(code)** The double-notifications tip (step 2.12) seemed buggy to the developer on Nov 21. What they saw: the tip sheet flashed up and vanished by itself, then showed again on Back; and after following the button they couldn't tell whether they had turned off all of Google Messages' notifications or only Incoming messages. Found Nov 21 on your Pixel (beta15, read-only checks: `dumpsys notification`, the events log, dropbox) and on the emulator:
    - **The flash** (fixed in beta16, 216d983): Home showed the sheet the moment it was on screen. Opening a chat from a notification on a fresh start draws Home first for about half a second, and a text that alerts while Home is open is about to be tapped. Either way the sheet opened over Home, went away with it, and came back when Back returned to Home. Now the sheet waits for a visit to Home that began after the first alert and has lasted a second (`SETTLE_MS`; Home's own lifecycle is resumed only while it's the screen on show), and it doesn't open during a selection or over another dialog. 2 new unit tests. On the emulator, with the conditions forced on in a temporary debug build: a fresh start from the chat intent showed Home for 0.45 s with no sheet; Back to Home showed the sheet once, about a second later; its button opened Incoming messages, Back returned to Transmit, and the sheet didn't come back, even after a restart.
    - **What was switched off:** neither. Before the retest below (Nov 21), Google Messages' notifications were allowed and Incoming messages (`bugle_default_channel`) was on at high importance, marked as changed by the user. So the switch-off didn't stick, and texts still alerted twice.
    - **Android 17's Settings crashed** (20:08:50, Nov 21) 6 s after the button opened Incoming messages, as the switch was flipped: `IllegalStateException: Cannot call this method while RecyclerView is computing a layout` from `BlockPreferenceController.onCheckedChanged`, called while the main switch was being rebound. The crash probably undid the change. It's a bug in Settings (build CP3A.261005.005) and comes and goes: the developer's second try (20:13) didn't crash, and neither did a retest at 20:46, which switched Incoming messages off, as the developer allowed. It stayed off (importance 0) with Google Messages' other notifications on. The emulator (Android 16) doesn't crash.
    - On Android 16 and 17 the button opens `ChannelPanelActivity`, which hands straight over to the full Incoming messages page in Transmit's task, so Back returns to Transmit. That page names the category ("Incoming messages", "Default settings") but not the app, and its switch just says "Show notifications"; once off, it says Android is blocking "this category".
    - Passed on your Pixel (Nov 21, beta16 from Play, after clearing Transmit's data and pairing again). A recorder on the phone logged Transmit's windows about 7 times a second, without screenshots. A text arrived while Home was open: no tip. Tapping its notification opened the chat with no flash. Back to Home: the tip, once. Its button opened Incoming messages (already off), Back returned to Transmit with no tip, and Settings → Messages → Double notifications reopened it; Not now closed it. No crashes. A half-second second window that came when a later notification brought Transmit back was Android's splash screen (`APPLICATION_STARTING`), not the tip.
    - Not changed: it shows again after a reinstall even if the switch is already off (apps can't read another app's channels), and the Settings row shows while unpaired and in the demo.
16. [x] Set `versionName` to `1.0.0` for the production build (2026-11-22, versionCode 187, after beta18 was approved). The app code is the same as beta18's (fd65efc); only the version changed. Tagged `play-1.0.0-187`.
17. [x] **(code)** Japanese, Chinese or Korean text with an emoji (for example "住所送るね📍") showed as a big, bubble-less emoji with no romaji, because `Reactions.isEmoji` only counted letters below U+2000 as text. Found while retaking the store screenshots (Nov 21) and fixed in 67e41bf, with a test (351 tests pass). It's in beta18 (versionCode 181, built Nov 21, upload-key signed, 351 tests pass, launches on the emulator), uploaded to internal testing by the developer on Nov 21; your Pixel updated from Play at 23:30.
18. [ ] **(watch)** On your Pixel, beta16's connection notification said "Connecting…" from at least 23:21 to 23:30 on Nov 21, while Transmit was open and the phone was online. Home showed no banner. The beta18 update restarted it, and the new process reached "Receiving messages" in seconds. The phone's log only went back to 23:21, so the cause is unknown. One possible path: if `setActiveSession` fails in `GmClient.postConnect`, the bridge gets `ConnectionTrouble`, which `GmBridge` ignores unless it's already Connected. Nothing retries, so the state stays Connecting until a ping timeout makes the client reconnect. Messages may still arrive meanwhile. If it shows again, read `adb logcat -s GmClient GmBridge` straight away, before restarting anything.

## 3. Host the privacy policy (you + code)

Play needs "an active, publicly accessible and non-geofenced URL (no PDFs)" ([User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)). Pick one:

- **A route on the existing Worker** (chosen, live since 2026-11-19): `https://transmit.ermay12.workers.dev/privacy`.
  - It works now while the repo is private, uses the domain the policy already names, and its URL won't change if the repo moves.
  - The page is rendered from `privacy-policy.md` whenever the Worker is deployed. To update it, edit the Markdown and redeploy (`backend/README.md`, "Policy pages").
- **GitHub Pages**, once the repo is public, serving `main` / `docs`: `https://ermay12.github.io/transmit/launch/privacy-policy.html`.
  - No code, and edits are versioned.
  - Pages on a private repo needs a paid GitHub plan. The URL changes if you rename the repo.
  - The drafting notes in HTML comments don't show when rendered.
- A Cloudflare Pages site, or a Google Sites page, if you want it separate from both.

Either way:

- [x] Fill the placeholders and the effective date in `privacy-policy.md`, redeploy the Worker, and check the URL. Done 2026-11-19: effective date November 19, 2026, deployed as version `e6b36645`. The live page shows the name, address and email, with no placeholders left.
- [ ] Put the URL in Play Console (App content → Privacy policy) and in the app (step 2.1).

## 4. Account and service checks (you)

- [x] **OpenRouter** (account and the Transmit Customers workspace):
  - [x] Input & Output Logging is **off**, and "OpenRouter use of inputs/outputs" is **off**. Confirmed by the developer on 2026-11-19. The privacy policy says so (section 3).
  - The workspace guardrail still enforces ZDR on every model group, with safety retention off and only Gemma 4 31B allowed.
  - The $100/day workspace budget stays (developer, 2026-11-22).
  - The account-wide management key stays: the developer uses OpenRouter only for Transmit, so a workspace-scoped key adds nothing (2026-11-22).
- [x] **Back up the Worker's `DATA_SECRET`** (done 2026-11-19). Without it, every hashed record is useless (`backend/README.md`).
- [ ] **Payments profile** linked and verified. The subscriptions are active, so it probably is; confirm in Play Console → Setup → Payments profile.
- [x] **Trader declaration** submitted (developer, by 2026-11-22). Give `https://transmit.ermay12.workers.dev/terms` as Transmit's terms link.

## 5. Play Console: App content and store listing (you)

You can do this alongside step 6. It must all be done before the closed-test release goes to review.

- [x] Privacy policy URL (2026-11-20).
- [x] App access (2026-11-20): the instructions, the review OpenRouter key (`transmit-review`, $3 limit, in the Transmit Customers workspace) — the key itself was created 2026-11-21; re-paste the text from `~/.config/transmit/app-access-text.txt`, which adds it, and the video link (`content-rating-and-declarations.md` §4).
- [x] Ads: No. Advertising ID: No. (2026-11-20)
- [x] Content rating (IARC questionnaire, §1). Done by the developer (2026-11-21).
- [x] Target audience and content (§2). Done by the developer (2026-11-21).
- [x] Data safety (`data-safety.md`). Keep it consistent with the privacy policy. Done by the developer (2026-11-21).
- [x] Financial features: "My app doesn't provide any financial features." Done by the developer (2026-11-21).
- [x] Health (if shown), Government, News: no. Done by the developer (2026-11-21).
- [x] Foreground service declaration: done by the developer (2026-11-22). Remote messaging, with a video (§7). Record it during the internal test. Required: Google's page says apps targeting Android 14+ "need to declare any foreground service types that you use in a new declaration on the App content page", and remote messaging is one of the listed types. The developer hadn't seen the form on App content as of 2026-11-21. **Video made (Nov 21):** `~/Downloads/transmit-fgs-remote-messaging.mp4`, 47 s, captioned, private content blurred (`fgs-video/README.md`). Still to do: upload it to YouTube as Unlisted (or to Drive, anyone with the link), paste the link into the form with the §7 description, impact and use case, and submit.
- [x] Store listing (`store-listing.md`): name, short and full description, the 512 px icon, the 1024 × 500 feature graphic, 2–8 phone screenshots, category Communication, tags, contact email. Updated by the developer on 2026-11-21 with the graphics remade that day (51e1580, `store-assets/README.md`).
- [ ] Countries and regions. Match the 174 regions the subscriptions are on, or start smaller.

## 6. Internal test (you + me)

1. [x] Build the bundle with the upload key (beta15 = versionCode 166):
   ```sh
   set -a; . ~/.config/transmit/upload-key.env; set +a; ./gradlew :app:bundleRelease
   ```
   Its versionCode must be higher than any bundle uploaded so far. It's the commit count, so committing is enough.
2. [x] Upload it to **Internal testing**. Add yourself and your license testers.
3. [ ] Install it from the Play opt-in link on a Pixel, and test everything that isn't proven yet. Passed on one Pixel (Nov 19–21): a real free week with Integrity, purchases, the test renewals, the allowance bar and the 80%/100% warnings, upgrade (CHARGE_FULL_PRICE), switch to Transmit (DEFERRED; Keep Plus was removed since Play can't revert it), restore without rotation, cancel and lapse (the ended banner while Home is open, with no false "in use on another device"), a refund by revoke, and own-key mode. Passed on the emulator (Nov 21): the not-from-Play paths and the reviewer demo. Passed on two Pixels (Nov 21, beta11): 3 texts stack into one notification; reading the chat in Google Messages clears it (about 2 s), and so does opening it in Transmit (about 3 s); inline reply from the notification; no crashes. Failed: catch-up after being offline (step 2.11, fixed in beta12). Passed on your Pixel after clearing Transmit's data (Nov 21, beta13 from Play): the reviewer demo by the App access text. Long-pressing "Connect your messages" opens it; "Start your free week" answers "This device has already used its free week" (`trial_already_used`); the `transmit-review` key saves under Settings → Advanced → Use my own AI key, and all four chats translate to Spanish; Explain shows plain → arrows; a correct reply gets a green check with your own words and its meaning in the English box; sending in the demo adds the bubble and the sample reply; removing the key goes back to "Translation is off"; Exit demo returns to onboarding step 2; no crashes. Tested by the developer (Nov 21, beta13): the double-notifications tip (step 2.12), found buggy (step 2.15). Passed on your Pixel (Nov 21, beta16 from Play, freshly paired): the tip after its fixes (step 2.15). Still open:
   - a group notification showing member names, which needs a group chat;
   - a second device on the same Play account ("Use it here?"). The second test phone uses a different Play account, so it can't test this;
   - the free week on a device that hasn't used one: **the start passed** on the second test phone (Nov 21, 11:07 PM ET, beta16 from Play). Its own key was removed with the developer's OK (Settings → Advanced → API key → Remove), and Home then showed "Start your free week". The free week started at once: Integrity passed, Settings showed "Free week · 7 days left" and 0% used, and Explain worked on the new key. Nothing was sent. Still to check: the "Your free week ends tomorrow" reminder (scheduled a day before the end, so about Nov 27, 11:07 PM ET) and the ended notification if Transmit isn't open at the end (about Nov 28, 11:07 PM ET). Its owner will need to paste the key again afterwards to go back to own-key mode. The original list (`backend/README.md` "Not done yet"; `docs/product/phase2b-notes.md` §3):
   - a real free week (Integrity passes; `restored: true` after a reinstall; `trial_already_used` the second time);
   - a license-tester purchase of Transmit; the 5-minute test renewals; the allowance bar;
   - upgrade to Plus (CHARGE_FULL_PRICE); switch to Transmit (DEFERRED) and Keep Plus, noting which replacement mode Play accepts;
   - restore on a second device; "in use on another device";
   - a refund in Play Console → the key turns off within a day;
   - BYOK; and the not-from-Play paths with a sideloaded build.
4. [ ] Read the **pre-launch report** for crashes and accessibility problems. Its robot can't pair, which is expected.
5. [ ] Record the foreground service video, and the reviewer video if you have no demo mode.

## 7. Merge `play-launch` into `main` (you approve)

- [x] Merged on 2026-11-22, with the developer's OK, after the repo went public. **Every push to `main` runs `.github/workflows/release.yml`.** It runs the unit tests and builds an APK signed with the shared debug key. Since 2026-11-22 it publishes a GitHub release `v<versionName>` only when `versionName` is new, so bump it to publish; the 0.3.0 releases are marked pre-release.
  - That APK can't start the free week or subscribe (not Play-recognized). It works with your own key only, and can't update a Play install, or the reverse.
  - Decided 2026-11-22: keep them, one per version, with notes that point most people to Google Play (`.github/release-footer.md`).
- [x] Tag the commit of each Play release (for example `play-1.0.0-<versionCode>`). The source of every build you distribute is then easy to point to, as the AGPL requires. Started 2026-11-22: `play-1.0.0-beta18-181` is fd65efc, the beta18 bundle submitted for production. Tag each later Play upload the same way.

## 8. Make the repo public (done 2026-11-22)

Do this before anyone outside your own accounts gets a build: before the closed test.

- The app's Settings → Source code link (`https://github.com/ermay12/transmit`) must work for testers.
- Transmit includes a port of AGPL-licensed libgm, so you must offer the source to everyone you distribute to.

Before flipping:

- [x] Re-run a secret scan over commits since 2026-11-18 (2026-11-22, every commit on every branch: none found). The last clean scan was 2c13272. Look at `backend/`, `docs/` and the workflow.
- [x] Remember that the full history becomes public, with your personal email as the commit author (you said that's fine), along with every existing GitHub release and its APK.
- [x] Check the docs you'd be publishing (2026-11-22: a tester's name, device serials, home paths, and the phone number and address outside the policy pages were redacted in a new commit; older commits still have them): `docs/product/*` (internal notes) and `docs/launch/*` (this folder).
  - They hold nothing secret, but they do show the Worker URL, the GCP project number and the OpenRouter workspace ID.
  - None of those are credentials. Remove or rewrite anything you'd rather not publish.
- [x] GitHub → Settings → General → Danger zone → Change visibility → Public (the developer, 2026-11-22).

## 9. Closed test: 14 days, at least 12 testers (you)

[Google's rule](https://support.google.com/googleplay/android-developer/answer/14151465): personal accounts created after November 13, 2023 "must run a closed test for their app with a minimum of 12 testers who have been opted in continuously for at least 14 days".

- If your account predates that, Play Console won't show the requirement and you can skip to step 10.
- Check the app's Dashboard.
- **Not required for this account** (the developer, Nov 21): it predates the rule, so a closed test is optional. Its use is the first full review before production. License testers get the same 5-minute test renewals on any track, so moving off internal testing doesn't affect subscription tests.

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
- [x] 1.0.0-beta18 (181) was approved for production on 2026-11-22. 1.0.0 (187, the same code relabelled) was uploaded to production the same day and is in review.
- [ ] After approval, create the production release from the tested bundle. Use a **staged rollout** (for example 10–20%), and add release notes.
- [ ] The production release goes through app review. App access (step 5) and Device and Network Abuse are the likely sticking points.

## 11. After launch

- [x] **Email alerts** (2026-11-22, Worker version `1860c6b7`): an hourly job emails ermay12@gmail.com through Resend about low OpenRouter credits, high daily spend, the model losing its zero-data-retention hosts, the free-week cap, Play Integrity use, unacknowledged purchases, a stopped job and backend errors. A GitHub Actions watchdog (`.github/workflows/watchdog.yml`) checks from outside that the Worker and both jobs run. Details: `backend/README.md`, "Alerts".
- [ ] Turn on Play Console's own email notifications (Settings → Notifications) for Android vitals and policy, and check that GitHub emails failed Actions runs (GitHub → Settings → Notifications → Actions).
- [ ] Watch:
  - `exceededCpu` in the Worker's Metrics (the alerts don't cover CPU limits);
  - Play vitals, reviews and the subscription numbers.
- [ ] Handle data deletion requests as the privacy policy promises: find the row by order ID, delete it, and delete the key.
- [ ] Keep `data-safety.md` and the privacy policy in step with the code. Update them when anything sent off the device changes.
- [ ] Before you widen the rollout to 100%, re-check the trial daily budget (`TRIAL_DAILY_BUDGET_USD`, $30) against real abuse.
