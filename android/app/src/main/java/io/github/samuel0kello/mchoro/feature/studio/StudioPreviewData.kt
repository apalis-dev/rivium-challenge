package io.github.samuel0kello.mchoro.feature.studio

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.samuel0kello.mchoro.R
import io.github.samuel0kello.mchoro.data.model.DrawingStyle
import io.github.samuel0kello.mchoro.designsystem.PreviewSketches
import io.github.samuel0kello.mchoro.presentation.UiText
import io.github.samuel0kello.mchoro.presentation.ViewState

/** Real roles Rust produced for the parrot sample (seed #de3709, tonal_spot). */
internal object StudioPreviewData {
    private val parrotLight = lightColorScheme(
        primary = Color(0xFF8C4E3F), onPrimary = Color(0xFFFFF7F6), primaryContainer = Color(0xFFFDAD9A),
        onPrimaryContainer = Color(0xFF622D20), secondaryContainer = Color(0xFFFFDAD2),
        onSecondaryContainer = Color(0xFF694A43), tertiary = Color(0xFF775A26), surface = Color(0xFFFFF8F6),
        onSurface = Color(0xFF3E2F2B), onSurfaceVariant = Color(0xFF6D5B57), surfaceContainerLow = Color(0xFFFFF0ED),
        surfaceContainer = Color(0xFFFFE9E5), surfaceContainerHigh = Color(0xFFFAE3DE),
        surfaceContainerHighest = Color(0xFFF6DDD8), outline = Color(0xFF8A7672), outlineVariant = Color(0xFFC3ADA8),
    )

    private val parrotDark = darkColorScheme(
        primary = Color(0xFFF9B7A7), onPrimary = Color(0xFF613429), primaryContainer = Color(0xFF76463A),
        onPrimaryContainer = Color(0xFFFFDBD3), secondaryContainer = Color(0xFF51342E),
        onSecondaryContainer = Color(0xFFDFB6AC), tertiary = Color(0xFFFFE8C7), surface = Color(0xFF130D0B),
        onSurface = Color(0xFFF9E0DA), onSurfaceVariant = Color(0xFFBCA6A1), surfaceContainerLow = Color(0xFF1A110F),
        surfaceContainer = Color(0xFF221715), surfaceContainerHigh = Color(0xFF291D1A),
        surfaceContainerHighest = Color(0xFF30231F), outline = Color(0xFF84716D), outlineVariant = Color(0xFF554440),
    )

    private val parrotSeeds = listOf(Color(0xFFDE3709), Color(0xFF2B6D41), Color(0xFF444038), Color(0xFF3B4F66))

    val notFound = StudioState(ViewState.Error(UiText.Resource(R.string.studio_not_found)))

    fun drawing(darkPaper: Boolean = false) = StudioState(
        ViewState.Success(
            StudioContent(
                sketch = PreviewSketches.mask(),
                swatches = parrotSeeds,
                style = DrawingStyle(darkPaper = darkPaper),
                scheme = if (darkPaper) parrotDark else parrotLight,
            ),
        ),
    )
}
