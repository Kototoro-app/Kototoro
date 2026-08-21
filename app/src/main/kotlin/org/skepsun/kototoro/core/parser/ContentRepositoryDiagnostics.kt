package org.skepsun.kototoro.core.parser

fun ContentRepositoryFactory.CreationResult.isUnavailable(): Boolean {
    return repository is EmptyContentRepository
}

fun ContentRepositoryFactory.CreationResult.toDiagnosticSummary(): String {
    return "source=${requestedSource.name} resolved=${resolvedSource.name} resolution=$resolutionStatus provider=$providerStatus cache=$cacheStatus failure=$failureReason"
}

fun ContentRepositoryFactory.CreationResult.logUnavailable(tag: String, prefix: String) {
    if (!isUnavailable()) return
    android.util.Log.w(tag, "$prefix ${toDiagnosticSummary()}")
}

fun ContentRepositoryFactory.CreationResult.getAvailableRepositoryOrNull(
    tag: String,
    prefix: String,
): ContentRepository? {
    if (isUnavailable()) {
        logUnavailable(tag, prefix)
        return null
    }
    return repository
}

inline fun ContentRepositoryFactory.CreationResult.requireAvailableRepository(
    tag: String,
    prefix: String,
    errorMessage: () -> String,
): ContentRepository {
    return getAvailableRepositoryOrNull(tag, prefix) ?: error(errorMessage())
}
