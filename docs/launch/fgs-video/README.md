# Foreground service declaration video

The video for Play Console's foreground service declaration (remote messaging), made on 2026-11-21. It's 47 s at 1920 × 1080, captioned, and was saved to `~/Downloads/transmit-fgs-remote-messaging.mp4`. It isn't in the repo: it shows a real notification (Melodee's name and a test text) and the developer's home screen, blurred. The raw recordings aren't in the repo either, because they aren't blurred: they're in a temporary folder on the Mac, to be deleted once the video is approved.

## What it shows

1. **Title card:** Transmit is a client for the user's own Google Messages, like Messages for Web. A foreground service keeps the connection open.
2. **Pairing** (emulator, beta18, not paired): step 2 "Connect your messages", the "Pair with Google Messages" screen (Sign in with Google), then the QR code. The emulator can't complete pairing, so the clip stops at the code.
3. **The service starts** (developer's Pixel 9a, Android 17, beta18 from Play, paired): Transmit was force-stopped first, so no service was running. Tapping its icon opens Transmit and starts the service. The shade then shows the ongoing "Connected to Google Messages · Receiving messages" notification, expanded so its "Transmit" header shows.
4. **A text arrives in the background:** with the launcher showing, a short test text sent from Melodee's phone arrives as Transmit's heads-up notification, then sits in the shade above the ongoing notification.
5. **End card:** the service stops when the user unpairs or Google Messages signs Transmit out.

## Privacy

On the Pixel, everything is blurred (1/30 downscale plus Gaussian blur) except, at the moments they're on screen and still: the status bar, Transmit's launcher icon, Transmit's top bar (Search and Settings), Transmit's ongoing notification, and Transmit's message notification. Melodee's profile photo is blurred again inside that notification, while Transmit's small badge on it stays sharp. Her name and the test text ("ok") show. The sharp areas are timed to the still stretches of the recording (`onsets.py`). The positions come from `uiautomator` dumps that recorded only bounds, plus a frame comparison for the heads-up.

## Scripts

- `drive.py` and `drive2.py`: record on the Pixel over adb (`screenrecord`), logging each step's device time and the notification rows' bounds. `drive.py` covers the launch and the shade. `drive2.py` covers the arrival, because the first take's test text wasn't sent in time.
- `onsets.py`: checks when each logged step shows up in a recording. The video runs about 0.45 s behind the device clock.
- `privacy.py`: what stays sharp, and when.
- `compose.py`: builds the video from `pair.mp4`, `phone.mp4` and `phone2.mp4` in a working folder. Run it with `/usr/bin/python3 compose.py <folder> <out.mp4>`. It decodes each clip once at a fixed 30 fps and picks frames by index, because seeking into screenrecord's variable frame rate stretched the still parts.

Once the raw clips are deleted, retaking it means new recordings. The coordinates in `privacy.py` and `drive.py` fit the Pixel's home screen and shade as they were on Nov 21.
