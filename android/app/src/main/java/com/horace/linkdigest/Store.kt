package com.horace.linkdigest

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.UUID

data class Digest(val id: Long, val url: String, val title: String?, val sha: String?, val bytes: Long)

enum class State { WORKING, DONE, FAILED }

/** One row on screen: a finished digest, a job still in flight, or a job that gave up. */
data class Item(val key: String, val state: State, val url: String, val title: String?, val sha: String?,
                val bytes: Long, val error: String?, val attempts: Int)

data class FailedJob(val id: String, val url: String, val error: String)

/** Reads the SQLite file the Rust worker writes; hands new links over via <root>/inbox. */
class Store(context: Context) {
    val root = File(context.filesDir, "rivium")
    private val stateFile = File(root, "state.json")

    /** Finds the first http(s) link in arbitrary text (shared text, clipboard) and queues it. */
    fun submitText(text: String?): String? {
        val url = Regex("https?://[^\\s<>\"]+").find(text ?: "")?.value ?: return null
        submit(url)
        return url
    }

    fun submit(url: String) {
        val inbox = File(root, "inbox").apply { mkdirs() }
        val tmp = File(inbox, ".${UUID.randomUUID()}")
        tmp.writeText(url)
        // Rust ignores nothing it can read, so publish atomically; dotfile is renamed into place.
        tmp.renameTo(File(inbox, UUID.randomUUID().toString()))
    }

    /**
     * Everything the user queued, newest first, so a failure is never silent.
     * Read from state.json written by the Rust side; this app never opens jobs.db itself.
     */
    fun items(): List<Item> = try {
        val arr = JSONArray(stateFile.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Item(
                key = o.getString("key"),
                state = when (o.getString("state")) { "done" -> State.DONE; "failed" -> State.FAILED; else -> State.WORKING },
                url = o.optString("url"), title = o.optString("title").takeIf { o.has("title") && !o.isNull("title") },
                sha = o.optString("sha").takeIf { !o.isNull("sha") }, bytes = o.optLong("bytes"),
                error = o.optString("error").takeIf { !o.isNull("error") }, attempts = o.optInt("attempts")
            )
        }
    } catch (e: Exception) { emptyList() }

    fun digests(afterId: Long = 0): List<Digest> = items().filter { it.state == State.DONE }
        .map { Digest(it.key.substring(1).toLong(), it.url, it.title, it.sha, it.bytes) }
        .filter { it.id > afterId }

    fun failed(): List<FailedJob> = items().filter { it.state == State.FAILED }
        .map { FailedJob(it.key, it.url, it.error ?: "unknown error") }
}
