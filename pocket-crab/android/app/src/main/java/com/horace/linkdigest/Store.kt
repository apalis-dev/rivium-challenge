package com.horace.linkdigest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Digest(val id: Long, val url: String, val title: String?, val sha: String?, val bytes: Long)

enum class State { WORKING, DONE, FAILED }

/** One row on screen: a finished digest, a job still in flight, or a job that gave up. */
data class Item(val key: String, val state: State, val url: String, val title: String?, val sha: String?,
                val bytes: Long, val error: String?, val attempts: Int)

data class FailedJob(val id: String, val url: String, val error: String)

/** A page that is re-checked on a schedule. `changedAt` is epoch seconds of the last detected change. */
data class Watch(val url: String, val title: String?, val everySecs: Long, val checked: Long?,
                 val changes: Int, val changedAt: Long?, val error: String?)

/** One detected change, with a short excerpt of the text before and after. */
data class Change(val id: Long, val url: String, val title: String?, val before: String?, val after: String?, val at: Long)

/** Everything the Rust side reports in state.json. */
data class Snapshot(val items: List<Item>, val watches: List<Watch>, val changes: List<Change>,
                    val signedIn: Set<String> = emptySet())

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.lng(key: String): Long? = if (isNull(key)) null else getLong(key)

/**
 * The app never opens the database. Rust writes <root>/state.json for reading, and this class
 * hands commands to Rust by dropping small files into <root>/inbox.
 */
class Store(context: Context) {
    val root = File(context.filesDir, "rivium")
    private val stateFile = File(root, "state.json")

    /** Finds the first http(s) link in arbitrary text (shared text, clipboard) and returns it. */
    fun findUrl(text: String?): String? = Regex("https?://[^\\s<>\"]+").find(text ?: "")?.value

    /** Pinch once. Kept for the tile and for callers that just want a one-off digest. */
    fun submitText(text: String?): String? = findUrl(text)?.also { submit(it) }

    fun submit(url: String) = command(url)

    /** Re-check `url` every `everySecs` seconds and report changes. */
    fun watch(url: String, everySecs: Long) = command("watch $everySecs $url")

    fun unwatch(url: String) = command("unwatch $url")

    /** Hand the worker a login (the Cookie header from the sign-in screen). Sent to `host` only. */
    fun signIn(host: String, cookie: String) = command("cookie $host ${cookie.replace('\n', ' ')}")

    fun signOut(host: String) = command("uncookie $host")

    private fun command(text: String) {
        val inbox = File(root, "inbox").apply { mkdirs() }
        val tmp = File(inbox, ".${UUID.randomUUID()}")
        tmp.writeText(text)
        // Rust skips dotfiles, so the command appears atomically when renamed into place.
        tmp.renameTo(File(inbox, UUID.randomUUID().toString()))
    }

    fun snapshot(): Snapshot = try {
        val o = JSONObject(stateFile.readText())
        Snapshot(
            items = o.getJSONArray("items").map { i ->
                Item(
                    key = i.getString("key"),
                    state = when (i.getString("state")) { "done" -> State.DONE; "failed" -> State.FAILED; else -> State.WORKING },
                    url = i.optString("url"), title = i.str("title"), sha = i.str("sha"),
                    bytes = i.optLong("bytes"), error = i.str("error"), attempts = i.optInt("attempts")
                )
            },
            watches = o.getJSONArray("watches").map { w ->
                Watch(w.getString("url"), w.str("title"), w.getLong("every"), w.lng("checked"),
                    w.getInt("changes"), w.lng("changed_at"), w.str("error"))
            },
            changes = o.getJSONArray("changes").map { c ->
                Change(c.getLong("id"), c.getString("url"), c.str("title"), c.str("before"), c.str("after"), c.getLong("at"))
            },
            signedIn = o.optJSONArray("signed_in")?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: emptySet()
        )
    } catch (e: Exception) { Snapshot(emptyList(), emptyList(), emptyList()) }

    fun items(): List<Item> = snapshot().items

    fun digests(afterId: Long = 0): List<Digest> = items().filter { it.state == State.DONE }
        .map { Digest(it.key.substring(1).toLong(), it.url, it.title, it.sha, it.bytes) }
        .filter { it.id > afterId }

    fun failed(): List<FailedJob> = items().filter { it.state == State.FAILED }
        .map { FailedJob(it.key, it.url, it.error ?: "unknown error") }
}

private fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = List(length()) { f(getJSONObject(it)) }
