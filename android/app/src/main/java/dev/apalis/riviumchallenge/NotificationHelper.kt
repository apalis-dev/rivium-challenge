package dev.apalis.riviumchallenge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    private const val CHANNEL_SYNC = "reader_sync"
    private const val CHANNEL_ARTICLES = "reader_articles"
    const val SYNC_NOTIFICATION_ID = 1001
    private const val PROBLEM_NOTIFICATION_ID = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "Downloading links", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while shared links are being downloaded" },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ARTICLES, "Saved articles", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When an article is ready to read offline, or couldn't be saved" },
        )
    }

    fun syncNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setContentTitle("Pocket Reader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openApp(context, null))
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun updateSync(context: Context, text: String) = notify(context, SYNC_NOTIFICATION_ID, syncNotification(context, text))

    fun articleFinished(context: Context, article: Article) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ARTICLES)
            .setAutoCancel(true)
            .setGroup("articles")
        if (article.status == "SAVED" && article.isVideo) {
            builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(article.title)
                .setContentText("Saved to watch later · ${article.metaLine()}")
                .setContentIntent(openApp(context, article.id))
                .addAction(0, "Watch now", watch(context, article))
        } else if (article.status == "SAVED") {
            val prefix = if (article.kind == "article") "Saved for offline" else "Saved"
            builder
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle(article.title)
                .setContentText("$prefix · ${article.metaLine()}")
                .setContentIntent(openApp(context, article.id))
                .addAction(0, "Read now", openApp(context, article.id))
        } else {
            builder
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Couldn't save ${article.site.ifBlank { "link" }}")
                .setContentText(article.lastError)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${article.url}\n${article.lastError}"))
                .setContentIntent(openApp(context, null))
                .addAction(0, "Retry", ReaderService.retryIntent(context, article.id))
        }
        notify(context, article.id.hashCode(), builder.build())
    }

    fun problem(context: Context, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ARTICLES)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context, null))
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .build()
        notify(context, PROBLEM_NOTIFICATION_ID, notification)
    }

    fun cancelArticle(context: Context, articleId: String) =
        NotificationManagerCompat.from(context).cancel(articleId.hashCode())

    private fun openApp(context: Context, articleId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        articleId?.let { intent.putExtra(MainActivity.EXTRA_ARTICLE_ID, it) }
        return PendingIntent.getActivity(
            context,
            articleId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun watch(context: Context, article: Article): PendingIntent = PendingIntent.getActivity(
        context,
        article.id.hashCode(),
        Intent(Intent.ACTION_VIEW, Uri.parse(article.url)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(context: Context, id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(context).notify(id, notification)
    }
}
