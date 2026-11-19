package app.transmit.ai

import app.transmit.R
import app.transmit.UiText
import app.transmit.access.AiAccess
import app.transmit.access.EndReason
import app.transmit.access.Tier

/** Why an AI call can't go ahead. Only a BYOK budget can be lifted for one call ("Allow once"). */
sealed interface AiBlock {
    /** A BYOK dollar budget (overall or per chat) is spent. */
    data class Budget(val status: BudgetStatus) : AiBlock

    /** The trial's or this period's managed allowance is spent; [access] is the trial or subscription it belonged to. */
    data class AllowanceUsedUp(val access: AiAccess) : AiBlock

    /** No AI set up yet. */
    data object NotSetUp : AiBlock

    data class Ended(val reason: EndReason) : AiBlock

    /** Another device took the subscription's key (it has one working key at a time). Ask before taking it back. */
    data object InUseElsewhere : AiBlock

    val canAllowOnce get() = this is Budget

    /** One line for banners and toasts. Never mentions keys, models, settings or dollars outside BYOK budgets. */
    val message: UiText
        get() = when (this) {
            is Budget -> when (val s = status) {
                is BudgetStatus.GlobalExceeded -> UiText.res(R.string.ai_budget_reached, usd(s.spent), usd(s.limit))
                is BudgetStatus.ChatExceeded -> UiText.res(R.string.ai_chat_budget_reached, usd(s.spent), usd(s.limit))
                BudgetStatus.Ok -> UiText.Plain("Budget OK")
            }
            is AllowanceUsedUp -> UiText.res(if (access is AiAccess.Trial) R.string.ai_free_week_ended else R.string.ai_month_used)
            NotSetUp -> UiText.res(R.string.ai_translation_off)
            InUseElsewhere -> UiText.res(R.string.ai_in_use_elsewhere)
            // One name for the end of the free week, whether its days or its AI ran out.
            is Ended -> UiText.res(if (reason.wasTrial) R.string.ai_free_week_ended else R.string.ai_subscription_ended)
        }
}

/** Its message is for logs; the user sees [AiBlock.message]. */
class AiBlockedException(val block: AiBlock) : Exception(block.toString())

/**
 * Decides whether an AI call may go ahead. Dollar budgets are BYOK's alone: managed modes are limited only by the
 * allowance, which "Allow once" can't lift since the backend enforces it anyway.
 */
object AiGate {
    suspend fun check(
        access: AiAccess,
        overrideBudget: Boolean,
        allowanceUsedUp: suspend () -> Boolean,
        byokBudget: suspend () -> BudgetStatus,
    ): AiBlock? = when (access) {
        AiAccess.None -> AiBlock.NotSetUp
        is AiAccess.Ended -> AiBlock.Ended(access.reason)
        AiAccess.Byok -> if (overrideBudget) null else byokBudget().takeIf { it != BudgetStatus.Ok }?.let { AiBlock.Budget(it) }
        is AiAccess.Subscribed -> when {
            access.inUseElsewhere -> AiBlock.InUseElsewhere
            allowanceUsedUp() -> AiBlock.AllowanceUsedUp(access)
            else -> null
        }
        is AiAccess.Trial -> if (allowanceUsedUp()) AiBlock.AllowanceUsedUp(access) else null
    }
}

/** What to tell the user when AI fails. BYOK users see the provider's message; everyone else, plain language. */
object AiMessages {
    val SERVICE_PROBLEM: UiText = UiText.res(R.string.ai_service_problem)
    val NO_CONNECTION: UiText = UiText.res(R.string.ai_no_connection)
    val REFUSED: UiText = UiText.res(R.string.ai_refused)

    fun describe(e: Throwable, access: AiAccess?): UiText = when {
        e is AiBlockedException -> e.block.message
        access == null || access is AiAccess.Byok -> e.message?.let { UiText.Plain(it) } ?: UiText.res(R.string.ai_something_wrong)
        e is AiException && e.kind == AiException.Kind.NETWORK -> NO_CONNECTION
        e is AiException && e.kind == AiException.Kind.REFUSAL -> REFUSED
        else -> SERVICE_PROBLEM
    }

    /** "Upgrade to Plus" makes sense for a Standard subscriber only. */
    fun canUpgrade(access: AiAccess?) = access is AiAccess.Subscribed && access.tier == Tier.STANDARD
}
