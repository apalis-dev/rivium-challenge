package io.github.samuel0kello.mchoro.feature.gallery

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.samuel0kello.mchoro.data.DrawingRepository
import io.github.samuel0kello.mchoro.data.model.Drawing
import io.github.samuel0kello.mchoro.data.model.DrawingStyle
import io.github.samuel0kello.mchoro.data.model.EngineState
import io.github.samuel0kello.mchoro.data.model.SketchingPhoto
import io.github.samuel0kello.mchoro.data.model.schemeFor
import io.github.samuel0kello.mchoro.designsystem.theme.ink
import io.github.samuel0kello.mchoro.designsystem.theme.paper
import io.github.samuel0kello.mchoro.presentation.UiText
import io.github.samuel0kello.mchoro.presentation.ViewState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GalleryState(
    val content: ViewState<List<GalleryItem>> = ViewState.Loading,
)

sealed interface GalleryItem {
    val id: String

    /** A photo waiting for (or inside) the Rust worker. */
    data class Sketching(override val id: String, val photo: ImageBitmap?) : GalleryItem

    data class Ready(
        override val id: String,
        val sketch: ImageBitmap,
        val ink: Color,
        val paper: Color,
    ) : GalleryItem
}

sealed interface GalleryAction {
    data object AddPhotosClicked : GalleryAction
    data class PhotosPicked(val uris: List<Uri>) : GalleryAction
    data class DrawingClicked(val id: String) : GalleryAction
    data object RetryClicked : GalleryAction
}

sealed interface GalleryEvent {
    data object OpenPhotoPicker : GalleryEvent
    data class OpenStudio(val id: String) : GalleryEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(
    private val repository: DrawingRepository,
) : ViewModel() {

    private val _events = Channel<GalleryEvent>()
    val events = _events.receiveAsFlow()

    // Bitmaps are decoded once per file, not on every folder scan.
    private val thumbnails = mutableMapOf<String, ImageBitmap>()
    private val photos = mutableMapOf<String, ImageBitmap?>()

    val state: StateFlow<GalleryState> = combine(
        repository.engine,
        repository.drawings,
        repository.sketching,
        repository.styles,
    ) { engine, drawings, sketching, styles -> Snapshot(engine, drawings, sketching, styles) }
        .mapLatest(::toState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryState())

    fun onAction(action: GalleryAction) {
        when (action) {
            GalleryAction.AddPhotosClicked -> send(GalleryEvent.OpenPhotoPicker)
            is GalleryAction.PhotosPicked -> viewModelScope.launch { repository.addPhotos(action.uris) }
            is GalleryAction.DrawingClicked -> send(GalleryEvent.OpenStudio(action.id))
            GalleryAction.RetryClicked -> viewModelScope.launch { repository.startEngine() }
        }
    }

    private suspend fun toState(snapshot: Snapshot): GalleryState {
        val (engine, drawings, sketching, styles) = snapshot
        if (engine is EngineState.Failed) {
            // The title is fixed copy in the UI; the message is the engine's own reason.
            return GalleryState(ViewState.Error(UiText.Raw(engine.reason)))
        }
        if (engine == EngineState.Starting && drawings.isEmpty()) return GalleryState(ViewState.Loading)

        val done = drawings.mapTo(HashSet()) { it.id }
        val queued = sketching.filter { it.id !in done }.map { photo ->
            GalleryItem.Sketching(photo.id, photos.getOrPut(photo.id) { repository.loadPhoto(photo, THUMB_SIDE) })
        }
        val ready = drawings.map { drawing ->
            val scheme = drawing.schemeFor(styles[drawing.id] ?: DrawingStyle())
            GalleryItem.Ready(
                id = drawing.id,
                sketch = thumbnails.getOrPut(drawing.id) { repository.loadSketch(drawing, THUMB_SIDE) },
                ink = scheme.ink,
                paper = scheme.paper,
            )
        }
        return GalleryState(ViewState.Success(queued + ready))
    }

    private fun send(event: GalleryEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    private data class Snapshot(
        val engine: EngineState,
        val drawings: List<Drawing>,
        val sketching: List<SketchingPhoto>,
        val styles: Map<String, DrawingStyle>,
    )

    private companion object {
        const val THUMB_SIDE = 512
    }
}
