package app.transmit.ai

import app.transmit.access.AiAccess
import app.transmit.access.EndReason
import app.transmit.access.Tier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dollar budgets are BYOK's; managed modes answer only to the allowance; no AI set up blocks everything. */
class AiGateTest {
    private val over = BudgetStatus.GlobalExceeded(spent = 12.0, limit = 10.0)
    private val trial = AiAccess.Trial(Long.MAX_VALUE)
    private val standard = AiAccess.Subscribed(Tier.STANDARD, null)

    private fun check(access: AiAccess, override: Boolean = false, usedUp: Boolean = false, budget: BudgetStatus = BudgetStatus.Ok) = runBlocking {
        AiGate.check(access, override, { usedUp }, { budget })
    }

    @Test
    fun byokBudgetsBlockUnlessAllowedOnce() {
        val block = check(AiAccess.Byok, budget = over)
        assertEquals(AiBlock.Budget(over), block)
        assertTrue(block!!.canAllowOnce)
        assertNull(check(AiAccess.Byok, override = true, budget = over))
        assertNull(check(AiAccess.Byok, budget = BudgetStatus.Ok))
    }

    @Test
    fun managedModesIgnoreDollarBudgets() {
        var budgetAsked = false
        val block = runBlocking { AiGate.check(standard, false, { false }, { budgetAsked = true; over }) }
        assertNull(block)
        assertFalse(budgetAsked)
        assertNull(check(trial, budget = over))
    }

    @Test
    fun managedAllowanceCantBeAllowedOnce() {
        val block = check(standard, override = true, usedUp = true)
        assertEquals(AiBlock.AllowanceUsedUp(standard), block)
        assertFalse(block!!.canAllowOnce)
        assertEquals("You've used this month's AI.", block.message)
        assertEquals("Your free week has ended.", check(trial, usedUp = true)!!.message)
    }

    @Test
    fun noAiOrAnEndedPlanBlocksWithoutAskingAnything() {
        val none = runBlocking { AiGate.check(AiAccess.None, true, { error("not asked") }, { error("not asked") }) }
        assertEquals(AiBlock.NotSetUp, none)
        assertEquals(AiBlock.Ended(EndReason.TRIAL_EXPIRED), check(AiAccess.Ended(EndReason.TRIAL_EXPIRED), override = true))
    }

    @Test
    fun outsideByokErrorsNeverMentionKeysModelsOrSettings() {
        val errors = listOf(
            AiException("Add your OpenRouter API key in Settings.", AiException.Kind.NO_KEY),
            AiException("Choose a translation model in Settings.", AiException.Kind.NO_MODEL),
            AiException("OpenRouter: Model blocked by guardrail", AiException.Kind.BAD_REQUEST, httpStatus = 404),
            AiException("OpenRouter: Internal error", AiException.Kind.OTHER, httpStatus = 502),
            AiException("The model returned no translation.", AiException.Kind.OTHER),
            AiException("Network error talking to OpenRouter.", AiException.Kind.NETWORK),
            AiException("The model declined to process this message.", AiException.Kind.REFUSAL),
        )
        for (access in listOf(trial, standard, AiAccess.None, AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED))) {
            for (e in errors) {
                val text = AiMessages.describe(e, access)
                for (word in listOf("key", "model", "Settings", "OpenRouter", "$")) assertFalse("$text mentions $word", text.contains(word, ignoreCase = true))
            }
        }
        assertEquals(AiMessages.SERVICE_PROBLEM, AiMessages.describe(errors[3], standard))
        // BYOK users see what the provider said.
        assertEquals("Add your OpenRouter API key in Settings.", AiMessages.describe(errors[0], AiAccess.Byok))
    }
}
