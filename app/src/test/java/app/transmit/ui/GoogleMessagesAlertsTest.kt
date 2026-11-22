package app.transmit.ui

import app.transmit.ui.GoogleMessagesAlerts.offerTip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home offers the double-notifications tip once, after Transmit's first alert, and never when it would mislead. */
class GoogleMessagesAlertsTest {
    @Test
    fun offeredAfterTheFirstAlert() {
        assertTrue(offerTip(alertedBeforeVisit = true, settled = true, tipSeen = false, alertsOn = true, paired = true, demo = false, installed = true))
    }

    /**
     * Not until Home has been on screen a moment, in a visit that began after the alert. Opening a chat from the
     * notification draws Home first, and a text that alerts while Home is open is about to be tapped.
     */
    @Test
    fun notWhileHomeIsOnlyPassingThrough() {
        assertFalse(offerTip(alertedBeforeVisit = true, settled = false, tipSeen = false, alertsOn = true, paired = true, demo = false, installed = true))
        assertFalse(offerTip(alertedBeforeVisit = false, settled = true, tipSeen = false, alertsOn = true, paired = true, demo = false, installed = true))
    }

    @Test
    fun notBeforeAnyAlertNorOnceSeen() {
        assertFalse(offerTip(alertedBeforeVisit = false, settled = true, tipSeen = false, alertsOn = true, paired = true, demo = false, installed = true))
        assertFalse(offerTip(alertedBeforeVisit = true, settled = true, tipSeen = true, alertsOn = true, paired = true, demo = false, installed = true))
    }

    /** With Transmit's own alerts off, turning off Google Messages' would leave the user hearing about nothing. */
    @Test
    fun notWhileTransmitsAlertsAreOff() {
        assertFalse(offerTip(alertedBeforeVisit = true, settled = true, tipSeen = false, alertsOn = false, paired = true, demo = false, installed = true))
    }

    @Test
    fun notUnpairedInTheDemoOrWithoutGoogleMessages() {
        assertFalse(offerTip(alertedBeforeVisit = true, settled = true, tipSeen = false, alertsOn = true, paired = false, demo = false, installed = true))
        assertFalse(offerTip(alertedBeforeVisit = true, settled = true, tipSeen = false, alertsOn = true, paired = true, demo = true, installed = true))
        assertFalse(offerTip(alertedBeforeVisit = true, settled = true, tipSeen = false, alertsOn = true, paired = true, demo = false, installed = false))
    }
}
