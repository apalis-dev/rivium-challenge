package io.github.kdbrian.rivapln.todoradar.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kdbrian.rivapln.todoradar.api.Peek
import io.github.kdbrian.rivapln.todoradar.api.RadarApi
import io.github.kdbrian.rivapln.todoradar.api.Scan
import io.github.kdbrian.rivapln.todoradar.api.Todo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Advanced filters over a scan's TODOs; all criteria combine with AND. */
data class TodoFilter(
    val onlyNew: Boolean = false,
    /** Empty = every tag. */
    val tags: Set<String> = emptySet(),
    /** File extensions without the dot (e.g. "rs"); "" means files without one. Empty = all. */
    val extensions: Set<String> = emptySet(),
    /**
     * Name pattern. With `*` or `?` it is a glob on the path (`src/**/*.rs`, `*test*`);
     * otherwise a case-insensitive substring of the path or the TODO text.
     */
    val pattern: String = "",
) {
    /** Count of active criteria, for the badge on the Filters button (onlyNew has its own tab). */
    val activeCount get() = (if (tags.isNotEmpty()) 1 else 0) + (if (extensions.isNotEmpty()) 1 else 0) + (if (pattern.isNotBlank()) 1 else 0)

    private val glob: Regex? by lazy { if ('*' in pattern || '?' in pattern) globToRegex(pattern.trim()) else null }

    fun matches(todo: Todo): Boolean {
        if (onlyNew && !todo.isNew) return false
        if (tags.isNotEmpty() && todo.tag !in tags) return false
        if (extensions.isNotEmpty() && extensionOf(todo.path) !in extensions) return false
        val p = pattern.trim()
        if (p.isEmpty()) return true
        return glob?.let { it.matches(todo.path) || it.matches(todo.path.substringAfterLast('/')) }
            ?: (todo.path.contains(p, ignoreCase = true) || todo.text.contains(p, ignoreCase = true))
    }

    companion object {
        fun extensionOf(path: String): String {
            val name = path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
        }

        /** `**` crosses folders, `*` stays inside one, `?` is one character. */
        fun globToRegex(glob: String): Regex {
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val ch = glob[i]
                when {
                    ch == '*' && glob.getOrNull(i + 1) == '*' -> { out.append(".*"); i++; if (glob.getOrNull(i + 1) == '/') i++ }
                    ch == '*' -> out.append("[^/]*")
                    ch == '?' -> out.append("[^/]")
                    ch in "\\.[]{}()+-^$|" -> out.append('\\').append(ch)
                    else -> out.append(ch)
                }
                i++
            }
            return Regex(out.toString(), RegexOption.IGNORE_CASE)
        }
    }
}

sealed interface PeekState {
    data object Loading : PeekState
    data class Ready(val peek: Peek) : PeekState
    data class Failed(val message: String) : PeekState
}

data class ScanState(
    val scan: Scan? = null,
    val todos: List<Todo> = emptyList(),
    /** False until the first TODO fetch returns, so an empty list isn't shown as "No TODOs". */
    val todosLoaded: Boolean = false,
    val filter: TodoFilter = TodoFilter(),
    val filtersOpen: Boolean = false,
    /** Expanded code peeks keyed by TODO fingerprint + line. */
    val peeks: Map<String, PeekState> = emptyMap(),
    val error: String? = null,
) {
    val visible: List<Todo> get() = todos.filter(filter::matches)

    /** TODOs grouped by file, after filters. */
    val grouped: Map<String, List<Todo>> get() = visible.groupBy { it.path }

    val tagCounts: Map<String, Int> get() = todos.groupingBy { it.tag }.eachCount()

    /** File types present in this scan, most common first. */
    val extensionCounts: List<Pair<String, Int>>
        get() = todos.groupingBy { TodoFilter.extensionOf(it.path) }.eachCount().toList().sortedByDescending { it.second }
}

fun Todo.peekKey() = "$fingerprint:$line"

/** One scan: live progress while it runs, then its TODOs (refetched as checkpoints land). */
class ScanViewModel(private val api: RadarApi, private val id: Long) : ViewModel() {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var lastCount = -1L
            while (true) {
                try {
                    val scan = api.scan(id)
                    _state.update { it.copy(scan = scan, error = null) }
                    if (scan.todoCount != lastCount || scan.isDone) {
                        lastCount = scan.todoCount
                        val todos = api.todos(id)
                        _state.update { it.copy(todos = todos, todosLoaded = true) }
                    }
                    if (!scan.isActive) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message) }
                }
                delay(1_000)
            }
        }
    }

    fun updateFilter(change: (TodoFilter) -> TodoFilter) = _state.update { it.copy(filter = change(it.filter)) }

    fun toggleFilters() = _state.update { it.copy(filtersOpen = !it.filtersOpen) }

    fun clearFilters() = _state.update { it.copy(filter = TodoFilter(onlyNew = it.filter.onlyNew)) }

    /** Expand or collapse the code peek for [todo]; loads it on first expand. */
    fun togglePeek(todo: Todo) {
        val key = todo.peekKey()
        if (_state.value.peeks.containsKey(key)) {
            _state.update { it.copy(peeks = it.peeks - key) }
            return
        }
        _state.update { it.copy(peeks = it.peeks + (key to PeekState.Loading)) }
        viewModelScope.launch {
            val result = try {
                PeekState.Ready(api.peek(id, todo.path, todo.line))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PeekState.Failed(e.message ?: "Couldn't load the code")
            }
            _state.update { s -> if (s.peeks.containsKey(key)) s.copy(peeks = s.peeks + (key to result)) else s }
        }
    }

    fun keep() = mutate { api.keepFiles(id) }

    fun deleteNow() = mutate { api.deleteFiles(id) }

    /**
     * Scan the same source again: a GitHub repo at its latest commit (instant "no changes" when the
     * sha is the same), or a shared zip while it is still on disk. Reports the new scan's id.
     */
    fun rescan(onStarted: (Long) -> Unit) = viewModelScope.launch {
        val scan = _state.value.scan ?: return@launch
        try {
            val next = when {
                scan.owner != null && scan.repo != null -> api.startScan(scan.owner, scan.repo)
                scan.zipPath != null -> api.startZipScan(scan.zipPath)
                else -> return@launch
            }
            onStarted(next.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Couldn't start a rescan") }
        }
    }

    suspend fun report(): String = api.report(id)

    private fun mutate(call: suspend () -> Scan) = viewModelScope.launch {
        try {
            val scan = call()
            _state.update { it.copy(scan = scan) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }
}
