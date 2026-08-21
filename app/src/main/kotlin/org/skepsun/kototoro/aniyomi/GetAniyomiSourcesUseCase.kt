package org.skepsun.kototoro.aniyomi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for getting Aniyomi sources to display in the UI.
 */
@Singleton
class GetAniyomiSourcesUseCase @Inject constructor(
    private val extensionManager: AniyomiExtensionManager,
    private val settings: org.skepsun.kototoro.core.prefs.AppSettings,
) {

    fun getSourcesFlow(): Flow<List<AniyomiSourceItem>> {
        return extensionManager.installedExtensions.map { extensions ->
            val allSources = extensions.flatMap { ext ->
                ext.catalogueSources.map { catalogueSource ->
                    Triple(ext, catalogueSource, catalogueSource.name)
                }
            }

            val nameCountMap = allSources.groupBy { it.third }.mapValues { it.value.size }

            allSources.map { (ext, catalogueSource, baseName) ->
                val needsLanguageSuffix = nameCountMap[baseName]?.let { it > 1 } ?: false

                AniyomiSourceItem(
                    source = AniyomiAnimeSource(
                        animeCatalogueSource = catalogueSource,
                        pkgName = ext.pkgName,
                        isNsfw = ext.isNsfw,
                    ),
                    extensionName = ext.appName,
                    versionName = ext.versionName,
                    hasLanguageSuffix = needsLanguageSuffix,
                )
            }
        }
    }

    fun getSourcesFlowFiltered(userLanguages: Set<String>): Flow<List<AniyomiSourceItem>> {
        return getSourcesFlow()
    }

    fun getSourcesByLanguage(): Map<String, List<AniyomiAnimeSource>> {
        return extensionManager.getSourcesByLanguage().mapValues { (_, sources) ->
            sources.map { catalogueSource ->
                val ext = extensionManager.installedExtensions.value.find {
                    it.sources.contains(catalogueSource)
                }
                AniyomiAnimeSource(
                    animeCatalogueSource = catalogueSource,
                    pkgName = ext?.pkgName ?: "",
                    isNsfw = ext?.isNsfw ?: false,
                )
            }
        }
    }

    fun hasExtensions(): Boolean = extensionManager.hasExtensions()

    fun isLoading(): Flow<Boolean> = extensionManager.isLoading
}

data class AniyomiSourceItem(
    val source: AniyomiAnimeSource,
    val extensionName: String,
    val versionName: String,
    val hasLanguageSuffix: Boolean = false,
) {
    val displayName: String get() {
        return if (hasLanguageSuffix) {
            "${source.displayName} (${getLanguageDisplayName(language)})"
        } else {
            source.displayName
        }
    }

    val language: String get() = source.language
    val isNsfw: Boolean get() = source.isNsfw
    val sourceId: Long get() = source.sourceId

    companion object {
        private fun getLanguageDisplayName(langCode: String): String {
            return when (langCode.lowercase()) {
                "zh" -> "中文"
                "zh-hans" -> "简体中文"
                "zh-hant" -> "繁體中文"
                "en" -> "English"
                "ja" -> "日本語"
                "ko" -> "한국어"
                "all" -> "Multi"
                else -> langCode.uppercase()
            }
        }
    }
}
