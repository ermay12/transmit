package app.transmit.gm

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.transmit.app
import app.transmit.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process, and so the connection to Google Messages, alive while Transmit is paired. */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val container = app.container
        ServiceCompat.startForeground(
            this, Notifier.CONNECTION_ID, container.notifier.connectionNotification("Connecting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        scope.launch {
            container.bridge.state.collect { state ->
                val text = when (state) {
                    is BridgeState.Connected -> when {
                        state.trouble != null -> state.trouble
                        !state.phoneResponding -> "Your phone isn't responding"
                        state.inactive -> "Messages for Web is open somewhere else"
                        else -> "Receiving messages"
                    }
                    BridgeState.Connecting -> "Connecting…"
                    is BridgeState.Pairing -> "Pairing…"
                    BridgeState.Unpaired, is BridgeState.LoggedOut -> {
                        stopSelf()
                        return@collect
                    }
                }
                runCatching { NotificationManagerCompat.from(this@BridgeService).notify(Notifier.CONNECTION_ID, container.notifier.connectionNotification(text)) }
            }
        }
        container.bridge.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        app.container.bridge.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Starts the service if paired. Android refuses from the background; the next app open retries. */
        fun start(context: Context) {
            if (!context.app.container.bridge.isPaired) return
            try {
                context.startForegroundService(Intent(context, BridgeService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w("BridgeService", "Not allowed to start from the background", e)
                context.app.container.bridge.start()
            }
        }
    }
}
