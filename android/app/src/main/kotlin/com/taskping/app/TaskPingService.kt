package com.taskping.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and so the Rivium-managed Rust worker) alive while
 * long jobs are pending, even after the app's UI is closed. It only watches progress; the work
 * itself happens in the Apalis worker. Stops itself when every long job has finished.
 */
class TaskPingService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var loop: Job? = null

  override fun onBind(intent: Intent?) = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Notifications.ensureChannels(this)
    ServiceCompat.startForeground(
      this,
      Notifications.ONGOING_ID,
      Notifications.ongoing(this, "Starting worker…", 0, 0),
      if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )
    if (loop?.isActive != true) loop = scope.launch { watch() }
    // If Android kills the process, recreate the service so pending jobs resume.
    return START_STICKY
  }

  private suspend fun watch() {
    // 0 = started, 1 = already running (e.g. started by the activity).
    val code = TaskPingBridge.nativeStart(arrayOf("--root", filesDir.absolutePath))
    Log.i(TAG, "service nativeStart=$code")
    while (true) {
      val jobs = LongJobs.refresh(this)
      for (job in jobs.filter { it.finished && !it.notified }) {
        Notifications.done(this, "Long task finished", "Sum of ${job.base}×1 … ${job.base}×${job.upto} = ${job.result}")
        LongJobs.update(this, job.copy(notified = true))
      }
      val active = jobs.filterNot { it.finished }
      if (active.isEmpty()) break
      val job = active.last() // oldest unfinished job
      val text = "${job.base} × ${job.step} = ${job.base * job.step}  ·  step ${job.step}/${job.upto}"
      NotificationManagerCompat.from(this)
        .notify(Notifications.ONGOING_ID, Notifications.ongoing(this, text, job.step, job.upto))
      delay(1000)
    }
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    scope.cancel()
    // No jobs left and no UI open: stop the Rivium host cleanly.
    if (!MainActivity.visible) Thread { TaskPingBridge.nativeStop(2000) }.start()
    super.onDestroy()
  }

  private companion object {
    const val TAG = "TaskPing"
  }
}
