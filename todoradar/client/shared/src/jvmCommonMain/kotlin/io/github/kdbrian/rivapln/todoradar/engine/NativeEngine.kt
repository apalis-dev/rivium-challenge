package io.github.kdbrian.rivapln.todoradar.engine

import io.github.kdbrian.rivapln.todoradar.api.HttpRadarApi
import io.github.kdbrian.rivapln.todoradar.api.RadarApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

/** Engine options passed to Rivium as `--set` overrides. */
data class EngineConfig(
    /** 0 picks a free loopback port, so several instances (or `cargo run`) never clash. */
    val port: Int = 0,
    val githubClientId: String = "",
    /** Optional pre-issued GitHub token (desktop dev: `gh auth token`). */
    val githubToken: String = "",
)

/**
 * The Rust engine embedded in this JVM (Android or desktop) through JNI. Shared by both apps:
 * only library loading and the root directory differ per platform.
 */
class NativeEngine(
    private val root: File,
    private val config: EngineConfig = EngineConfig(),
) : Engine {
    /** Fresh secret per process; only this app can call the localhost API. */
    private val token = UUID.randomUUID().toString()
    private val port = config.port.takeIf { it != 0 } ?: ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    private val lock = Mutex()
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)

    override val state: StateFlow<EngineState> = _state.asStateFlow()
    override val api: RadarApi = HttpRadarApi("http://127.0.0.1:$port/", token)
    override val isDemo = false

    val version: String get() = runCatching { RadarBridge.nativeVersion() }.getOrDefault("unknown")

    override suspend fun start() = lock.withLock {
        if (_state.value == EngineState.Running) return@withLock
        _state.value = EngineState.Starting
        val code = withContext(Dispatchers.IO) {
            root.mkdirs()
            val args = buildList {
                add("--root"); add(root.absolutePath)
                add("--set"); add("api_token=$token")
                add("--set"); add("port=$port")
                if (config.githubClientId.isNotBlank()) { add("--set"); add("github_client_id=${config.githubClientId}") }
                if (config.githubToken.isNotBlank()) { add("--set"); add("github_token=${config.githubToken}") }
            }
            RadarBridge.nativeStart(args.toTypedArray())
        }
        if (code != 0) {
            _state.value = EngineState.Failed(RadarBridge.nativeLastError().ifBlank { "engine start failed ($code)" })
            return@withLock
        }
        // nativeStart returns once services are constructed; wait for the API to answer.
        repeat(50) {
            if (api.health()) {
                _state.value = EngineState.Running
                return@withLock
            }
            delay(100)
        }
        _state.value = EngineState.Failed("engine started but the API did not answer")
    }

    override suspend fun stop(paused: Boolean) = lock.withLock {
        if (_state.value == EngineState.Stopped || _state.value == EngineState.Paused) return@withLock
        withContext(Dispatchers.IO) { RadarBridge.nativeStop(5_000) }
        _state.value = if (paused) EngineState.Paused else EngineState.Stopped
    }

    companion object {
        /** Load the native engine with [load], falling back to the demo when it is missing. */
        fun createOrDemo(root: File, config: EngineConfig, load: () -> Unit): Engine =
            try {
                load()
                NativeEngine(root, config)
            } catch (error: UnsatisfiedLinkError) {
                System.err.println("TODO Radar: native engine unavailable, running demo mode: ${error.message}")
                DemoEngine()
            }
    }
}
