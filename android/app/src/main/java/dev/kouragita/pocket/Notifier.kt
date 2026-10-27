package dev.kouragita.pocket

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Shows the result of a job once the worker is done with it. */
object Notifier {
    private const val CHANNEL = "jobs"

    fun done(context: Context, job: PocketClient.Job) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jobs", NotificationManager.IMPORTANCE_DEFAULT),
        )

        val preview = job.text.take(40)
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Job done")
            .setContentText("$preview — ${job.summary ?: job.status}")
            .setAutoCancel(true)
            .build()

        manager.notify(job.id.hashCode(), notification)
    }
}
