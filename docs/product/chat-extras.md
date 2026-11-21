# Chat extras (beta8): search, stacked notifications, the translate toggle

Added 2026-11-20 at the user's request, separate from the 1.0 release checklist. Each feature is built on its own
branch off `play-launch` and merged back. New user-facing strings go into `copy-review.md` under a "beta8" heading
for the user's sign-off. All three must also work in the reviewer demo mode (it is what promo videos are recorded in).

## 1. Search within a conversation

- **Entry.** The chat top bar's Info icon becomes a ⋮ overflow menu with "Search" and "Chat settings" (as in Google
  Messages and Signal). Tapping the name or avatar still opens chat settings in one tap. The top bar keeps the same
  number of controls, which leaves room for the wider translate toggle (section 3).
- **Search bar.** It replaces the top bar while open: a back arrow (closes search), a text field ("Search this chat"),
  a clear ✕, and up/down arrows with a "3 of 12" count. Matches update as you type (debounced). The first result
  is the newest match. Up goes to older matches and down to newer ones. The keyboard's search key goes to the newest match.
- **What matches.** The message text **and** its cached translation for the chat's language, so you can find a
  Spanish message by its English meaning. Matching ignores case and accents ("cafe" finds "café"), which matters for
  learners typing without accents. It also does plain substring matching for CJK. Reactions and other system rows are
  not matched.
- **Showing a match.** The list scrolls to the current match and centres it. Every match is highlighted in its bubble,
  and the current one more strongly. If a match is only in the side of the bubble that isn't showing, the bubble shows
  that side while it is the current match, and goes back afterwards.
- **Reach.** All messages cached for the chat. If the bridge only has recent history cached, stepping past the oldest
  cached match loads older pages from the phone the way scrolling up does. That fetch is bounded and shows a quiet
  "Looking further back…" indicator. When nothing more is available it says "No more matches".
- **Cost.** Search never triggers AI calls. It searches only text and translations that already exist.
- **Empty states.** "No matches" under the field. Closing search keeps the scroll position on the last match.

## 2. Notifications that stack messages like other messaging apps

The reference is Google Messages on Android 17.

- **One notification per conversation (MessagingStyle).** Collapsed, it shows the newest message. Expanded, it shows the
  recent unread messages in order, oldest at the top (the last ~8), with sender names and avatars in group chats.
  A new message updates the same notification and alerts again. Today the history is lost and only one message shows;
  find the root cause and fix it. Don't just restyle the notification.
- **Several conversations** are bundled under one group with a summary notification (`GROUP_ALERT_CHILDREN`, so the
  summary never alerts on its own).
- **Inline reply** appends "You: …" to the same notification (MessagingStyle with the user as the sender) and stops
  the reply spinner. The notification is not dismissed, and there is no second alert. The conversation counts as read.
- **Mark as read** and opening the chat clear it. So does reading the chat elsewhere (the phone's Google Messages),
  when the bridge reports it.
- **Translations** in each line follow the existing "notification content" setting, unchanged.
- Existing behaviour stays: muted chats, the active-chat suppression, the copy-code action, and conversation shortcuts.

## 3. The translate toggle

- **Control.** The flag circle in the top bar becomes a small switch-style pill whose thumb carries the practice-language
  flag. Off: the thumb is on the left, the track is outlined, and the flag is dimmed. On: the thumb slides right with
  a spring, the track fills with the tertiary colour, and the flag lights up with a small scale "pop". There is also a
  light haptic tick. It should read well on camera, since it is meant for promo videos, but stay compact (about 56–64 dp
  wide) and use theme colours in light and dark.
- **No toast.** The switch shows its own state, so the "Auto-translate on/off" toast goes. For TalkBack, the switch
  has a role and a state description ("Auto-translate, on"). A long-press shows the tooltip "Auto-translate".
- **Turning it on translates the recent messages.** It translates the newest messages the same way opening a chat
  does: the same count (the "Translate on open" setting, `backfillCount`; for managed users the fixed managed value)
  and the same rules (sent messages only if "Translate my sent messages" is on). It skips any message that already
  has a translation. Bubbles waiting for a translation show the existing pending treatment (add a subtle shimmer if
  there is none). A translation that arrives replaces the original with a short crossfade.
- **Turning it off returns the chat to the user's native language** (the user's correction, 2026-11-20). Every
  bubble crossfades to its native-language side: the original when it is in the native language, otherwise a cached
  native translation if there is one, otherwise the original. New messages also stop being translated automatically.
  Cached translations are kept, so switching back on shows them again at once, and the newest messages are translated
  as before. Flipping the toggle either way resets any bubble the user flipped by hand. While it is off, the
  per-bubble translate action still shows one message's translation, the way it does today.
- **No AI access (NONE/ENDED).** The toggle never looks on. A tap makes the thumb start across and spring back, then
  opens the existing "Turn on translation" sheet.

## As built

- **Search** counts one match per message, however often the words appear in it. Past the oldest match it asks the
  phone for at most 4 pages of 50 older messages. Subjects are not searched.
- **Notifications.** The summary appears only once 2 or more chats are showing. It then stays while one is left, since
  cancelling a summary cancels the notifications under it. A group's title is its member list ("Ana, Ben").
- **The translate toggle** has no shimmer, because bubbles already pulse while their translation is pending.
- **The switch holds its position.** The thumb moves at the tap and stays on its new side; nothing springs it back. The
  chat changes once the thumb is within 3% of rest, about 0.36 s after the tap. Waiting for full rest took about
  0.47 s. Changing the chat during the slide dropped frames (one of 53–59 ms in a debug build), so the change still
  waits for the slide to end, though no longer for the spring's last wobble. With quick taps, only where the thumb
  ends up is sent, and nothing is sent if that is where it started.
- **Off the main thread.** The work after a flip now runs in the background: the translations, the chat's settings,
  the recent-message translation, and the settings write. The write runs in order, one at a time. Rows whose
  message hasn't changed are reused, so only the bubbles that change are redrawn; before, every bubble in the chat
  was redrawn twice.
- **Off is the native language.** Each bubble's native-language side is its original. Translations are stored only
  into the chat's practice language, and two-way translation sends the native text as the message itself, so no
  message ever has a cached native translation to show. A friend's message in the practice language stays in it when
  off. Sent messages show the text friends got, not the practice-language text the user wrote. While off, a
  translation that is still on its way isn't waited for (the original shows, not the placeholder) unless the user
  asked for it, and romanization isn't fetched for a translation that isn't showing.
- **The switch's position decides, wherever it's set.** The "Auto-translate" choice in chat settings moves it too, and
  so does AI ending or coming back. Without AI (none set up, or it ended) the switch is never on, so those chats show
  their native side, and translations cached in a trial are a tap away. Taps on bubbles are forgotten at each move.
- **Chats that are off by default** (unknown numbers, when that setting is off) now open in the native language. Before,
  their sent messages showed the practice text the user wrote, and a message translated by a tap kept showing its
  translation each time the chat was opened. Now a tap shows one message's translation until the switch moves or the
  chat is closed.
- **Debug builds only.** The fake trial's dummy key never reaches OpenRouter. A fake on the device answers it, with
  the demo's lines in Spanish and anything else marked "≈", so a debug trial no longer ends at the first
  translation. Release builds are unchanged.
