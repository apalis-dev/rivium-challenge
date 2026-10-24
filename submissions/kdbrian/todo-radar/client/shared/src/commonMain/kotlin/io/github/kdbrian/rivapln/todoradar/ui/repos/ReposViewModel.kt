package io.github.kdbrian.rivapln.todoradar.ui.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kdbrian.rivapln.todoradar.api.RadarApi
import io.github.kdbrian.rivapln.todoradar.api.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReposState(
    val query: String = "",
    val repos: List<Repo> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val starting: String? = null,
)

@OptIn(FlowPreview::class)
class ReposViewModel(private val api: RadarApi) : ViewModel() {
    private val _state = MutableStateFlow(ReposState())
    val state: StateFlow<ReposState> = _state.asStateFlow()
    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            query.debounce(350).distinctUntilChanged().collectLatest { q ->
                _state.update { it.copy(loading = true, error = null) }
                try {
                    val repos = api.repos(q)
                    _state.update { it.copy(repos = repos, loading = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, error = e.message, repos = emptyList()) }
                }
            }
        }
    }

    fun onQuery(value: String) {
        _state.update { it.copy(query = value) }
        query.value = value.trim()
    }

    fun scan(repo: Repo, onStarted: (Long) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(starting = repo.fullName) }
        try {
            onStarted(api.startScan(repo.owner, repo.name).id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        } finally {
            _state.update { it.copy(starting = null) }
        }
    }
}
