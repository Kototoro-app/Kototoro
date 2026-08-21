package org.skepsun.kototoro.extensions.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class ExternalExtensionManagerFacade<ResultT, SuccessT, ErrorT, SourceT, CatalogueT : SourceT, WrappedSourceT>(
    context: Context,
    scope: CoroutineScope,
    private val logTag: String,
    private val ecosystem: String,
    private val sourceNamePrefix: String,
    private val loadResults: suspend (Context) -> List<ResultT>,
    private val successOf: (ResultT) -> SuccessT?,
    private val errorOf: (ResultT) -> ErrorT?,
    private val untrustedPackageNameOf: (ResultT) -> String?,
    private val successSources: (SuccessT) -> List<SourceT>,
    private val successPackageName: (SuccessT) -> String,
    private val successIsNsfw: (SuccessT) -> Boolean,
    private val successCatalogueSources: (SuccessT) -> List<CatalogueT>,
    private val sourceId: (SourceT) -> Long,
    private val asCatalogueSource: (SourceT) -> CatalogueT?,
    private val catalogueSourceName: (CatalogueT) -> String,
    private val catalogueSourceLang: (CatalogueT) -> String,
    private val buildWrappedSource: (CatalogueT, String, Boolean, Boolean) -> WrappedSourceT,
    private val errorPackageName: (ErrorT) -> String,
    private val errorMessage: (ErrorT) -> String,
) {

    private val runtime = ExternalExtensionManagerRuntime<
        ResultT,
        SuccessT,
        ErrorT,
        SourceT,
        WrappedSourceT,
    >(
        context = context,
        scope = scope,
    )

    val installedExtensions: StateFlow<List<SuccessT>> = runtime.installedExtensions
    val failedExtensions: StateFlow<List<ErrorT>> = runtime.failedExtensions
    val isLoading: StateFlow<Boolean> = runtime.isLoading
    val changes: StateFlow<Int> = runtime.changes

    fun initialize() {
        runtime.initialize(::loadExtensions)
    }

    suspend fun loadExtensions() {
        runtime.loadExtensions(
            loadResults = loadResults,
            processResults = { results ->
                Log.d(logTag, "load_start ecosystem=$ecosystem")
                processExternalExtensionResults<ResultT, SuccessT, ErrorT, SourceT, CatalogueT, WrappedSourceT>(
                    results = results,
                    successOf = successOf,
                    errorOf = errorOf,
                    untrustedPackageNameOf = untrustedPackageNameOf,
                    successSources = successSources,
                    successPackageName = successPackageName,
                    successIsNsfw = successIsNsfw,
                    sourceId = sourceId,
                    asCatalogueSource = asCatalogueSource,
                    catalogueSourceName = catalogueSourceName,
                    buildWrappedSource = buildWrappedSource,
                    onError = { error ->
                        Log.e(
                            logTag,
                            "load_error ecosystem=$ecosystem pkg=${errorPackageName(error)} message=${errorMessage(error)}",
                        )
                    },
                    onUntrusted = { pkgName ->
                        Log.w(logTag, "load_untrusted ecosystem=$ecosystem pkg=$pkgName")
                    },
                ).also { processed: ProcessedExternalExtensions<SuccessT, ErrorT, SourceT, WrappedSourceT> ->
                    Log.d(
                        logTag,
                        "load_complete ecosystem=$ecosystem success=${processed.successful.size} failed=${processed.failed.size} untrusted=${processed.untrustedPackages.size} sources=${processed.wrappedSourceById.size}",
                    )
                }
            },
        )
    }

    fun getInstalledExtensions(): List<SuccessT> = installedExtensions.value

    fun getCatalogueSources(): List<CatalogueT> {
        return installedExtensions.value.flatMap(successCatalogueSources)
    }

    fun getWrappedSources(): List<WrappedSourceT> = runtime.getWrappedSources()

    fun getSourceById(sourceId: Long): SourceT? = runtime.getSourceById(sourceId)

    fun getCatalogueSourceById(sourceId: Long): CatalogueT? = runtime.getSourceById(sourceId)?.let(asCatalogueSource)

    fun getWrappedSourceById(sourceId: Long): WrappedSourceT? = runtime.getWrappedSourceById(sourceId)

    fun getWrappedSourceByName(name: String): WrappedSourceT? {
        if (!name.startsWith(sourceNamePrefix)) return null
        val sourceId = name.substringAfter(sourceNamePrefix).toLongOrNull() ?: return null
        return getWrappedSourceById(sourceId)
    }

    fun getSourcesByLanguage(): Map<String, List<CatalogueT>> {
        return getCatalogueSources().groupBy(catalogueSourceLang)
    }

    fun getSourceCount(): Int = runtime.getSourceCount()

    fun hasExtensions(): Boolean = runtime.hasExtensions()
}
