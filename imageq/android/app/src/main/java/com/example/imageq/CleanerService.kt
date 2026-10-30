package com.example.imageq

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.concurrent.thread

object Notif {
    const val CH_RUN = "running"
    const val CH_DONE = "done"

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Engine", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Cleaned photos", NotificationManager.IMPORTANCE_DEFAULT))
    }

    @SuppressLint("MissingPermission")
    fun done(ctx: Context, rows: List<Cleaned>) {
        val gps = rows.count { it.hadGps }
        val title = if (rows.size == 1) "Photo cleaned" else "${rows.size} photos cleaned"
        val text = if (gps > 0) "Location removed from $gps. Tap to share the clean copy."
        else "No location data found. Tap to share the clean copy."
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(2, n)
        } catch (e: SecurityException) { /* notification permission denied */ }
    }
}

class CleanerService : Service() {
    @Volatile private var alive = false
    private var poller: Thread? = null
    private var lastRemaining = -1

    @SuppressLint("MissingPermission")
    private fun updateOngoing(remaining: Int) {
        val b = NotificationCompat.Builder(this, Notif.CH_RUN)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(if (remaining > 0) "Cleaning $remaining photo(s)…" else "imageq is ready")
            .setContentText(if (remaining > 0) "Removing location and camera info" else "Share photos to clean them")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (remaining > 0) b.setProgress(0, 0, true)
        try { NotificationManagerCompat.from(this).notify(1, b.build()) } catch (_: SecurityException) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notif.channels(this)
        val ongoing = NotificationCompat.Builder(this, Notif.CH_RUN)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("imageq is ready")
            .setContentText("Share photos to clean them")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, ongoing)
        }

        if (!alive) {
            alive = true
            poller = thread {
                val code = Engine.ensureStarted(this)
                android.util.Log.i("imageq", "engine start=$code")
                val prefs = getSharedPreferences("imageq", MODE_PRIVATE)
                while (alive) {
                    val last = prefs.getLong("lastRow", 0)
                    val p = Queue.progress(this)
                    val remaining = p.waiting + p.running
                    if (remaining != lastRemaining) {
                        lastRemaining = remaining
                        updateOngoing(remaining)
                    }
                    val fresh = Store.load(this, last)
                    if (fresh.isNotEmpty()) {
                        prefs.edit().putLong("lastRow", fresh.maxOf { it.rowId }).apply()
                        Notif.done(this, fresh)
                    }
                    try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        poller?.interrupt()
        thread { Bridge.nativeStop(5000) }
        super.onDestroy()
    }
}
