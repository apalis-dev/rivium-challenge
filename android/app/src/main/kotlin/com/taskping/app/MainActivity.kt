package com.taskping.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.taskping.app.ui.theme.TaskPingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TaskPing"
private const val BASE = 10L

private enum class Worker { Starting, Running, Failed }

/** One finished quick (Add) job as shown in the UI. */
private data class QuickRun(val a: Long, val b: Long, val result: String)

class MainActivity : ComponentActivity() {
  private var worker by mutableStateOf(Worker.Starting)
  private var quickRunning by mutableStateOf(false)
  private var quick by mutableStateOf<QuickRun?>(null)
  private var longJobs by mutableStateOf(emptyList<LongJob>())
  private var steps by mutableFloatStateOf(30f)
  private var nextA = 1L

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Notifications.ensureChannels(this)
    if (Build.VERSION.SDK_INT >= 33) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    // Rivium embedded host: starts the supervised Apalis worker service; root holds taskping.db.
    lifecycleScope.launch {
      val code = withContext(Dispatchers.IO) {
        TaskPingBridge.nativeStart(arrayOf("--root", filesDir.absolutePath))
      }
      // 0 = started, 1 = already running (e.g. kept alive by TaskPingService).
      worker = if (code == 0 || code == 1) Worker.Running else Worker.Failed
      Log.i(TAG, "nativeStart=$code status=${TaskPingBridge.nativeStatus()} ${TaskPingBridge.nativeLastError()}")
      // Reopened after the process died with long jobs unfinished: bring the service back.
      if (LongJobs.all(this@MainActivity).any { !it.finished }) startWatcher()
    }

    // While visible, refresh long-job progress from Rust once per second.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
          longJobs = withContext(Dispatchers.IO) { LongJobs.refresh(this@MainActivity) }
          delay(1000)
        }
      }
    }

    enableEdgeToEdge()
    setContent {
      TaskPingTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          TaskPingScreen(
            worker = worker,
            longJobs = longJobs,
            steps = steps,
            onStepsChange = { steps = it },
            onStartLong = ::startLongTask,
            quick = quick,
            quickRunning = quickRunning,
            onRunQuick = ::runQuickTask,
          )
        }
      }
    }
  }

  private fun startLongTask() {
    val upto = steps.toLong()
    lifecycleScope.launch {
      val id = withContext(Dispatchers.IO) {
        TaskPingBridge.nativeSubmitTable(filesDir.absolutePath, BASE, upto)
      }
      Log.i(TAG, "Table $BASE x 1..$upto -> $id")
      if (id.startsWith("error")) return@launch
      LongJobs.add(this@MainActivity, LongJob(id = id, base = BASE, upto = upto))
      longJobs = LongJobs.all(this@MainActivity)
      startWatcher()
    }
  }

  private fun startWatcher() = startForegroundService(Intent(this, TaskPingService::class.java))

  private fun runQuickTask() {
    val a = nextA++
    val b = 41L
    quickRunning = true
    lifecycleScope.launch {
      val result = withContext(Dispatchers.IO) {
        TaskPingBridge.nativeRunDemoJob(filesDir.absolutePath, a, b)
      }
      Log.i(TAG, "DemoJob $a + $b -> $result")
      quick = QuickRun(a, b, result)
      Notifications.done(this@MainActivity, "TaskPing job complete", "DemoJob $a + $b = $result")
      quickRunning = false
    }
  }

  override fun onStart() {
    super.onStart()
    visible = true
  }

  override fun onStop() {
    visible = false
    super.onStop()
  }

  override fun onDestroy() {
    // Closing the app stops the Rivium host only if no long job needs it;
    // otherwise TaskPingService keeps the worker alive and stops it when the jobs finish.
    if (isFinishing && LongJobs.all(this).all { it.finished }) {
      Thread { TaskPingBridge.nativeStop(2000) }.start()
    }
    super.onDestroy()
  }

  companion object {
    @Volatile
    var visible = false
  }
}

@Composable
private fun TaskPingScreen(
  worker: Worker,
  longJobs: List<LongJob>,
  steps: Float,
  onStepsChange: (Float) -> Unit,
  onStartLong: () -> Unit,
  quick: QuickRun?,
  quickRunning: Boolean,
  onRunQuick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val active = longJobs.lastOrNull { !it.finished }
  val finished = longJobs.filter { it.finished }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .safeDrawingPadding()
      .padding(horizontal = 24.dp, vertical = 16.dp),
  ) {
    // Header
    Text("TaskPing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(
      "Background job runner",
      style = MaterialTheme.typography.bodyMedium,
      color = colors.onSurfaceVariant,
    )

    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(top = 28.dp, bottom = 16.dp),
    ) {
      LongTaskCard(active = active, last = finished.firstOrNull(), steps = steps, onStepsChange = onStepsChange)
      Spacer(Modifier.height(16.dp))
      QuickTaskRow(quick = quick, running = quickRunning, enabled = worker == Worker.Running, onRun = onRunQuick)

      // Earlier long jobs
      val earlier = finished.drop(if (active == null) 1 else 0)
      if (earlier.isNotEmpty()) {
        Spacer(Modifier.height(28.dp))
        Text("Earlier long tasks", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        earlier.forEachIndexed { i, job ->
          if (i > 0) HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
          Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text("${job.base}×1 … ${job.base}×${job.upto}", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(job.result.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
          }
        }
      }
    }

    // Primary action
    Button(
      onClick = onStartLong,
      enabled = worker == Worker.Running && active == null,
      shape = RoundedCornerShape(20.dp),
      modifier = Modifier.fillMaxWidth().height(60.dp),
    ) {
      Text(
        if (active == null) "Start long task" else "Long task running…",
        style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}

@Composable
private fun LongTaskCard(active: LongJob?, last: LongJob?, steps: Float, onStepsChange: (Float) -> Unit) {
  val colors = MaterialTheme.colorScheme
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(28.dp))
      .background(colors.surfaceContainer)
      .padding(28.dp),
  ) {
    Column {
      AnimatedContent(
        targetState = active?.id ?: last?.id,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "long-task",
      ) { _ ->
        when {
          active != null -> Column {
            Text("Long task · running", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Spacer(Modifier.height(8.dp))
            Text(
              "${active.base} × ${active.step} = ${active.base * active.step}",
              fontSize = 40.sp,
              lineHeight = 44.sp,
              fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
              progress = { if (active.upto == 0L) 0f else active.step.toFloat() / active.upto },
              modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
              "Step ${active.step} of ${active.upto}  ·  ${duration(active.upto - active.step)} left",
              style = MaterialTheme.typography.bodyMedium,
              color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
              "You can close the app. The Rust worker keeps going and notifies you when it's done.",
              style = MaterialTheme.typography.bodySmall,
              color = colors.onSurfaceVariant,
            )
          }
          last != null -> Column {
            Text("Long task · finished", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(last.result.orEmpty(), fontSize = 64.sp, lineHeight = 68.sp, fontWeight = FontWeight.Light)
            Text(
              "Sum of ${last.base}×1 … ${last.base}×${last.upto}",
              style = MaterialTheme.typography.bodyLarge,
              color = colors.onSurfaceVariant,
            )
          }
          else -> Column {
            Text("Long task", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("—", fontSize = 64.sp, lineHeight = 68.sp, fontWeight = FontWeight.Light, color = colors.outline)
            Text("No long tasks yet", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
          }
        }
      }

      // Step picker, hidden while a job runs.
      if (active == null) {
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))
        val n = steps.toLong()
        Row(verticalAlignment = Alignment.Bottom) {
          Text("$BASE×1 … $BASE×$n", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.weight(1f))
          Text("~${duration(n)}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        Slider(value = steps, onValueChange = { onStepsChange(it.toInt().toFloat()) }, valueRange = 5f..300f)
        Text(
          "One step per second. Start it, close the app, come back later.",
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun QuickTaskRow(quick: QuickRun?, running: Boolean, enabled: Boolean, onRun: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(20.dp))
      .background(colors.surfaceContainerLow)
      .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text("Quick task", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
      Text(
        quick?.let { "${it.a} + ${it.b} = ${it.result}" } ?: "Instant job, ~1 s",
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    FilledTonalButton(onClick = onRun, enabled = enabled && !running) {
      if (running) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
      }
      Text("Run")
    }
  }
}

/** 75 -> "1:15", 30 -> "30 s". */
private fun duration(seconds: Long): String =
  if (seconds < 60) "$seconds s" else "%d:%02d min".format(seconds / 60, seconds % 60)
