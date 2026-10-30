package dev.apalis.riviumchallenge

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (as a foreground service) only while links are being
 * downloaded, so a share completes even after the user goes back to the other app.
 * Stops itself once the queue is empty.
 */
class ReaderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null
    @Volatile private var lastStartId = 0

    companion object {
        private const val ACTION_SAVE = "dev.apalis.riviumchallenge.SAVE"
        private const val ACTION_RETRY = "dev.apalis.riviumchallenge.RETRY"
        private const val ACTION_SYNC = "dev.apalis.riviumchallenge.SYNC"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_ID = "id"

        fun save(context: Context, sharedText: String) =
            launch(context, Intent(context, ReaderService::class.java).setAction(ACTION_SAVE).putExtra(EXTRA_TEXT, sharedText))

        fun sync(context: Context) =
            launch(context, Intent(context, ReaderService::class.java).setAction(ACTION_SYNC))

        /** For the "Retry" button on a failure notification. */
        fun retryIntent(context: Context, articleId: String): PendingIntent {
            val intent = Intent(context, ReaderService::class.java).setAction(ACTION_RETRY).putExtra(EXTRA_ID, articleId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, articleId.hashCode(), intent, flags)
            } else {
                PendingIntent.getService(context, articleId.hashCode(), intent, flags)
            }
        }

        private fun launch(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ServiceCompat.startForeground(
            this,
            NotificationHelper.SYNC_NOTIFICATION_ID,
            NotificationHelper.syncNotification(this, "Saving to your reading list…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        scope.launch { handle(intent) }
        if (monitor?.isActive != true) monitor = scope.launch { watchQueue() }
        return START_NOT_STICKY
    }

    private suspend fun handle(intent: Intent?) {
        if (!ReaderHost.start(this)) {
            NotificationHelper.problem(this, "Reader couldn't start", RiviumBridge.nativeLastError() ?: "unknown error")
            return
        }
        when (intent?.action) {
            ACTION_SAVE -> {
                val result = ReaderHost.save(intent.getStringExtra(EXTRA_TEXT).orEmpty())
                when {
                    !result.ok -> NotificationHelper.problem(this, "Nothing saved", result.error ?: "unknown error")
                    result.duplicate -> NotificationHelper.problem(this, "Already in your reading list", "")
                }
            }
            ACTION_RETRY -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                NotificationHelper.cancelArticle(this, id)
                ReaderHost.retry(id)
            }
            ACTION_SYNC -> ReaderHost.retryFailed()
        }
    }

    private suspend fun watchQueue() {
        var idleTicks = 0
        while (true) {
            delay(1000)
            val counts = runCatching { ReaderHost.counts() }.getOrDefault(Counts())
            if (counts.pending > 0) {
                idleTicks = 0
                val text = if (counts.pending == 1) "Downloading 1 link…" else "Downloading ${counts.pending} links…"
                NotificationHelper.updateSync(this, text)
            } else if (++idleTicks >= 3) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                // Only stops if no newer share arrived in the meantime.
                stopSelfResult(lastStartId)
                return
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
