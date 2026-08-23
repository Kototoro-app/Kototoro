package org.skepsun.kototoro.tsundoku

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionManagerFacade
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for Tsundoku novel extensions (plan T2A.5).
 *
 * Built on the generic [ExternalExtensionManagerFacade], which already provides:
 * - installed/failed/isLoading/changes state flows and caching;
 * - `TSUNDOKU_{sourceId}` key resolution ([getWrappedSourceByName] path);
 * - package add/replace/remove broadcast reload (T2A.4, via the facade runtime).
 */
@Singleton
class TsundokuExtensionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loader: TsundokuExtensionLoader,
    private val originRecorder: TsundokuOriginRecorder,
) {
    companion object {
        private const val TAG = "TsundokuExtensionManager"
        const val SOURCE_KEY_PREFIX = "TSUNDOKU_"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val facade = ExternalExtensionManagerFacade<
        TsundokuLoadResult,
        TsundokuLoadResult.Success,
        TsundokuLoadResult.Error,
        Source,
        Source,
        TsundokuNovelSource,
    >(
        context = context,
        scope = scope,
        logTag = TAG,
        ecosystem = "tsundoku",
        sourceNamePrefix = SOURCE_KEY_PREFIX,
        // Every load path (initialize, explicit call, package broadcast reload) funnels
        // through this lambda; side-effect origin recording (T3A.7) lives here so it fires
        // on scan success regardless of entry point. Uninstall never deletes origins.
        loadResults = { loadContext ->
            loader.loadExtensions(loadContext).also { results ->
                originRecorder.recordLoadResults(results)
            }
        },
        successOf = { it as? TsundokuLoadResult.Success },
        errorOf = { it as? TsundokuLoadResult.Error },
        untrustedPackageNameOf = { null },
        successSources = { it.sources },
        successPackageName = { it.pkgName },
        successIsNsfw = { it.isNsfw },
        successCatalogueSources = { it.sources },
        sourceId = { it.id },
        asCatalogueSource = { it as? Source },
        catalogueSourceName = { it.name },
        catalogueSourceLang = { it.lang },
        buildWrappedSource = { source, pkgName, isNsfw, hasLanguageSuffix ->
            TsundokuNovelSource(
                upstreamSource = source,
                pkgName = pkgName,
                isNsfw = isNsfw,
                hasLanguageSuffix = hasLanguageSuffix,
            )
        },
        errorPackageName = { it.pkgName },
        errorMessage = { it.message },
    )

    val installedExtensions: StateFlow<List<TsundokuLoadResult.Success>> = facade.installedExtensions
    val failedExtensions: StateFlow<List<TsundokuLoadResult.Error>> = facade.failedExtensions
    val isLoading: StateFlow<Boolean> = facade.isLoading
    val changes: StateFlow<Int> = facade.changes

    fun initialize() = facade.initialize()

    suspend fun loadExtensions() = facade.loadExtensions()

    fun getInstalledExtensions(): List<TsundokuLoadResult.Success> = facade.getInstalledExtensions()

    /** All loaded novel sources, wrapped as [TsundokuNovelSource]. */
    fun getTsundokuNovelSources(): List<TsundokuNovelSource> = facade.getWrappedSources()

    /** Resolves `TSUNDOKU_{sourceId}` (or any other source name) to the wrapped source. */
    fun resolveSource(name: String): TsundokuNovelSource? = facade.getWrappedSourceByName(name)

    fun resolveSourceById(sourceId: Long): TsundokuNovelSource? = facade.getWrappedSourceById(sourceId)

    fun getSourceById(sourceId: Long): Source? = facade.getSourceById(sourceId)

    fun getSourceCount(): Int = facade.getSourceCount()

    fun hasExtensions(): Boolean = facade.hasExtensions()
}
