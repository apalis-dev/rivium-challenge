package io.github.kdbrian.rivapln.todoradar.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.kdbrian.rivapln.todoradar.api.Scan
import io.github.kdbrian.rivapln.todoradar.engine.EngineState

/**
 * All notifications. The engine notification doubles as the live scan progress: on Android 16+
 * it uses Notification.ProgressStyle (stage segments + a point per TODO milestone) and asks to
 * be promoted as a Live Update; older versions get a classic progress bar. Actions map to
 * engine operations: Pause/Resume are Rivium stop/start.
 */
class ScanNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val glow = Color.parseColor("#1F8A4C")
    private val stageColors = intArrayOf(
        Color.parseColor("#7356D4"), // resolve (lavender)
        Color.parseColor("#2B7DB5"), // download (sky)
        Color.parseColor("#1F8A4C"), // scan (mint)
        Color.parseColor("#C0651E"), // diff (peach)
    )
    private val deletedShown = mutableSetOf<Long>()

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ENGINE, context.getString(R.string.channel_engine), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun updateEngine(state: EngineState, active: List<Scan>) = manager.notify(ENGINE_ID, engine(state, active))

    fun engine(state: EngineState, active: List<Scan>): Notification {
        val scan = active.firstOrNull()
        val paused = state == EngineState.Paused
        val title = when {
            scan != null && paused -> "Paused · ${scan.title}"
            scan != null -> scan.title
            paused -> "Engine paused"
            state is EngineState.Failed -> "Engine error"
            state == EngineState.Starting -> "Starting the Rust engine…"
            else -> "TODO Radar is ready"
        }
        val text = when {
            scan == null && paused -> "Queued work resumes when you tap Resume."
            scan == null -> if (state is EngineState.Failed) state.message else "Rivium engine running"
            else -> detail(scan) + if (active.size > 1) " · +${active.size - 1} queued" else ""
        }
        val action = if (paused) {
            NotificationCompat.Action(0, "Resume", service(EngineService.ACTION_RESUME))
        } else {
            NotificationCompat.Action(0, "Pause", service(EngineService.ACTION_PAUSE))
        }
        val percent = ((scan?.progress ?: 0.0) * 100).toInt()

        if (scan != null && Build.VERSION.SDK_INT >= 36) return liveUpdate(scan, title, text, percent, paused, action)

        return NotificationCompat.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setColor(glow)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open(scan?.id))
            .apply { if (scan != null) setProgress(100, percent, scan.stage == "resolve") }
            .apply { if (state == EngineState.Running || paused) addAction(action) }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Android 16 progress-centric notification: four stage segments, TODO milestones as points. */
    @SuppressLint("NewApi")
    private fun liveUpdate(
        scan: Scan,
        title: String,
        text: String,
        percent: Int,
        paused: Boolean,
        action: NotificationCompat.Action,
    ): Notification {
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(
                listOf(5, 45, 45, 5).mapIndexed { i, length ->
                    Notification.ProgressStyle.Segment(length).setColor(stageColors[i])
                },
            )
            .setProgress(percent)
        if (scan.todoCount > 0) {
            // One point per 25% of the scan stage that already produced TODOs.
            style.setProgressPoints(
                (1..((scan.progress.coerceIn(0.5, 0.95) - 0.5) / 0.1125).toInt()).map {
                    Notification.ProgressStyle.Point(50 + it * 11).setColor(Color.parseColor("#C0651E"))
                },
            )
        }
        val platformAction = Notification.Action.Builder(null, action.title, action.actionIntent).build()
        return Notification.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setColor(glow)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(scan.id))
            .setStyle(style)
            .setShortCriticalText(if (paused) "Paused" else "$percent%")
            .setRequestPromotedOngoing(true)
            .addAction(platformAction)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Result notification with the default-delete prompt: Open, Keep, Delete now. */
    fun result(scan: Scan) {
        if (scan.isActive) return
        val id = RESULT_BASE + scan.id.toInt()
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setColor(glow)
            .setContentIntent(open(scan.id))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (scan.isFailed) {
            builder.setContentTitle("Scan failed · ${scan.title}").setContentText(scan.error ?: "Unknown error")
        } else {
            val summary = buildString {
                append("${scan.todoCount} TODOs")
                if (scan.hasBaseline) append(" · +${scan.newCount} new · −${scan.resolvedCount} resolved")
            }
            val files = when {
                scan.filesDeleted -> "Download deleted."
                scan.keepFiles -> "Download kept on this device."
                else -> "The download will be deleted automatically in ${minutesLeft(scan)} min."
            }
            builder.setContentTitle("${scan.title} @ ${scan.shortSha ?: "zip"}")
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n$files"))
                .addAction(0, "Open", open(scan.id))
            if (!scan.filesDeleted) {
                if (!scan.keepFiles) builder.addAction(0, "Keep", service(EngineService.ACTION_KEEP, scan.id))
                builder.addAction(0, "Delete now", service(EngineService.ACTION_DELETE, scan.id))
            }
        }
        manager.notify(id, builder.build())
    }

    /** Once the delayed cleanup job ran, refresh the result notification (only if still shown). */
    fun markDeleted(scan: Scan) {
        if (!deletedShown.add(scan.id)) return
        val id = RESULT_BASE + scan.id.toInt()
        if (manager.activeNotifications.any { it.id == id }) result(scan)
    }

    private fun minutesLeft(scan: Scan): Long {
        val left = (scan.cleanupAt ?: 0) - System.currentTimeMillis() / 1000
        return (left / 60).coerceAtLeast(0) + 1
    }

    private fun open(scanId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { scanId?.let { putExtra(MainActivity.EXTRA_SCAN_ID, it) } }
        return PendingIntent.getActivity(
            context, (scanId ?: 0).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun service(action: String, scanId: Long = 0): PendingIntent {
        val intent = Intent(context, EngineService::class.java).setAction(action).putExtra(EngineService.EXTRA_SCAN_ID, scanId)
        return PendingIntent.getForegroundService(
            context, (action.hashCode() * 31 + scanId).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun detail(scan: Scan): String = when (scan.stage) {
        "resolve" -> "Resolving the latest commit…"
        "download" -> "Downloading · ${scan.bytesDone / 1024} KB"
        "scan" -> "Scanning ${scan.entriesDone}/${scan.entriesTotal ?: 0} files · ${scan.todoCount} TODOs"
        "diff" -> "Comparing with the last scan…"
        else -> scan.stage
    } + (scan.error?.let { " · $it" } ?: "")

    companion object {
        const val CHANNEL_ENGINE = "engine"
        const val CHANNEL_RESULTS = "results"
        const val ENGINE_ID = 1
        const val RESULT_BASE = 1_000
    }
}
