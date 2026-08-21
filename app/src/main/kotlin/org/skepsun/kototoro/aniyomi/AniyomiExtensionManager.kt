package org.skepsun.kototoro.aniyomi

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionManagerFacade
import org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource
import org.skepsun.kototoro.aniyomi.model.AniyomiLoadResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for Aniyomi extensions.
 *
 * Handles loading, caching, and providing access to Aniyomi extension sources.
 */
@Singleton
class AniyomiExtensionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loader: AniyomiExtensionLoader,
) {
    companion object {
        private const val TAG = "AniyomiExtensionManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val facade = ExternalExtensionManagerFacade<
        AniyomiLoadResult,
        AniyomiLoadResult.Success,
        AniyomiLoadResult.Error,
        AnimeSource,
        AnimeCatalogueSource,
        AniyomiAnimeSource,
    >(
        context = context,
        scope = scope,
        logTag = TAG,
        ecosystem = "aniyomi",
        sourceNamePrefix = "ANIYOMI_",
        loadResults = loader::loadExtensions,
        successOf = { it as? AniyomiLoadResult.Success },
        errorOf = { it as? AniyomiLoadResult.Error },
        untrustedPackageNameOf = { (it as? AniyomiLoadResult.Untrusted)?.pkgName },
        successSources = { it.sources },
        successPackageName = { it.pkgName },
        successIsNsfw = { it.isNsfw },
        successCatalogueSources = { it.catalogueSources },
        sourceId = { it.id },
        asCatalogueSource = { it as? AnimeCatalogueSource },
        catalogueSourceName = { it.name },
        catalogueSourceLang = { it.lang },
        buildWrappedSource = { catalogueSource, pkgName, isNsfw, hasLanguageSuffix ->
            AniyomiAnimeSource(
                animeCatalogueSource = catalogueSource,
                pkgName = pkgName,
                isNsfw = isNsfw,
                hasLanguageSuffix = hasLanguageSuffix,
            )
        },
        errorPackageName = { it.pkgName },
        errorMessage = { it.message },
    )
    val installedExtensions: StateFlow<List<AniyomiLoadResult.Success>> = facade.installedExtensions
    val failedExtensions: StateFlow<List<AniyomiLoadResult.Error>> = facade.failedExtensions
    val isLoading: StateFlow<Boolean> = facade.isLoading
    val changes: StateFlow<Int> = facade.changes

    /**
     * Initialize the extension manager and load all extensions.
     */
    fun initialize() {
        facade.initialize()
    }

    /**
     * Reload all extensions.
     */
    suspend fun loadExtensions() {
        facade.loadExtensions()
    }

    /**
     * Get all available AnimeCatalogueSource instances.
     */
    fun getCatalogueSources(): List<AnimeCatalogueSource> {
        return facade.getCatalogueSources()
    }

    /**
     * Get all AniyomiAnimeSource wrappers.
     */
    fun getAniyomiAnimeSources(): List<AniyomiAnimeSource> {
        return facade.getWrappedSources()
    }

    /**
     * Get a source by its ID.
     */
    fun getSourceById(sourceId: Long): AnimeSource? {
        return facade.getSourceById(sourceId)
    }

    /**
     * Get an AnimeCatalogueSource by its ID.
     */
    fun getCatalogueSourceById(sourceId: Long): AnimeCatalogueSource? {
        return facade.getCatalogueSourceById(sourceId)
    }

    /**
     * Get an AniyomiAnimeSource wrapper by source ID.
     */
    fun getAniyomiAnimeSourceById(sourceId: Long): AniyomiAnimeSource? {
        return facade.getWrappedSourceById(sourceId)
    }

    /**
     * Get an AniyomiAnimeSource by its name (format: "ANIYOMI_{sourceId}").
     */
    fun getAniyomiAnimeSourceByName(name: String): AniyomiAnimeSource? {
        return facade.getWrappedSourceByName(name)
    }

    /**
     * Get sources grouped by language.
     */
    fun getSourcesByLanguage(): Map<String, List<AnimeCatalogueSource>> {
        return facade.getSourcesByLanguage()
    }

    /**
     * Get the number of loaded sources.
     */
    fun getSourceCount(): Int = facade.getSourceCount()

    /**
     * Check if any Aniyomi extensions are loaded.
     */
    fun hasExtensions(): Boolean = facade.hasExtensions()
}
