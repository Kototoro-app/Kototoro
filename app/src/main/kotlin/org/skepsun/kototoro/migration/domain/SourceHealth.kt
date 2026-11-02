package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.tracker.data.TrackEntity

enum class SourceHealthStatus {
    UNINSTALLED,
    BROKEN,
    FAILING,

    /**
     * Update checks that failed only once, or only for some entries: usually a network hiccup on this
     * device rather than a source that no longer works.
     */
    SUSPICIOUS,
    DISABLED,
    HEALTHY,
    ;

    /** Disabled and suspicious sources are only hidden or unstable: their entries still open. */
    val needsAttention: Boolean
        get() = this == UNINSTALLED || this == BROKEN || this == FAILING

    /** Order of the groups in the migration sources screen, most urgent first. */
    val sectionRank: Int
        get() = when (this) {
            UNINSTALLED, BROKEN, FAILING -> 0
            SUSPICIOUS -> 1
            DISABLED -> 2
            HEALTHY -> 3
        }
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

/** Offline verdict for one source, in priority order uninstalled > broken > failing > suspicious > disabled. */
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
        val failures = recent.filter { it.lastResult == TrackEntity.RESULT_FAILED }
        if (failures.isNotEmpty()) {
            // Only an unbroken run of failures is a source verdict; a single failure or mixed results can
            // come from this device's network and must not push the user into migrating their library.
            val persistent = failures.size == recent.size && failures.size >= MIN_FAILURES
            val summary = failures.mapNotNull { it.lastError?.takeIf(String::isNotBlank) }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            return SourceVerdict(
                if (persistent) SourceHealthStatus.FAILING else SourceHealthStatus.SUSPICIOUS,
                summary,
            )
        }
        if (signals.isDisabled) return SourceVerdict(SourceHealthStatus.DISABLED, null)
        return SourceVerdict(SourceHealthStatus.HEALTHY, null)
    }
}
