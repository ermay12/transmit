# Handoff: getting Transmit 1.0 released

For an agent picking up the launch work. Updated 2026-11-21.

## Read first

- **What's left:** the release doc, a Claude Doc titled "Transmit 1.0: remaining work to test and build" (https://claude.ai/artifact/9HjbXywXz6UCJMpdzU2BNr). It lists only remaining work and decisions. When something is done, delete it from the doc rather than ticking it.
- **History and results:** `docs/launch/launch-checklist.md`. Record each test result and fix there, with the build and the date.
- **The product:** `docs/product/release-1.0.md` (the spec) and `docs/product/copy-review.md` (approved wording).
- **The other launch drafts:** `privacy-policy.md`, `data-safety.md`, `store-listing.md` and `content-rating-and-declarations.md`, all in `docs/launch/`.

## Where things stand

- Branch `play-launch`, not pushed. **Never push without asking**: every push to `main` publishes a GitHub release (`.github/workflows/release.yml`).
- beta16 (versionCode 173) is on Play's internal testing track. The developer's Pixel has it installed from Play, paired with Google Messages (data cleared and paired again on Nov 21). The repo is already at beta17 (174), a version bump built when Play seemed stuck, then not needed. Upload versionCode 174 or higher next.
- On that Pixel, Google Messages' own "Incoming messages" notifications are switched off, so Transmit is its only alert for texts. Checked Nov 21 after switching it off at 20:46 with the developer's OK: the earlier switch-off hadn't stuck. Check with `dumpsys notification`: `mId='bugle_default_channel'` should have `mImportance=0`.
- versionCode is the commit count, so every commit raises it.

## Building and installing

- Build the Play bundle with the upload key:
  ```sh
  set -a; . ~/.config/transmit/upload-key.env; set +a; ./gradlew :app:bundleRelease :app:assembleRelease
  ```
  Copy `app/build/outputs/bundle/release/app-release.aab` to `~/Downloads/transmit-<versionName>-<versionCode>.aab`. The developer uploads it to internal testing, and the phone updates from Play.
- **Never sideload onto the phones.** Play re-signs with its own app-signing key, so an upload-key build can't install over the Play copy. Removing the Play copy first loses the pairing, and the phone then behaves as a copy not installed from Play. Installing anything on a phone needs the developer's go-ahead each time.
- The emulator (`emulator-5554`) takes the upload-key APK (`app-release.apk`). It can't pair with Google Messages, so use it for the demo, the screens and launch checks only.

## Rules on the developer's phones

These are the developer's standing rules. Don't relax them without asking.

- **Never send, type, reply or react in real conversations.** The one exception is short test texts in the 1:1 conversation between the two test phones, when the developer allows it.
- **Reports:** no message text, contact names or phone numbers.
- **Apps:** drive only `com.ermay12.transmit` on the phones, plus Play's purchase and subscription-management pages.
- **Purchases:** only with Play's test cards.
- **Pairing:** never pair or unpair, and never change the default SMS app. Clearing Transmit's data for a fresh-install test was allowed once; the developer paired again afterwards.
- **Secrets:** they live in `~/.config/transmit/`. Never print them. Type a key into the phone by sourcing the file inside a subshell.
- **OpenRouter:** only touch keys named `transmit-*`.
- **The repo:** ask before making it public.
- **Recordings:** screenshots and videos must not show real chats. Use the reviewer demo (long-press "Connect your messages" on onboarding step 2), or have the developer check them before they leave the machine.

## Testing techniques that worked

- **adb:** `~/Library/Android/sdk/platform-tools/adb -s <serial>`. In zsh, wrap it in a function, not a variable.
- **Finding what to tap:** `uiautomator dump`, then the bounds of each node; `screencap` to check.
- **Keyboard in dialogs:** the keyboard covers dialog buttons. Press Back (keyevent 4) to hide it. Escape (keyevent 111) closes the dialog.
- **Notifications without reading their text:**
  - `dumpsys notification --noredact`, filtered to `pkg=com.ermay12.transmit` with `channel=messages`. Compare `mUpdateTimeMs` to spot a new post.
  - `mInterruptionTimeMs` equal to `mUpdateTimeMs` means it alerted.
  - The `mArchive` section lists recently removed notifications.
- **Logs:**
  - Release builds keep Transmit's `W` and `I` lines (`GmClient`, `GmBridge`).
  - Google Messages floods the ring buffer, so capture `logcat --pid=<Transmit's pid>` to a file in the background before a test.
  - To time Google Messages' side, count its log lines per second (`logcat --pid=<its pid>`). Don't print them.
- **Offline catch-up:**
  - `cmd connectivity set-chain3-enabled true`, then `cmd connectivity set-package-networking-enabled false com.ermay12.transmit`, blocks only Transmit, so Google Messages still receives texts at once.
  - `svc wifi disable` and `svc data disable` take the whole phone offline. Texts still arrive over SMS.
  - Put everything back afterwards, and check: `cmd wifi status`, the chain disabled, and Transmit's networking on "allow".
  - An RCS text sent to a phone that's offline waits on Google's server until the phone is back, so it tests the live path, not catch-up.
- **Timings seen on Nov 21 (beta15):**
  - Transmit retries about 1 s after the network returns.
  - After 3 minutes fully offline, the alert came 23 s after reconnecting. Most of that was Google Messages and Google's push service coming back.
- **Emulator:**
  - To install, lift the storage threshold first: `settings put global sys_storage_threshold_max_bytes 209715200`, install, then delete the setting.
  - Never kill a mock server by its port, which also kills the emulator.

## The next two tasks

1. **Make the store listing graphics** (`store-listing.md`):
   - 2 to 8 phone screenshots, taken in the reviewer demo so no real chats show;
   - the 512 px icon;
   - the 1024 × 500 feature graphic.
   They block the first closed-testing release.
2. **Record the foreground service declaration video** (`content-rating-and-declarations.md` §7: remote messaging). Play's review needs it. It has to show the connection running and a message arriving, so plan it with the developer to keep real chats out of the frame.

After those: the Play Console forms (the developer's), the remaining tests in the release doc, and beta15's pre-launch report in Play Console.
