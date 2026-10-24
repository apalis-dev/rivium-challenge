package io.github.kdbrian.rivapln.todoradar.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.kdbrian.rivapln.todoradar.ui.App
import io.github.kdbrian.rivapln.todoradar.ui.Navigator

class MainActivity : ComponentActivity() {
    private val navigator = Navigator()
    private lateinit var platform: AndroidPlatform

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the status and navigation bars; Compose applies safe-drawing insets.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        platform = AndroidPlatform(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        EngineService.start(this)
        handle(intent)
        setContent { App(engine = radarApp.engine, platform = platform, navigator = navigator) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Notifications deep-link into a scan. */
    private fun handle(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_SCAN_ID, -1L) ?: -1L
        if (id > 0) navigator.openScan(id)
    }

    companion object {
        const val EXTRA_SCAN_ID = "scan_id"
    }
}
