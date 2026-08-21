package org.skepsun.kototoro.settings.sources.extensions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class ExternalInstalledExtensionsScreenRuntime(
    private val scope: CoroutineScope,
    installedEntries: StateFlow<List<InstalledExtensionEntry>>,
    private val hasExtensions: () -> Boolean,
    private val reloadAction: suspend () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val isLoadingMutable = MutableStateFlow(false)

    val isLoading: StateFlow<Boolean> = isLoadingMutable.asStateFlow()

    val extensions: StateFlow<List<InstalledExtensionItem>> = installedEntries
        .map { list ->
            list.map { ext ->
                InstalledExtensionItem(
                    pkgName = ext.pkgName,
                    appName = ext.name,
                    versionName = ext.versionName,
                    lang = ext.lang,
                    isNsfw = ext.isNsfw,
                    sourceCount = ext.sourceNames.size,
                    sourceNames = ext.sourceNames,
                )
            }
        }
        .stateIn(this.scope, SharingStarted.Lazily, emptyList())

    val extensionCount: StateFlow<Int> = installedEntries
        .map { it.size }
        .stateIn(this.scope, SharingStarted.Lazily, 0)

    val sourceCount: StateFlow<Int> = installedEntries
        .map { list -> list.sumOf { it.sourceNames.size } }
        .stateIn(this.scope, SharingStarted.Lazily, 0)

    fun initialize() {
        if (!hasExtensions()) {
            refresh()
        }
    }

    fun refresh() {
        scope.launch(dispatcher) {
            isLoadingMutable.value = true
            try {
                reloadAction()
            } finally {
                isLoadingMutable.value = false
            }
        }
    }
}
