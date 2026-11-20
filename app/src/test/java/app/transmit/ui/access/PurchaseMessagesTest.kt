package app.transmit.ui.access

import app.transmit.R
import app.transmit.TestStrings
import app.transmit.access.AccessError
import app.transmit.access.AccessErrorCode
import app.transmit.access.LaunchResult
import app.transmit.access.PlayBilling
import app.transmit.access.PurchaseUi
import app.transmit.access.PurchaseUpdate
import com.android.billingclient.api.BillingClient.BillingResponseCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the plans screen says when Google Play or the Worker didn't do what was asked: always something calm. */
class PurchaseMessagesTest {
    /** An upgrade or a switch to Transmit. */
    private val calm = "Google Play couldn't make that change. Please try again later."
    /** A new subscription: Play's refusal (a declined card, say) charged nothing. */
    private val declined = "Your purchase didn't go through. You haven't been charged."

    @Test
    fun everyErrorFromPlaysSheetGetsACalmSentence() {
        val codes = BillingResponseCode::class.java.fields.map { it.getInt(null) }
        val quiet = setOf(BillingResponseCode.OK, BillingResponseCode.USER_CANCELED, BillingResponseCode.ITEM_ALREADY_OWNED)
        for (code in codes - quiet) {
            val update = PlayBilling.updateFor(code, "debug") { emptyList() } as PurchaseUpdate.Failed
            // Purchases turns Play's failure into Failed(null): Play, not the Worker. One sentence for a change of plan,
            // one for a new subscription, whatever the code.
            assertEquals("code ${update.responseCode}", calm, TestStrings.text(AccessText.purchaseProblem(PurchaseUi.Failed(null, planChange = true))))
            assertEquals("code ${update.responseCode}", declined, TestStrings.text(AccessText.purchaseProblem(PurchaseUi.Failed(null, planChange = false))))
        }
        assertEquals(calm, TestStrings.all["billing_failed"])
        assertEquals(declined, TestStrings.all["purchase_declined"])
    }

    @Test
    fun aSheetThatCouldntOpenSaysWhy() {
        assertNull(AccessText.launchProblem(LaunchResult.LAUNCHED))
        assertEquals(declined, TestStrings.text(AccessText.launchProblem(LaunchResult.FAILED)!!))
        assertEquals(calm, TestStrings.text(AccessText.launchProblem(LaunchResult.FAILED, planChange = true)!!))
        assertEquals("Google Play isn't available right now. Try again later.", TestStrings.text(AccessText.launchProblem(LaunchResult.UNAVAILABLE)!!))
        assertEquals("You already have this plan. Manage it in Google Play.", TestStrings.text(AccessText.launchProblem(LaunchResult.ALREADY_OWNED)!!))
    }

    @Test
    fun theWorkersFailuresKeepTheirOwnWords() {
        val later = AccessError(AccessErrorCode.SERVICE, retryable = true)
        assertEquals(R.string.purchase_retrying, AccessText.purchaseProblem(PurchaseUi.Failed(later)))
        assertEquals(R.string.purchase_retrying, AccessText.purchaseProblem(PurchaseUi.Failed(later, planChange = true)))
        val never = AccessError(AccessErrorCode.SERVICE)
        assertEquals(R.string.purchase_failed, AccessText.purchaseProblem(PurchaseUi.Failed(never)))
        assertEquals(R.string.billing_failed, AccessText.purchaseProblem(PurchaseUi.Failed(never, planChange = true)))
    }
}
