package io.github.samuel0kello.mchoro.data.model

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirrors `SketchResult` in `rust/src/lib.rs`. Role maps use snake_case names like `on_primary`. */
@Serializable
internal data class SketchResultJson(
    val id: String,
    val width: Int,
    val height: Int,
    val sketch: String,
    val swatches: List<SwatchJson>,
)

@Serializable
internal data class SwatchJson(
    val seed: String,
    val schemes: Map<String, SchemePairJson>,
)

@Serializable
internal data class SchemePairJson(
    val light: Map<String, String>,
    val dark: Map<String, String>,
)

internal val ResultJson = Json { ignoreUnknownKeys = true }

internal fun SketchResultJson.toDrawing(createdAt: Long) = Drawing(
    id = id,
    width = width,
    height = height,
    sketchPath = sketch,
    swatches = swatches.map { swatch ->
        Swatch(
            seed = parseHex(swatch.seed),
            schemes = SchemeVariant.entries.associateWith { variant ->
                val pair = swatch.schemes.getValue(variant.key)
                SchemePair(light = pair.light.toColorScheme(dark = false), dark = pair.dark.toColorScheme(dark = true))
            },
        )
    },
    createdAt = createdAt,
)

internal fun Map<String, String>.toColorScheme(dark: Boolean): ColorScheme {
    // Any role missing from the JSON keeps the Material baseline value.
    val base = if (dark) darkColorScheme() else lightColorScheme()
    fun role(name: String, fallback: Color) = get(name)?.let(::parseHex) ?: fallback
    return base.copy(
        primary = role("primary", base.primary),
        onPrimary = role("on_primary", base.onPrimary),
        primaryContainer = role("primary_container", base.primaryContainer),
        onPrimaryContainer = role("on_primary_container", base.onPrimaryContainer),
        inversePrimary = role("inverse_primary", base.inversePrimary),
        secondary = role("secondary", base.secondary),
        onSecondary = role("on_secondary", base.onSecondary),
        secondaryContainer = role("secondary_container", base.secondaryContainer),
        onSecondaryContainer = role("on_secondary_container", base.onSecondaryContainer),
        tertiary = role("tertiary", base.tertiary),
        onTertiary = role("on_tertiary", base.onTertiary),
        tertiaryContainer = role("tertiary_container", base.tertiaryContainer),
        onTertiaryContainer = role("on_tertiary_container", base.onTertiaryContainer),
        background = role("background", base.background),
        onBackground = role("on_background", base.onBackground),
        surface = role("surface", base.surface),
        onSurface = role("on_surface", base.onSurface),
        surfaceVariant = role("surface_variant", base.surfaceVariant),
        onSurfaceVariant = role("on_surface_variant", base.onSurfaceVariant),
        surfaceTint = role("surface_tint", base.surfaceTint),
        inverseSurface = role("inverse_surface", base.inverseSurface),
        inverseOnSurface = role("inverse_on_surface", base.inverseOnSurface),
        error = role("error", base.error),
        onError = role("on_error", base.onError),
        errorContainer = role("error_container", base.errorContainer),
        onErrorContainer = role("on_error_container", base.onErrorContainer),
        outline = role("outline", base.outline),
        outlineVariant = role("outline_variant", base.outlineVariant),
        scrim = role("scrim", base.scrim),
        surfaceBright = role("surface_bright", base.surfaceBright),
        surfaceDim = role("surface_dim", base.surfaceDim),
        surfaceContainer = role("surface_container", base.surfaceContainer),
        surfaceContainerHigh = role("surface_container_high", base.surfaceContainerHigh),
        surfaceContainerHighest = role("surface_container_highest", base.surfaceContainerHighest),
        surfaceContainerLow = role("surface_container_low", base.surfaceContainerLow),
        surfaceContainerLowest = role("surface_container_lowest", base.surfaceContainerLowest),
    )
}

/** `#rrggbb` → opaque [Color]. */
internal fun parseHex(hex: String): Color = Color(0xFF000000 or hex.removePrefix("#").toLong(16))
