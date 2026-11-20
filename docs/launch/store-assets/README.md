# Google Play store graphics

Made on 2026-11-20 for the Main store listing (`../store-listing.md`, Graphics and Screenshot captions). Every file's size, format and dimensions were checked by script.

## Files

| File | Size | Format | What it is |
| --- | --- | --- | --- |
| `icon-512.png` | 512 × 512 | PNG, 32-bit (RGBA, opaque), 13 KB | App icon: the adaptive launcher icon's middle 72 dp (what launchers show) as a full square. Play adds the rounded mask and shadow. |
| `feature-graphic-1024x500.png` | 1024 × 500 | PNG, RGB (no alpha), 183 KB | The icon, "Transmit" and "Learn a language by texting" in the middle, with Spanish and English chat bubbles at the edges. |
| `screenshots/captioned/*.png` | 1080 × 1920 | PNG, RGB, 330–475 KB each | The screenshots on the brand colour, in a phone frame, with the caption above. **Upload these.** |
| `screenshots/raw/*.png` | 1080 × 1920 | PNG, RGB, 70–190 KB each | The same screens as captured, without frame or caption. |

Brand colours: `#3F51E8` (`ic_bg`, the icon's background), with a lighter `#5163F2` and darker `#3241CF` for the gradient. Text is Roboto.

## Screenshots, in upload order

| # | File (in `captioned/` and `raw/`) | Screen | Caption |
| --- | --- | --- | --- |
| 1 | `01-translated-chat.png` | A chat with Emma, her texts shown in Spanish | Messages from friends, in the language you're practicing |
| 2 | `02-original.png` | The same chat, one bubble tapped to show the English original | Tap any message to see the original |
| 3 | `03-reply.png` | The composer: Spanish typed, the English that Emma gets prepared, ready to send | Reply in your practice language. They get it in yours. |
| 4 | `04-check.png` | Check my translation: the correction as a diff, a more natural version and notes | Check my translation: corrections and a natural version |
| 5 | `05-explain.png` | The Explain sheet for "Te aviso, mi prima habla muchísimo 😂" | Explain: grammar, idioms and slang, phrase by phrase |
| 6 | `06-romaji-dark.png` | A Japanese chat with Yuki, romaji under the characters, dark theme | Pinyin and romaji, the way you like to read |
| 7 | `07-connect.png` | Onboarding step 2, "Connect your messages" | Works with Google Messages on your phone |
| (6) | `06-romaji-light.png` | Shot 6 in the light theme, if you'd rather not have a dark one | Pinyin and romaji, the way you like to read |

- **Caption 8 ("Start with a free week. No card needed.") was dropped:** it's promotional.
- Upload seven: 1 to 7, with either version of 6.

## How they were made

- **Device:** the API 36 emulator, with a release build signed with the upload key, so no debug UI shows. The display was set to 1080 × 1920 (`wm size`), because its native 1080 × 2400 is longer than Play's 2:1 limit. The status bar uses SystemUI demo mode: 9:41, Wi-Fi and battery full, no notifications. The mobile signal was hidden, because demo mode on API 36 kept showing a "3G" label. The light theme is used throughout, apart from the dark shot 6.
- **Content:** the reviewer demo's sample chats, with temporary local code that was reverted and never committed:
  - fuller sample chats with Emma (Spanish) and a new Japanese chat with Yuki;
  - the DEMO label, the demo bar and the demo banner hidden, and the call button shown, as in a real chat;
  - AI access shown as a Transmit Plus subscriber, so no free-week or plan banners appear;
  - canned AI answers (translations, romaji, Check my translation, Explain) written for the shots, so no AI provider, backend or Play call was made;
  - every message in view translated on open, rather than only the newest four.
- **Privacy:** made-up first names and initial avatars only. No phone numbers, photos or real conversations appear.
- **Graphics:** headless Chrome rendered the icon from the launcher icon's vector paths, the feature graphic, and the captioned frames. Pillow checked and saved the PNGs. The scripts weren't committed; ask if you want them.

## For product to review

1. **The icon and the impersonation rule.** `store-listing.md` says the icon mustn't look like the Google Messages icon ("no blue speech bubble"). It's two white speech bubbles on a blue square, and its 文 / A glyphs are close to the Material "translate" symbol that Google Translate's icon also uses. It is the app's real launcher icon, but decide whether it's distinct enough before launch.
2. **The AI text in the shots was written by hand.** The UI is the real app, but the Spanish and Japanese translations, the romaji, the correction in shot 4 and the explanation in shot 5 are canned, not the managed model's output. Play wants screenshots to show the app accurately. Before you upload, run the same messages through a real free week and check that the output is comparable.
3. **Shot 7 is sparse.** The lower half of the onboarding screen is empty. The button has a generic Material message glyph, not the Messages logo, and the caption names Google Messages only as what Transmit works with. You could swap it for an inbox shot, but that would need a new caption, which isn't in the approved list.
4. **Dark or light shot 6.** The set uses the dark one, for variety.
5. **Small UI nit seen in shot 4:** in the Check my translation diff, a deleted word runs straight into the inserted one ("~~para~~**de**" reads as "parade"). A space or gap between the deletion and the insertion would read better.
6. The feature graphic's line, "Learn a language by texting", is the one `store-listing.md` suggested. The short description is now "Practice any language while texting your friends", so you could use a line closer to that.
