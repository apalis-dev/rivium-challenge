package io.github.kdbrian.rivapln.todoradar.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.kdbrian.rivapln.todoradar.platform.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Android side of [Platform]. Must be created in the activity's onCreate (it registers a launcher). */
class AndroidPlatform(private val activity: ComponentActivity) : Platform {
    override val name = "Android"

    private var pending: CompletableDeferred<Uri?>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pending?.complete(uri)
    }

    override fun openUrl(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun share(text: String, subject: String?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        activity.startActivity(Intent.createChooser(send, subject ?: "Share"))
    }

    override fun copy(text: String) {
        activity.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("TODO Radar", text))
        Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** SAF picker; the zip is copied into the engine's inbox because Rust cannot read content:// URIs. */
    override suspend fun pickZip(): String? {
        val result = CompletableDeferred<Uri?>().also { pending = it }
        picker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        val uri = result.await() ?: return null
        return importZip(activity, uri)
    }

    companion object {
        /** Null when the URI cannot be read (no grant, revoked, deleted): never crash a share. */
        suspend fun importZip(activity: ComponentActivity, uri: Uri): String? = withContext(Dispatchers.IO) {
            // inbox/<uuid>/<original name>.zip: unique on disk, readable as the scan title.
            val dir = File(File(activity.radarApp.engineRoot, "inbox"), UUID.randomUUID().toString()).apply { mkdirs() }
            val target = File(dir, displayName(activity, uri))
            try {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: return@withContext null
                target.absolutePath
            } catch (e: Exception) {
                android.util.Log.w("TodoRadar", "cannot import $uri", e)
                dir.deleteRecursively()
                null
            }
        }

        private fun displayName(activity: ComponentActivity, uri: Uri): String {
            val raw = runCatching {
                activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "shared"
            val safe = raw.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "shared" }
            return if (safe.endsWith(".zip", ignoreCase = true)) safe else "$safe.zip"
        }
    }
}
