package dev.apalis.riviumchallenge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Article(
    val id: String,
    val url: String,
    val status: String,
    /** "article", "video" or "link" (nothing readable to show offline). */
    val kind: String,
    val title: String,
    val site: String,
    val author: String,
    val description: String,
    val body: String?,
    val readingMinutes: Int,
    val durationSeconds: Int,
    val attempts: Int,
    val lastError: String,
    val readAt: Long?,
) {
    val isUnread get() = status == "SAVED" && readAt == null
    val isVideo get() = kind == "video"

    /** "YouTube · Zack D. Films · 0:32 video" or "BBC Sport · 1 min read". */
    fun metaLine(): String {
        val length = when {
            isVideo && durationSeconds > 0 -> "%d:%02d video".format(durationSeconds / 60, durationSeconds % 60)
            isVideo -> "video"
            readingMinutes > 0 -> "$readingMinutes min read"
            else -> "link"
        }
        return listOf(site, author, length).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }
}

data class Counts(val unread: Int = 0, val pending: Int = 0, val failed: Int = 0)

data class SaveResult(val ok: Boolean, val duplicate: Boolean, val error: String?)

/**
 * Owns the embedded Rivium host for this process. The host is started once and left
 * running; jobs live in SQLite, so if Android kills the process they resume next launch.
 */
object ReaderHost {
    private const val TAG = "ReaderHost"
    private const val STATUS_RUNNING = 2
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pumpStarted = false

    /** Blocks until the host is running (it opens the database first). Call off the main thread. */
    @Synchronized
    fun ensureStarted(context: Context): Boolean {
        if (RiviumBridge.nativeStatus() == STATUS_RUNNING) return true
        val root = context.applicationContext.filesDir.absolutePath
        val code = RiviumBridge.nativeStart(arrayOf("--root", root, "--set", "log.logcat.filter=info"))
        val ok = code == 0 || code == 1
        if (!ok) {
            Log.e(TAG, "nativeStart failed ($code): ${RiviumBridge.nativeLastError()}")
            return false
        }
        if (!pumpStarted) {
            pumpStarted = true
            startNotificationPump(context.applicationContext)
        }
        return true
    }

    suspend fun start(context: Context) = withContext(Dispatchers.IO) { ensureStarted(context) }

    // Announces finished articles. The "notified" flag lives in SQLite, so an article
    // finished just before a kill still gets its notification after the next launch.
    private fun startNotificationPump(context: Context) = scope.launch {
        while (true) {
            runCatching {
                val finished = parseArticles(RiviumBridge.nativeTakeFinished())
                finished.forEach { NotificationHelper.articleFinished(context, it) }
            }.onFailure { Log.w(TAG, "notification pump", it) }
            delay(1500)
        }
    }

    suspend fun save(text: String): SaveResult = withContext(Dispatchers.IO) {
        val json = JSONObject(RiviumBridge.nativeSaveShared(text))
        SaveResult(
            ok = json.optBoolean("ok"),
            duplicate = json.optBoolean("duplicate"),
            error = json.optString("error").ifBlank { null },
        )
    }

    suspend fun list(limit: Int = 200): List<Article> = withContext(Dispatchers.IO) {
        parseArticles(RiviumBridge.nativeListArticles(limit))
    }

    suspend fun open(id: String): Article? = withContext(Dispatchers.IO) {
        RiviumBridge.nativeOpenArticle(id).takeIf { it.isNotBlank() }?.let { parseArticle(JSONObject(it)) }
    }

    suspend fun retry(id: String) = withContext(Dispatchers.IO) { RiviumBridge.nativeRetryArticle(id) }

    suspend fun retryFailed() = withContext(Dispatchers.IO) { RiviumBridge.nativeRetryFailed() }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { RiviumBridge.nativeDeleteArticle(id) }

    suspend fun counts(): Counts = withContext(Dispatchers.IO) {
        val json = JSONObject(RiviumBridge.nativeCounts())
        Counts(json.optInt("unread"), json.optInt("pending"), json.optInt("failed"))
    }

    private fun parseArticles(json: String): List<Article> {
        val arr = JSONArray(json.ifBlank { "[]" })
        return List(arr.length()) { parseArticle(arr.getJSONObject(it)) }
    }

    private fun parseArticle(o: JSONObject) = Article(
        id = o.getString("id"),
        url = o.getString("url"),
        status = o.getString("status"),
        kind = o.optString("kind", "article"),
        title = o.optString("title"),
        site = o.optString("site"),
        author = o.optString("author"),
        description = o.optString("description"),
        body = if (o.has("body")) o.optString("body") else null,
        readingMinutes = o.optInt("reading_minutes"),
        durationSeconds = o.optInt("duration_seconds"),
        attempts = o.optInt("attempts"),
        lastError = o.optString("last_error"),
        readAt = if (o.isNull("read_at")) null else o.optLong("read_at"),
    )
}
