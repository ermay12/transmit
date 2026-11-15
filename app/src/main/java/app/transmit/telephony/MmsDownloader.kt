package app.transmit.telephony

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import app.transmit.telephony.pdu.PduComposer
import java.io.File

class MmsDownloader(private val context: Context, private val subs: Subscriptions) {

    fun download(notificationUri: Uri, contentLocation: String, transactionId: String?, subId: Int) {
        try {
            val file = File(context.cacheDir, "mms/dl-${System.nanoTime()}.pdu")
            file.parentFile?.mkdirs()
            file.createNewFile()
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            Sender.grantToMmsService(context, contentUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val intent = Intent(context, MmsStatusReceiver::class.java)
                .setAction(MmsStatusReceiver.ACTION_DOWNLOADED)
                .setData(notificationUri)
                .putExtra(MmsStatusReceiver.EXTRA_FILE, file.absolutePath)
                .putExtra(MmsStatusReceiver.EXTRA_SUB_ID, subId)
                .putExtra(MmsStatusReceiver.EXTRA_TRANSACTION_ID, transactionId)
            val pi = PendingIntent.getBroadcast(context, Sender.nextRequestCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            subs.smsManager(subId).downloadMultimediaMessage(context, contentLocation, contentUri, null, pi)
        } catch (e: Exception) {
            Log.e(TAG, "MMS download request failed", e)
        }
    }

    /** Tells the MMSC we retrieved the message so it does not re-send the notification. */
    fun acknowledge(transactionId: String, subId: Int) {
        try {
            val file = File(context.cacheDir, "mms/ack-${System.nanoTime()}.pdu")
            file.parentFile?.mkdirs()
            file.writeBytes(PduComposer.notifyResponse(transactionId))
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            Sender.grantToMmsService(context, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val intent = Intent(context, MmsStatusReceiver::class.java)
                .setAction(MmsStatusReceiver.ACTION_ACK_SENT)
                .putExtra(MmsStatusReceiver.EXTRA_FILE, file.absolutePath)
            val pi = PendingIntent.getBroadcast(context, Sender.nextRequestCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            subs.smsManager(subId).sendMultimediaMessage(context, contentUri, null, null, pi)
        } catch (e: Exception) {
            Log.w(TAG, "NotifyResp failed", e)
        }
    }

    private companion object {
        const val TAG = "MmsDownloader"
    }
}
