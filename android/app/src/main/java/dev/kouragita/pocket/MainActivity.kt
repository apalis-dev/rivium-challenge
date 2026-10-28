package dev.kouragita.pocket

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.view.View
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
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
        // The app draws its own header; the platform title bar would cover it.
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(buildUi())
        askForNotifications()

        Host.start(this) { result ->
            runOnUiThread {
                status.text = when (result) {
                    is Host.Result.Ready -> result.version
                    is Host.Result.Failed -> "host failed (${result.code}): ${result.message}"
                }
                refresh()
                // The listener is up now, so reconciliation can actually read.
                reconcilePublished()
            }
        }

        sharedText(intent)?.let(::offerActions)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText(intent)?.let(::offerActions)
    }

    override fun onResume() {
        super.onResume()
        polling = true
        handler.post(poll)
        reconcilePublished()
    }

    /**
     * A download that finished while the app was dead is still in app storage.
     * Only safe to run once the host is serving, so call it from readiness too.
     */
    private fun reconcilePublished(attempt: Int = 0) {
        io.execute {
            if (!PocketClient.isReady() && attempt < 5) {
                // On a cold start the listener can still be binding; try again shortly.
                handler.postDelayed({ reconcilePublished(attempt + 1) }, 1_000)
                return@execute
            }
            PocketClient.list()
                .filter { it.status == "done" }
                .forEach { job ->
                    val isCard = job.raw?.optString("kind") == "card"
                    val name = when {
                        isCard -> cardName(job.raw!!, job.id)
                        job.savedFile != null -> File(job.savedFile).name
                        else -> return@forEach
                    }
                    if (!alreadyPublished(name)) {
                        publishFinished(job)?.let { published ->
                            Notifier.done(applicationContext, job, published)
                        }
                    }
                }
        }
    }

    /** Our own inserts are visible to us, so this is enough to avoid duplicates. */
    private fun alreadyPublished(name: String): Boolean = try {
        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { it.count > 0 } ?: false
    } catch (_: Exception) {
        false
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

    /** A link can be archived; plain text can only be described. */
    private fun offerActions(text: String) {
        if (!text.contains("/status/")) {
            submit(text)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Save this post")
            .setItems(arrayOf("Download the video", "Save a card image", "Just describe the text")) { _, which ->
                when (which) {
                    0 -> submit(text, action = "video")
                    1 -> submit(text, action = "card")
                    else -> submit(text)
                }
            }
            .show()
    }

    /** The queue takes the text from a share, then reports back through a notification. */
    private fun submit(text: String, workMs: Long? = null, action: String? = null) {
        io.execute {
            val id = PocketClient.submit(text, workMs, action) ?: return@execute
            val job = PocketClient.awaitDone(id, timeoutMs = 180_000) ?: return@execute
            val published = publishFinished(job)
            Notifier.done(applicationContext, job, published)
            refresh()
        }
    }

    /** A finished job is either a downloaded video or a card we draw ourselves. */
    private fun publishFinished(job: PocketClient.Job): String? {
        val raw = job.raw
        if (raw != null && raw.optString("kind") == "card") {
            val name = cardName(raw, job.id)
            return if (alreadyPublished(name)) name else publishCard(raw, job.id)
        }
        val path = job.savedFile ?: return null
        val name = File(path).name
        return if (alreadyPublished(name)) name else publishToDownloads(path, "video/mp4")
    }

    private fun publishCard(raw: org.json.JSONObject, id: String): String? {
        val target = File(filesDir, "pocket/cards/${cardName(raw, id)}")
        val rendered = CardRenderer.render(raw, target) ?: return null
        return publishToDownloads(rendered.path, "image/png")
    }

    /** Name a card the way a person would look for it. */
    private fun cardName(raw: org.json.JSONObject, id: String): String {
        val postId = raw.optString("source").substringAfterLast('/').ifEmpty { id }
        return "${raw.optString("author").ifEmpty { "post" }}_${postId}_card.png"
    }

    /** MediaStore keeps the file visible in Files and Gallery, not buried in app data. */
    private fun publishToDownloads(path: String, mime: String): String? {
        return try {
        val source = File(path)
        if (!source.isFile) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Pocket")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out -> source.inputStream().use { input -> input.copyTo(out) } }
        }
        source.name
        } catch (_: Exception) {
            null
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
        val groups = current.groupBy { it.topics.firstOrNull() ?: "Unsorted" }
        groups.keys
            .sortedWith(compareBy({ it == "Unsorted" }, { it.lowercase() }))
            .forEach { topic ->
                jobs.addView(heading(topic, groups.getValue(topic).size))
                groups.getValue(topic).forEach { jobs.addView(row(it)) }
            }
    }

    /** Decode a saved thumbnail at list size rather than full resolution. */
    private fun thumbnail(path: String?): android.graphics.Bitmap? {
        if (path == null) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    /** Section heading: the context a person would look under. */
    private fun heading(topic: String, count: Int): TextView = TextView(this).apply {
        text = "${topic.replaceFirstChar { it.uppercase() }}  ·  $count"
        textSize = 13f
        letterSpacing = 0.08f
        setPadding(0, 28, 0, 10)
        alpha = 0.75f
    }

    private fun row(job: PocketClient.Job): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(0x14888888)
            }
            setPadding(36, 24, 36, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 16 }
            isClickable = true
            setOnClickListener { showDetail(job) }
        }

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        thumbnail(job.thumb)?.let { bitmap ->
            body.addView(
                ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (170 * resources.displayMetrics.density).toInt(),
                    ).apply { bottomMargin = 16 }
                },
            )
        }

        body.addView(chip(job))
        body.addView(TextView(this).apply {
            text = job.text.take(140)
            textSize = 15f
            setPadding(0, 6, 0, 6)
        })
        if (job.topics.isNotEmpty()) {
            body.addView(TextView(this).apply {
                text = job.topics.joinToString("   ") { "#$it" }
                textSize = 12f
                alpha = 0.65f
            })
        }
        job.detail.takeIf { it.isNotEmpty() }?.let { detail ->
            body.addView(TextView(this).apply {
                text = detail
                textSize = 13f
                alpha = 0.7f
            })
        }
        card.addView(body)
        return card
    }


    /** A tinted pill, so done, running, recovered and failed differ at a glance. */
    private fun chip(job: PocketClient.Job): TextView {
        val tone = when {
            job.status == "failed" -> 0xFFD9534F
            job.recovered -> 0xFFE0A030
            job.status == "running" -> 0xFF4A90D9
            job.status == "pending" -> 0xFF9E9E9E
            else -> 0xFF4CAF50
        }.toInt()

        return TextView(this).apply {
            text = job.statusLine
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(tone)
            setPadding(30, 10, 30, 10)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor((tone and 0x00FFFFFF) or 0x26000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun showDetail(job: PocketClient.Job) {
        val outcome = job.outcome
        val body = buildString {
            append(job.text)
            append("\n\n")
            append("status      ${job.statusLine}\n")
            append("attempts    ${job.attempts}\n")
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
        val message = SpannableString(body)
        job.outcome?.hash?.let { hash ->
            val start = body.indexOf(hash)
            if (start >= 0) {
                message.setSpan(
                    TypefaceSpan("monospace"),
                    start,
                    start + hash.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Job ${job.id.takeLast(6)}")
            .setMessage(message)
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
