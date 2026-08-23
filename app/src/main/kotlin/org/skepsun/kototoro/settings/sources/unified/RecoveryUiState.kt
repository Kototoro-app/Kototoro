package org.skepsun.kototoro.settings.sources.unified

import org.skepsun.kototoro.extensions.recovery.SourceRecoveryStatus

/**
 * UI-facing projection of the source recovery domain (T5.1/T5.2).
 *
 * Produced by [org.skepsun.kototoro.extensions.recovery.RecoveryActionCoordinator] and
 * re-projected by [UnifiedSourcesViewModel.recoveryState]; the parallel UI agent consumes
 * this shape directly (missing badge, per-source recovery banner, action results).
 */
data class RecoveryUiState(
    /** Number of origins currently in a missing state ([SourceRecoveryStatus.isMissing]). */
    val missingCount: Int = 0,
    /** Total number of tracked origins. */
    val total: Int = 0,
    /** sourceKey -> strictly derived recovery status. */
    val perSource: Map<String, SourceRecoveryStatus> = emptyMap(),
    /** sourceKeys with an action currently running (serialized by the coordinator). */
    val inFlightSourceKeys: Set<String> = emptySet(),
    /** Last finished action outcome, cleared/reset by the next action. */
    val actionResult: RecoveryActionResult? = null,
    /** Whether the "recovery / missing only" filter is active (owned by the ViewModel). */
    val recoveryFilterActive: Boolean = false,
)

/**
 * Outcome of a single recovery action. `ok = true` means the origin resolved (or the action
 * was a harmless no-op); otherwise [message] carries a human-readable reason.
 */
data class RecoveryActionResult(
    val sourceKey: String,
    val ok: Boolean,
    val message: String?,
)
