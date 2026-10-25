package io.github.kdbrian.rivapln.todoradar.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import io.github.kdbrian.rivapln.todoradar.api.Scan
import io.github.kdbrian.rivapln.todoradar.engine.EngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process (and the embedded Rust engine) alive while there is work: active scans or
 * downloads waiting for their default deletion. Turns engine state into notifications and runs
 * the notification actions (Pause/Resume = Rivium stop/start, Keep, Delete now).
 */
class EngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: ScanNotifications
    private val engine get() = (application as RadarApplication).engine
    private val app get() = application as RadarApplication

    /** Scans we saw running, so we notice when they finish. */
    private val watching = mutableSetOf<Long>()
    private var lastSnapshot: List<Scan> = emptyList()

    override fun onCreate() {
        super.onCreate()
        notifications = ScanNotifications(this)
        ServiceCompat.startForeground(
            this, ScanNotifications.ENGINE_ID, notifications.engine(EngineState.Starting, emptyList()),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch { engine.start() }
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_SCAN_ID, -1L) ?: -1L
        scope.launch {
            try {
                when (intent?.action) {
                    ACTION_PAUSE -> engine.stop(paused = true)
                    ACTION_RESUME -> engine.start()
                    ACTION_KEEP -> {
                        ensureRunning()
                        notifications.result(engine.api.keepFiles(id))
                    }
                    ACTION_DELETE -> {
                        ensureRunning()
                        notifications.result(engine.api.deleteFiles(id))
                    }
                    ACTION_SCAN_ZIP -> {
                        ensureRunning()
                        val scan = engine.api.startZipScan(intent.getStringExtra(EXTRA_PATH).orEmpty())
                        watching += scan.id
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "action ${intent?.action} failed", e)
            }
        }
        return START_STICKY
    }

    private suspend fun ensureRunning() {
        if (engine.state.value != EngineState.Running) engine.start()
    }

    private suspend fun loop() {
        var idleSince = 0L
        while (true) {
            val state = engine.state.value
            if (state == EngineState.Running) {
                runCatching { engine.api.scans() }.onSuccess { scans ->
                    lastSnapshot = scans
                    for (scan in scans) {
                        if (scan.isActive) watching += scan.id
                        else if (watching.remove(scan.id)) notifications.result(scan)
                        else if (scan.filesDeleted) notifications.markDeleted(scan)
                    }
                }
            }
            val active = lastSnapshot.filter { it.isActive }
            notifications.updateEngine(state, active)

            // Stop when nothing needs the engine and nobody is looking at the app.
            val busy = active.isNotEmpty() || lastSnapshot.any { it.cleanupPending } || state == EngineState.Paused
            if (!busy && app.visibleActivities == 0 && state !is EngineState.Starting) {
                if (idleSince == 0L) idleSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - idleSince > IDLE_STOP_MS) {
                    engine.stop()
                    stopSelf()
                    return
                }
            } else {
                idleSince = 0L
            }
            delay(if (active.isNotEmpty()) 1_000 else 2_500)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TodoRadar"
        private const val IDLE_STOP_MS = 30_000L
        const val ACTION_PAUSE = "io.github.kdbrian.rivapln.todoradar.PAUSE"
        const val ACTION_RESUME = "io.github.kdbrian.rivapln.todoradar.RESUME"
        const val ACTION_KEEP = "io.github.kdbrian.rivapln.todoradar.KEEP"
        const val ACTION_DELETE = "io.github.kdbrian.rivapln.todoradar.DELETE"
        const val ACTION_SCAN_ZIP = "io.github.kdbrian.rivapln.todoradar.SCAN_ZIP"
        const val EXTRA_SCAN_ID = "scan_id"
        const val EXTRA_PATH = "path"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }

        fun scanZip(context: Context, path: String) {
            context.startForegroundService(
                Intent(context, EngineService::class.java).setAction(ACTION_SCAN_ZIP).putExtra(EXTRA_PATH, path),
            )
        }
    }
}
