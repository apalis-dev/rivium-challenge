package io.github.samuel0kello.mchoro.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import io.github.samuel0kello.mchoro.data.model.Drawing
import io.github.samuel0kello.mchoro.data.model.DrawingStyle
import io.github.samuel0kello.mchoro.data.model.EngineState
import io.github.samuel0kello.mchoro.data.model.ResultJson
import io.github.samuel0kello.mchoro.data.model.SchemeVariant
import io.github.samuel0kello.mchoro.data.model.SketchResultJson
import io.github.samuel0kello.mchoro.data.model.SketchingPhoto
import io.github.samuel0kello.mchoro.data.model.toDrawing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

interface DrawingRepository {
    val engine: StateFlow<EngineState>

    /** Finished drawings, newest first. */
    val drawings: Flow<List<Drawing>>

    /** Photos still queued in Rust, oldest first. */
    val sketching: Flow<List<SketchingPhoto>>

    /** The saved [DrawingStyle] per drawing id. Missing = default style. */
    val styles: Flow<Map<String, DrawingStyle>>

    fun drawing(id: String): Flow<Drawing?>

    suspend fun startEngine()

    /** Copies photos into Rust's inbox. Each becomes one Apalis job. */
    suspend fun addPhotos(uris: List<Uri>)

    suspend fun loadSketch(drawing: Drawing, maxSide: Int): ImageBitmap

    suspend fun loadPhoto(photo: SketchingPhoto, maxSide: Int): ImageBitmap?

    suspend fun saveStyle(id: String, style: DrawingStyle)

    suspend fun delete(id: String)

    /** Flattens the drawing onto paper in the given colours and returns a PNG to share. */
    suspend fun exportForSharing(drawing: Drawing, ink: Color, paper: Color): File
}

internal class FileDrawingRepository(
    private val context: Context,
    private val rust: MchoroEngine,
    scope: CoroutineScope,
) : DrawingRepository {

    override val engine: StateFlow<EngineState> = rust.state

    init {
        scope.launch { rust.start() }
    }

    // Rust writes files; we poll the folders. A FileObserver would also work, but polling twice
    // a second is simple, cheap, and also notices files that appeared while the app was closed.
    override val drawings: Flow<List<Drawing>> = poll { scanOutbox() }.shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override val sketching: Flow<List<SketchingPhoto>> = poll { scanQueue() }.shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val prefs = context.getSharedPreferences("styles", Context.MODE_PRIVATE)
    private val _styles = MutableStateFlow(readStyles())
    override val styles: Flow<Map<String, DrawingStyle>> = _styles.asStateFlow()

    override fun drawing(id: String): Flow<Drawing?> = drawings.map { list -> list.find { it.id == id } }.distinctUntilChanged()

    override suspend fun startEngine() = rust.start()

    override suspend fun addPhotos(uris: List<Uri>) = withContext(Dispatchers.IO) {
        rust.inbox.mkdirs()
        val stamp = System.currentTimeMillis()
        uris.forEachIndexed { index, uri ->
            // One unreadable photo (revoked permission, deleted file) must not stop the others.
            runCatching { import(uri, id = "mchoro-$stamp-$index") }
                .onFailure { Log.w(TAG, "Could not import $uri", it) }
        }
    }

    private fun import(uri: Uri, id: String) {
        val type = context.contentResolver.getType(uri).orEmpty()
        val extension = when (type) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> null // HEIC and friends: Rust can't decode them, so we re-encode as JPEG
        }
        // Write to `.part` first and rename, so Rust never picks up half a photo.
        val part = File(rust.inbox, "$id.part")
        if (extension != null) {
            val input = context.contentResolver.openInputStream(uri) ?: error("no stream")
            input.use { part.outputStream().use { output -> it.copyTo(output) } }
        } else {
            val bitmap = decodeUri(uri) ?: error("cannot decode $type")
            part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
        }
        part.renameTo(File(rust.inbox, "$id.${extension ?: "jpg"}"))
    }

    override suspend fun loadSketch(drawing: Drawing, maxSide: Int): ImageBitmap = withContext(Dispatchers.IO) {
        decodeFile(drawing.sketchPath, maxSide)?.asImageBitmap() ?: error("Cannot read ${drawing.sketchPath}")
    }

    override suspend fun loadPhoto(photo: SketchingPhoto, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        // The file moves from inbox/ to pending/ and is deleted when done, so it may be gone.
        val path = listOf(photo.photoPath, File(rust.pending, File(photo.photoPath).name).path).firstOrNull { File(it).exists() }
        path?.let { decodeFile(it, maxSide)?.asImageBitmap() }
    }

    override suspend fun saveStyle(id: String, style: DrawingStyle) {
        _styles.update { it + (id to style) }
        prefs.edit { putString(id, "${style.swatchIndex}|${style.variant.key}|${style.darkPaper}") }
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        // JSON first: once it's gone the drawing disappears from the list, even if a PNG lingers.
        listOf("$id.json", "$id.png", "$id.preview.png").forEach { File(rust.outbox, it).delete() }
        _styles.update { it - id }
        prefs.edit { remove(id) }
    }

    override suspend fun exportForSharing(drawing: Drawing, ink: Color, paper: Color): File = withContext(Dispatchers.IO) {
        val mask = BitmapFactory.decodeFile(drawing.sketchPath) ?: error("Cannot read ${drawing.sketchPath}")
        val art = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        Canvas(art).apply {
            drawColor(paper.toArgb())
            drawBitmap(mask, 0f, 0f, Paint().apply { colorFilter = inkFilter(ink) })
        }
        mask.recycle()
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        File(dir, "${drawing.id}.png").also { file ->
            file.outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
            art.recycle()
        }
    }

    private val cache = mutableMapOf<String, Pair<Long, Drawing>>()

    private fun scanOutbox(): List<Drawing> {
        val files = rust.outbox.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
        return files.mapNotNull { file ->
            val modified = file.lastModified()
            cache[file.name]?.takeIf { it.first == modified }?.second
                ?: runCatching { ResultJson.decodeFromString<SketchResultJson>(file.readText()).toDrawing(createdAt = modified) }
                    .getOrNull()
                    ?.also { cache[file.name] = modified to it }
        }.sortedByDescending { it.createdAt }
    }

    private fun scanQueue(): List<SketchingPhoto> =
        listOf(rust.inbox, rust.pending)
            .flatMap { it.listFiles().orEmpty().asList() }
            .filter { it.isFile && it.extension != "part" }
            .sortedBy { it.lastModified() }
            .map { SketchingPhoto(id = it.nameWithoutExtension, photoPath = it.path) }

    private fun readStyles(): Map<String, DrawingStyle> = prefs.all.mapNotNull { (id, value) ->
        val parts = (value as? String)?.split("|") ?: return@mapNotNull null
        val variant = SchemeVariant.entries.find { it.key == parts.getOrNull(1) } ?: return@mapNotNull null
        id to DrawingStyle(parts[0].toIntOrNull() ?: 0, variant, parts.getOrNull(2).toBoolean())
    }.toMap()

    private fun decodeUri(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                if (side > MAX_IMPORT_SIDE) decoder.setTargetSampleSize(side / MAX_IMPORT_SIDE)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()

    private fun <T> poll(scan: () -> T): Flow<T> = flow {
        while (true) {
            emit(scan())
            delay(POLL_MILLIS)
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    private companion object {
        const val TAG = "DrawingRepository"
        const val POLL_MILLIS = 500L
        const val MAX_IMPORT_SIDE = 2048
    }
}

/** Reads a downsampled bitmap so a grid of thumbnails doesn't decode full-size PNGs. */
private fun decodeFile(path: String, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Paints a white ink mask in [ink], keeping its alpha. Same maths as the on-screen sketch. */
private fun inkFilter(ink: Color) = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            0f, 0f, 0f, 0f, ink.red * 255f,
            0f, 0f, 0f, 0f, ink.green * 255f,
            0f, 0f, 0f, 0f, ink.blue * 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)
