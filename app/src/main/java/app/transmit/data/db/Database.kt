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
)

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

    @Query("SELECT * FROM explanations WHERE `key` = :key")
    suspend fun explanation(key: String): ExplanationEntity?

    @Upsert
    suspend fun upsertExplanation(entity: ExplanationEntity)

    @Insert
    suspend fun insertUsage(usage: UsageEntity)

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE at >= :since")
    fun spentSinceFlow(since: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE at >= :since")
    suspend fun spentSince(since: Long): Double

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE at >= :since AND threadId = :threadId")
    fun threadSpentSinceFlow(threadId: Long, since: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE at >= :since AND threadId = :threadId")
    suspend fun threadSpentSince(threadId: Long, since: Long): Double

    @Query("SELECT threadId, SUM(costUsd) AS cost, COUNT(*) AS calls FROM usage WHERE at >= :since GROUP BY threadId ORDER BY cost DESC")
    fun spendByThread(since: Long): Flow<List<ThreadSpend>>

    @Query("SELECT purpose, SUM(costUsd) AS cost, COUNT(*) AS calls, SUM(inputTokens) AS inputTokens, SUM(outputTokens) AS outputTokens FROM usage WHERE at >= :since GROUP BY purpose ORDER BY cost DESC")
    fun spendByPurpose(since: Long): Flow<List<PurposeSpend>>

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

@Database(
    entities = [TranslationEntity::class, ExplanationEntity::class, UsageEntity::class, ChatSettingsEntity::class, DraftEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TransmitDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "transmit.db").build()
    }
}
