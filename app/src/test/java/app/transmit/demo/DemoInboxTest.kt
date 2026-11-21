package app.transmit.demo

import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Reaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemoInboxTest {
    @Test
    fun samplesAreFictionalAndIncludeAGroup() = runTest {
        val inbox = DemoInbox(backgroundScope)
        val chats = inbox.conversations(100)
        assertEquals(DemoChats.chats.size, chats.size)
        // Negative thread ids can't collide with a real conversation's, and 555-01xx numbers reach no one.
        assertTrue(chats.all { it.threadId < 0 })
        assertTrue(chats.flatMap { it.recipients }.all { it.matches(Regex("\\+155555501\\d\\d")) })
        val groups = chats.filter { it.recipients.size > 1 }
        assertEquals(1, groups.size)
        assertTrue(groups.single().groupName!!.isNotBlank())
        // Each chat has a message of the user's, written in Spanish, to show what sent messages look like.
        assertEquals(chats.map { it.threadId }.toSet(), inbox.written().map { it.first }.toSet())
    }

    /** The sample group shows who reacted, and the user's reaction joins, switches and goes as in Google Messages. */
    @Test
    fun reactingInTheGroupKeepsEveryoneElses() = runTest {
        val inbox = DemoInbox(backgroundScope)
        val group = DemoChats.chats.single { it.groupName != null }.threadId
        val key = inbox.messages(group, 100).first { it.body.startsWith("I booked the flights") }.key
        suspend fun react(emoji: String) = inbox.react(inbox.message(key)!!, emoji)
        val sams = Reaction("🎉", 1, mine = false, others = listOf(DemoChats.SAM))
        assertEquals(listOf(sams.copy(count = 2, mine = true)), inbox.message(key)!!.reactions)
        react("❤️")
        assertEquals(listOf(sams, Reaction("❤️", 1, mine = true)), inbox.message(key)!!.reactions)
        react("❤️")
        assertEquals(listOf(sams), inbox.message(key)!!.reactions)
        react("🎉")
        assertEquals(listOf(sams.copy(count = 2, mine = true)), inbox.message(key)!!.reactions)
    }

    @Test
    fun sendingGetsAReadReceiptThenAnAnswer() = runTest {
        val inbox = DemoInbox(backgroundScope)
        val answered = mutableListOf<Pair<Long, String>>()
        inbox.onReply = { threadId, key -> answered += threadId to key }
        val thread = DemoChats.chats.first().threadId
        val before = inbox.messages(thread, 100).size

        var prepared: String? = null
        val result = inbox.sendToThread(thread, "Sounds great", prepare = { prepared = it })
        assertEquals(result.key, prepared)
        val sent = inbox.message(result.key)!!
        assertTrue(sent.outgoing)
        assertEquals(DeliveryStatus.DELIVERED, sent.status)

        advanceTimeBy(1_300)
        assertEquals(DeliveryStatus.READ, inbox.message(result.key)!!.status)
        assertTrue(answered.isEmpty())

        // The answer comes two seconds after the read receipt.
        advanceTimeBy(2_100)
        assertEquals(before + 2, inbox.messages(thread, 100).size)
        assertEquals(thread, answered.single().first)
        val reply = inbox.message(answered.single().second)!!
        assertFalse(reply.outgoing)
        assertEquals(DemoChats.chats.first().replies.first().second, reply.body)
        assertTrue(inbox.conversations(100).first { it.threadId == thread }.unread)
    }

    @Test
    fun markingReadAnnouncesOnlyARealChange() = runTest {
        val inbox = DemoInbox(backgroundScope)
        val unread = DemoChats.chats.first { it.unread }.threadId
        var changes = 0
        backgroundScope.launch { inbox.changes.collect { changes++ } }
        runCurrent()

        inbox.markThreadRead(unread)
        runCurrent()
        assertEquals(1, changes)
        assertTrue(inbox.messages(unread, 100).all { it.read })

        // Chats mark read whenever they reload, so a second time must not set off another reload.
        inbox.markThreadRead(unread)
        runCurrent()
        assertEquals(1, changes)
        assertFalse(inbox.conversations(100).first { it.threadId == unread }.unread)
    }

    @Test
    fun theLinkIsAlwaysConnected() = runTest {
        val inbox = DemoInbox(backgroundScope)
        assertTrue(inbox.synced.first())
        assertTrue(inbox.canRead())
    }
}
