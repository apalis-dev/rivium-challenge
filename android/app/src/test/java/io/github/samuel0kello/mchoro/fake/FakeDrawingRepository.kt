package io.github.samuel0kello.mchoro.fake

import android.net.Uri
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import io.github.samuel0kello.mchoro.data.DrawingRepository
import io.github.samuel0kello.mchoro.data.model.Drawing
import io.github.samuel0kello.mchoro.data.model.DrawingStyle
import io.github.samuel0kello.mchoro.data.model.EngineState
import io.github.samuel0kello.mchoro.data.model.SchemePair
import io.github.samuel0kello.mchoro.data.model.SchemeVariant
import io.github.samuel0kello.mchoro.data.model.SketchingPhoto
import io.github.samuel0kello.mchoro.data.model.Swatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File

class FakeDrawingRepository : DrawingRepository {
    override val engine = MutableStateFlow<EngineState>(EngineState.Running)
    override val drawings = MutableStateFlow<List<Drawing>>(emptyList())
    override val sketching = MutableStateFlow<List<SketchingPhoto>>(emptyList())
    override val styles = MutableStateFlow<Map<String, DrawingStyle>>(emptyMap())

    var startCalls = 0
    val added = mutableListOf<Uri>()
    val deleted = mutableListOf<String>()
    var export: () -> File = { File("shared/drawing.png") }

    override fun drawing(id: String): Flow<Drawing?> = drawings.map { list -> list.find { it.id == id } }

    override suspend fun startEngine() {
        startCalls++
        engine.value = EngineState.Running
    }

    override suspend fun addPhotos(uris: List<Uri>) {
        added += uris
    }

    override suspend fun loadSketch(drawing: Drawing, maxSide: Int): ImageBitmap = FakeBitmap

    override suspend fun loadPhoto(photo: SketchingPhoto, maxSide: Int): ImageBitmap? = null

    override suspend fun saveStyle(id: String, style: DrawingStyle) {
        styles.update { it + (id to style) }
    }

    override suspend fun delete(id: String) {
        deleted += id
        drawings.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun exportForSharing(drawing: Drawing, ink: Color, paper: Color): File = export()
}

/** A drawing whose schemes are easy to tell apart: primary encodes swatch, variant and paper. */
fun testDrawing(id: String, createdAt: Long = 0) = Drawing(
    id = id,
    width = 400,
    height = 300,
    sketchPath = "/outbox/$id.png",
    swatches = listOf(Color.Red, Color.Green).mapIndexed { index, seed ->
        Swatch(
            seed = seed,
            schemes = SchemeVariant.entries.associateWith { variant ->
                SchemePair(
                    light = lightColorScheme(primary = primaryFor(index, variant, dark = false)),
                    dark = darkColorScheme(primary = primaryFor(index, variant, dark = true)),
                )
            },
        )
    },
    createdAt = createdAt,
)

fun primaryFor(swatch: Int, variant: SchemeVariant, dark: Boolean) =
    Color(red = swatch * 100, green = variant.ordinal * 40, blue = if (dark) 200 else 10)

object FakeBitmap : ImageBitmap {
    override val width = 4
    override val height = 3
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha = true
    override val config = ImageBitmapConfig.Argb8888

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = Unit

    override fun prepareToDraw() = Unit
}
