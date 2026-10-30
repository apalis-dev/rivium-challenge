package io.github.samuel0kello.mchoro.feature.gallery

import androidx.compose.ui.graphics.Color
import io.github.samuel0kello.mchoro.designsystem.PreviewSketches
import io.github.samuel0kello.mchoro.presentation.UiText
import io.github.samuel0kello.mchoro.presentation.ViewState

/** Shared by previews (and screenshot tests later) so they render identical states. */
internal object GalleryPreviewData {
    val empty = GalleryState(ViewState.Success(emptyList()))

    val error = GalleryState(ViewState.Error(UiText.Raw("code 78: invalid configuration")))

    // Colours are the tonal_spot primary/surface Rust produced for the sample photos.
    fun withItems() = GalleryState(
        ViewState.Success(
            listOf(
                GalleryItem.Sketching(id = "queued", photo = null),
                GalleryItem.Ready("parrot", PreviewSketches.mask(), ink = Color(0xFF8C4E3F), paper = Color(0xFFFFF8F6)),
                GalleryItem.Ready("owl", PreviewSketches.mask(320, 320), ink = Color(0xFF725C2E), paper = Color(0xFFFFF8F1)),
                GalleryItem.Ready("zebra", PreviewSketches.mask(320, 440), ink = Color(0xFF3D6288), paper = Color(0xFFF8F9FF)),
            ),
        ),
    )
}
