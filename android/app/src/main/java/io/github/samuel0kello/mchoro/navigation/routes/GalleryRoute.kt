package io.github.samuel0kello.mchoro.navigation.routes

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.samuel0kello.mchoro.feature.gallery.GalleryRoute
import io.github.samuel0kello.mchoro.navigation.Route

fun EntryProviderScope<NavKey>.galleryRoute(backStack: NavBackStack<NavKey>) {
    entry<Route.Gallery> {
        GalleryRoute(onOpenStudio = { id -> backStack.add(Route.Studio(id)) })
    }
}
