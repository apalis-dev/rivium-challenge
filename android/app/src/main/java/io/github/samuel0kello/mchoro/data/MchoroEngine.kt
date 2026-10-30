package io.github.samuel0kello.mchoro.data

import android.util.Log
import io.github.samuel0kello.mchoro.RustBridge
import io.github.samuel0kello.mchoro.data.model.EngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Starts the Rust worker (Rivium host + Apalis queue) inside this app's process.
 *
 * Rust and Kotlin share nothing but files under [root]:
 * the app drops photos into `inbox/`, Rust writes drawings into `outbox/`,
 * and Apalis keeps its queue in `jobs.db`, so unfinished jobs survive the app being closed.
 */
internal class MchoroEngine(val root: File) {
    val inbox = File(root, "inbox")
    val pending = File(root, "pending")
    val outbox = File(root, "outbox")

    private val _state = MutableStateFlow<EngineState>(EngineState.Starting)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val startLock = Mutex()

    /** Safe to call again after a failure. `nativeStart` blocks, so it runs on the IO pool. */
    suspend fun start(): Unit = startLock.withLock {
        if (_state.value == EngineState.Running) return@withLock
        _state.value = EngineState.Starting
        _state.value = withContext(Dispatchers.IO) {
            try {
                root.mkdirs()
                val code = RustBridge.nativeStart(
                    arrayOf("--root", root.absolutePath, "--set", "log.logcat.filter=info"),
                )
                // 0 = started, 1 = already running (e.g. the activity was recreated).
                if (code == 0 || code == 1) {
                    Log.i(TAG, "Rust worker ${RustBridge.nativeVersion()} running in $root")
                    EngineState.Running
                } else {
                    EngineState.Failed("code $code: ${RustBridge.nativeLastError()}")
                }
            } catch (e: UnsatisfiedLinkError) {
                EngineState.Failed(e.message ?: "libmchoro.so not found")
            }
        }
        (_state.value as? EngineState.Failed)?.let { Log.e(TAG, "Rust worker failed: ${it.reason}") }
    }

    private companion object {
        const val TAG = "MchoroEngine"
    }
}
