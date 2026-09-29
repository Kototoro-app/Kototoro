package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.tracker.data.TrackEntity

enum class SourceHealthStatus {
    UNINSTALLED,
    BROKEN,
    FAILING,
    DISABLED,
    HEALTHY,
    ;

    val needsAttention: Boolean get() = this != HEALTHY
}

data class SourceSignals(val isUnresolved: Boolean, val isBroken: Boolean, val isDisabled: Boolean)

data class TrackSignal(val lastResult: Int?, val lastCheckTime: Long?, val lastError: String?)

data class SourceVerdict(val status: SourceHealthStatus, val errorSummary: String?)

data class SourceHealth(
    val source: ContentSource,
    val status: SourceHealthStatus,
    val errorSummary: String?,
    val contentIds: List<Long>,
    /** Website of the entries, shown instead of an opaque id when the source is gone. */
    val siteHint: String? = null,
) {
    val favouriteCount: Int get() = contentIds.size
}

/** Offline verdict for one source, in priority order uninstalled > broken > failing > disabled. */
object SourceHealthClassifier {

    private const val WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    private const val MIN_FAILURES = 2

    fun classify(signals: SourceSignals, tracks: List<TrackSignal>, now: Long): SourceVerdict {
        if (signals.isUnresolved) return SourceVerdict(SourceHealthStatus.UNINSTALLED, null)
        if (signals.isBroken) return SourceVerdict(SourceHealthStatus.BROKEN, null)
        val recent = tracks.filter { track ->
            val checkedAt = track.lastCheckTime ?: return@filter false
            val result = track.lastResult ?: return@filter false
            result != TrackEntity.RESULT_NONE && now - checkedAt <= WINDOW_MS
        }
        if (recent.size >= MIN_FAILURES && recent.all { it.lastResult == TrackEntity.RESULT_FAILED }) {
            val summary = recent.mapNotNull { it.lastError?.takeIf(String::isNotBlank) }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            return SourceVerdict(SourceHealthStatus.FAILING, summary)
        }
        if (signals.isDisabled) return SourceVerdict(SourceHealthStatus.DISABLED, null)
        return SourceVerdict(SourceHealthStatus.HEALTHY, null)
    }
}
