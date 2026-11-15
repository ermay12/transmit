package app.transmit.telephony

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import app.transmit.app
import app.transmit.telephony.pdu.MmsValues
import app.transmit.telephony.pdu.PduParser
import kotlinx.coroutines.launch
import java.io.File

private fun Intent.subscriptionId(): Int {
    val id = getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
    return if (id >= 0) id else getIntExtra("subscription", -1)
}

/** Runs [block] off the main thread while keeping the broadcast alive. */
private fun BroadcastReceiver.async(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    context.app.container.scope.launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e("Receivers", "broadcast handling failed", e)
        } finally {
            pending.finish()
        }
    }
}

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (messages.isEmpty()) return
        val address = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: return
        val body = messages.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
        val sentAt = messages[0].timestampMillis
        val subId = intent.subscriptionId()
        async(context) { context.app.container.incoming.onSms(address, body, sentAt, subId) }
    }
}

class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = intent.subscriptionId()
        async(context) { context.app.container.incoming.onWapPush(data, subId) }
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data?.buildUpon()?.fragment(null)?.build() ?: return
        val part = intent.getIntExtra(EXTRA_PART, 0)
        val count = intent.getIntExtra(EXTRA_COUNT, 1)
        val result = resultCode
        async(context) {
            val resolver = context.contentResolver
            when (intent.action) {
                ACTION_SENT -> {
                    if (result == Activity.RESULT_OK) {
                        if (part == count - 1) {
                            val values = ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT) }
                            resolver.update(uri, values, "${Telephony.Sms.TYPE}=${Telephony.Sms.MESSAGE_TYPE_OUTBOX}", null)
                        }
                    } else {
                        Log.w(TAG, "SMS part $part failed: result=$result error=${intent.getIntExtra("errorCode", 0)}")
                        val values = ContentValues().apply {
                            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                            put(Telephony.Sms.ERROR_CODE, intent.getIntExtra("errorCode", result))
                        }
                        resolver.update(uri, values, null, null)
                        context.app.container.notifier.notifySendFailed(uri)
                    }
                }
                ACTION_DELIVERED -> {
                    val pdu = intent.getByteArrayExtra("pdu") ?: return@async
                    val format = intent.getStringExtra("format")
                    val status = SmsMessage.createFromPdu(pdu, format)?.status ?: return@async
                    val mapped = when {
                        status == 0 -> Telephony.Sms.STATUS_COMPLETE
                        status >= 0x40 -> Telephony.Sms.STATUS_FAILED
                        else -> Telephony.Sms.STATUS_PENDING
                    }
                    if (mapped != Telephony.Sms.STATUS_COMPLETE || part == count - 1) {
                        val values = ContentValues().apply { put(Telephony.Sms.STATUS, mapped) }
                        resolver.update(uri, values, null, null)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "SmsStatus"
        const val ACTION_SENT = "app.transmit.SMS_SENT"
        const val ACTION_DELIVERED = "app.transmit.SMS_DELIVERED"
        const val EXTRA_PART = "part"
        const val EXTRA_COUNT = "count"
    }
}

class MmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = resultCode
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File)
        async(context) {
            val container = context.app.container
            when (intent.action) {
                ACTION_SENT -> {
                    val uri = intent.data ?: return@async
                    val conf = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)?.let { runCatching { PduParser.parse(it) }.getOrNull() }
                    val ok = result == Activity.RESULT_OK && (conf?.responseStatus ?: MmsValues.RESPONSE_STATUS_OK) == MmsValues.RESPONSE_STATUS_OK
                    if (!ok) Log.w(TAG, "MMS send failed: result=$result status=${conf?.responseStatus} ${conf?.responseText}")
                    container.mmsStore.setBox(
                        uri,
                        if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED,
                        messageId = conf?.messageId,
                        responseStatus = conf?.responseStatus,
                    )
                    if (!ok) container.notifier.notifySendFailed(uri)
                }
                ACTION_DOWNLOADED -> {
                    val uri = intent.data ?: return@async
                    container.incoming.onMmsDownloaded(
                        notificationUri = uri,
                        file = file,
                        subId = intent.getIntExtra(EXTRA_SUB_ID, -1),
                        transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID),
                        success = result == Activity.RESULT_OK,
                    )
                }
            }
            file?.delete()
        }
    }

    companion object {
        private const val TAG = "MmsStatus"
        const val ACTION_SENT = "app.transmit.MMS_SENT"
        const val ACTION_DOWNLOADED = "app.transmit.MMS_DOWNLOADED"
        const val ACTION_ACK_SENT = "app.transmit.MMS_ACK_SENT"
        const val EXTRA_FILE = "file"
        const val EXTRA_SUB_ID = "sub_id"
        const val EXTRA_TRANSACTION_ID = "tr_id"
    }
}

/** "Respond via message" from the incoming-call screen. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val recipients = intent?.data?.schemeSpecificPart?.substringBefore('?')
            ?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (text.isNullOrBlank() || recipients.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        app.container.scope.launch {
            runCatching { app.container.sender.send(recipients, text) }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
