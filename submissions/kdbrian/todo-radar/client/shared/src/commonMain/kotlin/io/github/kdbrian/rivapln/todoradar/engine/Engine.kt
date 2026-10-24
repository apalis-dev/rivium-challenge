package io.github.kdbrian.rivapln.todoradar.engine

import io.github.kdbrian.rivapln.todoradar.api.FakeRadarApi
import io.github.kdbrian.rivapln.todoradar.api.RadarApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data object Running : EngineState

    /** Stopped on purpose by the user; queued work resumes on [Engine.start]. */
    data object Paused : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * The Rust engine as seen by the clients: a lifecycle (Rivium start/stop through JNI) plus the
 * localhost [api]. Pausing is just a graceful Rivium stop; Apalis re-enqueues unfinished work.
 */
interface Engine {
    val state: StateFlow<EngineState>
    val api: RadarApi

    /** True when the native library is missing and [api] is the in-memory demo. */
    val isDemo: Boolean

    suspend fun start()
    suspend fun stop(paused: Boolean = false)
}

/** Fallback used when `radar_jni` cannot be loaded (e.g. the `main` branch build). */
class DemoEngine : Engine {
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    override val api: RadarApi = FakeRadarApi()
    override val isDemo = true

    override suspend fun start() {
        _state.value = EngineState.Running
    }

    override suspend fun stop(paused: Boolean) {
        _state.value = if (paused) EngineState.Paused else EngineState.Stopped
    }
}
