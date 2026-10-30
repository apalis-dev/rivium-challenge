package io.github.samuel0kello.mchoro.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.samuel0kello.mchoro.navigation.routes.galleryRoute
import io.github.samuel0kello.mchoro.navigation.routes.studioRoute

@Composable
fun MainNavDisplay() {
    val backStack = rememberNavBackStack(Route.Gallery)
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        // Each destination gets its own saved state and ViewModels, cleared when popped.
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            galleryRoute(backStack)
            studioRoute(backStack)
        },
    )
}
