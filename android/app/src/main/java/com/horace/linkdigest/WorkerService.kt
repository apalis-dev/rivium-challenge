package com.horace.linkdigest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Keeps the Rivium-hosted Apalis worker alive. Rust runs the jobs; this service
 * starts/stops it and turns finished digests into notifications.
 */
class WorkerService : Service() {
    @Volatile private var running = false
    private lateinit var store: Store

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Worker", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Pinched links", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Pocketcrab is awake")
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running) {
            running = true
            thread(name = "rivium-start") {
                val code = Bridge.nativeStart(arrayOf("--root", store.root.path))
                Log.i(TAG, "nativeStart=$code status=${Bridge.statusName(Bridge.nativeStatus())} err=${Bridge.nativeLastError()}")
            }
            thread(name = "result-notifier") { notifyLoop() }
        }
        return START_STICKY
    }

    private fun notifyLoop() {
        val prefs = getSharedPreferences("state", MODE_PRIVATE)
        val nm = getSystemService(NotificationManager::class.java)
        while (running) {
            val last = prefs.getLong("lastNotified", 0)
            for (d in store.digests(last).sortedBy { it.id }) {
                // Tapping a result opens the page that was digested.
                val open = PendingIntent.getActivity(
                    this, d.id.toInt(), Intent(Intent.ACTION_VIEW, android.net.Uri.parse(d.url)),
                    PendingIntent.FLAG_IMMUTABLE
                )
                val n = Notification.Builder(this, CH_RESULT)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(d.title ?: "(no title)")
                    .setContentText("${d.url}  sha256:${d.sha?.take(10)}  ${d.bytes} B")
                    .setStyle(Notification.BigTextStyle().bigText("${d.url}\nsha256: ${d.sha}\n${d.bytes} bytes"))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                nm.notify(1000 + d.id.toInt(), n)
                prefs.edit().putLong("lastNotified", d.id).apply()
            }
            // Jobs that exhausted their retries: say so instead of failing silently.
            val told = prefs.getStringSet("failedNotified", emptySet())!!.toMutableSet()
            for (f in store.failed().filter { it.id !in told }) {
                val host = android.net.Uri.parse(f.url).host ?: f.url
                nm.notify(f.id.hashCode(), Notification.Builder(this, CH_RESULT)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Couldn't pinch $host")
                    .setContentText(f.error)
                    .setStyle(Notification.BigTextStyle().bigText(f.error))
                    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true)
                    .build())
                told += f.id
                prefs.edit().putStringSet("failedNotified", told).apply()
            }
            Thread.sleep(1000)
        }
    }

    override fun onDestroy() {
        running = false
        thread(name = "rivium-stop") { Log.i(TAG, "nativeStop=${Bridge.nativeStop(3000)}") }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "LinkDigest"
        const val CH_SERVICE = "service"
        const val CH_RESULT = "result"
    }
}
