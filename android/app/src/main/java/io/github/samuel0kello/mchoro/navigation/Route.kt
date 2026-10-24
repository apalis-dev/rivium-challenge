package io.github.samuel0kello.mchoro.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Gallery : Route

    @Serializable data class Studio(val drawingId: String) : Route
}
