package org.skepsun.kototoro.extensions.runtime

data class ProcessedExternalExtensions<SuccessT, ErrorT, SourceT, WrappedSourceT>(
    val successful: List<SuccessT>,
    val failed: List<ErrorT>,
    val sourceById: Map<Long, SourceT>,
    val wrappedSourceById: Map<Long, WrappedSourceT>,
    val untrustedPackages: List<String>,
)

fun <ResultT, SuccessT, ErrorT, SourceT, CatalogueSourceT : SourceT, WrappedSourceT> processExternalExtensionResults(
    results: List<ResultT>,
    successOf: (ResultT) -> SuccessT?,
    errorOf: (ResultT) -> ErrorT?,
    untrustedPackageNameOf: (ResultT) -> String?,
    successSources: (SuccessT) -> List<SourceT>,
    successPackageName: (SuccessT) -> String,
    successIsNsfw: (SuccessT) -> Boolean,
    sourceId: (SourceT) -> Long,
    asCatalogueSource: (SourceT) -> CatalogueSourceT?,
    catalogueSourceName: (CatalogueSourceT) -> String,
    buildWrappedSource: (CatalogueSourceT, String, Boolean, Boolean) -> WrappedSourceT,
    onError: (ErrorT) -> Unit = {},
    onUntrusted: (String) -> Unit = {},
): ProcessedExternalExtensions<SuccessT, ErrorT, SourceT, WrappedSourceT> {
    val successful = mutableListOf<SuccessT>()
    val failed = mutableListOf<ErrorT>()
    val sourceById = linkedMapOf<Long, SourceT>()
    val catalogueSources = mutableListOf<Triple<CatalogueSourceT, String, Boolean>>()
    val untrustedPackages = mutableListOf<String>()

    results.forEach { result ->
        when {
            successOf(result) != null -> {
                val success = requireNotNull(successOf(result))
                successful += success
                successSources(success).forEach { source ->
                    sourceById[sourceId(source)] = source
                    val catalogueSource = asCatalogueSource(source) ?: return@forEach
                    catalogueSources += Triple(
                        catalogueSource,
                        successPackageName(success),
                        successIsNsfw(success),
                    )
                }
            }

            errorOf(result) != null -> {
                val error = requireNotNull(errorOf(result))
                failed += error
                onError(error)
            }

            untrustedPackageNameOf(result) != null -> {
                val pkgName = requireNotNull(untrustedPackageNameOf(result))
                untrustedPackages += pkgName
                onUntrusted(pkgName)
            }
        }
    }

    val nameCount = catalogueSources.groupingBy { catalogueSourceName(it.first) }.eachCount()
    val wrappedSourceById = linkedMapOf<Long, WrappedSourceT>()
    catalogueSources.forEach { (catalogueSource, pkgName, isNsfw) ->
        val hasLanguageSuffix = (nameCount[catalogueSourceName(catalogueSource)] ?: 0) > 1
        wrappedSourceById[sourceId(catalogueSource)] = buildWrappedSource(
            catalogueSource,
            pkgName,
            isNsfw,
            hasLanguageSuffix,
        )
    }

    return ProcessedExternalExtensions(
        successful = successful,
        failed = failed,
        sourceById = sourceById,
        wrappedSourceById = wrappedSourceById,
        untrustedPackages = untrustedPackages,
    )
}
