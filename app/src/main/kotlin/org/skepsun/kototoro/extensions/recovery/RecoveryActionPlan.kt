package org.skepsun.kototoro.extensions.recovery

import org.skepsun.kototoro.core.db.entity.SourceOriginEntity

/**
 * A concrete recovery channel for a source origin. Pure value type derived from a
 * [SourceRecoveryStatus] + [SourceOriginEntity]; the plan itself never executes anything.
 *
 * `Rescan` is intentionally *not* produced by [planRecoveryAction] — the derivation has no
 * `RESCAN_REQUIRED` state. It exists so the action coordinator can express the "the package
 * was just installed on disk; reload the runtime managers" follow-up after a side-load.
 */
sealed interface RecoveryActionPlan {

    /** Origin has a recorded repository URL: install (or re-import) from it. */
    data class InstallFromRepository(
        val repositoryUrl: String,
    ) : RecoveryActionPlan

    /** Origin is package-backed but has no repository: side-load the package (.apk/.jar/cs3). */
    data class InstallSideload(
        val packageName: String?,
        val kind: String,
    ) : RecoveryActionPlan

    /** Non-extension origin with a locator: re-import the locator (JSON sources). */
    data class Reimport(
        val locator: String,
    ) : RecoveryActionPlan

    /** Package is installed but its signing digest changed: the user must confirm it. */
    data class ConfirmSignature(
        val expectedDigest: String?,
    ) : RecoveryActionPlan

    /** Coordinator-internal: reload the runtime managers after a package landed on disk. */
    data class Rescan(
        val sourceKey: String,
    ) : RecoveryActionPlan

    /** No automatic channel exists — the origin can only be surfaced as plain missing. */
    data object NoActionMissing : RecoveryActionPlan
}

/**
 * Maps a strictly derived [SourceRecoveryStatus] to its recovery plan.
 *
 * The mapping mirrors the derivation's rule order: repository wins over locator over
 * package. [SourceRecoveryStatus.RESOLVED] maps to [RecoveryActionPlan.NoActionMissing] —
 * callers must not trigger an action on a resolved origin (the coordinator guards this);
 * the `RESOLVED` branch is defined only to keep the mapping total and explicit.
 *
 * Field extraction is defensive (`orEmpty()`): the derivation guarantees
 * `repositoryUrl`/`locator` are non-null for their states, but callers may hand this
 * function an inconsistent pair, and a plan with an empty string still fails loudly at
 * execution time instead of crashing at plan time.
 */
fun planRecoveryAction(
    status: SourceRecoveryStatus,
    origin: SourceOriginEntity,
): RecoveryActionPlan {
    return when (status) {
        SourceRecoveryStatus.REPOSITORY_REQUIRED ->
            RecoveryActionPlan.InstallFromRepository(origin.repositoryUrl.orEmpty())

        SourceRecoveryStatus.SIDELOAD_REQUIRED ->
            RecoveryActionPlan.InstallSideload(origin.packageName, origin.kind)

        SourceRecoveryStatus.REIMPORT_REQUIRED ->
            RecoveryActionPlan.Reimport(origin.locator.orEmpty())

        SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED ->
            RecoveryActionPlan.ConfirmSignature(origin.signingDigest)

        SourceRecoveryStatus.MISSING,
        SourceRecoveryStatus.RESOLVED -> RecoveryActionPlan.NoActionMissing
    }
}
