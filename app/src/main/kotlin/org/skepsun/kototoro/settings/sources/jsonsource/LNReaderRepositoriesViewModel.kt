package org.skepsun.kototoro.settings.sources.jsonsource

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import javax.inject.Inject

@HiltViewModel
class LNReaderRepositoriesViewModel @Inject constructor(
    private val settings: AppSettings,
) : BaseViewModel() {

    val repos: StateFlow<List<String>> = settings.observeAsFlow(
        key = AppSettings.KEY_LNREADER_REPOS,
        valueProducer = { lnReaderRepoUrls }
    )
        .map { it.toList().sorted() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val repoCount: StateFlow<Int> = repos
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _onMessage = MutableEventFlow<String>()
    val onMessage = _onMessage.asSharedFlow()

    fun addRepo(url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return

        val currentRepos = settings.lnReaderRepoUrls
        if (currentRepos.contains(cleanUrl)) {
            viewModelScope.launch {
                _onMessage.call("Repository already exists")
            }
            return
        }

        settings.lnReaderRepoUrls = currentRepos + cleanUrl
        viewModelScope.launch {
            _onMessage.call("Added repository")
        }
    }

    fun deleteRepo(url: String) {
        val currentRepos = settings.lnReaderRepoUrls
        settings.lnReaderRepoUrls = currentRepos - url
        viewModelScope.launch {
            _onMessage.call("Deleted repository")
        }
    }
}
