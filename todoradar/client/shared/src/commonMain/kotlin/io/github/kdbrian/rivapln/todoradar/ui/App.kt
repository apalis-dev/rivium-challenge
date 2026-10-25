package io.github.kdbrian.rivapln.todoradar.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kdbrian.rivapln.todoradar.engine.Engine
import io.github.kdbrian.rivapln.todoradar.platform.LocalPlatform
import io.github.kdbrian.rivapln.todoradar.platform.Platform
import io.github.kdbrian.rivapln.todoradar.platform.PlatformBackHandler
import io.github.kdbrian.rivapln.todoradar.ui.home.HomeScreen
import io.github.kdbrian.rivapln.todoradar.ui.home.HomeViewModel
import io.github.kdbrian.rivapln.todoradar.ui.repos.ReposScreen
import io.github.kdbrian.rivapln.todoradar.ui.repos.ReposViewModel
import io.github.kdbrian.rivapln.todoradar.ui.scan.ScanScreen
import io.github.kdbrian.rivapln.todoradar.ui.scan.ScanViewModel
import io.github.kdbrian.rivapln.todoradar.ui.theme.RadarTheme

sealed interface Route {
    data object Home : Route
    data object Repos : Route
    data class ScanDetail(val id: Long) : Route
}

/** Navigation with a plain back stack; [openScan] lets the host deep-link (e.g. a notification). */
class Navigator(start: List<Route> = listOf(Route.Home)) {
    val stack = mutableStateListOf<Route>().apply { addAll(start) }
    val current get() = stack.last()
    val canGoBack get() = stack.size > 1

    /** Direction of the last change, for the slide transition. */
    var forward = true
        private set

    fun push(route: Route) {
        forward = true
        stack.add(route)
    }

    fun pop() {
        if (!canGoBack) return
        forward = false
        stack.removeAt(stack.lastIndex)
    }

    fun openScan(id: Long) {
        forward = true
        stack.removeAll { it is Route.ScanDetail || it is Route.Repos }
        stack.add(Route.ScanDetail(id))
    }
}

/** The whole UI, shared by Android and desktop. Hosts only supply the engine and platform. */
@Composable
fun App(engine: Engine, platform: Platform, navigator: Navigator = remember { Navigator() }) {
    CompositionLocalProvider(LocalPlatform provides platform) {
        RadarTheme {
            val home = viewModel { HomeViewModel(engine) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { home.refreshNow() }
            PlatformBackHandler(enabled = navigator.canGoBack) { navigator.pop() }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AnimatedContent(
                    targetState = navigator.current,
                    transitionSpec = {
                        val forward = navigator.forward
                        (slideInHorizontally(tween(260)) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(260)))
                            .togetherWith(slideOutHorizontally(tween(200)) { if (forward) -it / 8 else it / 8 } + fadeOut(tween(200)))
                    },
                    contentKey = { it.toString() },
                ) { route ->
                    // Phones use the full width; wide desktop windows get a centred column.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 820.dp).fillMaxSize()) {
                            when (route) {
                                Route.Home -> HomeScreen(
                                    vm = home,
                                    onScanRepo = { navigator.push(Route.Repos) },
                                    onOpenScan = navigator::openScan,
                                )
                                Route.Repos -> {
                                    val vm = viewModel(key = "repos") { ReposViewModel(engine.api) }
                                    ReposScreen(vm, onBack = navigator::pop, onStarted = navigator::openScan)
                                }
                                is Route.ScanDetail -> {
                                    val vm = viewModel(key = "scan-${route.id}") { ScanViewModel(engine.api, route.id) }
                                    ScanScreen(vm, onBack = navigator::pop, onOpenScan = navigator::openScan)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
