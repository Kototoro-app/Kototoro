package org.skepsun.kototoro.mihon

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionManagerFacade
import org.skepsun.kototoro.mihon.model.MihonLoadResult
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for Mihon extensions.
 *
 * Handles loading, caching, and providing access to Mihon extension sources.
 */
@Singleton
class MihonExtensionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loader: MihonExtensionLoader,
) {
    companion object {
        private const val TAG = "MihonExtensionManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val facade = ExternalExtensionManagerFacade<
        MihonLoadResult,
        MihonLoadResult.Success,
        MihonLoadResult.Error,
        Source,
        CatalogueSource,
        MihonMangaSource,
    >(
        context = context,
        scope = scope,
        logTag = TAG,
        ecosystem = "mihon",
        sourceNamePrefix = "MIHON_",
        loadResults = loader::loadExtensions,
        successOf = { it as? MihonLoadResult.Success },
        errorOf = { it as? MihonLoadResult.Error },
        untrustedPackageNameOf = { (it as? MihonLoadResult.Untrusted)?.pkgName },
        successSources = { it.sources },
        successPackageName = { it.pkgName },
        successIsNsfw = { it.isNsfw },
        successCatalogueSources = { it.catalogueSources },
        sourceId = { it.id },
        asCatalogueSource = { it as? CatalogueSource },
        catalogueSourceName = { it.name },
        catalogueSourceLang = { it.lang },
        buildWrappedSource = { catalogueSource, pkgName, isNsfw, hasLanguageSuffix ->
            MihonMangaSource(
                catalogueSource = catalogueSource,
                pkgName = pkgName,
                isNsfw = isNsfw,
                hasLanguageSuffix = hasLanguageSuffix,
            )
        },
        errorPackageName = { it.pkgName },
        errorMessage = { it.message },
    )
    val installedExtensions: StateFlow<List<MihonLoadResult.Success>> = facade.installedExtensions
    val failedExtensions: StateFlow<List<MihonLoadResult.Error>> = facade.failedExtensions
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
     * Get all available CatalogueSource instances.
     */
    fun getCatalogueSources(): List<CatalogueSource> {
        return facade.getCatalogueSources()
    }

    /**
     * Get all MihonMangaSource wrappers.
     */
    fun getMihonMangaSources(): List<MihonMangaSource> {
        return facade.getWrappedSources()
    }

    /**
     * Get a source by its ID.
     */
    fun getSourceById(sourceId: Long): Source? {
        return facade.getSourceById(sourceId)
    }

    /**
     * Get a CatalogueSource by its ID.
     */
    fun getCatalogueSourceById(sourceId: Long): CatalogueSource? {
        return facade.getCatalogueSourceById(sourceId)
    }

    /**
     * Get a MihonMangaSource wrapper by source ID.
     */
    fun getMihonMangaSourceById(sourceId: Long): MihonMangaSource? {
        return facade.getWrappedSourceById(sourceId)
    }

    /**
     * Get a MihonMangaSource by its name (format: "MIHON_{sourceId}").
     */
    fun getMihonMangaSourceByName(name: String): MihonMangaSource? {
        return facade.getWrappedSourceByName(name)
    }

    /**
     * Get sources grouped by language.
     */
    fun getSourcesByLanguage(): Map<String, List<CatalogueSource>> {
        return facade.getSourcesByLanguage()
    }

    /**
     * Get the number of loaded sources.
     */
    fun getSourceCount(): Int = facade.getSourceCount()

    /**
     * Check if any Mihon extensions are loaded.
     */
    fun hasExtensions(): Boolean = facade.hasExtensions()
}
