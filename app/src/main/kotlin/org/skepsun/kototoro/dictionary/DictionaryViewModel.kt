package org.skepsun.kototoro.dictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.dictionary.DictionaryLookupResult
import org.skepsun.kototoro.core.dictionary.DictionaryLookupService
import org.skepsun.kototoro.core.dictionary.DictionaryRule
import org.skepsun.kototoro.core.dictionary.DictionaryRuleRepository
import javax.inject.Inject

data class DictionaryPageState(
    val rule: DictionaryRule,
    val result: DictionaryLookupResult? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

data class DictionaryUiState(
    val word: String = "",
    val pages: List<DictionaryPageState> = emptyList(),
    val isSearching: Boolean = false,
)

@HiltViewModel
class DictionaryViewModel @Inject constructor(
    private val repository: DictionaryRuleRepository,
    private val lookupService: DictionaryLookupService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DictionaryUiState())
    val uiState: StateFlow<DictionaryUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null

    fun search(word: String) {
        val query = word.trim()
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val rules = repository.getEnabled()
            _uiState.value = DictionaryUiState(
                word = query,
                pages = rules.map { DictionaryPageState(it, isLoading = true) },
                isSearching = rules.isNotEmpty(),
            )
            rules.forEach { rule ->
                launch {
                    val pageResult = runCatching { lookupService.search(rule, query) }.fold(
                        onSuccess = { DictionaryPageState(rule = rule, result = it, isLoading = false) },
                        onFailure = { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            DictionaryPageState(rule = rule, error = error, isLoading = false)
                        },
                    )
                    _uiState.update { current ->
                        val updated = current.pages.map { if (it.rule.name == rule.name) pageResult else it }
                        current.copy(
                            pages = updated,
                            isSearching = updated.any { it.isLoading },
                        )
                    }
                }
            }
        }
    }

    fun retryRule(rule: DictionaryRule) {
        val query = _uiState.value.word.trim()
        if (query.isBlank()) return
        _uiState.update { current ->
            val updated = current.pages.map {
                if (it.rule.name == rule.name) it.copy(isLoading = true, error = null) else it
            }
            current.copy(pages = updated, isSearching = true)
        }
        viewModelScope.launch {
            val pageResult = runCatching { lookupService.search(rule, query) }.fold(
                onSuccess = { DictionaryPageState(rule = rule, result = it, isLoading = false) },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    DictionaryPageState(rule = rule, error = error, isLoading = false)
                },
            )
            _uiState.update { current ->
                val updated = current.pages.map { if (it.rule.name == rule.name) pageResult else it }
                current.copy(
                    pages = updated,
                    isSearching = updated.any { it.isLoading },
                )
            }
        }
    }
}
