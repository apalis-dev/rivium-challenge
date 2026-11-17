package com.horace.linkdigest

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home screen widget: how many pages are watched and how many changed since you last looked. */
class WatchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WatchWidget::class.java))
            if (ids.isEmpty()) return
            val watches = Store(context).snapshot().watches
            val seen = context.getSharedPreferences("state", Context.MODE_PRIVATE).getLong("seenAt", 0)
            val changed = watches.count { (it.changedAt ?: 0) > seen }
            val views = RemoteViews(context.packageName, R.layout.widget_watch).apply {
                setTextViewText(R.id.widget_count, if (watches.isEmpty()) "No pages yet" else "${watches.size} watching")
                setTextViewText(
                    R.id.widget_changed,
                    when {
                        watches.isEmpty() -> "Share a link and choose Watch"
                        changed == 0 -> "nothing new"
                        else -> "$changed changed"
                    }
                )
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                )
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
