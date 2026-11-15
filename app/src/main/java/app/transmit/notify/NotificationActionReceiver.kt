package app.transmit.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import app.transmit.app
import app.transmit.smart.SmartActions
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD, -1)
        if (threadId < 0) return
        val container = context.app.container
        if (intent.action == ACTION_COPY_CODE) {
            // The clipboard write happens right away, on the main thread; marking read can follow.
            SmartActions.copyCode(context, intent.getStringExtra(EXTRA_CODE) ?: return)
        }
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
        val pending = goAsync()
        container.scope.launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> if (!reply.isNullOrBlank()) {
                        val recipients = container.messaging.recipientsForThread(threadId)
                        if (recipients.isNotEmpty()) container.sender.send(recipients, reply)
                        container.messaging.markThreadRead(threadId)
                        container.notifier.cancel(threadId)
                    }
                    ACTION_MARK_READ, ACTION_COPY_CODE -> {
                        container.messaging.markThreadRead(threadId)
                        container.notifier.cancel(threadId)
                    }
                }
            } catch (e: Exception) {
                Log.e("NotificationAction", "action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "app.transmit.REPLY"
        const val ACTION_MARK_READ = "app.transmit.MARK_READ"
        const val ACTION_COPY_CODE = "app.transmit.COPY_CODE"
        const val EXTRA_THREAD = "thread_id"
        const val KEY_REPLY = "reply"
        const val EXTRA_CODE = "code"
    }
}
