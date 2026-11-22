package app.transmit.gm

import android.content.ContextWrapper
import app.transmit.data.ContactsRepository
import app.transmit.data.KeyVault
import app.transmit.data.db.TransmitDao
import app.transmit.gm.proto.Conversation
import app.transmit.gm.proto.MessageContent
import app.transmit.gm.proto.MessageInfo
import app.transmit.gm.proto.MessageStatus
import app.transmit.gm.proto.MessageStatusType
import app.transmit.gm.proto.Participant
import app.transmit.gm.proto.SmallInfo
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import app.transmit.gm.proto.Message as GmMessage

/**
 * What the phone says about read state, applied to the cache that conversation notifications are built from: the
 * unread incoming messages of a conversation are what its notification stacks.
 */
class GmBridgeReadSyncTest {
    private val gmDao = FakeGmDao()
    private val readElsewhere = mutableListOf<Long>()
    private val incoming = mutableListOf<String>()
    /** Each call to onIncoming: one alert, for a conversation's new messages. */
    private val alerts = mutableListOf<List<String>>()
    private val thread = GmMapping.threadId(CONV)

    private fun TestScope.bridge() = GmBridge(
        secrets = object : KeyVault {
            override fun get(name: String): String? = null
            override fun put(name: String, value: String?) = Unit
        },
        gmDao = gmDao,
        dao = Proxy.newProxyInstance(TransmitDao::class.java.classLoader, arrayOf(TransmitDao::class.java)) { _, _, _ -> null } as TransmitDao,
        contacts = ContactsRepository(object : ContextWrapper(null) {}),
        mediaDir = File(System.getProperty("java.io.tmpdir"), "gm-bridge-test"),
        scope = backgroundScope,
        onIncoming = { _, keys -> incoming += keys; alerts += keys },
        onReadElsewhere = { readElsewhere += it },
        isOnScreen = { false },
        onLoggedOut = {},
    )

    /** The phone's view of the conversation: read or unread up to its latest message, at [lastMs]. */
    private fun conversation(unread: Boolean, lastMs: Long, latestId: String) = GmEvent.ConversationUpdate(
        Conversation.newBuilder()
            .setConversationID(CONV)
            .setUnread(unread)
            .setLastMessageTimestamp(lastMs * 1000)
            .setLatestMessageID(latestId)
            .addParticipants(
                Participant.newBuilder().setID(SmallInfo.newBuilder().setParticipantID("2").setNumber(ANA)).setIsVisible(true),
            )
            .build(),
    )

    private fun received(id: String, ms: Long, text: String = "Message $id", isOld: Boolean = false, conv: String = CONV) = GmEvent.MessageUpdate(
        GmMessage.newBuilder()
            .setMessageID(id)
            .setConversationID(conv)
            .setParticipantID("2")
            .setTimestamp(ms * 1000)
            .setMessageStatus(MessageStatus.newBuilder().setStatus(MessageStatusType.INCOMING_COMPLETE))
            .addMessageInfo(MessageInfo.newBuilder().setMessageContent(MessageContent.newBuilder().setContent(text)))
            .build(),
        isOld = isOld,
    )

    /** Past the bridge's wait for the phone's replays (its scope is the background scope, which only virtual time runs). */
    private fun TestScope.catchUp() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    /** What the conversation's notification would show: its unread received messages, oldest first. */
    private suspend fun stacked() = gmDao.unreadIncoming(thread, 8).sortedBy { it.timestamp }.map { it.messageId }

    @Test
    fun aReadUpdateFromBeforeNewMessagesLeavesThemStacked() = runTest {
        val bridge = bridge()
        // Read up to m0 on the phone; then Ana texts twice. Between her texts the phone sends the conversation as it
        // was before m1 (a conversation update can trail the messages: the echo of an earlier read, a status change).
        bridge.handle(received("m0", 1_000))
        bridge.handle(conversation(unread = false, lastMs = 1_000, latestId = "m0"))
        assertEquals(listOf(thread), readElsewhere)
        bridge.handle(received("m1", 2_000))
        bridge.handle(conversation(unread = false, lastMs = 1_000, latestId = "m0"))
        bridge.handle(received("m2", 3_000))

        assertEquals(listOf("m1", "m2"), stacked())
        // Nothing new was read, so the notification isn't touched.
        assertEquals(listOf(thread), readElsewhere)
        assertEquals(listOf("gm0", "gm1", "gm2"), incoming)
    }

    @Test
    fun readingOnThePhoneClearsWhatItCovers() = runTest {
        val bridge = bridge()
        bridge.handle(conversation(unread = true, lastMs = 2_000, latestId = "m1"))
        bridge.handle(received("m1", 2_000))
        bridge.handle(received("m2", 3_000))
        assertEquals(listOf("m1", "m2"), stacked())

        bridge.handle(conversation(unread = false, lastMs = 3_000, latestId = "m2"))
        assertEquals(emptyList<String>(), stacked())
        assertEquals(listOf(thread), readElsewhere)
    }

    @Test
    fun readingOnThePhoneBeforeTheNewestArrivedKeepsTheNewest() = runTest {
        val bridge = bridge()
        bridge.handle(conversation(unread = true, lastMs = 2_000, latestId = "m1"))
        bridge.handle(received("m1", 2_000))
        bridge.handle(received("m2", 3_000))

        // Read on the phone when m1 was the latest; m2 arrived after.
        bridge.handle(conversation(unread = false, lastMs = 2_000, latestId = "m1"))
        assertEquals(listOf("m2"), stacked())
        // The notification is told, so it can drop m1 and keep m2.
        assertEquals(listOf(thread), readElsewhere)
    }

    @Test
    fun theLatestMessageTheUpdateNamesCountsAsRead() = runTest {
        val bridge = bridge()
        bridge.handle(received("m1", 2_000))
        // The conversation's own time can trail its latest message's by a little.
        bridge.handle(conversation(unread = false, lastMs = 1_999, latestId = "m1"))
        assertEquals(emptyList<String>(), stacked())
    }

    @Test
    fun anOutOfDateUnreadAfterReadingInTransmitLeavesNewMessagesStacked() = runTest {
        val bridge = bridge()
        bridge.handle(conversation(unread = true, lastMs = 1_000, latestId = "m0"))
        bridge.handle(received("m0", 1_000))
        // Read in Transmit (the phone isn't connected here, so it isn't told).
        bridge.markRead(thread)
        assertEquals(emptyList<String>(), stacked())

        bridge.handle(received("m1", 2_000))
        // The phone hasn't caught up with the read yet: still unread, as of m0.
        bridge.handle(conversation(unread = true, lastMs = 1_000, latestId = "m0"))
        bridge.handle(received("m2", 3_000))

        assertEquals(listOf("m1", "m2"), stacked())
        assertEquals(emptyList<Long>(), readElsewhere)
    }

    @Test
    fun aMessageReplayedAfterTheReadIsStillUnread() = runTest {
        val bridge = bridge()
        bridge.handle(received("m0", 1_000))
        bridge.handle(conversation(unread = false, lastMs = 1_000, latestId = "m0"))
        // Ana texted while the connection was down; the phone replays it on reconnecting, before the conversation.
        bridge.handle(received("m1", 2_000, isOld = true))

        assertEquals(listOf("m1"), stacked())
        // Not alerted yet: that waits for the sync after reconnecting (and this one is too old to alert at all).
        assertEquals(listOf("gm0"), incoming)
    }

    @Test
    fun textsMissedWhileOfflineAlertOnceAfterTheCatchUp() = runTest {
        val bridge = bridge()
        val now = System.currentTimeMillis()
        bridge.handle(received("m0", now - 60_000))
        bridge.handle(conversation(unread = false, lastMs = now - 60_000, latestId = "m0"))
        // Offline; Ana texts twice; the connection comes back and the phone replays both.
        bridge.handle(GmEvent.ConnectionRestored)
        bridge.handle(received("m1", now - 30_000, isOld = true))
        bridge.handle(received("m2", now - 20_000, isOld = true))
        assertEquals(listOf(listOf("gm0")), alerts)

        catchUp()
        assertEquals(listOf("m1", "m2"), stacked())
        // One alert for both, oldest first, so each is translated and the notification stacks them.
        assertEquals(listOf(listOf("gm0"), listOf("gm1", "gm2")), alerts)
    }

    @Test
    fun aMissedTextReadOnThePhoneBeforeTheCatchUpStaysQuiet() = runTest {
        val bridge = bridge()
        val now = System.currentTimeMillis()
        bridge.handle(received("m0", now - 60_000))
        bridge.handle(conversation(unread = false, lastMs = now - 60_000, latestId = "m0"))
        bridge.handle(received("m1", now - 30_000, isOld = true))
        // The sync's conversation list says it was read on the phone meanwhile.
        bridge.handle(conversation(unread = false, lastMs = now - 30_000, latestId = "m1"))

        catchUp()
        assertEquals(emptyList<String>(), stacked())
        assertEquals(listOf(listOf("gm0")), alerts)
    }

    @Test
    fun aMissedTextFromLongAgoStaysQuiet() = runTest {
        val bridge = bridge()
        val twoDaysAgo = System.currentTimeMillis() - 2 * 24 * 60 * 60_000L
        bridge.handle(conversation(unread = true, lastMs = twoDaysAgo, latestId = "m1"))
        bridge.handle(received("m1", twoDaysAgo, isOld = true))

        catchUp()
        // Still unread, so it's in the next notification, but Google Messages will have shown it long ago.
        assertEquals(listOf("m1"), stacked())
        assertEquals(emptyList<List<String>>(), alerts)
    }

    @Test
    fun aMissedTextInANewConversationAlertsAfterTheCatchUp() = runTest {
        val bridge = bridge()
        val now = System.currentTimeMillis()
        bridge.handle(received("m0", now - 60_000))
        bridge.handle(conversation(unread = false, lastMs = now - 60_000, latestId = "m0"))
        bridge.handle(GmEvent.ConnectionRestored)
        // Someone new texted while Transmit was offline: no conversation is cached for it.
        bridge.handle(received("n1", now - 30_000, isOld = true, conv = "8"))

        catchUp()
        assertEquals(listOf(listOf("gm0"), listOf("gn1")), alerts)
    }

    @Test
    fun textsReplayedRightAfterPairingDontAlert() = runTest {
        // Nothing was cached before this connection: a fresh pairing, where everything the phone sends is history.
        val bridge = bridge()
        val now = System.currentTimeMillis()
        // Today's unread texts, replayed. The first one's conversation is fetched and cached, unread.
        bridge.handle(received("m1", now - 30_000, isOld = true))
        bridge.handle(conversation(unread = true, lastMs = now - 20_000, latestId = "m2"))
        bridge.handle(received("m2", now - 20_000, isOld = true))

        catchUp()
        // The conversation's next notification still shows m2, but pairing doesn't alert to it.
        assertEquals(listOf("m2"), stacked())
        assertEquals(emptyList<List<String>>(), alerts)
    }

    @Test
    fun aReplayOlderThanTheCacheDoesntAlert() = runTest {
        val bridge = bridge()
        val now = System.currentTimeMillis()
        bridge.handle(conversation(unread = true, lastMs = now - 10_000, latestId = "m2"))
        bridge.handle(received("m2", now - 10_000))
        bridge.handle(GmEvent.ConnectionRestored)
        // An earlier text of the unread conversation that was never cached: history, not something missed offline.
        bridge.handle(received("m1", now - 30_000, isOld = true))

        catchUp()
        assertEquals(listOf(listOf("gm2")), alerts)
    }

    @Test
    fun aReplayOfAMessageAlreadyCachedDoesntAlertAgain() = runTest {
        val bridge = bridge()
        val now = System.currentTimeMillis()
        bridge.handle(received("m1", now - 30_000))
        bridge.handle(received("m1", now - 30_000, isOld = true))

        catchUp()
        assertEquals(listOf(listOf("gm1")), alerts)
    }

    @Test
    fun aReplayedMessageTheReadCoversIsRead() = runTest {
        val bridge = bridge()
        bridge.handle(conversation(unread = false, lastMs = 2_000, latestId = "m1"))
        bridge.handle(received("m0", 1_000, isOld = true))
        bridge.handle(received("m1", 2_000, isOld = true))
        assertEquals(emptyList<String>(), stacked())
    }

    @Test
    fun aReplayedMessageInAnUnreadConversationIsUnread() = runTest {
        val bridge = bridge()
        bridge.handle(conversation(unread = true, lastMs = 2_000, latestId = "m1"))
        bridge.handle(received("m1", 2_000, isOld = true))
        assertEquals(listOf("m1"), stacked())
        assertEquals(emptyList<String>(), incoming)
    }

    @Test
    fun aReplayedMessageWithNoConversationKnownIsRead() = runTest {
        val bridge = bridge()
        bridge.handle(received("m1", 2_000, isOld = true))
        assertEquals(emptyList<String>(), stacked())
    }

    private companion object {
        const val CONV = "7"
        const val ANA = "+15555550100"
    }
}
