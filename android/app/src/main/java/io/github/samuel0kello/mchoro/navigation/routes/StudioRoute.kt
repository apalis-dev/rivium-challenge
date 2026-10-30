package io.github.samuel0kello.mchoro.navigation.routes

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.samuel0kello.mchoro.feature.studio.StudioRoute
import io.github.samuel0kello.mchoro.navigation.Route

fun EntryProviderScope<NavKey>.studioRoute(backStack: NavBackStack<NavKey>) {
    entry<Route.Studio> { key ->
        StudioRoute(drawingId = key.drawingId, onBack = { backStack.removeLastOrNull() })
    }
}
