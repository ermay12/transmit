package app.transmit.ai

import app.transmit.data.AppSettings
import app.transmit.data.ContactsRepository
import app.transmit.data.Language
import app.transmit.data.Languages
import app.transmit.data.db.ChatSettingsEntity
import app.transmit.data.db.TransmitDao
import app.transmit.messaging.MessagingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Per-conversation settings after applying contact / unknown-number defaults. */
data class EffectiveChat(
    val threadId: Long,
    val autoTranslate: Boolean,
    val monthlyBudgetUsd: Double,
    val target: Language,
    val isKnown: Boolean,
    val overrides: ChatSettingsEntity?,
    val muted: Boolean,
)

class ChatPolicy(
    private val dao: TransmitDao,
    private val repo: MessagingRepository,
    private val contacts: ContactsRepository,
) {
    suspend fun effective(threadId: Long, s: AppSettings, recipients: List<String>? = null): EffectiveChat = withContext(Dispatchers.IO) {
        val cs = dao.chatSettings(threadId)
        val people = recipients ?: repo.recipientsForThread(threadId)
        val known = people.any { contacts.lookup(it).isContact }
        val defaults = if (known) s.contactDefaults else s.unknownDefaults
        EffectiveChat(
            threadId = threadId,
            autoTranslate = cs?.autoTranslate ?: defaults.autoTranslate,
            monthlyBudgetUsd = cs?.monthlyBudgetUsd ?: defaults.monthlyBudgetUsd,
            target = Languages.get(cs?.targetLang ?: s.targetLang),
            isKnown = known,
            overrides = cs,
            muted = cs?.muted ?: false,
        )
    }
}

sealed interface BudgetStatus {
    data object Ok : BudgetStatus
    data class GlobalExceeded(val spent: Double, val limit: Double) : BudgetStatus
    data class ChatExceeded(val spent: Double, val limit: Double) : BudgetStatus
}

class BudgetManager(
    private val dao: TransmitDao,
    private val policy: ChatPolicy,
) {
    /** Budgets reset on the first of each calendar month (local time). */
    fun periodStart(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun nextReset(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = periodStart(now)
        add(Calendar.MONTH, 1)
    }.timeInMillis

    suspend fun status(threadId: Long?, s: AppSettings): BudgetStatus {
        val start = periodStart()
        val global = dao.spentSince(start)
        if (global >= s.monthlyBudgetUsd) return BudgetStatus.GlobalExceeded(global, s.monthlyBudgetUsd)
        if (threadId != null && threadId > 0) {
            val limit = policy.effective(threadId, s).monthlyBudgetUsd
            val spent = dao.threadSpentSince(threadId, start)
            if (spent >= limit) return BudgetStatus.ChatExceeded(spent, limit)
        }
        return BudgetStatus.Ok
    }
}
