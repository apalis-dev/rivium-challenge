package io.github.kdbrian.rivapln.todoradar.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Share-sheet target: "Scan TODOs" on any .zip. Copies it into the engine inbox, hands it to the
 * engine service and finishes immediately, so the user never leaves the app they shared from.
 */
class ZipShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = sharedUri(intent)
        if (uri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val path = AndroidPlatform.importZip(this@ZipShareActivity, uri)
            if (path != null) {
                EngineService.scanZip(this@ZipShareActivity, path)
                Toast.makeText(this@ZipShareActivity, "Scanning for TODOs…", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ZipShareActivity, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun sharedUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }
}
