# Chat fixes (beta10): who reacted, photomoji, and the correction diff

The user asked for these on 2026-11-20, separately from the 1.0 release checklist. New strings are in `copy-review.md`
under "beta10".

## 1. Seeing who reacted

- **Tapping a reaction under a bubble opens a "Reactions" sheet**, in every chat, as in Google Messages: the user's
  own row first ("You · Tap to remove"), then everyone else, each with their emoji. Before, tapping toggled the user's
  own reaction with that emoji. Reacting and switching stay in the long-press sheet, which already had the quick row.
- **Names** come from the phone's participant ids (`GmMapping.reaction`), shown the way the chat shows senders
  (contact name, else Google Messages' name, else the number). Someone the phone counts but doesn't list in the chat,
  such as a person who left the group, shows as "Someone".
- **SMS tapback texts** that Google Messages didn't recognize ("Loved “…”") also say who sent them
  (`Reactions.Tally`). The user's own tapback text can't be taken back from the sheet, since Google Messages doesn't
  track it, so its row has no "Tap to remove".
- The sheet follows the live message, so a reaction that arrives while it's open shows, and it closes when the last
  reaction goes. Removing your own closes it.
- The reviewer demo's "Trip to Madrid" group has two reacted messages, and its `react()` now keeps other people's
  reactions.

## 2. Photomoji

- Beta9 showed a photomoji reaction as its id ("f65f3565-fbf4-…"). The phone puts the photomoji's id in the
  reaction's emoji field (`unicode`), with the picture in `customEmoji`.
- A photomoji is recognized by its custom-emoji data, the EMOTIFY type, or an id shaped like a UUID where the emoji
  goes. Its id stays the key, which is what a removal sends back.
- The picture is shown when the phone gives a web address (`customEmoji.innerData.second`, the sized copy, else
  `first`). Otherwise, or if the address doesn't load, a picture icon shows. The id is never shown.
- **Unverified on a device:** what the phone actually puts in those fields, since the Play build's data can't be read.
  `MessagingRepository` logs each photomoji's source once (tag `Messaging`: type, and the scheme and host of each
  address, never the address itself), and a picture that fails to load logs its error type (tag `Reactions`). After
  installing beta10 from Play, `adb logcat -s Messaging Reactions` while opening a chat with a photomoji shows which
  case applies. If the phone sends no web address, the next step is to find how Messages for Web fetches the picture.

## 3. The correction diff ("Check my translation")

The user's report: when a correction only adds punctuation they left out, the word next to it shows as wrong.
Beta7 had already made punctuation its own token, but four cases still marked correct words:

- a capital after an added full stop (`~~see~~ See`)
- an apostrophe or hyphen missing inside a word (`~~cest~~ c'est`, `~~est ce~~ est-ce`)
- a curly apostrophe or a no-break space from the model, which looks identical (`~~c'est~~ c’est`)
- spaces, which match anywhere, splitting one change in two

As built (`Diff.kt`):

- Words that differ only in case, apostrophe or quote style, or the kind of space are the same word. A capital the
  correction added is marked as an addition on that letter alone: "**O**k**.** **S**ee you".
- Where a stretch between two matching words differs only in punctuation and spacing, its letters being the same
  apart from case, just the marks are shown: "c**'**est", "est**-**ce". The exception: when the only change is a space
  added or removed (invisible on its own), the word is replaced as before: "~~alot~~ **a lot**".
- A spelling fix, accents included, still replaces the whole word: "~~ca~~ **ça**".
