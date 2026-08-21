package org.skepsun.kototoro.settings.sources.extensions

import kotlinx.coroutines.flow.StateFlow

interface InstalledExtensionsScreenModel {
    val isLoading: StateFlow<Boolean>
    val extensions: StateFlow<List<InstalledExtensionItem>>
    val extensionCount: StateFlow<Int>
    val sourceCount: StateFlow<Int>

    fun refresh()

    fun getSourcesForPackage(pkgName: String): List<org.skepsun.kototoro.parsers.model.ContentSource>
}
