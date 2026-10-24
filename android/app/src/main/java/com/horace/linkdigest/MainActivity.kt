package com.horace.linkdigest

import android.Manifest
import android.app.Activity
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

class MainActivity : Activity() {
    private lateinit var store: Store
    private val ui = Handler(Looper.getMainLooper())
    private var items: List<Item> = emptyList()
    private val prefs by lazy { getSharedPreferences("state", MODE_PRIVATE) }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = items[i].key.hashCode().toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_digest, parent, false)
            val d = items[i]
            val badge = v.findViewById<TextView>(R.id.badge)
            val title = v.findViewById<TextView>(R.id.title)
            val detail = v.findViewById<TextView>(R.id.detail)
            val host = Uri.parse(d.url).host ?: d.url
            v.findViewById<TextView>(R.id.host).text = host
            when (d.state) {
                State.DONE -> {
                    badge.text = "PINCHED"; badge.setTextColor(getColor(R.color.ok))
                    title.text = d.title ?: "(no title)"
                    detail.text = "sha256 ${d.sha?.take(12)}  \u00b7  %.1f KB".format(d.bytes / 1024.0)
                }
                State.WORKING -> {
                    badge.text = if (d.attempts > 0) "RETRYING (try ${d.attempts + 1})" else "FERRIS IS WORKING"
                    badge.setTextColor(getColor(R.color.warn))
                    title.text = d.url
                    detail.text = if (d.attempts > 0) "Last error: ${d.error}" else "Fetching the page\u2026"
                }
                State.FAILED -> {
                    badge.text = "FAILED"; badge.setTextColor(getColor(R.color.accent))
                    title.text = "Couldn't pinch $host"
                    detail.text = "${d.error}\nTap to retry \u00b7 long-press to dismiss"
                }
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
            val d = items[i]
            if (d.state == State.FAILED) {          // retry: queue it again, hide the old failure
                store.submit(d.url); dismiss(d)
                Toast.makeText(this, "Retrying ${d.url}", Toast.LENGTH_SHORT).show()
                render()
            } else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.url)))
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            if (items[i].state == State.FAILED) { dismiss(items[i]); render() }
            true
        }
        findViewById<Button>(R.id.paste).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            toastQueued(store.submitText(text), "No link on the clipboard. Copy one first.")
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
        toastQueued(store.submitText(intent.getStringExtra(Intent.EXTRA_TEXT)), "No link found in shared text")
        intent.action = null // do not re-queue on rotation
    }

    /** The queue is owned by Rust, so the UI only hides failures it has handled. */
    private fun dismiss(d: Item) {
        prefs.edit().putStringSet("dismissed", (prefs.getStringSet("dismissed", emptySet())!! + d.key)).apply()
    }

    private fun toastQueued(url: String?, none: String) {
        Toast.makeText(this, if (url != null) "Pinched! Ferris is on it" else none, Toast.LENGTH_SHORT).show()
        render()
    }

    private fun render() {
        val code = Bridge.nativeStatus()
        val running = code == 2
        findViewById<TextView>(R.id.status).text = "Ferris is " + (if (running) "awake" else Bridge.statusName(code).lowercase())
        findViewById<TextView>(R.id.versions).text = "Rivium ${Bridge.nativeVersion()}  ·  Apalis + SQLite"
        findViewById<View>(R.id.dot).background.setTint(getColor(if (running) R.color.ok else R.color.warn))
        val dismissed = prefs.getStringSet("dismissed", emptySet())!!
        items = store.items().filter { it.key !in dismissed }
        val w = items.count { it.state == State.WORKING }
        findViewById<TextView>(R.id.waiting).text = if (w == 0) "all caught up" else "$w working"
        adapter.notifyDataSetChanged()
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { ui.removeCallbacks(refresh); super.onPause() }
}
