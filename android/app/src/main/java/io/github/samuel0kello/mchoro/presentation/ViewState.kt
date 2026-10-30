package io.github.samuel0kello.mchoro.presentation

sealed interface ViewState<out T> {
    data object Loading : ViewState<Nothing>
    data class Error(val message: UiText) : ViewState<Nothing>
    data class Success<T>(val data: T) : ViewState<T>
}
