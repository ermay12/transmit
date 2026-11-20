# Google Play store graphics

Made on 2026-11-20 for the Main store listing (`../store-listing.md`, Graphics and Screenshot captions), and retaken the same day with real AI output. Every file's size, format and dimensions were checked by script.

## Files

| File | Size | Format | What it is |
| --- | --- | --- | --- |
| `icon-512.png` | 512 × 512 | PNG, 32-bit (RGBA, opaque), 13 KB | App icon: the adaptive launcher icon's middle 72 dp (what launchers show) as a full square. Play adds the rounded mask and shadow. Unchanged in the retake. |
| `feature-graphic-1024x500.png` | 1024 × 500 | PNG, RGB (no alpha), 188 KB | The icon, "Transmit" and "Practice any language while texting your friends" in the middle, with Spanish and English chat bubbles at the edges. The right-hand bubbles sit a little higher than before, to clear the longer line. |
| `screenshots/captioned/*.png` | 1080 × 1920 | PNG, RGB, 389–514 KB each | The screenshots on the brand colour, in a phone frame, with the caption above. **Upload these.** |
| `screenshots/raw/*.png` | 1080 × 1920 | PNG, RGB, 145–212 KB each | The same screens as captured, without frame or caption. |

Brand colours: `#3F51E8` (`ic_bg`, the icon's background), with a lighter `#5163F2` and darker `#3241CF` for the gradient. Text is Roboto. Captions break into two balanced lines.

## Screenshots, in upload order

| # | File (in `captioned/` and `raw/`) | Screen | Caption |
| --- | --- | --- | --- |
| 1 | `01-translated-chat.png` | A chat with Emma, her texts shown in Spanish | Messages from friends, in the language you're practicing |
| 2 | `02-original.png` | The same chat, one bubble tapped to show the English original | Tap any message to see the original |
| 3 | `03-reply.png` | The composer: Spanish typed, the English that Emma gets prepared, ready to send | Reply in your practice language. They get it in yours. |
| 4 | `04-check.png` | Check my translation: the correction as a diff, a more natural version and a note | Check my translation: corrections and a natural version |
| 5 | `05-explain.png` | The Explain sheet for "Aviso, mi primo habla muchísimo 😂" | Explain: grammar, idioms and slang, phrase by phrase |
| 6 | `06-romaji.png` | A Japanese chat with Yuki, romaji under the characters, light theme | Pinyin and romaji, the way you like to read |
| 7 | `07-inbox.png` | The inbox: seven chats with made-up contacts, in Spanish and Japanese | Practice in the chats you already have |

- Upload all seven, in this order.
- Caption 7 is new (38 characters) and replaces "Works with Google Messages on your phone", which went with the onboarding shot.
- Caption 8 ("Start with a free week. No card needed.") stays dropped: it's promotional.

## How they were made

- **Device:** the API 36 emulator, with a release build signed with the upload key, so no debug UI shows. The display was set to 1080 × 1920 (`wm size`), because its native 1080 × 2400 is longer than Play's 2:1 limit. The status bar uses SystemUI demo mode: 9:41, Wi-Fi and battery full, no notifications, no mobile signal. The clock was set to 9:41 so the message times fit. Light theme throughout.
- **AI output is real.** Every translation, the romaji, the English prepared in shot 3, the Check my translation result and the Explain text came from `google/gemma-4-31b-it`, the managed model, at effort Auto. Nothing was written by hand or edited. The calls went through a temporary OpenRouter key ($0.25 limit, in the account's default workspace, not "Transmit Customers"), set as the app's own key with the managed settings: OpenRouter, that model for translation and tutoring, effort Auto, 8 messages of context. The key showed $0.0045 of use (OpenRouter books usage a few minutes late) and was deleted afterwards: a GET for it returns 404.
- **Temporary code, reverted and never committed:**
  - fuller sample chats: Emma (Spanish), a new Japanese chat with Yuki, and Leo and Nina for the inbox, besides the demo's group, Jake and Mom;
  - the DEMO label, demo bar and demo banner hidden; the call button, search bar and Start chat button shown, as in a real inbox and chat;
  - the own-key extras (costs, model names, budgets) hidden, so the screens look as a subscriber's do;
  - every message in view translated on open (20 rather than the managed 4), as messages are when they arrive one by one;
  - the practice-language text in shots 3 and 4 put in the composer by an intent, since adb can't type "¡" or accents. That text is what the user writes, not AI output.
- **Sample messages changed after seeing the real output:**
  - Emma's "We booked a table for 8" became "for 8pm": the model read it as a table for eight people ("Reservamos mesa para 8").
  - Yuki's "Great! I know a good place near the station" became "Yay! I know a great place…": "Great!" came out as 「いいんじゃん！」, which reads oddly.
  - The Spanish attempt checked in shot 4 ends with a full stop. Without one, the correction added it, and the diff struck out and re-inserted the whole last word ("~~primo~~primo.").
- **Privacy:** made-up first names and initial avatars only. No phone numbers, photos or real conversations appear.
- **Graphics:** headless Chrome rendered the icon from the launcher icon's vector paths, the feature graphic, and the captioned frames. Pillow checked and saved the PNGs. The scripts weren't committed; ask if you want them.

## For product to review

1. **The icon and the impersonation rule.** `store-listing.md` says the icon mustn't look like the Google Messages icon ("no blue speech bubble"). It's two white speech bubbles on a blue square, and its 文 / A glyphs are close to the Material "translate" symbol that Google Translate's icon also uses. It is the app's real launcher icon. A decision on it is pending.
2. **Caption 7** is new wording, not from the earlier caption list.
3. **The real output, as shown:**
   - "Fair warning, my cousin talks a lot 😂" came out as "Aviso, mi primo habla muchísimo 😂". It's understandable, but "Te aviso" would be more usual. The Explain sheet itself says the one-word "Aviso" is common in texting.
   - "Cousin" came out as *primo* (male), so the shots say *primo*, where the hand-written set said *prima*.
   - The Madrid group says *departamento* for apartment, the Latin American word; Spain says *piso*.
   - The Explain sheet calls *-ísimo* "the superlative" (strictly, the absolute superlative) and calls "Mi primo es muy hablador" "slangy", which it isn't really.
4. **UI nits seen in shot 4:**
   - In the Check my translation diff, a deleted word runs straight into the inserted one ("~~para~~**de**" reads as "parade"). A space or gap between them would read better.
   - A punctuation-only change strikes out and re-inserts the whole word ("~~primo~~primo."). Diffing punctuation apart from the word would avoid it.
