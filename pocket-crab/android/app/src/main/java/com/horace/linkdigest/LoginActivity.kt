package com.horace.linkdigest

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Sign in to a site inside the app. When you tap "Use this login", the session cookies for that
 * site are handed to the Rust worker (via Store.signIn), which sends them only to that host.
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val host = Uri.parse(url).host ?: return finish()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F5F2EC")); setPadding(32, 16, 32, 16)
        }
        bar.addView(TextView(this).apply {
            text = "Sign in to $host, then tap Use this login"; textSize = 14f; setTextColor(Color.parseColor("#1B1A17"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "Use this login"; isAllCaps = false
            setOnClickListener { save(url, host) }
        })
        root.addView(bar)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient() // keep navigation inside the app
            loadUrl(url)
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun save(url: String, host: String) {
        // Includes HttpOnly cookies, which is what most session logins use.
        val cookie = CookieManager.getInstance().getCookie(url)
        if (cookie.isNullOrBlank()) {
            Toast.makeText(this, "No login found yet. Sign in first.", Toast.LENGTH_LONG).show()
            return
        }
        Store(this).signIn(host, cookie)
        Toast.makeText(this, "Signed in to $host", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "url" }
}
