# Google Play store graphics

Remade on 2026-11-21 around one message: you practice a language in your real SMS and RCS chats, and the people you text just see normal messages. The captions name real languages ("Spanish", "English") instead of "practice language" or "native language". Every file's size and format was checked by script.

## Files

| File | Size | Format | What it is |
| --- | --- | --- | --- |
| `icon-512.png` | 512 × 512 | PNG, 32-bit (RGBA, opaque) | App icon, unchanged since 2026-11-20. Play adds the rounded mask and shadow. |
| `feature-graphic-1024x500.png` | 1024 × 500 | PNG, RGB (no alpha) | The icon, "Transmit", "Practice a language in your everyday texts" and "Your friends just see normal messages" in the middle. At the edges: "You see" with two Spanish bubbles, and "They see" with the same two in English. A crop of the edges loses only the bubbles. |
| `screenshots/captioned/*.png` | 1080 × 1920 | PNG, RGB | Captioned screenshots on the brand colour. **Upload these.** |
| `screenshots/raw/*.png` | 1080 × 1920 | PNG, RGB | The screens as captured, without frame or caption. |
| `make.py` | | | Renders the feature graphic and the captioned shots from `screenshots/raw/` (see its docstring). Change a caption there and run it again. |

Brand colours: `#3F51E8` (`ic_bg`), with `#5163F2` and `#3241CF` for the gradient. Text is Roboto.

## Screenshots, in upload order

| # | File | Screen | Caption · subline |
| --- | --- | --- | --- |
| 1 | `01-you-vs-them.png` | "What you see": Kyle's chat in Spanish (real screen, cropped). "What Kyle sees": the same messages in English, in a generic messaging screen | **You text in Spanish. They see English.** · Kyle gets ordinary texts. No app needed. |
| 2 | `02-inbox.png` | The inbox: Kyle, Rachel, a group and four more chats | **Your real texts, SMS and RCS** · Same number, same chats. Your friends don't install anything. |
| 3 | `03-original.png` | Kyle's chat, with one message tapped to show his English | **Read every text in Spanish** · Stuck? Tap to see what they actually wrote. |
| 4 | `04-reply.png` | The composer: a Spanish reply, and the English Kyle will get | **Reply in Spanish** · See the English Kyle will get before you send. |
| 5 | `05-check.png` | Check my translation on that reply: *para* → *de*, a more natural version and a note | **Get corrected before you hit send** · Fixes your mistakes and says why. |
| 6 | `06-explain.png` | The Explain sheet for "Aviso, mi primo habla mucho 😂" | **Understand the grammar, idioms and slang** · Tap Explain on any message. |
| 7 | `07-per-chat.png` | Kyle's Spanish chat behind Rachel's Japanese chat, with romaji | **Spanish with Kyle, Japanese with Rachel** · Pick a language for each chat. |

- The contacts have names that are clearly neither Spanish nor Japanese, so the people you text read as your usual friends, not native speakers.
- Rachel's chat no longer has the old "Your Japanese is getting really good!" line, which gave the practice away.
- "Kyle's phone" in shot 1 is a mockup, not Transmit, and is labelled as what Kyle sees. It's deliberately generic (dark and light grey bubbles, Roboto, no icons) so it looks like neither Google Messages nor iMessage. The person texting is "Alex".
- No Google or Google Messages names, logos or colours appear anywhere. "SMS" and "RCS" are standard names.

## How they were made

- **Device:** the API 36 emulator with an upload-key release build, at 1080 × 1920 (`wm size`), with SystemUI demo mode (9:41, full Wi-Fi and battery, no notifications). Light theme. The emulator was reset afterwards: display size, demo mode, and the app's data (`pm clear`).
- **The AI output is real.** Every translation, the romaji, the English in shot 4, the Check my translation result and the Explain text came from the managed model (`google/gemma-4-31b-it`, effort Auto, 8 context messages), through the `transmit-review` key set as the app's own key, in one demo session for shots 1 to 6. Nothing was written or edited by hand. A few sessions were tried. The first said "Aviso previo, mi primo habla mucho", which is stiff, so it was dropped. The retakes used about $0.007 of the key's $3 limit ($0.0085 used in all, $2.99 left).
- **Temporary code, reverted and never committed:** the screenshot chats (Kyle in Spanish, Rachel in Japanese, a "Beach trip 🏖️" group, Mom, Jake, Leo, Nina), with a language per chat and times fixed around 9:41; the Demo label, bar and banner hidden; the call button and Start chat shown; own-key extras hidden; 20 messages translated on open; and a hook that put the Spanish draft in the composer, since adb can't type accents. The draft is what the user writes, not AI output.
- **A real bug found and fixed (67e41bf):** "住所送るね📍" showed as a big emoji with no bubble and no romaji, because Japanese, Chinese and Korean text with an emoji counted as emoji-only. It's fixed in the app, and shot 7 was taken with the fix.
- **Privacy:** made-up first names, initial avatars, numbers in the 555-01xx fiction range (never shown). No real chats.

## For product to review

1. **The icon and the impersonation rule** (unchanged, still pending): two white speech bubbles on blue, with 文 / A glyphs close to the Material "translate" symbol.
2. **Wording:** the captions above are new, apart from the ones the outline already had. "Fixes your mistakes and says why", "Tap Explain on any message" and "Pick a language for each chat" haven't been through the copy review.
3. **Real output, as shown:** the Beach trip group says *a dos cuadras de la playa*, with the Latin American *cuadras* (Spain says *manzanas*). Explain calls *Aviso* shorthand for "Te doy un aviso", which is fair.
