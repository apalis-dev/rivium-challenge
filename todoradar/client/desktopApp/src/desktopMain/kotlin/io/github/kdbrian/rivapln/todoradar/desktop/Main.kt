package io.github.kdbrian.rivapln.todoradar.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdbrian.rivapln.todoradar.engine.Engine
import io.github.kdbrian.rivapln.todoradar.engine.EngineConfig
import io.github.kdbrian.rivapln.todoradar.engine.EngineState
import io.github.kdbrian.rivapln.todoradar.engine.NativeEngine
import io.github.kdbrian.rivapln.todoradar.ui.App
import io.github.kdbrian.rivapln.todoradar.ui.Navigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/** Load radar_jni: explicit path, then the packaged resources dir, then java.library.path. */
private fun loadEngineLibrary() {
    val explicit = System.getProperty("radar.lib") ?: System.getenv("RADAR_LIB")
    val packaged = System.getProperty("compose.application.resources.dir")?.let { dir ->
        File(dir).listFiles()?.firstOrNull { it.name.contains("radar_jni") }
    }
    when {
        explicit != null && File(explicit).isFile -> System.load(File(explicit).absolutePath)
        packaged != null -> System.load(packaged.absolutePath)
        else -> System.loadLibrary("radar_jni")
    }
}

fun main() {
    val root = File(System.getProperty("user.home"), ".todo-radar")
    val config = EngineConfig(
        githubClientId = System.getenv("TODO_RADAR_GITHUB_CLIENT_ID").orEmpty(),
        // Dev convenience: TODO_RADAR_GITHUB_TOKEN=$(gh auth token) skips Device Flow.
        githubToken = System.getenv("TODO_RADAR_GITHUB_TOKEN").orEmpty(),
    )
    val engine: Engine = NativeEngine.createOrDemo(root, config, ::loadEngineLibrary)

    application {
        val trayState = rememberTrayState()
        val navigator = remember { Navigator() }
        val window = rememberWindowState(width = 1080.dp, height = 820.dp, position = WindowPosition.PlatformDefault)
        val platform = remember { DesktopPlatform(trayState) }
        val icon = rememberVectorPainter(Icons.Rounded.Radar)

        val quit = {
            // Graceful Rivium stop: services drain and unfinished scans stay queued for next launch.
            runBlocking { engine.stop() }
            exitApplication()
        }

        Tray(icon = icon, state = trayState, tooltip = "TODO Radar", menu = {
            Item("Open", onClick = { window.isMinimized = false })
            Item("Pause engine", onClick = { runBlocking { engine.stop(paused = true) } })
            Item("Resume engine", onClick = { runBlocking { engine.start() } })
            Separator()
            Item("Quit", onClick = quit)
        })

        // Tray notifications when scans finish (desktop notifications have no action buttons,
        // so Keep / Delete now live in the window).
        LaunchedEffect(engine) {
            val running = mutableSetOf<Long>()
            while (true) {
                if (engine.state.value == EngineState.Running) {
                    runCatching { engine.api.scans() }.getOrNull()?.forEach { scan ->
                        if (scan.isActive) running += scan.id
                        else if (running.remove(scan.id)) {
                            val body = if (scan.isDone) {
                                "${scan.todoCount} TODOs" + (if (scan.hasBaseline) " · +${scan.newCount} new · −${scan.resolvedCount} resolved" else "") +
                                    " · download auto-deletes unless kept"
                            } else scan.error ?: "Scan failed"
                            trayState.sendNotification(Notification("${scan.title} @ ${scan.shortSha ?: ""}", body,
                                if (scan.isDone) Notification.Type.Info else Notification.Type.Error))
                        }
                    }
                }
                delay(1_500)
            }
        }

        // No OS frame: a transparent window hosting our own rounded, shadowed surface.
        Window(
            onCloseRequest = quit,
            state = window,
            title = "TODO Radar",
            icon = icon,
            undecorated = true,
            transparent = true,
        ) {
            FramedWindow(window, onClose = quit) {
                App(engine = engine, platform = platform, navigator = navigator)
            }
        }
    }
}
