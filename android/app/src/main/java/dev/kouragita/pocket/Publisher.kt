package dev.kouragita.pocket

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/**
 * Copies a finished job into Downloads/Pocket, once.
 *
 * The copy is written under a .partial name and renamed only after the bytes
 * match, so a kill mid-copy cannot leave a truncated file under the final name,
 * and a row whose size already matches is not copied twice.
 */
object Publisher {

    /**
     * The activity and the background service both poll, so the decision to copy
     * and the copy itself have to be one step: otherwise both see "not there yet"
     * and the user gets a duplicate.
     */
    private val lock = Any()

    private fun record(context: Context) = context.getSharedPreferences("published", Context.MODE_PRIVATE)

    private fun publishedNames(context: Context): MutableSet<String> =
        (record(context).getStringSet("names", emptySet()) ?: emptySet()).toMutableSet()


    /** The name a card gets on disk, the way a person would look for it. */
    fun cardName(raw: JSONObject, id: String): String {
        val postId = raw.optString("source").substringAfterLast('/').ifEmpty { id }
        return "${raw.optString("author").ifEmpty { "post" }}_${postId}_card.png"
    }

    /** The file name, but only when this call actually published it. */
    fun publishFinished(context: Context, job: PocketClient.Job): String? = synchronized(lock) {
        val raw = job.raw
        if (raw != null && raw.optString("kind") == "card") {
            val target = File(context.filesDir, "pocket/cards/${cardName(raw, job.id)}")
            val rendered = if (target.isFile) target else (CardRenderer.render(raw, target) ?: return null)
            return if (publishIfNeeded(context, rendered, "image/png")) rendered.name else null
        }
        val source = job.savedFile?.let(::File) ?: return null
        return if (publishIfNeeded(context, source, "video/mp4")) source.name else null
    }

    private fun publishIfNeeded(context: Context, source: File, mime: String): Boolean {
        if (!source.isFile) return false
        if (alreadyPublished(context, source.name)) return false
        if (!copy(context, source, mime)) return false
        record(context).edit().putStringSet("names", publishedNames(context) + source.name).apply()
        return true
    }

    /**
     * Our own record of what has been published. Asking MediaStore instead looked
     * tidier but was wrong twice over: the size column is not indexed yet right
     * after a write, and the query fails quietly under scoped storage, so every
     * poll concluded the file was missing and copied it again.
     */
    fun alreadyPublished(context: Context, name: String): Boolean =
        synchronized(lock) { name in publishedNames(context) }

    private fun copy(context: Context, source: File, mime: String): Boolean {
        return try {
            val partial = "${source.name}.partial"
            context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(partial),
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, partial)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Pocket")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            var copied = 0L
            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> copied = input.copyTo(out) }
            }
            if (copied != source.length()) {
                context.contentResolver.delete(uri, null, null)
                return false
            }
            val rename = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, source.name) }
            if (context.contentResolver.update(uri, rename, null, null) <= 0) {
                context.contentResolver.delete(uri, null, null)
                return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
