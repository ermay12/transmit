package app.transmit.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import app.transmit.data.ScriptDisplay
import kotlinx.coroutines.flow.Flow

object TranslationOrigin {
    /** Produced by the AI provider. */
    const val AI = "ai"
    /** The user's own target-language text, captured when they sent the message. */
    const val SELF = "self"
    /** Nothing to translate (emoji, numbers, links). */
    const val SAME = "same"
}

@Entity(tableName = "translations", primaryKeys = ["msgKey", "lang"], indices = [Index("threadId"), Index("sourceHash")])
data class TranslationEntity(
    val msgKey: String,
    val lang: String,
    val threadId: Long,
    val sourceHash: Int,
    val text: String,
    val origin: String,
    val model: String?,
    val costUsd: Double,
    val inputTokens: Long,
    val outputTokens: Long,
    val createdAt: Long,
    /** [text] in its language's romanization (pinyin, romaji); null until asked for, or for languages without one. */
    val romanized: String? = null,
)

/** The translation as one line of text in [display]: just the romanization for someone who reads only that. */
fun TranslationEntity.shown(display: ScriptDisplay?): String = if (display == ScriptDisplay.ROMANIZED) romanized ?: text else text

/** With [ScriptDisplay.BOTH], [shown] plus the romanization on a line of its own. */
fun TranslationEntity.shownWithReading(display: ScriptDisplay?): String {
    val reading = romanized?.takeIf { display == ScriptDisplay.BOTH && it != text } ?: return shown(display)
    return "$text\n$reading"
}

@Entity(tableName = "explanations")
data class ExplanationEntity(
    @PrimaryKey val key: String,
    val text: String,
    val model: String?,
    val costUsd: Double,
    val createdAt: Long,
)

@Entity(tableName = "usage", indices = [Index("at"), Index("threadId")])
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long?,
    val at: Long,
    val provider: String,
    val model: String,
    val purpose: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
)

@Entity(tableName = "chat_settings")
data class ChatSettingsEntity(
    @PrimaryKey val threadId: Long,
    /** null = follow the contact/unknown default. */
    val autoTranslate: Boolean? = null,
    /** null = follow the contact/unknown default. */
    val monthlyBudgetUsd: Double? = null,
    /** null = the global target language. */
    val targetLang: String? = null,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    /**
     * Date of the newest message when the chat was archived; null = not archived. Newer messages
     * bring the chat back unless it is muted.
     */
    val archivedAt: Long? = null,
    /** The user's name for the chat, shown only in Transmit; null = named after its people. */
    val name: String? = null,
) {
    fun archived(latest: Long) = archivedAt != null && (muted || latest <= archivedAt)
}

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val threadId: Long,
    val native: String,
    val target: String,
    val updatedAt: Long,
)

/**
 * A Google Messages conversation as the phone last described it. [proto] holds the full
 * `Conversation` message; the other columns are copies for querying. [threadId] is Transmit's id.
 */
@Entity(tableName = "gm_conversations", indices = [Index(value = ["conversationId"], unique = true)])
class GmConversationEntity(
    @PrimaryKey val threadId: Long,
    val conversationId: String,
    /** Milliseconds. */
    val lastMessageAt: Long,
    val unread: Boolean,
    /** `ConversationStatus`: active, archived, deleted, spam... */
    val status: Int,
    val proto: ByteArray,
)

/** A message fetched from, or announced by, the phone. [proto] holds the full `Message`. */
@Entity(tableName = "gm_messages", indices = [Index(value = ["threadId", "timestamp"]), Index("tmpId")])
class GmMessageEntity(
    @PrimaryKey val messageId: String,
    val threadId: Long,
    /** Milliseconds. */
    val timestamp: Long,
    val outgoing: Boolean,
    val read: Boolean,
    /** The id Transmit gave a message it sent, which the phone echoes back on the real message. */
    val tmpId: String?,
    val proto: ByteArray,
)

data class ThreadSpend(val threadId: Long?, val cost: Double, val calls: Int)
data class PurposeSpend(val purpose: String, val cost: Double, val calls: Int, val inputTokens: Long, val outputTokens: Long)

@Dao
interface TransmitDao {
    @Query("SELECT * FROM translations WHERE threadId = :threadId")
    fun translationsForThread(threadId: Long): Flow<List<TranslationEntity>>

    @Query("SELECT * FROM translations WHERE msgKey IN (:keys)")
    fun translationsForKeys(keys: List<String>): Flow<List<TranslationEntity>>

    @Query("SELECT * FROM translations WHERE msgKey = :key AND lang = :lang")
    suspend fun translation(key: String, lang: String): TranslationEntity?

    @Query("SELECT * FROM translations WHERE msgKey IN (:keys) AND lang = :lang")
    suspend fun translations(keys: List<String>, lang: String): List<TranslationEntity>

    @Query("SELECT * FROM translations WHERE sourceHash = :hash AND lang = :lang AND origin != 'self' LIMIT 1")
    suspend fun translationBySource(hash: Int, lang: String): TranslationEntity?

    @Upsert
    suspend fun upsertTranslation(entity: TranslationEntity)

    @Query("DELETE FROM translations WHERE msgKey = :key AND lang = :lang")
    suspend fun deleteTranslation(key: String, lang: String)

    /** Only while the translation is still [text]: a redone translation must not get the old one's romanization. */
    @Query("UPDATE translations SET romanized = :romanized WHERE msgKey = :key AND lang = :lang AND text = :text")
    suspend fun setRomanized(key: String, lang: String, text: String, romanized: String)

    /** Replaces a translation's [was] text with [text], written in characters, keeping [romanized] as its romanization. */
    @Query("UPDATE translations SET text = :text, romanized = :romanized WHERE msgKey = :key AND lang = :lang AND text = :was")
    suspend fun setCharacters(key: String, lang: String, was: String, text: String, romanized: String)

    /**
     * Gives a sent message's translations to the message that replaces its placeholder. Copied, not
     * moved: the chat still shows the placeholder until its list reloads, and it must not lose its
     * translation meanwhile (see [deleteOrphanPlaceholderTranslations]).
     */
    @Query(
        "INSERT OR REPLACE INTO translations (msgKey, lang, threadId, sourceHash, text, origin, model, costUsd, inputTokens, outputTokens, createdAt, romanized) " +
            "SELECT :to, lang, threadId, sourceHash, text, origin, model, costUsd, inputTokens, outputTokens, createdAt, romanized FROM translations WHERE msgKey = :from",
    )
    suspend fun copyTranslations(from: String, to: String)

    /** Drops translations of placeholders that are gone. Message keys are "g" + the message id. */
    @Query(
        "DELETE FROM translations WHERE substr(msgKey, 1, length(:keyPrefix)) = :keyPrefix " +
            "AND msgKey NOT IN (SELECT 'g' || messageId FROM gm_messages)",
    )
    suspend fun deleteOrphanPlaceholderTranslations(keyPrefix: String)

    @Query("SELECT * FROM explanations WHERE `key` = :key")
    suspend fun explanation(key: String): ExplanationEntity?

    @Upsert
    suspend fun upsertExplanation(entity: ExplanationEntity)

    @Insert
    suspend fun insertUsage(usage: UsageEntity)

    // The user's own spending, for BYOK budgets and the Usage screen. Managed calls (trial, subscription) count
    // against the allowance instead.
    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE at >= :since AND provider != 'MANAGED'")
    fun spentSinceFlow(since: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE at >= :since AND provider != 'MANAGED'")
    suspend fun spentSince(since: Long): Double

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE at >= :since AND threadId = :threadId AND provider != 'MANAGED'")
    fun threadSpentSinceFlow(threadId: Long, since: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE at >= :since AND threadId = :threadId AND provider != 'MANAGED'")
    suspend fun threadSpentSince(threadId: Long, since: Long): Double

    @Query("SELECT threadId, SUM(costUsd) AS cost, COUNT(*) AS calls FROM usage WHERE at >= :since AND provider != 'MANAGED' GROUP BY threadId ORDER BY cost DESC")
    fun spendByThread(since: Long): Flow<List<ThreadSpend>>

    @Query("SELECT purpose, SUM(costUsd) AS cost, COUNT(*) AS calls, SUM(inputTokens) AS inputTokens, SUM(outputTokens) AS outputTokens FROM usage WHERE at >= :since AND provider != 'MANAGED' GROUP BY purpose ORDER BY cost DESC")
    fun spendByPurpose(since: Long): Flow<List<PurposeSpend>>

    /** Trial and subscription spending, each call's `usage.cost`: the local tally of the managed allowance. */
    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE at >= :since AND provider = 'MANAGED'")
    fun managedSpentSinceFlow(since: Long): Flow<Double>

    @Query("SELECT * FROM chat_settings WHERE threadId = :threadId")
    suspend fun chatSettings(threadId: Long): ChatSettingsEntity?

    @Query("SELECT * FROM chat_settings WHERE threadId = :threadId")
    fun chatSettingsFlow(threadId: Long): Flow<ChatSettingsEntity?>

    @Query("SELECT * FROM chat_settings")
    fun allChatSettings(): Flow<List<ChatSettingsEntity>>

    @Upsert
    suspend fun upsertChatSettings(entity: ChatSettingsEntity)

    @Query("DELETE FROM chat_settings WHERE threadId = :threadId")
    suspend fun deleteChatSettings(threadId: Long)

    @Query("SELECT * FROM drafts WHERE threadId = :threadId")
    suspend fun draft(threadId: Long): DraftEntity?

    @Query("SELECT * FROM drafts")
    fun drafts(): Flow<List<DraftEntity>>

    @Upsert
    suspend fun upsertDraft(entity: DraftEntity)

    @Query("DELETE FROM drafts WHERE threadId = :threadId")
    suspend fun deleteDraft(threadId: Long)
}

@Dao
interface GmDao {
    @Query("SELECT * FROM gm_conversations WHERE status IN (0, 1, 2, 4) ORDER BY lastMessageAt DESC")
    suspend fun conversations(): List<GmConversationEntity>

    @Query("SELECT * FROM gm_conversations WHERE threadId = :threadId")
    suspend fun conversation(threadId: Long): GmConversationEntity?

    @Query("SELECT * FROM gm_conversations WHERE conversationId = :conversationId")
    suspend fun conversationById(conversationId: String): GmConversationEntity?

    @Upsert
    suspend fun upsertConversations(list: List<GmConversationEntity>)

    @Query("DELETE FROM gm_conversations WHERE threadId = :threadId")
    suspend fun deleteConversation(threadId: Long)

    /** Only touches unread rows: a no-op must not count as a change, or open chats would reload forever. */
    @Query("UPDATE gm_conversations SET unread = 0 WHERE threadId = :threadId AND unread = 1")
    suspend fun markConversationRead(threadId: Long)

    @Query("SELECT * FROM gm_messages WHERE threadId = :threadId AND timestamp < :before ORDER BY timestamp DESC, messageId DESC LIMIT :limit")
    suspend fun messages(threadId: Long, limit: Int, before: Long = Long.MAX_VALUE): List<GmMessageEntity>

    @Query("SELECT COUNT(*) FROM gm_messages WHERE threadId = :threadId")
    suspend fun messageCount(threadId: Long): Int

    @Query("SELECT * FROM gm_messages WHERE messageId = :messageId")
    suspend fun message(messageId: String): GmMessageEntity?

    @Query("SELECT * FROM gm_messages WHERE tmpId = :tmpId")
    suspend fun messagesWithTmpId(tmpId: String): List<GmMessageEntity>

    @Upsert
    suspend fun upsertMessages(list: List<GmMessageEntity>)

    @Query("DELETE FROM gm_messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM gm_messages WHERE threadId = :threadId")
    suspend fun deleteThreadMessages(threadId: Long)

    @Query("SELECT * FROM gm_messages WHERE threadId = :threadId AND read = 0 AND outgoing = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun unreadIncoming(threadId: Long, limit: Int): List<GmMessageEntity>

    @Query("SELECT DISTINCT threadId FROM gm_messages WHERE read = 0 AND outgoing = 0")
    suspend fun unreadThreadIds(): List<Long>

    @Query("UPDATE gm_messages SET read = 1 WHERE threadId = :threadId AND read = 0")
    suspend fun markThreadRead(threadId: Long)

    /** The newest message of every conversation that has any cached. */
    @Query(
        "SELECT m.* FROM gm_messages m JOIN (SELECT threadId, MAX(timestamp) AS ts FROM gm_messages GROUP BY threadId) l " +
            "ON m.threadId = l.threadId AND m.timestamp = l.ts",
    )
    suspend fun latestMessages(): List<GmMessageEntity>

    /** Re-emits whenever either table changes. */
    @Query("SELECT (SELECT COUNT(*) FROM gm_conversations) + (SELECT COUNT(*) FROM gm_messages)")
    fun changes(): Flow<Int>

    @Query("DELETE FROM gm_conversations")
    suspend fun clearConversations()

    @Query("DELETE FROM gm_messages")
    suspend fun clearMessages()
}

@Database(
    entities = [
        TranslationEntity::class, ExplanationEntity::class, UsageEntity::class, ChatSettingsEntity::class, DraftEntity::class,
        GmConversationEntity::class, GmMessageEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TransmitDao
    abstract fun gmDao(): GmDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "transmit.db").build()
    }
}
