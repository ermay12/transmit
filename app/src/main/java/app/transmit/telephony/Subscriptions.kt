package app.transmit.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

class Subscriptions(private val context: Context) {
    private val subscriptionManager get() = context.getSystemService(SubscriptionManager::class.java)

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun defaultSmsSubId(): Int {
        val id = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return id
        return activeSubscriptions().firstOrNull()?.subscriptionId ?: SubscriptionManager.getDefaultSubscriptionId()
    }

    fun smsManager(subId: Int): SmsManager {
        val base = context.getSystemService(SmsManager::class.java)
        return if (subId >= 0) base.createForSubscriptionId(subId) else base
    }

    @SuppressLint("MissingPermission")
    fun activeSubscriptions(): List<SubscriptionInfo> {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return runCatching { subscriptionManager.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())
    }

    fun label(subId: Int): String? =
        activeSubscriptions().firstOrNull { it.subscriptionId == subId }?.let { "${it.displayName} (SIM ${it.simSlotIndex + 1})" }

    private val prefs by lazy { context.getSharedPreferences("subscriptions", Context.MODE_PRIVATE) }

    @Volatile private var ownCache: Pair<Long, Set<String>>? = null

    /** Our own numbers, used to drop ourselves from group MMS recipient lists. */
    fun ownNumbers(): Set<String> {
        ownCache?.let { (at, numbers) -> if (System.currentTimeMillis() - at < 60_000) return numbers }
        val numbers = queryOwnNumbers() + prefs.getStringSet(KEY_LEARNED, emptySet()).orEmpty()
        ownCache = System.currentTimeMillis() to numbers
        return numbers
    }

    /** Remembers our number when the carrier reveals it (the sole "To" of a 1:1 MMS). */
    fun learnOwnNumber(number: String) {
        if (number.isBlank() || ownNumbers().any { PhoneNumbers.same(it, number) }) return
        prefs.edit().putStringSet(KEY_LEARNED, prefs.getStringSet(KEY_LEARNED, emptySet()).orEmpty() + number).apply()
        ownCache = null
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun queryOwnNumbers(): Set<String> {
        val numbers = mutableSetOf<String>()
        for (info in activeSubscriptions()) {
            val n = runCatching {
                if (Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_PHONE_NUMBERS)) {
                    subscriptionManager.getPhoneNumber(info.subscriptionId)
                } else {
                    @Suppress("DEPRECATION")
                    info.number
                }
            }.getOrNull()
            if (!n.isNullOrBlank()) numbers += n
        }
        return numbers
    }

    /** Carrier MMS size limit in bytes (defaults to a conservative 300 KB). */
    fun mmsMaxBytes(subId: Int): Int = runCatching {
        smsManager(subId).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 300 * 1024)
    }.getOrDefault(300 * 1024).coerceIn(100 * 1024, 5 * 1024 * 1024)

    private companion object {
        const val KEY_LEARNED = "learned_own_numbers"
    }
}
