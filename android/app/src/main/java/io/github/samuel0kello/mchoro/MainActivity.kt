package io.github.samuel0kello.mchoro

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroTheme
import io.github.samuel0kello.mchoro.navigation.MainNavDisplay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch: after rotation the shared photos were already queued.
        if (savedInstanceState == null) queueSharedPhotos(intent)
        setContent {
            MchoroTheme { MainNavDisplay() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        queueSharedPhotos(intent)
    }

    /** "Share → Mchoro" from any app lands here; the photos join Rust's queue like picked ones. */
    private fun queueSharedPhotos(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamExtras()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch { appContainer.drawingRepository.addPhotos(uris) }
    }
}

private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)
    }

private fun Intent.streamExtras(): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        @Suppress("DEPRECATION") getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
