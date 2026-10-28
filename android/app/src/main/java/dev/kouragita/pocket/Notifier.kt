package dev.kouragita.pocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Shows the result of a job, with actions so it is not a dead end. */
object Notifier {
    private const val CHANNEL = "jobs"

    const val ACTION_COPY = "dev.kouragita.pocket.COPY_HASH"
    const val ACTION_SHARE = "dev.kouragita.pocket.SHARE_RESULT"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"

    fun done(context: Context, job: PocketClient.Job) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jobs", NotificationManager.IMPORTANCE_DEFAULT),
        )

        val failed = job.status == "failed"
        val title = (if (failed) "Job failed" else "Job done") + " · " + job.text.take(30)
        val body = job.outcome?.summary ?: job.error ?: job.status
        val code = job.id.hashCode()

        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(open(context, code))
            .addAction(0, "Copy result", action(context, code * 31 + 1, ACTION_COPY, title, body))
            .addAction(0, "Share result", action(context, code * 31 + 2, ACTION_SHARE, title, body))
            .build()

        manager.notify(code, notification)
    }

    private fun open(context: Context, code: Int): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun action(
        context: Context,
        code: Int,
        action: String,
        title: String,
        body: String,
    ): PendingIntent {
        val intent = Intent(context, ResultActions::class.java).apply {
            this.action = action
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
        }
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
