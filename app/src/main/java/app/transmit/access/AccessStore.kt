package app.transmit.access

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The allowance as last read from the backend or OpenRouter: [remainingUsd] of [allowanceUsd] were left at [at].
 * Managed calls the app logged since then are subtracted on top.
 */
data class AllowanceBaseline(val allowanceUsd: Double, val remainingUsd: Double, val at: Long) {
    fun usedFraction(spentSince: Double): Double =
        if (allowanceUsd <= 0) 1.0 else ((allowanceUsd - remainingUsd + spentSince) / allowanceUsd).coerceIn(0.0, 1.0)
}

/** What's saved about AI access. The managed key and purchase token live in the encrypted [app.transmit.data.SecretStore]. */
data class StoredAccess(
    /** The user chose their own key. Kept apart from [entitlement], so switching back and forth loses neither. */
    val ownKey: Boolean = false,
    val entitlement: Entitlement? = null,
    /** Set when the trial or subscription ended early (used up, lapsed, refunded); a trial past its end needs none. */
    val ended: EndReason? = null,
    val keyHash: String? = null,
    val baseline: AllowanceBaseline? = null,
    /** [AiAccess.kind] when last seen, to notice that a trial or subscription ended while the app was closed. */
    val lastKind: String? = null,
    /** The [allowancePeriod] the Home "nearly used up" banner was dismissed for. */
    val warningDismissedFor: String? = null,
    /** OpenRouter refused the subscription key while Play still lists the subscription: another device took it. */
    val inUseElsewhere: Boolean = false,
    /** The chats' "Translation is off" strip was dismissed; it stays hidden in every chat from then on. */
    val offStripDismissed: Boolean = false,
    /** Home's "Translation is off" banner was dismissed; it stays hidden from then on. Settings keeps its plan card. */
    val offBannerDismissed: Boolean = false,
) {
    fun resolve(now: Long): AiAccess {
        if (ownKey) return AiAccess.Byok
        ended?.let { return AiAccess.Ended(it) }
        return when (val e = entitlement) {
            null -> AiAccess.None
            is Entitlement.Trial -> if (now >= e.endsAt) AiAccess.Ended(EndReason.TRIAL_EXPIRED) else AiAccess.Trial(e.endsAt)
            // Renewal turned off and the paid period over: it has ended, whether or not the Worker has said so yet
            // (the next refresh confirms it). Resubscribing in Play before then keeps it.
            is Entitlement.Subscription -> if (!e.autoRenewing && e.periodEndsAt != null && now >= e.periodEndsAt) {
                AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED)
            } else {
                // A switch only happens at a renewal, so one is never shown for a plan that won't renew.
                AiAccess.Subscribed(
                    e.tier, e.periodEndsAt.takeIf { e.autoRenewing }, inUseElsewhere,
                    pendingSwitch = e.pendingSwitch.takeIf { e.autoRenewing }, periodEndsAt = e.periodEndsAt,
                    canceled = !e.autoRenewing,
                )
            }
        }
    }

    /**
     * Names the allowance period now: the trial, or a plan's billing period. A renewal, an upgrade or a new trial
     * is a new period, so a dismissed banner shows again then.
     */
    val allowancePeriod: String?
        get() = when (val e = entitlement) {
            null -> null
            is Entitlement.Trial -> "trial:${e.endsAt}"
            is Entitlement.Subscription -> "sub:${e.tier.name}:${e.periodEndsAt ?: 0}"
        }

    val warningDismissed: Boolean get() = warningDismissedFor != null && warningDismissedFor == allowancePeriod

    /** A trial or subscription that still gives AI, whichever mode the user picked. */
    fun hasActiveEntitlement(now: Long) = copy(ownKey = false).resolve(now).isManaged
}

class AccessStore(private val store: DataStore<Preferences>, private val hasOwnKey: () -> Boolean) {
    val state: Flow<StoredAccess> = store.data.map { decode(it, hasOwnKey) }

    suspend fun current(): StoredAccess = state.first()

    suspend fun update(block: (StoredAccess) -> StoredAccess) {
        store.edit { p -> encode(block(decode(p, hasOwnKey)), p) }
    }

    /** Saves the mode an install from before access modes starts in, so it no longer depends on the saved keys. */
    suspend fun migrate() {
        store.edit { p -> if (p[K.version] == null) encode(decode(p, hasOwnKey), p) }
    }

    private object K {
        val version = intPreferencesKey("version")
        val ownKey = booleanPreferencesKey("own_key")
        val entitlement = stringPreferencesKey("entitlement")
        val trialEndsAt = longPreferencesKey("trial_ends_at")
        val tier = stringPreferencesKey("sub_tier")
        val periodEndsAt = longPreferencesKey("sub_period_ends_at")
        val autoRenewing = booleanPreferencesKey("sub_auto_renewing")
        val pendingTier = stringPreferencesKey("sub_pending_tier")
        val pendingStartsAt = longPreferencesKey("sub_pending_starts_at")
        val ended = stringPreferencesKey("ended")
        val keyHash = stringPreferencesKey("key_hash")
        val allowanceUsd = doublePreferencesKey("allowance_usd")
        val remainingUsd = doublePreferencesKey("allowance_remaining_usd")
        val baselineAt = longPreferencesKey("allowance_at")
        val lastKind = stringPreferencesKey("last_kind")
        val warningDismissedFor = stringPreferencesKey("warning_dismissed_for")
        val inUseElsewhere = booleanPreferencesKey("in_use_elsewhere")
        val offStripDismissed = booleanPreferencesKey("off_strip_dismissed")
        val offBannerDismissed = booleanPreferencesKey("off_banner_dismissed")
    }

    companion object {
        private const val VERSION = 1

        /**
         * Before access modes (0.3 and earlier) the only AI was the user's own key: an install with a key saved starts
         * in BYOK, one without in NONE.
         */
        fun decode(p: Preferences, hasOwnKey: () -> Boolean): StoredAccess {
            if (p[K.version] == null) return StoredAccess(ownKey = hasOwnKey())
            val entitlement = when (p[K.entitlement]) {
                "trial" -> p[K.trialEndsAt]?.let { Entitlement.Trial(it) }
                "subscription" -> Tier.entries.firstOrNull { it.name == p[K.tier] }?.let { tier ->
                    val pending = Tier.entries.firstOrNull { it.name == p[K.pendingTier] }?.let { t -> p[K.pendingStartsAt]?.let { PlanSwitch(t, it) } }
                    Entitlement.Subscription(tier, p[K.periodEndsAt]?.takeIf { it > 0 }, p[K.autoRenewing] ?: true, pending)
                }
                else -> null
            }
            val baseline = p[K.allowanceUsd]?.let { usd -> AllowanceBaseline(usd, p[K.remainingUsd] ?: usd, p[K.baselineAt] ?: 0) }
            return StoredAccess(
                ownKey = p[K.ownKey] ?: false,
                entitlement = entitlement,
                ended = p[K.ended]?.let { v -> EndReason.entries.firstOrNull { it.name == v } },
                keyHash = p[K.keyHash],
                baseline = baseline,
                lastKind = p[K.lastKind],
                warningDismissedFor = p[K.warningDismissedFor],
                inUseElsewhere = p[K.inUseElsewhere] ?: false,
                offStripDismissed = p[K.offStripDismissed] ?: false,
                offBannerDismissed = p[K.offBannerDismissed] ?: false,
            )
        }

        fun encode(s: StoredAccess, p: MutablePreferences) {
            p[K.version] = VERSION
            p[K.ownKey] = s.ownKey
            listOf(K.trialEndsAt, K.periodEndsAt).forEach { p.remove(it) }
            p.remove(K.tier)
            p.remove(K.autoRenewing)
            p.remove(K.pendingTier)
            p.remove(K.pendingStartsAt)
            when (val e = s.entitlement) {
                null -> p.remove(K.entitlement)
                is Entitlement.Trial -> {
                    p[K.entitlement] = "trial"
                    p[K.trialEndsAt] = e.endsAt
                }
                is Entitlement.Subscription -> {
                    p[K.entitlement] = "subscription"
                    p[K.tier] = e.tier.name
                    e.periodEndsAt?.let { p[K.periodEndsAt] = it }
                    p[K.autoRenewing] = e.autoRenewing
                    e.pendingSwitch?.let {
                        p[K.pendingTier] = it.tier.name
                        p[K.pendingStartsAt] = it.startsAt
                    }
                }
            }
            if (s.ended != null) p[K.ended] = s.ended.name else p.remove(K.ended)
            if (s.keyHash != null) p[K.keyHash] = s.keyHash else p.remove(K.keyHash)
            val b = s.baseline
            if (b != null) {
                p[K.allowanceUsd] = b.allowanceUsd
                p[K.remainingUsd] = b.remainingUsd
                p[K.baselineAt] = b.at
            } else {
                listOf(K.allowanceUsd, K.remainingUsd).forEach { p.remove(it) }
                p.remove(K.baselineAt)
            }
            if (s.lastKind != null) p[K.lastKind] = s.lastKind else p.remove(K.lastKind)
            if (s.warningDismissedFor != null) p[K.warningDismissedFor] = s.warningDismissedFor else p.remove(K.warningDismissedFor)
            if (s.inUseElsewhere) p[K.inUseElsewhere] = true else p.remove(K.inUseElsewhere)
            if (s.offStripDismissed) p[K.offStripDismissed] = true else p.remove(K.offStripDismissed)
            if (s.offBannerDismissed) p[K.offBannerDismissed] = true else p.remove(K.offBannerDismissed)
        }
    }
}
