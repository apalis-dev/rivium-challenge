package io.github.samuel0kello.mchoro

import android.app.Application
import android.content.Context
import io.github.samuel0kello.mchoro.data.DrawingRepository
import io.github.samuel0kello.mchoro.data.FileDrawingRepository
import io.github.samuel0kello.mchoro.data.MchoroEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class MchoroApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this) // also starts the Rust worker in the background
    }
}

/** Hand-written DI: the app is small enough that one container beats a DI framework. */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val drawingRepository: DrawingRepository = FileDrawingRepository(
        context = context.applicationContext,
        rust = MchoroEngine(File(context.filesDir, "mchoro")),
        scope = appScope,
    )
}

val Context.appContainer: AppContainer get() = (applicationContext as MchoroApp).container
