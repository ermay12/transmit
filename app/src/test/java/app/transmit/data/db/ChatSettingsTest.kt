package app.transmit.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSettingsTest {
    @Test
    fun newerMessagesBringAnArchivedChatBack() {
        val archived = ChatSettingsEntity(threadId = 1, archivedAt = 1_000)
        assertTrue(archived.archived(latest = 1_000))
        assertFalse(archived.archived(latest = 2_000))
        assertFalse(ChatSettingsEntity(threadId = 1).archived(latest = 0))
    }

    @Test
    fun mutedChatsStayArchived() {
        assertTrue(ChatSettingsEntity(threadId = 1, muted = true, archivedAt = 1_000).archived(latest = 2_000))
    }
}
