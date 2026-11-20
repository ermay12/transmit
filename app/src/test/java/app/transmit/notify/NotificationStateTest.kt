package app.transmit.notify

import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Message
import app.transmit.messaging.MessageKind
import app.transmit.notify.NotificationState.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStateTest {
    private val state = NotificationState()

    private fun received(n: Int, from: String = ANA) = Message(
        key = "gm$n", id = n.toLong(), kind = MessageKind.RCS, threadId = THREAD, sender = from, recipients = listOf(ANA),
        body = "Message $n", date = 1_000L * n, dateSent = 1_000L * n, outgoing = false, status = DeliveryStatus.RECEIVED,
        read = false, subId = 0,
    )

    private fun List<Line>.described() = map {
        when (it) {
            is Line.Received -> it.message.body
            is Line.Replied -> "You: ${it.text}"
        }
    }

    @Test
    fun unreadMessagesStackOldestFirst() {
        // The query gives them newest first.
        val lines = state.lines(THREAD, listOf(received(3), received(1), received(2)))
        assertEquals(listOf("Message 1", "Message 2", "Message 3"), lines.described())
    }

    @Test
    fun onlyTheNewestEightShow() {
        val lines = state.lines(THREAD, (1..12).map { received(it) })
        assertEquals((5..12).map { "Message $it" }, lines.described())
    }

    @Test
    fun groupSendersStayOnTheirLines() {
        val lines = state.lines(THREAD, listOf(received(1, ANA), received(2, BEN), received(3, ANA)))
        assertEquals(listOf(ANA, BEN, ANA), lines.map { (it as Line.Received).message.sender })
    }

    @Test
    fun aReplyFollowsWhatTheNotificationShowedAndStaysOnceRead() {
        val unread = listOf(received(1), received(2))
        state.posted(NotificationState.Shown(THREAD, "Ana", "Message 2", 2_000))
        state.replied(THREAD, state.lines(THREAD, unread), "On my way", at = 2_500)

        // Replying marks the chat read: nothing is unread now, and the notification still shows it all.
        assertEquals(listOf("Message 1", "Message 2", "You: On my way"), state.lines(THREAD, emptyList()).described())
        // While it's up, a new message joins it rather than replacing it.
        assertEquals(
            listOf("Message 1", "Message 2", "You: On my way", "Message 3"),
            state.lines(THREAD, listOf(received(3))).described(),
        )
    }

    @Test
    fun aMessageStillUnreadWhenTheReplyWentIsntRepeated() {
        val unread = listOf(received(1))
        state.replied(THREAD, state.lines(THREAD, unread), "Ok", at = 1_500)
        assertEquals(listOf("Message 1", "You: Ok"), state.lines(THREAD, unread).described())
    }

    @Test
    fun onceGoneTheNextNotificationStartsFromUnreadMessages() {
        state.posted(NotificationState.Shown(THREAD, "Ana", "Message 1", 1_000))
        state.replied(THREAD, state.lines(THREAD, listOf(received(1))), "Ok", at = 1_500)
        assertTrue(state.isShowing(THREAD))

        state.gone(THREAD)
        assertFalse(state.isShowing(THREAD))
        assertEquals(listOf("Message 4"), state.lines(THREAD, listOf(received(4))).described())
    }

    @Test
    fun theSummaryListsTheConversationsUpNewestFirst() {
        state.posted(NotificationState.Shown(THREAD, "Ana", "Hola", 1_000))
        state.posted(NotificationState.Shown(OTHER, "Ben", "Hey", 2_000))
        assertEquals(listOf("Ben", "Ana"), state.showing().map { it.title })

        // An update replaces the conversation's entry.
        state.posted(NotificationState.Shown(THREAD, "Ana", "¿Vienes?", 3_000))
        assertEquals(listOf("Ana" to "¿Vienes?", "Ben" to "Hey"), state.showing().map { it.title to it.text })

        state.allGone()
        assertEquals(emptyList<NotificationState.Shown>(), state.showing())
    }

    private companion object {
        const val THREAD = 10_000_000_007L
        const val OTHER = 10_000_000_008L
        const val ANA = "+15555550100"
        const val BEN = "+15555550101"
    }
}
