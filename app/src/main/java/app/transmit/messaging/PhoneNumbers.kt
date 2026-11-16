package app.transmit.messaging

import android.telephony.PhoneNumberUtils
import java.util.Locale

object PhoneNumbers {
    /** Digits (plus a leading '+') used to compare numbers; alphanumeric senders pass through. */
    fun normalize(address: String): String {
        val trimmed = address.trim()
        if (trimmed.contains('@') || trimmed.any { it.isLetter() }) return trimmed.lowercase(Locale.ROOT)
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /** A loose key that matches "+1 (555) 123-4567" with "5551234567". */
    fun matchKey(address: String): String {
        val n = normalize(address)
        if (n.any { it.isLetter() } || n.contains('@')) return n
        val digits = n.trimStart('+')
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun same(a: String, b: String): Boolean = matchKey(a) == matchKey(b)

    /** Short codes and alphanumeric senders: businesses, 2FA, alerts. */
    fun isShortCodeOrAlpha(address: String): Boolean {
        val n = normalize(address)
        if (n.any { it.isLetter() }) return true
        return n.trimStart('+').length in 1..6
    }

    fun format(address: String, countryIso: String = Locale.getDefault().country): String {
        if (address.any { it.isLetter() } || address.contains('@')) return address
        return PhoneNumberUtils.formatNumber(address, countryIso.ifBlank { "US" }) ?: address
    }
}
