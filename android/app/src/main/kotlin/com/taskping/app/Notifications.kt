package com.taskping.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** The two notification kinds: a silent ongoing one for the foreground service, and "job done". */
object Notifications {
  const val ONGOING_ID = 1
  private const val DONE_CHANNEL = "taskping_jobs"
  private const val RUNNING_CHANNEL = "taskping_running"

  fun ensureChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(DONE_CHANNEL, "Finished jobs", NotificationManager.IMPORTANCE_DEFAULT)
    )
    manager.createNotificationChannel(
      NotificationChannel(RUNNING_CHANNEL, "Running jobs", NotificationManager.IMPORTANCE_LOW)
    )
  }

  /** Tapping any TaskPing notification reopens the app. */
  private fun openApp(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context, 0,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )

  fun ongoing(context: Context, text: String, step: Long, total: Long): Notification =
    NotificationCompat.Builder(context, RUNNING_CHANNEL)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle("TaskPing is running a job")
      .setContentText(text)
      .setProgress(total.toInt(), step.toInt(), total == 0L)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openApp(context))
      .build()

  fun done(context: Context, title: String, text: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val notification = NotificationCompat.Builder(context, DONE_CHANNEL)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(title)
      .setContentText(text)
      .setAutoCancel(true)
      .setContentIntent(openApp(context))
      .build()
    NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
  }
}
