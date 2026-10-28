package dev.kouragita.pocket

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var hint: TextView
    private lateinit var jobs: LinearLayout

    /** One worker thread serialises HTTP calls, so polls can never overlap. */
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocket-io").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            refresh()
            handler.postDelayed(this, 700)
        }
    }

    /** Rendering only on change keeps the window idle, so nothing flickers. */
    private var rendered: List<PocketClient.Job>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        askForNotifications()

        Host.start(this) { result ->
            runOnUiThread {
                status.text = when (result) {
                    is Host.Result.Ready -> result.version
                    is Host.Result.Failed -> "host failed (${result.code}): ${result.message}"
                }
                refresh()
            }
        }

        sharedText(intent)?.let { submit(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText(intent)?.let { submit(it) }
    }

    override fun onResume() {
        super.onResume()
        polling = true
        handler.post(poll)
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        status = TextView(this).apply { text = "starting…"; textSize = 14f }
        summary = TextView(this).apply { textSize = 18f; setPadding(0, 8, 0, 4) }
        hint = TextView(this).apply {
            text = "Share text from any app to add a job. Long-press or select text, " +
                "tap Share, then pick Pocket."
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        jobs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val samples = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 16)
            addView(
                Button(this@MainActivity).apply {
                    text = "Try a sample"
                    setOnClickListener { submit("The quick brown fox jumps over the lazy dog") }
                },
            )
            addView(
                Button(this@MainActivity).apply {
                    text = "Slow job (6s)"
                    setOnClickListener { submit("A slow job, to watch recovery", workMs = 6_000) }
                },
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(summary)
            addView(hint)
            addView(samples)
            addView(jobs)
        }
        return ScrollView(this).apply { addView(column) }
    }

    /** The queue takes the text from a share, then reports back through a notification. */
    private fun submit(text: String, workMs: Long? = null) {
        io.execute {
            val id = PocketClient.submit(text, workMs) ?: return@execute
            val job = PocketClient.awaitDone(id) ?: return@execute
            Notifier.done(applicationContext, job)
            refresh()
        }
    }

    private fun refresh() {
        io.execute {
            val current = PocketClient.list()
            runOnUiThread { render(current) }
        }
    }

    private fun render(current: List<PocketClient.Job>) {
        if (current == rendered) return
        rendered = current

        summary.text = when (current.size) {
            0 -> "Nothing queued yet"
            else -> {
                fun count(status: String) = current.count { it.status == status }
                val parts = ArrayList<String>()
                count("running").takeIf { it > 0 }?.let { parts += "$it working" }
                count("done").takeIf { it > 0 }?.let { parts += "$it done" }
                count("failed").takeIf { it > 0 }?.let { parts += "$it failed" }
                current.count { it.status == "pending" }
                    .takeIf { it > 0 }?.let { parts += "$it waiting" }
                parts.joinToString(" · ")
            }
        }
        hint.visibility = if (current.isEmpty()) LinearLayout.VISIBLE else LinearLayout.GONE

        jobs.removeAllViews()
        current.forEach { jobs.addView(row(it)) }
    }

    private fun row(job: PocketClient.Job): TextView = TextView(this).apply {
        text = buildString {
            append(job.statusLine)
            append("\n")
            append(job.text.take(80))
            val detail = job.detail
            if (detail.isNotEmpty()) append("\n$detail")
        }
        textSize = 14f
        setPadding(0, 16, 0, 16)
        setOnClickListener { showDetail(job) }
    }

    private fun showDetail(job: PocketClient.Job) {
        val outcome = job.outcome
        val body = buildString {
            append(job.text)
            append("\n\n")
            append("status      ${job.statusLine}\n")
            if (outcome != null) {
                append("characters  ${outcome.chars}\n")
                append("words       ${outcome.words}\n")
                append("distinct    ${outcome.uniqueWords}\n")
                append("hash        ${outcome.hash}\n")
                append("work time   ${outcome.elapsedMs} ms\n")
            }
            job.error?.let { append("error       $it\n") }
            append("queued      ${job.enqueuedAt}\n")
            job.doneAt?.let { append("finished    $it\n") }
        }
        AlertDialog.Builder(this)
            .setTitle("Job ${job.id.takeLast(6)}")
            .setMessage(body)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun sharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
