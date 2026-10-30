package io.github.samuel0kello.mchoro.data.model

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** A finished drawing, read from `outbox/<id>.json`. */
data class Drawing(
    val id: String,
    val width: Int,
    val height: Int,
    /** The ink mask PNG: white pixels whose alpha is the amount of ink. */
    val sketchPath: String,
    /** Up to four, ranked best-first by Rust. Never empty. */
    val swatches: List<Swatch>,
    val createdAt: Long,
) {
    val aspectRatio: Float get() = width.toFloat() / height
}

/** One seed colour from the photo with every scheme variant built from it. */
data class Swatch(
    val seed: Color,
    val schemes: Map<SchemeVariant, SchemePair>,
)

data class SchemePair(val light: ColorScheme, val dark: ColorScheme)

/** The scheme variants Rust builds. [key] is the name used in the JSON. */
enum class SchemeVariant(val key: String) {
    TonalSpot("tonal_spot"),
    Vibrant("vibrant"),
    Expressive("expressive"),
    Fidelity("fidelity"),
    Monochrome("monochrome"),
}

/** A photo waiting in Rust's queue (`inbox/` or `pending/`). */
data class SketchingPhoto(val id: String, val photoPath: String)

/** How the user has chosen to ink a drawing. Saved per drawing. */
data class DrawingStyle(
    val swatchIndex: Int = 0,
    val variant: SchemeVariant = SchemeVariant.TonalSpot,
    val darkPaper: Boolean = false,
)

/** Picks the colour scheme a [DrawingStyle] asks for, falling back to the first swatch. */
fun Drawing.schemeFor(style: DrawingStyle): ColorScheme {
    val swatch = swatches.getOrElse(style.swatchIndex) { swatches.first() }
    val pair = swatch.schemes.getValue(style.variant)
    return if (style.darkPaper) pair.dark else pair.light
}

sealed interface EngineState {
    data object Starting : EngineState
    data object Running : EngineState
    data class Failed(val reason: String) : EngineState
}
