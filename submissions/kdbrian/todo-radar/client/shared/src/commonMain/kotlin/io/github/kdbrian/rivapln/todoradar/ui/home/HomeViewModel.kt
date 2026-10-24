package io.github.kdbrian.rivapln.todoradar.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kdbrian.rivapln.todoradar.api.AuthStatus
import io.github.kdbrian.rivapln.todoradar.api.Scan
import io.github.kdbrian.rivapln.todoradar.engine.Engine
import io.github.kdbrian.rivapln.todoradar.engine.EngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class HomeState(
    val engine: EngineState = EngineState.Stopped,
    val isDemo: Boolean = false,
    val auth: AuthStatus? = null,
    val scans: List<Scan> = emptyList(),
    val loaded: Boolean = false,
    val message: String? = null,
) {
    val active get() = scans.filter { it.isActive }
    val pendingCleanup get() = scans.filter { it.cleanupPending }
    val history get() = scans.filter { !it.isActive }
    val paused get() = engine == EngineState.Paused
}

/** Engine lifecycle, sign-in and the live scan list. Polls only while the engine runs. */
class HomeViewModel(private val engine: Engine) : ViewModel() {
    private val _state = MutableStateFlow(HomeState(isDemo = engine.isDemo))
    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch { engine.start() }
        viewModelScope.launch {
            engine.state.collectLatest { engineState ->
                _state.update { it.copy(engine = engineState) }
                if (engineState == EngineState.Running) poll()
            }
        }
    }

    private suspend fun poll() {
        var tick = 0
        var forced = true
        while (true) {
            try {
                val scans = engine.api.scans()
                val auth = if (forced || tick % 5 == 0 || _state.value.auth?.pending == true) engine.api.authStatus()
                else _state.value.auth
                _state.update { it.copy(scans = scans, auth = auth, loaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loaded = true) }
            }
            tick++
            val wait = if (_state.value.active.isNotEmpty() || _state.value.auth?.pending == true) 1_000L else 3_000L
            // Wake early when the host asks (app resumed, e.g. after authorizing on another device).
            forced = withTimeoutOrNull(wait) { refresh.first() } != null
        }
    }

    /** Poll right away instead of waiting for the next tick. */
    fun refreshNow() {
        refresh.tryEmit(Unit)
    }

    fun pause() = viewModelScope.launch { engine.stop(paused = true) }

    fun resume() = viewModelScope.launch { engine.start() }

    fun signIn() = action { _state.update { s -> s.copy(auth = engine.api.startSignIn()) } }

    fun signOut() = action {
        engine.api.signOut()
        _state.update { it.copy(auth = engine.api.authStatus()) }
    }

    fun keep(id: Long) = action { replace(engine.api.keepFiles(id)) }

    fun deleteNow(id: Long) = action { replace(engine.api.deleteFiles(id)) }

    fun scanZip(path: String, onStarted: (Long) -> Unit) = action {
        val scan = engine.api.startZipScan(path)
        replace(scan)
        onStarted(scan.id)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun replace(scan: Scan) = _state.update { s ->
        s.copy(scans = listOf(scan) + s.scans.filter { it.id != scan.id })
    }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = e.message ?: "Something went wrong") }
        }
    }
}
