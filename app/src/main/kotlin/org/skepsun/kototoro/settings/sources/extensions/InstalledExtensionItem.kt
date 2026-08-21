package org.skepsun.kototoro.settings.sources.extensions

data class InstalledExtensionItem(
    val pkgName: String,
    val appName: String,
    val versionName: String,
    val lang: String,
    val isNsfw: Boolean,
    val sourceCount: Int,
    val sourceNames: List<String>,
)
