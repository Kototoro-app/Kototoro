package org.skepsun.kototoro.settings.sources.jsonsource

import android.util.Log
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.lnreader.LNReaderPluginInfo
import org.skepsun.kototoro.core.lnreader.LNReaderRepository
import org.skepsun.kototoro.core.network.jsonsource.JsonSourceHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.settings.sources.extensions.normalizeExtensionLanguageCode
import javax.inject.Inject

@HiltViewModel
class LNReaderRepoViewModel @Inject constructor(
    private val jsonSourceManager: JsonSourceManager,
    @JsonSourceHttpClient private val okHttpClient: OkHttpClient,
    private val appSettings: AppSettings,
) : BaseViewModel() {

    private val repository = LNReaderRepository(okHttpClient, jsonSourceManager)

    private val _plugins = MutableStateFlow<List<LNReaderPluginInfo>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val selectedExtensionLanguages: StateFlow<Set<String>> = appSettings.observeAsFlow(
        AppSettings.KEY_EXTENSION_LANGUAGES,
    ) { extensionLanguages }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), appSettings.extensionLanguages)

    private val _collapsedLanguageGroups = MutableStateFlow<Set<String>>(emptySet())
    val collapsedLanguageGroups: StateFlow<Set<String>> = _collapsedLanguageGroups.asStateFlow()

    private val _uiState = MutableStateFlow<RepoUiState>(RepoUiState.Idle)
    val uiState: StateFlow<RepoUiState> = _uiState.asStateFlow()

    private val _installingPluginIds = MutableStateFlow<Set<String>>(emptySet())
    val installingPluginIds: StateFlow<Set<String>> = _installingPluginIds.asStateFlow()

    /** IDs of locally installed LNREADER sources */
    private val installedSourceIds: StateFlow<Set<String>> = jsonSourceManager
        .observeAllJsonSources()
        .combine(MutableStateFlow(Unit)) { sources, _ ->
            sources.filter { it.type == JsonSourceType.LNREADER }
                .map { it.id }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Available languages from the plugin list */
    val availableLanguages: StateFlow<List<String>> = _plugins
        .combine(MutableStateFlow(Unit)) { plugins, _ ->
            val languages = plugins.mapNotNull { plugin ->
                val normalized = plugin.normalizedLanguageCode()
                Log.d(
                    TAG,
                    "language raw='${plugin.lang}' normalized='$normalized' plugin=${plugin.id}",
                )
                normalized.takeIf { it.isNotBlank() }
            }
                .distinct()
                .sorted()
            Log.d(TAG, "language filter availableLanguages=$languages")
            languages
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Filtered + grouped plugin list for display */
    val displayPlugins: StateFlow<List<PluginDisplayItem>> = combine(
        _plugins,
        _searchQuery,
        selectedExtensionLanguages,
        _collapsedLanguageGroups,
        installedSourceIds,
    ) { plugins, query, selectedLanguages, collapsedGroups, installed ->
        val normalizedSelectedLanguages = selectedLanguages
            .mapTo(LinkedHashSet()) { it.normalizeExtensionLanguageCode() }
            .filterTo(LinkedHashSet()) { it.isNotBlank() }
        val filtered = plugins
            .filter { p ->
                val matchesQuery = query.isBlank() || p.name.contains(query, ignoreCase = true) || p.site.contains(query, ignoreCase = true)
                val normalizedLanguage = p.normalizedLanguageCode()
                val matchesLang = normalizedSelectedLanguages.isEmpty() ||
                    normalizedLanguage.isBlank() ||
                    normalizedLanguage in normalizedSelectedLanguages
                matchesQuery && matchesLang
            }

        // Group by language
        val grouped = filtered.groupBy { it.normalizedLanguageCode() }
        val items = mutableListOf<PluginDisplayItem>()
        for ((lang, langPlugins) in grouped.entries.sortedBy { it.key }) {
            val isCollapsed = collapsedGroups.contains(lang)
            items.add(PluginDisplayItem.LangHeader(lang, langPlugins.size, isCollapsed))
            if (!isCollapsed) {
                for (plugin in langPlugins.sortedBy { it.name }) {
                    val isInstalled = installed.any { id ->
                        // Match by hash-based ID pattern
                        id.contains(plugin.site.hashCode().toUInt().toString(16).uppercase()) ||
                            id.contains(plugin.id.hashCode().toUInt().toString(16).uppercase())
                    }
                    items.add(PluginDisplayItem.Plugin(plugin, isInstalled))
                }
            }
        }
        items
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadPlugins() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = RepoUiState.Loading
            val repoUrls = appSettings.lnReaderRepoUrls
            val allPlugins = mutableListOf<LNReaderPluginInfo>()

            for (url in repoUrls) {
                repository.fetchPluginIndex(url)
                    .onSuccess { allPlugins.addAll(it) }
                    .onFailure { e ->
                        android.util.Log.w("LNReaderRepoVM", "Failed to fetch $url", e)
                    }
            }

            if (allPlugins.isNotEmpty()) {
                _plugins.value = allPlugins
                _uiState.value = RepoUiState.Loaded(allPlugins.size)
            } else {
                _uiState.value = RepoUiState.Error("No plugins found")
            }
        }
    }

    fun installPlugin(plugin: LNReaderPluginInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            _installingPluginIds.value = _installingPluginIds.value + plugin.id
            repository.installPlugin(plugin)
                .onSuccess {
                    android.util.Log.d("LNReaderRepoVM", "Installed ${plugin.name}")
                }
                .onFailure { e ->
                    android.util.Log.e("LNReaderRepoVM", "Failed to install ${plugin.name}", e)
                }
            _installingPluginIds.value = _installingPluginIds.value - plugin.id
        }
    }

    fun uninstallPlugin(plugin: LNReaderPluginInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            _installingPluginIds.value = _installingPluginIds.value + plugin.id

            val idsToRemove = installedSourceIds.value.filter { id ->
                id.contains(plugin.site.hashCode().toUInt().toString(16).uppercase()) ||
                id.contains(plugin.id.hashCode().toUInt().toString(16).uppercase())
            }

            idsToRemove.forEach { id ->
                try {
                    jsonSourceManager.deleteSource(id)
                    android.util.Log.d("LNReaderRepoVM", "Deleted source $id for ${plugin.name}")
                } catch (e: Exception) {
                    android.util.Log.e("LNReaderRepoVM", "Failed to delete source $id", e)
                }
            }

            _installingPluginIds.value = _installingPluginIds.value - plugin.id
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSelectedExtensionLanguages(languages: Set<String>) {
        val normalized = languages
            .mapTo(LinkedHashSet()) { it.normalizeExtensionLanguageCode() }
            .filterTo(LinkedHashSet()) { it.isNotBlank() }
        Log.d(TAG, "language filter selectedLanguages=$normalized")
        appSettings.extensionLanguages = normalized
    }

    fun toggleLanguageGroup(lang: String) {
        _collapsedLanguageGroups.value = _collapsedLanguageGroups.value.toMutableSet().apply {
            if (!add(lang)) remove(lang)
        }
    }

    private fun LNReaderPluginInfo.normalizedLanguageCode(): String {
        return lang.normalizeExtensionLanguageCode()
    }

    private companion object {
        private const val TAG = "LNReaderRepoVM"
    }
}

sealed class RepoUiState {
    object Idle : RepoUiState()
    object Loading : RepoUiState()
    data class Loaded(val count: Int) : RepoUiState()
    data class Error(val message: String) : RepoUiState()
}

sealed class PluginDisplayItem {
    data class LangHeader(val lang: String, val count: Int, val isCollapsed: Boolean) : PluginDisplayItem()
    data class Plugin(val info: LNReaderPluginInfo, val isInstalled: Boolean) : PluginDisplayItem()
}
