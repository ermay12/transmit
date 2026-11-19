package app.transmit.ui.access

import app.transmit.R
import app.transmit.UiText
import app.transmit.access.AiAccess
import app.transmit.access.PlanSwitch
import app.transmit.access.Tier

/** The Plans screen's main button. */
enum class PlansAction {
    /** Nothing owned yet: subscribe to the plan chosen. */
    SUBSCRIBE,

    /** Transmit: Upgrade to Plus. */
    UPGRADE,

    /** Renewal is off: Play's page for the plan, where Resubscribe keeps it. */
    KEEP_SUBSCRIPTION,

    /** Plus, renewing or with a switch set: nothing to buy. */
    NONE,
}

/**
 * What the Plans screen offers for an [AiAccess], kept apart from Compose so the rules are unit-tested: the plan
 * cards, the main action, whether Switch to Transmit is offered, and the line under the subscriber's own plan.
 */
data class PlansModel(
    val owned: Tier?,
    /** The plan cards, in order: a Plus subscriber sees Plus first, then Transmit when a switch is offered or set. */
    val shown: List<Tier>,
    val action: PlansAction,
    /** Beside Keep my subscription on a Transmit whose renewal is off: Upgrade to Plus, which renews. */
    val upgradeToo: Boolean,
    /** Switch to Transmit is offered, and would take effect then. */
    val switchAt: Long?,
    /** A switch to Transmit is set: until [PlanSwitch.startsAt] it's still Plus. */
    val switch: PlanSwitch?,
    /** Renewal is off: the plan ends then, unless the user resubscribes in Google Play. */
    val endsAt: Long?,
    private val renewsAt: Long?,
) {
    /** The line under the subscriber's own plan card: when it switches, ends or renews. */
    val ownDetail: UiText?
        get() = when {
            switch != null -> UiText.res(R.string.plan_switches_on, UiText.Date(switch.startsAt))
            endsAt != null -> UiText.res(R.string.plan_ends, UiText.Date(endsAt))
            renewsAt != null -> UiText.res(R.string.plan_renews, UiText.Date(renewsAt))
            else -> null
        }

    /** What to know before the actions: the plan is ending, or a switch is set. */
    val note: UiText?
        get() = when {
            switch != null -> UiText.res(R.string.plan_switch_pending_note, UiText.Date(switch.startsAt))
            endsAt != null -> UiText.res(R.string.plan_ends_note, UiText.Date(endsAt))
            else -> null
        }

    companion object {
        fun of(access: AiAccess?): PlansModel {
            val subscribed = access as? AiAccess.Subscribed
            val owned = subscribed?.tier
            val canceled = subscribed?.canceled == true
            // Plus → Transmit: set now, it takes effect when the paid month ends (DEFERRED). While one is set, the
            // plan is still Plus, with Plus's AI, and nothing undoes it (Google documents no way to): Upgrade works
            // again once it has happened. No switch while another device has the key, nor on a plan that's ending:
            // there, the way on is Resubscribe in Google Play.
            val switch = subscribed?.pendingSwitch.takeIf { owned == Tier.PLUS && !canceled }
            val canChange = subscribed?.inUseElsewhere != true && !canceled
            val switchAt = subscribed?.periodEndsAt.takeIf { owned == Tier.PLUS && switch == null && canChange }
            val shown = when {
                owned == null || owned == Tier.STANDARD -> Tier.entries
                switch != null || switchAt != null -> listOf(Tier.PLUS, Tier.STANDARD)
                else -> listOf(Tier.PLUS)
            }
            val action = when {
                owned == null -> PlansAction.SUBSCRIBE
                canceled -> PlansAction.KEEP_SUBSCRIPTION
                owned == Tier.STANDARD -> PlansAction.UPGRADE
                else -> PlansAction.NONE
            }
            return PlansModel(
                owned, shown, action, upgradeToo = canceled && owned == Tier.STANDARD,
                switchAt = switchAt, switch = switch, endsAt = subscribed?.endsAt, renewsAt = subscribed?.renewsAt,
            )
        }
    }
}

/**
 * The Settings row under the plan's name ("Transmit Plus"): in use on another device, a switch that's set, the date it
 * ends (renewal off) or renews.
 */
fun subscriptionDetail(access: AiAccess.Subscribed): UiText? {
    val endsAt = access.endsAt
    return when {
        access.inUseElsewhere -> UiText.res(R.string.settings_subscription_in_use)
        // Still Plus, with Plus's AI, until then.
        access.pendingSwitch != null -> UiText.res(R.string.plan_switches_on, UiText.Date(access.pendingSwitch.startsAt))
        endsAt != null -> UiText.res(R.string.plan_ends, UiText.Date(endsAt))
        access.renewsAt != null -> UiText.res(R.string.plan_renews, UiText.Date(access.renewsAt))
        else -> null
    }
}
