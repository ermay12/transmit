package app.transmit.gm

import app.transmit.data.db.GmConversationEntity
import app.transmit.data.db.GmDao
import app.transmit.data.db.GmMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** The bridge's cache tables in memory, with the queries' ordering and filters. */
class FakeGmDao : GmDao {
    val conversationRows = ConcurrentHashMap<Long, GmConversationEntity>()
    val messageRows = ConcurrentHashMap<String, GmMessageEntity>()
    private val version = MutableStateFlow(0)

    private fun changed() {
        version.value++
    }

    private fun GmMessageEntity.withRead(read: Boolean) = GmMessageEntity(messageId, threadId, timestamp, outgoing, read, tmpId, proto)

    private fun GmConversationEntity.withUnread(unread: Boolean) = GmConversationEntity(threadId, conversationId, lastMessageAt, unread, status, proto)

    private fun newestFirst(rows: Collection<GmMessageEntity>) =
        rows.sortedWith(compareByDescending<GmMessageEntity> { it.timestamp }.thenByDescending { it.messageId })

    override suspend fun conversations() =
        conversationRows.values.filter { it.status in setOf(0, 1, 2, 4) }.sortedByDescending { it.lastMessageAt }

    override suspend fun conversation(threadId: Long) = conversationRows[threadId]

    override suspend fun conversationById(conversationId: String) = conversationRows.values.firstOrNull { it.conversationId == conversationId }

    override suspend fun upsertConversations(list: List<GmConversationEntity>) {
        list.forEach { conversationRows[it.threadId] = it }
        changed()
    }

    override suspend fun deleteConversation(threadId: Long) {
        conversationRows.remove(threadId)
        changed()
    }

    override suspend fun markConversationRead(threadId: Long) {
        conversationRows[threadId]?.takeIf { it.unread }?.let { conversationRows[threadId] = it.withUnread(false) }
    }

    override suspend fun messages(threadId: Long, limit: Int, before: Long) =
        newestFirst(messageRows.values.filter { it.threadId == threadId && it.timestamp < before }).take(limit)

    override suspend fun messageCount(threadId: Long) = messageRows.values.count { it.threadId == threadId }

    override suspend fun message(messageId: String) = messageRows[messageId]

    override suspend fun messagesWithTmpId(tmpId: String) = messageRows.values.filter { it.tmpId == tmpId }

    override suspend fun upsertMessages(list: List<GmMessageEntity>) {
        list.forEach { messageRows[it.messageId] = it }
        changed()
    }

    override suspend fun deleteMessage(messageId: String) {
        messageRows.remove(messageId)
        changed()
    }

    override suspend fun deleteThreadMessages(threadId: Long) {
        messageRows.values.removeIf { it.threadId == threadId }
        changed()
    }

    override suspend fun unreadIncoming(threadId: Long, limit: Int) =
        newestFirst(messageRows.values.filter { it.threadId == threadId && !it.read && !it.outgoing }).take(limit)

    override suspend fun unreadThreadIds() = messageRows.values.filter { !it.read && !it.outgoing }.map { it.threadId }.distinct()

    override suspend fun markThreadRead(threadId: Long) {
        messageRows.values.filter { it.threadId == threadId && !it.read }.forEach { messageRows[it.messageId] = it.withRead(true) }
        changed()
    }

    override suspend fun markThreadReadUpTo(threadId: Long, upTo: Long): Int {
        val rows = messageRows.values.filter { it.threadId == threadId && !it.read && it.timestamp <= upTo }
        rows.forEach { messageRows[it.messageId] = it.withRead(true) }
        if (rows.isNotEmpty()) changed()
        return rows.size
    }

    override suspend fun latestMessages() =
        messageRows.values.groupBy { it.threadId }.values.map { rows -> rows.maxBy { it.timestamp } }

    override fun changes(): Flow<Int> = version

    override suspend fun clearConversations() {
        conversationRows.clear()
        changed()
    }

    override suspend fun clearMessages() {
        messageRows.clear()
        changed()
    }
}
