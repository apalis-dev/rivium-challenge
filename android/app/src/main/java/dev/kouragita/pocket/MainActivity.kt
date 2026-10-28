package dev.kouragita.pocket

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var jobs: LinearLayout

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
                // Anything fetched before the listener existed came back empty.
                refresh()
            }
        }

        sharedText(intent)?.let(::submit)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText(intent)?.let(::submit)
    }

    private fun buildUi(): ScrollView {
        status = TextView(this).apply { text = "starting…"; textSize = 14f }
        jobs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(jobs)
        }
        return ScrollView(this).apply { addView(column) }
    }

    /** The queue takes the text from a share, then reports back through a notification. */
    private fun submit(text: String) {
        Thread {
            val id = PocketClient.submit(text) ?: return@Thread
            val job = PocketClient.awaitDone(id) ?: return@Thread
            Notifier.done(applicationContext, job)
            runOnUiThread { refresh() }
        }.start()
        runOnUiThread { refresh() }
    }

    private fun refresh() {
        Thread {
            val current = PocketClient.list()
            runOnUiThread { render(current) }
        }.start()
    }

    private fun render(current: List<PocketClient.Job>) {
        jobs.removeAllViews()
        if (current.isEmpty()) {
            jobs.addView(TextView(this).apply { text = "no jobs yet" })
            return
        }
        current.forEach { job ->
            jobs.addView(
                TextView(this).apply {
                    text = buildString {
                        append(job.status)
                        if (job.attempts > 1) append(" · attempt ${job.attempts}")
                        append("\n")
                        append(job.text.take(80))
                        job.summary?.let { append("\n$it") }
                    }
                    textSize = 14f
                    setPadding(0, 16, 0, 16)
                },
            )
        }
    }

    private fun sharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
