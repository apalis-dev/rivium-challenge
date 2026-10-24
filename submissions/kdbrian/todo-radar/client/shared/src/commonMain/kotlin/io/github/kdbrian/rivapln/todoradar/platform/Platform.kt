package io.github.kdbrian.rivapln.todoradar.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** OS integrations the shared UI needs; each app provides one through [LocalPlatform]. */
interface Platform {
    /** "Android" or "Desktop", shown in the about line. */
    val name: String

    fun openUrl(url: String)
    fun share(text: String, subject: String? = null)
    fun copy(text: String)

    /** Let the user pick a local `.zip` to scan offline; null when cancelled. */
    suspend fun pickZip(): String?
}

val LocalPlatform = staticCompositionLocalOf<Platform> { error("No Platform provided") }

/** System back on Android; no-op on desktop (the top bar has a back button). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
