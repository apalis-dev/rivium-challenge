package com.horace.linkdigest

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast

/** Invisible activity: Android only lets a focused window read the clipboard. */
class ClipboardDigestActivity : Activity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = Store(this).submitText(text)
        if (url != null) {
            startForegroundService(Intent(this, WorkerService::class.java)) // make sure the worker is up
            Toast.makeText(this, "Pinched! Ferris is on it: $url", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No link on the clipboard. Copy one first.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
