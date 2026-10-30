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
    private var lastWidget: Pair<Int, Int>? = null
    private lateinit var store: Store

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Worker", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Pinched links", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // "Stop watching" button on a change notification.
        if (intent?.action == ACTION_UNWATCH) {
            intent.getStringExtra(EXTRA_URL)?.let { store.unwatch(it) }
            getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
        }
        val n = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_crab)
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
                    .setSmallIcon(R.drawable.ic_stat_crab)
                    .setContentTitle(d.title ?: "(no title)")
                    .setContentText("${d.url}  sha256:${d.sha?.take(10)}  ${d.bytes} B")
                    .setStyle(Notification.BigTextStyle().bigText("${d.url}\nsha256: ${d.sha}\n${d.bytes} bytes"))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                nm.notify(1000 + d.id.toInt(), n)
                prefs.edit().putLong("lastNotified", d.id).apply()
            }
            // Watched pages whose text changed: show what changed, with a button to stop watching.
            val snap = store.snapshot()
            val lastChange = prefs.getLong("lastChangeNotified", 0)
            for (c in snap.changes.filter { it.id > lastChange }.sortedBy { it.id }) {
                val host = android.net.Uri.parse(c.url).host ?: c.url
                val excerpt = "${c.before} \u2192 ${c.after}"
                val nid = 5000 + c.id.toInt()
                val stop = PendingIntent.getService(
                    this, nid,
                    Intent(this, WorkerService::class.java).setAction(ACTION_UNWATCH)
                        .putExtra(EXTRA_URL, c.url).putExtra(EXTRA_NOTIFICATION, nid),
                    PendingIntent.FLAG_IMMUTABLE
                )
                nm.notify(nid, Notification.Builder(this, CH_RESULT)
                    .setSmallIcon(R.drawable.ic_stat_crab)
                    .setContentTitle("Page changed: ${c.title ?: host}")
                    .setContentText(excerpt)
                    .setStyle(Notification.BigTextStyle().bigText("$host\n$excerpt"))
                    .setContentIntent(PendingIntent.getActivity(this, nid, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(c.url)), PendingIntent.FLAG_IMMUTABLE))
                    .addAction(Notification.Action.Builder(null, "Stop watching", stop).build())
                    .setAutoCancel(true)
                    .build())
                prefs.edit().putLong("lastChangeNotified", c.id).apply()
            }
            // Keep the home screen widget in step with what we know.
            val widgetState = snap.watches.size to snap.watches.count { (it.changedAt ?: 0) > prefs.getLong("seenAt", 0) }
            if (widgetState != lastWidget) { lastWidget = widgetState; WatchWidget.refresh(this) }
            // Jobs that exhausted their retries: say so instead of failing silently.
            val told = prefs.getStringSet("failedNotified", emptySet())!!.toMutableSet()
            for (f in store.failed().filter { it.id !in told }) {
                val host = android.net.Uri.parse(f.url).host ?: f.url
                nm.notify(f.id.hashCode(), Notification.Builder(this, CH_RESULT)
                    .setSmallIcon(R.drawable.ic_stat_crab)
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
        const val ACTION_UNWATCH = "com.horace.linkdigest.UNWATCH"
        const val EXTRA_URL = "url"
        const val EXTRA_NOTIFICATION = "notification"
    }
}
