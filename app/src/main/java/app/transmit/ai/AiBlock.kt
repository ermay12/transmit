package app.transmit.ai

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
    val message: String
        get() = when (this) {
            is Budget -> when (val s = status) {
                is BudgetStatus.GlobalExceeded -> "Monthly AI budget reached (${usd(s.spent)} of ${usd(s.limit)})."
                is BudgetStatus.ChatExceeded -> "This chat's AI budget is used up (${usd(s.spent)} of ${usd(s.limit)})."
                BudgetStatus.Ok -> "Budget OK"
            }
            is AllowanceUsedUp -> if (access is AiAccess.Trial) "Your free week has ended." else "You've used this month's AI."
            NotSetUp -> "Translation is off."
            InUseElsewhere -> "Your subscription is in use on another device."
            is Ended -> when (reason) {
                EndReason.TRIAL_USED_UP, EndReason.TRIAL_EXPIRED -> "Your free week has ended."
                EndReason.SUBSCRIPTION_ENDED -> "Your subscription has ended."
            }
        }
}

class AiBlockedException(val block: AiBlock) : Exception(block.message)

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
    const val SERVICE_PROBLEM = "Translation is having trouble. Try again later."
    const val NO_CONNECTION = "No connection. Check your internet and try again."
    const val REFUSED = "The AI couldn't help with this message."

    fun describe(e: Throwable, access: AiAccess?): String = when {
        e is AiBlockedException -> e.block.message
        access == null || access is AiAccess.Byok -> e.message ?: "Something went wrong"
        e is AiException && e.kind == AiException.Kind.NETWORK -> NO_CONNECTION
        e is AiException && e.kind == AiException.Kind.REFUSAL -> REFUSED
        else -> SERVICE_PROBLEM
    }

    /** "Upgrade to Plus" makes sense for a Standard subscriber only. */
    fun canUpgrade(access: AiAccess?) = access is AiAccess.Subscribed && access.tier == Tier.STANDARD
}
