package app.transmit.access

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import app.transmit.ai.Effort
import app.transmit.ai.ProviderType
import app.transmit.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The access modes, how they resolve over time, how they're stored, and the migration from 0.3. */
class AccessModelTest {
    private val now = 1_000_000_000L
    private val day = 86_400_000L

    @Test
    fun upgradeWithASavedKeyIsByokAndWithoutOneIsNone() {
        val legacy = preferencesOf()
        assertEquals(AiAccess.Byok, AccessStore.decode(legacy) { true }.resolve(now))
        assertEquals(AiAccess.None, AccessStore.decode(legacy) { false }.resolve(now))
    }

    @Test
    fun migratedModeNoLongerDependsOnTheSavedKeys() {
        val p = mutablePreferencesOf()
        AccessStore.encode(AccessStore.decode(p) { true }, p)
        // The key is removed later: still BYOK, with the key missing, rather than silently switching mode.
        assertEquals(AiAccess.Byok, AccessStore.decode(p) { false }.resolve(now))
    }

    @Test
    fun everyStateSurvivesStoring() {
        val states = listOf(
            StoredAccess(),
            StoredAccess(entitlement = Entitlement.Trial(now + day), keyHash = "h1", baseline = AllowanceBaseline(0.5, 0.2, now)),
            StoredAccess(entitlement = Entitlement.Subscription(Tier.PLUS, now + 30 * day, autoRenewing = false), lastKind = "SUBSCRIBED"),
            StoredAccess(entitlement = Entitlement.Subscription(Tier.STANDARD, null), ended = EndReason.SUBSCRIPTION_ENDED),
            StoredAccess(ownKey = true, entitlement = Entitlement.Trial(now + day)),
        )
        for (s in states) {
            val p = mutablePreferencesOf()
            AccessStore.encode(s, p)
            assertEquals(s, AccessStore.decode(p) { error("migrated installs don't look at keys") })
        }
        // Clearing fields removes them.
        val p = mutablePreferencesOf()
        AccessStore.encode(states[1], p)
        AccessStore.encode(StoredAccess(), p)
        assertEquals(StoredAccess(), AccessStore.decode(p) { false })
    }

    @Test
    fun modesResolveFromTheStoredState() {
        val trial = StoredAccess(entitlement = Entitlement.Trial(now + 3 * day))
        assertEquals(AiAccess.Trial(now + 3 * day), trial.resolve(now))
        assertEquals(AiAccess.Ended(EndReason.TRIAL_EXPIRED), trial.resolve(now + 3 * day))
        assertEquals(AiAccess.Ended(EndReason.TRIAL_USED_UP), trial.copy(ended = EndReason.TRIAL_USED_UP).resolve(now))

        val sub = StoredAccess(entitlement = Entitlement.Subscription(Tier.STANDARD, now + day))
        assertEquals(AiAccess.Subscribed(Tier.STANDARD, now + day), sub.resolve(now))
        // Not renewing: no "Renews" or "Resets" date.
        assertEquals(AiAccess.Subscribed(Tier.STANDARD, null), sub.copy(entitlement = Entitlement.Subscription(Tier.STANDARD, now + day, false)).resolve(now))
    }

    @Test
    fun ownKeyWinsButKeepsTheEntitlementForLater() {
        val s = StoredAccess(ownKey = true, entitlement = Entitlement.Subscription(Tier.PLUS, null))
        assertSame(AiAccess.Byok, s.resolve(now))
        assertTrue(s.hasActiveEntitlement(now))
        assertFalse(StoredAccess(ownKey = true, entitlement = Entitlement.Trial(now - 1)).hasActiveEntitlement(now))
        assertFalse(StoredAccess(ownKey = true).hasActiveEntitlement(now))
    }

    @Test
    fun onlyManagedModesAreManagedAndOnlyByokShowsAiDetails() {
        val modes = listOf(AiAccess.None, AiAccess.Trial(now), AiAccess.Subscribed(Tier.PLUS, null), AiAccess.Ended(EndReason.TRIAL_EXPIRED), AiAccess.Byok)
        assertEquals(listOf(false, true, true, false, false), modes.map { it.isManaged })
        assertEquals(listOf(false, true, true, false, true), modes.map { it.canUseAi })
        assertEquals(listOf(false, false, false, false, true), modes.map { it.showsAiDetails })
    }

    @Test
    fun managedModesUseTheFixedModelAndCounts() {
        val mine = AppSettings(
            provider = ProviderType.ANTHROPIC, translateModel = "claude-opus-5-5", tutorModel = "claude-opus-5-5",
            translateEffort = Effort.HIGH, tutorEffort = Effort.MAX, contextMessages = 2, backfillCount = 20,
        )
        val managed = mine.forAccess(AiAccess.Trial(now))
        assertEquals(ProviderType.OPENROUTER, managed.provider)
        assertEquals("google/gemma-4-31b-it", managed.translateModel)
        assertEquals("google/gemma-4-31b-it", managed.tutorModel)
        assertEquals(Effort.DEFAULT, managed.translateEffort)
        assertEquals(Effort.DEFAULT, managed.tutorEffort)
        assertEquals(8, managed.contextMessages)
        assertEquals(4, managed.backfillCount)
        // The user's own choices are untouched, for BYOK and for showing in Advanced.
        assertSame(mine, mine.forAccess(AiAccess.Byok))
        assertSame(mine, mine.forAccess(AiAccess.None))
    }

    @Test
    fun allowanceIsCountedFromTheLedgerOnTopOfTheLastReading() {
        val b = AllowanceBaseline(allowanceUsd = 4.5, remainingUsd = 4.5, at = now)
        assertEquals(0.0, b.usedFraction(0.0), 1e-9)
        assertEquals(0.5, b.usedFraction(2.25), 1e-9)
        assertEquals(1.0, b.usedFraction(9.0), 1e-9)
        assertEquals(0.9, AllowanceBaseline(0.5, 0.1, now).usedFraction(0.05), 1e-9)
        assertEquals(85, AllowanceView(0.857, null).percent)
        assertTrue(AllowanceView(0.8, null).nearlyUsedUp)
        assertFalse(AllowanceView(0.79, null).nearlyUsedUp)
    }

    @Test
    fun workerErrorsMapToWhatTheAppShows() {
        fun code(error: String, reason: String? = null, state: String? = null) =
            (AccessErrorCode.resultFor(error, reason, state) as AccessResult.Failed).error.code
        assertEquals(AccessErrorCode.TRIAL_ALREADY_USED, code("trial_already_used"))
        assertEquals(AccessErrorCode.DEVICE_NOT_SUPPORTED, code("integrity_failed", reason = "device_integrity"))
        assertEquals(AccessErrorCode.NOT_FROM_PLAY, code("integrity_failed", reason = "app_not_recognized"))
        assertEquals(AccessErrorCode.NOT_FROM_PLAY, code("integrity_failed", reason = "unlicensed"))
        assertEquals(AccessErrorCode.SERVICE, code("integrity_failed", reason = "token_expired"))
        assertEquals(AccessErrorCode.PAYMENT_PROBLEM, code("purchase_not_active", state = "on_hold"))
        assertEquals(AccessErrorCode.PAYMENT_PENDING, code("purchase_not_active", state = "pending"))
        assertEquals(AccessErrorCode.NO_PURCHASE, code("purchase_invalid", reason = "token_unknown"))
        assertEquals(AccessErrorCode.BUSY, code("busy"))
        assertEquals(AccessErrorCode.SERVICE, code("upstream_error"))
        assertEquals(AccessErrorCode.SERVICE, code("something_new"))

        val full = AccessErrorCode.resultFor("trial_capacity_reached", retryAfterSeconds = 3600) as AccessResult.Failed
        assertEquals(AccessErrorCode.TRIALS_FULL, full.error.code)
        assertEquals(3_600_000L, full.error.retryAfterMs)
        assertEquals(AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED), AccessErrorCode.resultFor("purchase_not_active", state = "refunded"))
        assertNull((AccessErrorCode.resultFor("busy") as AccessResult.Failed).error.retryAfterMs)
    }
}
