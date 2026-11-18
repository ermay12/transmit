package app.transmit.access

import app.transmit.ai.Effort
import app.transmit.ai.ProviderType
import app.transmit.data.AppSettings

/** The two subscriptions on Google Play. */
enum class Tier(val productId: String, val label: String) {
    STANDARD("transmit_monthly", "Transmit"),
    PLUS("transmit_plus", "Transmit Plus"),
    ;

    companion object {
        fun fromProductId(id: String?): Tier? = entries.firstOrNull { it.productId == id }
    }
}

enum class EndReason {
    /** The trial's seven days are over. */
    TRIAL_EXPIRED,
    /** The trial's AI allowance is spent. */
    TRIAL_USED_UP,
    /** The subscription lapsed, was refunded, or its key was turned off. */
    SUBSCRIPTION_ENDED,
    ;

    val wasTrial get() = this != SUBSCRIPTION_ENDED
}

/**
 * Where the app's AI comes from: the one source of truth for what AI the user can use and what they see of it.
 * Trial and subscription are "managed": the key is Transmit's, and none of the AI plumbing shows.
 */
sealed interface AiAccess {
    /** No AI set up: skipped in onboarding, or the trial couldn't start. */
    data object None : AiAccess

    data class Trial(val endsAt: Long) : AiAccess

    /** [renewsAt] is the end of the paid period when it renews automatically, and also when the allowance resets. */
    data class Subscribed(val tier: Tier, val renewsAt: Long?) : AiAccess

    data class Ended(val reason: EndReason) : AiAccess

    /** The user's own key and provider. */
    data object Byok : AiAccess

    val isManaged get() = this is Trial || this is Subscribed

    val canUseAi get() = isManaged || this is Byok

    /** Whether provider, model, cost and dollar budgets may show. Only BYOK users pay for their own AI. */
    val showsAiDetails get() = this is Byok

    /** For noticing transitions across launches. */
    val kind: String
        get() = when (this) {
            None -> "NONE"
            is Trial -> "TRIAL"
            is Subscribed -> "SUBSCRIBED"
            is Ended -> "ENDED"
            Byok -> "BYOK"
        }
}

/** Fixed AI settings for trial and subscription users. None of this is shown to them. */
object ManagedAi {
    const val MODEL = "google/gemma-4-31b-it"
    const val CONTEXT_MESSAGES = 8
    const val TRANSLATE_ON_OPEN = 4
    /** The `provider` column of managed calls in the usage ledger, which BYOK budgets leave out. */
    const val LEDGER_PROVIDER = "MANAGED"
    const val TRIAL_DAYS = 7
}

/**
 * The settings AI calls run with. In managed modes: OpenRouter, the managed model at effort Auto, and the managed
 * context and translate-on-open counts, whatever the user set for their own key.
 */
fun AppSettings.forAccess(access: AiAccess?): AppSettings =
    if (access?.isManaged != true) {
        this
    } else {
        copy(
            provider = ProviderType.OPENROUTER,
            translateModel = ManagedAi.MODEL,
            tutorModel = ManagedAi.MODEL,
            translateEffort = Effort.DEFAULT,
            tutorEffort = Effort.DEFAULT,
            customBaseUrl = "",
            contextMessages = ManagedAi.CONTEXT_MESSAGES,
            backfillCount = ManagedAi.TRANSLATE_ON_OPEN,
            priceOverrides = emptyMap(),
        )
    }
