package app.transmit.telephony

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import app.transmit.telephony.pdu.MmsMessageType
import app.transmit.telephony.pdu.PduParser
import java.io.File

/** Stores incoming SMS/MMS in the platform store and hands new messages to [onNewMessage]. */
class IncomingHandler(
    private val context: Context,
    private val repo: MessagingRepository,
    private val mmsStore: MmsStore,
    private val downloader: MmsDownloader,
    private val subs: Subscriptions,
    private val onNewMessage: suspend (threadId: Long, key: String) -> Unit,
) {
    private val resolver get() = context.contentResolver

    suspend fun onSms(address: String, body: String, sentAt: Long, subId: Int) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, sentAt)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            put(Telephony.Sms.THREAD_ID, repo.threadIdFor(listOf(address)))
        }
        val uri = resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) ?: return
        val threadId = values.getAsLong(Telephony.Sms.THREAD_ID)
        repo.invalidate(threadId)
        onNewMessage(threadId, Message.smsKey(ContentUris.parseId(uri)))
    }

    suspend fun onWapPush(data: ByteArray, subId: Int) {
        val pdu = runCatching { PduParser.parse(data) }.getOrElse {
            Log.w(TAG, "Unparseable WAP push", it)
            return
        }
        if (pdu.messageType != MmsMessageType.NOTIFICATION_IND) {
            Log.i(TAG, "Ignoring MMS PDU type ${pdu.messageType}")
            return
        }
        val location = pdu.contentLocation ?: return
        if (mmsStore.findByLocation(location) != null) return // duplicate push
        val from = pdu.from ?: "Unknown"
        val threadId = repo.threadIdFor(listOf(from))
        val uri = mmsStore.insertNotification(pdu, threadId, subId)
        repo.invalidate(threadId)
        downloader.download(uri, location, pdu.transactionId, subId)
    }

    /** Re-requests an MMS whose automatic download failed. */
    fun retryDownload(message: Message) {
        val location = message.mmsContentLocation ?: return
        downloader.download(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, message.id), location, null, message.subId)
    }

    suspend fun onMmsDownloaded(notificationUri: Uri, file: File?, subId: Int, transactionId: String?, success: Boolean) {
        val bytes = file?.takeIf { success && it.exists() && it.length() > 0 }?.readBytes()
        val pdu = bytes?.let { runCatching { PduParser.parse(it) }.onFailure { e -> Log.w(TAG, "Bad retrieve.conf", e) }.getOrNull() }
        if (pdu == null || pdu.messageType != MmsMessageType.RETRIEVE_CONF) {
            Log.w(TAG, "MMS download failed (success=$success)")
            mmsStore.setDownloadFailed(notificationUri)
            return
        }
        val from = pdu.from
        if (from != null && pdu.to.size == 1 && pdu.cc.isEmpty()) subs.learnOwnNumber(pdu.to.single())
        val own = subs.ownNumbers()
        val others = (listOfNotNull(from) + pdu.to + pdu.cc)
            .filter { a -> a.isNotBlank() && a != "insert-address-token" && own.none { PhoneNumbers.same(it, a) } }
            .distinctBy { PhoneNumbers.matchKey(it) }
        val threadId = when {
            others.isNotEmpty() -> repo.threadIdFor(others)
            else -> mmsStore.threadIdOf(notificationUri)
        }
        val uri = mmsStore.insertRetrieved(pdu, threadId, subId, System.currentTimeMillis() / 1000)
        mmsStore.delete(notificationUri)
        (pdu.transactionId ?: transactionId)?.let { downloader.acknowledge(it, subId) }
        repo.invalidate(threadId)
        onNewMessage(threadId, Message.mmsKey(ContentUris.parseId(uri)))
    }

    private companion object {
        const val TAG = "Incoming"
    }
}
