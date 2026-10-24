package io.github.kdbrian.rivapln.todoradar.desktop

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import io.github.kdbrian.rivapln.todoradar.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

class DesktopPlatform(private val tray: TrayState) : Platform {
    override val name = "Desktop"

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    /** Desktop has no share sheet: put the text on the clipboard and say so. */
    override fun share(text: String, subject: String?) {
        copy(text)
        tray.sendNotification(Notification(subject ?: "TODO Radar", "Copied to the clipboard, ready to paste."))
    }

    override fun copy(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override suspend fun pickZip(): String? = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, "Choose a .zip to scan", FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
            file = "*.zip"
            isVisible = true
        }
        val name = dialog.file ?: return@withContext null
        File(dialog.directory, name).absolutePath
    }
}
