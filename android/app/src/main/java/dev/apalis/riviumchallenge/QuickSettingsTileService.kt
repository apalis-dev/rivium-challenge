package dev.apalis.riviumchallenge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Reading list" tile: shows what's waiting to be read, retries links that failed
 * while offline, and opens the list.
 */
class QuickSettingsTileService : TileService() {
    private val scope = MainScope()

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refresh() }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            if (ReaderHost.start(this@QuickSettingsTileService) && ReaderHost.counts().failed > 0) {
                ReaderHost.retryFailed()
            }
            openReadingList()
        }
    }

    private suspend fun refresh() {
        val tile = qsTile ?: return
        val counts = if (ReaderHost.start(this)) ReaderHost.counts() else Counts()
        tile.state = if (counts.unread > 0 || counts.pending > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = "Reading list"
            tile.subtitle = when {
                counts.failed > 0 -> "${counts.failed} failed · tap to retry"
                counts.pending > 0 -> "${counts.pending} downloading"
                counts.unread > 0 -> "${counts.unread} unread"
                else -> "All caught up"
            }
        } else {
            // No subtitles before Android 10, so the label has to carry the count.
            tile.label = when {
                counts.failed > 0 -> "${counts.failed} failed"
                counts.pending > 0 -> "${counts.pending} downloading"
                counts.unread > 0 -> "${counts.unread} to read"
                else -> "Reading list"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openReadingList() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
