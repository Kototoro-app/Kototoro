package org.skepsun.kototoro.video.performance

data class PlaybackDiagnosticsSnapshot(
    val startupTimeoutCount: Int,
    val fallbackCount: Int,
    val networkOrSourceErrorCount: Int,
    val compatibilityErrorCount: Int,
    val unknownErrorCount: Int,
    val lastFailureCategory: PlaybackFailureCategory?,
    val lastFailureTrigger: String?,
    val lastFailureDetail: String?,
    val lastFallbackReason: PlaybackFallbackReason?,
)

class PlaybackSessionDiagnostics {

    private var startupTimeoutCount = 0
    private var fallbackCount = 0
    private var networkOrSourceErrorCount = 0
    private var compatibilityErrorCount = 0
    private var unknownErrorCount = 0
    private var lastFailureCategory: PlaybackFailureCategory? = null
    private var lastFailureTrigger: String? = null
    private var lastFailureDetail: String? = null
    private var lastFallbackReason: PlaybackFallbackReason? = null

    fun recordFailure(
        trigger: String,
        category: PlaybackFailureCategory,
        detail: String?,
    ) {
        lastFailureTrigger = trigger
        lastFailureCategory = category
        lastFailureDetail = detail?.takeIf { it.isNotBlank() }?.take(240)
        when (category) {
            PlaybackFailureCategory.NETWORK_OR_SOURCE -> networkOrSourceErrorCount += 1
            PlaybackFailureCategory.COMPATIBILITY -> compatibilityErrorCount += 1
            PlaybackFailureCategory.UNKNOWN -> unknownErrorCount += 1
        }
        if (trigger == "startup_timeout") {
            startupTimeoutCount += 1
        }
    }

    fun recordFallback(reason: PlaybackFallbackReason) {
        fallbackCount += 1
        lastFallbackReason = reason
    }

    fun snapshot(): PlaybackDiagnosticsSnapshot = PlaybackDiagnosticsSnapshot(
        startupTimeoutCount = startupTimeoutCount,
        fallbackCount = fallbackCount,
        networkOrSourceErrorCount = networkOrSourceErrorCount,
        compatibilityErrorCount = compatibilityErrorCount,
        unknownErrorCount = unknownErrorCount,
        lastFailureCategory = lastFailureCategory,
        lastFailureTrigger = lastFailureTrigger,
        lastFailureDetail = lastFailureDetail,
        lastFallbackReason = lastFallbackReason,
    )
}
