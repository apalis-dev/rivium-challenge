package com.pocketshrink.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

class PocketBackendService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notifiedJobIds = mutableSetOf<String>()

    companion object {
        const val CHANNEL_ID_FOREGROUND = "pocket_worker_foreground"
        const val CHANNEL_ID_COMPLETED = "pocket_worker_completed"
        const val NOTIFICATION_ID_FOREGROUND = 1001

        fun start(context: Context) {
            val intent = Intent(context, PocketBackendService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PocketBackendService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID_FOREGROUND, buildForegroundNotification("Starting Rivium worker..."))

        val rootDir = try {
            File(applicationInfo.dataDir, "files/pocket-data").canonicalFile
        } catch (_: Exception) {
            File(filesDir, "pocket-data")
        }.apply { mkdirs() }

        // Ensure subdirectories exist and clear any stale lock
        try {
            File(rootDir, "logs/pocket-shrink").mkdirs()
            File(rootDir, "processed").mkdirs()
            File(rootDir, "logs/pocket-shrink/.rivium.lock").delete()
            File(rootDir, "logs/.rivium.lock").delete()
            File(rootDir, ".rivium.lock").delete()
        } catch (_: Exception) {}

        // Start Rivium embedded supervisor host
        serviceScope.launch {
            android.util.Log.d("PocketBackendService", "Starting Rivium with root: ${rootDir.absolutePath}")
            val code = Bridge.nativeStart(arrayOf("--root", rootDir.absolutePath))
            android.util.Log.d("PocketBackendService", "Rivium start returned code: $code")
            if (code == 0) {
                updateForegroundNotification("Rivium + Apalis worker is active (SQLite queue)")
                pollForCompletedJobs(rootDir.absolutePath)
            } else {
                val err = Bridge.nativeLastError() ?: "Unknown error"
                android.util.Log.e("PocketBackendService", "Rivium start error: $err")
                updateForegroundNotification("Rivium start error: $err")
            }
        }
    }

    private suspend fun pollForCompletedJobs(rootDir: String) {
        while (serviceScope.isActive) {
            delay(1500)
            try {
                val jsonStr = Bridge.nativeGetCompletedJobs(rootDir)
                if (jsonStr.isNotEmpty() && jsonStr != "[]") {
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val id = obj.getString("id")
                        if (!notifiedJobIds.contains(id)) {
                            notifiedJobIds.add(id)
                            val savedPct = obj.optInt("saved_percent", 0)
                            val origKb = obj.optLong("original_size_bytes", 0) / 1024
                            val procKb = obj.optLong("processed_size_bytes", 0) / 1024
                            val hash = obj.optString("sha256_hash", "").take(12)

                            showCompletionNotification(
                                title = "✅ Photo Processed & Sanitized!",
                                message = "Saved $savedPct% ($origKb KB → $procKb KB) • SHA-256: $hash...",
                                notificationId = id.hashCode()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)

            val fgChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "Pocket Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows that the PocketShrink Rust backend is active"
            }

            val completedChannel = NotificationChannel(
                CHANNEL_ID_COMPLETED,
                "Job Completion Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when background image jobs are completed"
            }

            mgr?.createNotificationChannel(fgChannel)
            mgr?.createNotificationChannel(completedChannel)
        }
    }

    private fun buildForegroundNotification(contentText: String) =
        NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle("PocketShrink Background Engine")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateForegroundNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID_FOREGROUND, buildForegroundNotification(text))
    }

    private fun showCompletionNotification(title: String, message: String, notificationId: Int) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COMPLETED)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        mgr.notify(notificationId, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        // Stop Rivium supervisor host cleanly
        try {
            Bridge.nativeStop(2000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
