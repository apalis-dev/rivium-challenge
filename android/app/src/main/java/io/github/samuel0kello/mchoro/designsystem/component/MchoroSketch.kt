package io.github.samuel0kello.mchoro.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroTheme
import io.github.samuel0kello.mchoro.designsystem.PreviewSketches

/**
 * Draws Rust's ink mask on paper.
 *
 * The mask is white with alpha = amount of ink, so one colour matrix both paints it in [ink]
 * and, through [reveal], decides how much of it shows: at a low reveal only the darkest
 * strokes pass, then lighter shading fills in, as if the drawing were being sketched.
 */
@Composable
fun MchoroSketch(
    mask: ImageBitmap,
    ink: Color,
    paper: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    reveal: Float = 1f,
    shape: Shape = MaterialTheme.shapes.large,
) {
    Image(
        bitmap = mask,
        contentDescription = contentDescription,
        modifier = modifier
            .aspectRatio(mask.width.toFloat() / mask.height)
            .clip(shape)
            .background(paper),
        contentScale = ContentScale.Fit,
        colorFilter = inkFilter(ink, reveal),
    )
}

/** RGB ← ink colour; alpha ← (alpha − threshold) stretched back to 0..255. */
private fun inkFilter(ink: Color, reveal: Float): ColorFilter {
    val progress = reveal.coerceIn(0.01f, 1f)
    val scale = 1f / progress
    val offset = -255f * (1f - progress) / progress
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, ink.red * 255f,
                0f, 0f, 0f, 0f, ink.green * 255f,
                0f, 0f, 0f, 0f, ink.blue * 255f,
                0f, 0f, 0f, scale, offset,
            ),
        ),
    )
}

@Preview(name = "Fully drawn")
@Composable
private fun MchoroSketchPreview() {
    MchoroTheme(dynamicColor = false) {
        MchoroSketch(
            mask = PreviewSketches.mask(),
            ink = Color(0xFF8C4E3F),
            paper = Color(0xFFFFF8F6),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "Half revealed: bold strokes only")
@Composable
private fun MchoroSketchRevealPreview() {
    MchoroTheme(dynamicColor = false) {
        MchoroSketch(
            mask = PreviewSketches.mask(),
            ink = Color(0xFF8C4E3F),
            paper = Color(0xFFFFF8F6),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            reveal = 0.4f,
        )
    }
}

@Preview(name = "Dark paper")
@Composable
private fun MchoroSketchDarkPreview() {
    MchoroTheme(dynamicColor = false) {
        MchoroSketch(
            mask = PreviewSketches.mask(),
            ink = Color(0xFFFFB4A3),
            paper = Color(0xFF1A1110),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
