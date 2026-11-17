package com.horace.linkdigest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** What one line of the list shows. */
private sealed interface Row {
    data class Header(val text: String) : Row
    data class Watching(val watch: Watch, val lastChange: Change?, val unseen: Boolean) : Row
    data class Job(val item: Item) : Row
}

class MainActivity : Activity() {
    private lateinit var store: Store
    private val ui = Handler(Looper.getMainLooper())
    private var rows: List<Row> = emptyList()
    private var signedInHosts: Set<String> = emptySet()
    private var pendingOffer: String? = null
    private val prefs by lazy { getSharedPreferences("state", MODE_PRIVATE) }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(i: Int) = if (rows[i] is Row.Header) 0 else 1
        override fun isEnabled(i: Int) = rows[i] !is Row.Header

        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(this@MainActivity)
            val row = rows[i]
            if (row is Row.Header) {
                val v = convert ?: inflater.inflate(R.layout.item_header, parent, false)
                (v as TextView).text = row.text
                return v
            }
            val v = convert ?: inflater.inflate(R.layout.item_digest, parent, false)
            val badge = v.findViewById<TextView>(R.id.badge)
            val title = v.findViewById<TextView>(R.id.title)
            val host = v.findViewById<TextView>(R.id.host)
            val detail = v.findViewById<TextView>(R.id.detail)
            when (row) {
                is Row.Watching -> {
                    val w = row.watch
                    host.text = Uri.parse(w.url).host ?: w.url
                    title.text = w.title ?: w.url
                    if (row.unseen) {
                        badge.text = "CHANGED ${ago(w.changedAt)}"; badge.setTextColor(getColor(R.color.accent))
                    } else {
                        badge.text = "WATCHING · every ${everyLabel(w.everySecs)}"; badge.setTextColor(getColor(R.color.ok))
                    }
                    val lines = mutableListOf(
                        if (w.checked == null) "Waiting for the first check…" else "Checked ${ago(w.checked)}"
                    )
                    row.lastChange?.let { lines += "Last change: ${it.before} → ${it.after}" }
                    w.error?.let {
                        val host = Uri.parse(w.url).host
                        val expired = (it.contains("401") || it.contains("403")) && host in signedInHosts
                        lines += if (expired) "Login may have expired. Share the link again and sign in again." else "Last check failed: $it"
                    }
                    detail.text = lines.joinToString("\n")
                }
                is Row.Job -> {
                    val d = row.item
                    val h = Uri.parse(d.url).host ?: d.url
                    host.text = h
                    when (d.state) {
                        State.DONE -> {
                            badge.text = "PINCHED"; badge.setTextColor(getColor(R.color.ok))
                            title.text = d.title ?: "(no title)"
                            detail.text = "sha256 ${d.sha?.take(12)}  ·  %.1f KB".format(d.bytes / 1024.0)
                        }
                        State.WORKING -> {
                            badge.text = if (d.attempts > 0) "RETRYING (try ${d.attempts + 1})" else "FERRIS IS WORKING"
                            badge.setTextColor(getColor(R.color.warn))
                            title.text = d.url
                            detail.text = if (d.attempts > 0) "Last error: ${d.error}" else "Fetching the page…"
                        }
                        State.FAILED -> {
                            badge.text = "FAILED"; badge.setTextColor(getColor(R.color.accent))
                            title.text = "Couldn't pinch $h"
                            detail.text = "${d.error}\nTap to retry · long-press to dismiss"
                        }
                    }
                }
                else -> {}
            }
            return v
        }
    }

    private val refresh = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, WorkerService::class.java))

        val list = findViewById<ListView>(R.id.results)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.setOnItemClickListener { _, _, i, _ ->
            when (val r = rows[i]) {
                is Row.Watching -> open(r.watch.url)
                is Row.Job -> if (r.item.state == State.FAILED) {   // retry: queue again, hide the old failure
                    store.submit(r.item.url); dismiss(r.item)
                    Toast.makeText(this, "Retrying ${r.item.url}", Toast.LENGTH_SHORT).show()
                    render()
                } else open(r.item.url)
                else -> {}
            }
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            when (val r = rows[i]) {
                is Row.Watching -> confirmUnwatch(r.watch)
                is Row.Job -> if (r.item.state == State.FAILED) { dismiss(r.item); render() }
                else -> {}
            }
            true
        }
        findViewById<Button>(R.id.paste).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            offer(store.findUrl(text), "No link on the clipboard. Copy one first.")
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Share Sheet: the shared text may be "title https://..." so pull out the first URL. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        offer(store.findUrl(intent.getStringExtra(Intent.EXTRA_TEXT)), "No link found in shared text")
        intent.action = null // do not ask again on rotation
    }

    /** A link arrived: ask whether to digest it once or to keep watching it for changes. */
    private fun offer(url: String?, none: String) {
        if (url == null) { Toast.makeText(this, none, Toast.LENGTH_SHORT).show(); return }
        val host = Uri.parse(url).host ?: url
        val signedIn = host in store.snapshot().signedIn
        val choices = mutableListOf<Pair<String, () -> Unit>>(
            "Pinch once" to { store.submit(url); queued("Pinched! Ferris is on it") },
            "Watch every 15 seconds (demo)" to { store.watch(url, 15); queued("Watching $host") },
            "Watch every 15 minutes" to { store.watch(url, 15 * 60); queued("Watching $host") },
            "Watch every hour" to { store.watch(url, 3600); queued("Watching $host") },
            "Watch every day" to { store.watch(url, 86400); queued("Watching $host") },
        )
        if (signedIn) {
            choices += "Sign out of $host" to {
                store.signOut(host)
                // Forget the in-app browser's session too, or the next sign-in screen would still be logged in.
                android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
                Toast.makeText(this, "Signed out of $host", Toast.LENGTH_SHORT).show()
            }
        }
        choices += (if (signedIn) "Sign in again\u2026" else "Sign in to $host first\u2026") to {
            startActivityForResult(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_URL, url), REQ_LOGIN)
            pendingOffer = url
        }
        AlertDialog.Builder(this)
            .setTitle(if (signedIn) "$host  (signed in)" else host)
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    private fun queued(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); render() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // After signing in, offer the same link again so it can be watched with the new login.
        if (requestCode == REQ_LOGIN) pendingOffer?.let { url -> pendingOffer = null; ui.postDelayed({ offer(url, "") }, 800) }
    }

    private fun confirmUnwatch(w: Watch) {
        AlertDialog.Builder(this)
            .setTitle("Stop watching ${Uri.parse(w.url).host}?")
            .setPositiveButton("Stop watching") { _, _ -> store.unwatch(w.url); render() }
            .setNegativeButton("Keep watching", null)
            .show()
    }

    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /** The queue is owned by Rust, so the UI only hides failures it has handled. */
    private fun dismiss(d: Item) {
        prefs.edit().putStringSet("dismissed", (prefs.getStringSet("dismissed", emptySet())!! + d.key)).apply()
    }

    private fun render() {
        val code = Bridge.nativeStatus()
        val running = code == 2
        findViewById<TextView>(R.id.status).text = "Ferris is " + (if (running) "awake" else Bridge.statusName(code).lowercase())
        findViewById<TextView>(R.id.versions).text = "Rivium ${Bridge.nativeVersion()}  ·  Apalis + SQLite"
        findViewById<View>(R.id.dot).background.setTint(getColor(if (running) R.color.ok else R.color.warn))

        val snap = store.snapshot()
        signedInHosts = snap.signedIn
        val dismissed = prefs.getStringSet("dismissed", emptySet())!!
        val jobs = snap.items.filter { it.key !in dismissed }
        val seenAt = prefs.getLong("seenAt", 0)
        val newRows = mutableListOf<Row>()
        if (snap.watches.isNotEmpty()) {
            newRows += Row.Header("WATCHING")
            snap.watches.forEach { w ->
                val last = snap.changes.firstOrNull { it.url == w.url }
                newRows += Row.Watching(w, last, unseen = (w.changedAt ?: 0) > seenAt)
            }
        }
        if (jobs.isNotEmpty()) {
            newRows += Row.Header("PINCHED LINKS")
            jobs.forEach { newRows += Row.Job(it) }
        }
        rows = newRows

        val working = jobs.count { it.state == State.WORKING }
        findViewById<TextView>(R.id.waiting).text = when {
            working > 0 -> "$working working"
            snap.watches.isNotEmpty() -> "${snap.watches.size} watching"
            else -> "all caught up"
        }
        adapter.notifyDataSetChanged()
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        // Anything that changed while the app was open counts as seen once the user leaves.
        prefs.edit().putLong("seenAt", System.currentTimeMillis() / 1000).apply()
        WatchWidget.refresh(this)
        super.onPause()
    }
}

private const val REQ_LOGIN = 7

fun ago(epochSecs: Long?): String {
    if (epochSecs == null) return "never"
    val d = System.currentTimeMillis() / 1000 - epochSecs
    return when {
        d < 5 -> "just now"
        d < 60 -> "${d}s ago"
        d < 3600 -> "${d / 60} min ago"
        d < 86400 -> "${d / 3600} h ago"
        else -> "${d / 86400} d ago"
    }
}

fun everyLabel(secs: Long): String = when {
    secs < 60 -> "$secs s"
    secs < 3600 -> "${secs / 60} min"
    secs == 3600L -> "hour"
    secs % 86400 == 0L -> if (secs == 86400L) "day" else "${secs / 86400} days"
    else -> "${secs / 3600} h"
}
