package app.transmit.notify

import app.transmit.messaging.Message
import java.util.concurrent.ConcurrentHashMap

/**
 * What the conversation notifications show, remembered between posts. Each conversation's notification stacks its
 * unread received messages, oldest first. After a reply from the notification it keeps what it showed, now read,
 * with the reply after it, for as long as it stays up. The group summary lists the conversations that are up.
 */
class NotificationState(private val maxLines: Int = MAX_LINES) {
    sealed interface Line {
        data class Received(val message: Message) : Line
        /** What the user replied from the notification, at [at] (ms). */
        data class Replied(val text: String, val at: Long) : Line
    }

    /** A conversation whose notification is up, as the summary lists it: its newest line, at [at] (ms). */
    data class Shown(val threadId: Long, val title: String, val text: String, val at: Long)

    private val kept = ConcurrentHashMap<Long, List<Line>>()
    private val shown = ConcurrentHashMap<Long, Shown>()
    /** Conversations whose notification, since the reply, has shown a message that came after it. */
    private val newSinceReply = ConcurrentHashMap.newKeySet<Long>()

    /** [threadId]'s lines, given its [unread] received messages: what it kept, then the unread ones it doesn't have. */
    fun lines(threadId: Long, unread: List<Message>): List<Line> {
        val keep = kept[threadId].orEmpty()
        val have = keep.mapNotNullTo(HashSet()) { (it as? Line.Received)?.message?.key }
        val fresh = unread.sortedBy { it.date }.filter { it.key !in have }.map { Line.Received(it) }
        return (keep + fresh).takeLast(maxLines)
    }

    /** The user replied [text] from [threadId]'s notification, which showed [lines]: they stay, and the reply follows. */
    fun replied(threadId: Long, lines: List<Line>, text: String, at: Long) {
        kept[threadId] = (lines + Line.Replied(text, at)).takeLast(maxLines)
        newSinceReply.remove(threadId)
    }

    /** [entry]'s notification was posted showing [lines]. */
    fun posted(entry: Shown, lines: List<Line> = emptyList()) {
        shown[entry.threadId] = entry
        val keep = kept[entry.threadId]?.mapNotNullTo(HashSet()) { (it as? Line.Received)?.message?.key } ?: return
        if (lines.any { it is Line.Received && it.message.key !in keep }) newSinceReply += entry.threadId
    }

    /**
     * Whether [threadId]'s notification goes after a read elsewhere left nothing [unread]. One showing what it showed at
     * a reply stays: the phone echoing that reply's read isn't the user reading. Once a message came after the reply,
     * the read is the user's.
     */
    fun goesOnReadElsewhere(threadId: Long, unread: List<Message>) =
        unread.isEmpty() && (kept[threadId] == null || threadId in newSinceReply)

    fun isShowing(threadId: Long) = shown.containsKey(threadId)

    /** [threadId]'s notification went (opened, read, dismissed). Its next one starts again from unread messages. */
    fun gone(threadId: Long) {
        kept.remove(threadId)
        shown.remove(threadId)
        newSinceReply.remove(threadId)
    }

    /** Every notification went (the group was dismissed). */
    fun allGone() {
        kept.clear()
        shown.clear()
        newSinceReply.clear()
    }

    /** The conversations whose notifications are up, newest first. */
    fun showing(): List<Shown> = shown.values.sortedByDescending { it.at }

    companion object {
        /** About as many as an expanded notification shows. */
        const val MAX_LINES = 8
    }
}
