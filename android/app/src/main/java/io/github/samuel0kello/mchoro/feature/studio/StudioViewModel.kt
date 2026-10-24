package io.github.samuel0kello.mchoro.feature.studio

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.samuel0kello.mchoro.R
import io.github.samuel0kello.mchoro.data.DrawingRepository
import io.github.samuel0kello.mchoro.data.model.Drawing
import io.github.samuel0kello.mchoro.data.model.DrawingStyle
import io.github.samuel0kello.mchoro.data.model.SchemeVariant
import io.github.samuel0kello.mchoro.data.model.schemeFor
import io.github.samuel0kello.mchoro.designsystem.theme.ink
import io.github.samuel0kello.mchoro.designsystem.theme.paper
import io.github.samuel0kello.mchoro.presentation.UiText
import io.github.samuel0kello.mchoro.presentation.ViewState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class StudioState(
    val content: ViewState<StudioContent> = ViewState.Loading,
    val showDeleteDialog: Boolean = false,
    val isSharing: Boolean = false,
)

data class StudioContent(
    val sketch: ImageBitmap,
    /** Seed colours from the photo, best first. */
    val swatches: List<Color>,
    val style: DrawingStyle,
    /** The scheme the whole screen wears: the chosen swatch × variant × paper. */
    val scheme: ColorScheme,
) {
    val ink: Color get() = scheme.ink
    val paper: Color get() = scheme.paper
}

sealed interface StudioAction {
    data class SwatchSelected(val index: Int) : StudioAction
    data class VariantSelected(val variant: SchemeVariant) : StudioAction
    data object PaperToggled : StudioAction
    data object ShareClicked : StudioAction
    data object DeleteClicked : StudioAction
    data object DeleteConfirmed : StudioAction
    data object DeleteDismissed : StudioAction
    data object BackClicked : StudioAction
}

sealed interface StudioEvent {
    data object NavigateBack : StudioEvent
    data class ShareImage(val file: File) : StudioEvent
    data class ShowMessage(val message: UiText) : StudioEvent
}

class StudioViewModel(
    private val drawingId: String,
    private val repository: DrawingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private val _events = Channel<StudioEvent>()
    val events = _events.receiveAsFlow()

    private var drawing: Drawing? = null
    private var sketch: ImageBitmap? = null

    init {
        viewModelScope.launch {
            combine(repository.drawing(drawingId), repository.styles) { drawing, styles ->
                drawing to (styles[drawingId] ?: DrawingStyle())
            }.collect { (found, style) ->
                if (found == null) {
                    // Deleted (here or elsewhere): nothing to show.
                    if (drawing != null) _events.send(StudioEvent.NavigateBack)
                    else _state.update { it.copy(content = ViewState.Error(UiText.Resource(R.string.studio_not_found))) }
                    return@collect
                }
                drawing = found
                val bitmap = sketch ?: repository.loadSketch(found, SKETCH_SIDE).also { sketch = it }
                _state.update {
                    it.copy(
                        content = ViewState.Success(
                            StudioContent(
                                sketch = bitmap,
                                swatches = found.swatches.map { swatch -> swatch.seed },
                                style = style,
                                scheme = found.schemeFor(style),
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun onAction(action: StudioAction) {
        when (action) {
            is StudioAction.SwatchSelected -> updateStyle { it.copy(swatchIndex = action.index) }
            is StudioAction.VariantSelected -> updateStyle { it.copy(variant = action.variant) }
            StudioAction.PaperToggled -> updateStyle { it.copy(darkPaper = !it.darkPaper) }
            StudioAction.ShareClicked -> share()
            StudioAction.DeleteClicked -> _state.update { it.copy(showDeleteDialog = true) }
            StudioAction.DeleteDismissed -> _state.update { it.copy(showDeleteDialog = false) }
            StudioAction.DeleteConfirmed -> viewModelScope.launch {
                _state.update { it.copy(showDeleteDialog = false) }
                repository.delete(drawingId) // the collector above then navigates back
            }
            StudioAction.BackClicked -> viewModelScope.launch { _events.send(StudioEvent.NavigateBack) }
        }
    }

    private fun updateStyle(change: (DrawingStyle) -> DrawingStyle) {
        viewModelScope.launch {
            val current = repository.styles.first()[drawingId] ?: DrawingStyle()
            repository.saveStyle(drawingId, change(current))
        }
    }

    private fun share() {
        val current = drawing ?: return
        val content = (_state.value.content as? ViewState.Success)?.data ?: return
        if (_state.value.isSharing) return
        viewModelScope.launch {
            _state.update { it.copy(isSharing = true) }
            runCatching { repository.exportForSharing(current, content.ink, content.paper) }
                .onSuccess { _events.send(StudioEvent.ShareImage(it)) }
                .onFailure { _events.send(StudioEvent.ShowMessage(UiText.Resource(R.string.studio_share_failed))) }
            _state.update { it.copy(isSharing = false) }
        }
    }

    private companion object {
        const val SKETCH_SIDE = 2048
    }
}
