package org.skepsun.kototoro.extensions.recovery

import org.skepsun.kototoro.core.db.entity.SourceOriginEntity

/**
 * Pure derivation of [SourceRecoveryStatus] from an [SourceOriginEntity] plus a
 * [SourceRuntimeSnapshot]. No Android / framework dependencies — plain JVM unit tests.
 *
 * Rule order is intentional (signature first, then "missing" causes, most actionable
 * recovery channel wins):
 *
 *  1. installed + recorded signature present + current snapshot digest present and
 *     different → [SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED]
 *     (package is installed but its signature changed; the recorded digest lets us keep
 *     the strict comparison. Without either side of the comparison we do NOT guess and
 *     fall through to RESOLVED.)
 *  2. installed → [SourceRecoveryStatus.RESOLVED]
 *  3. `repositoryUrl` recorded → [SourceRecoveryStatus.REPOSITORY_REQUIRED]
 *     (repository wins over locator/package: it is the highest-fidelity recovery channel.)
 *  4. `locator` recorded (non-extension source) → [SourceRecoveryStatus.REIMPORT_REQUIRED]
 *  5. package-backed (explicit `packageName`, or a known package ecosystem `kind`)
 *     → [SourceRecoveryStatus.SIDELOAD_REQUIRED]
 *  6. otherwise (unknown kind, no locator at all) → [SourceRecoveryStatus.MISSING]
 */
object SourceRecoveryDerivation {

    /**
     * Kinds whose origin is a locally installed package (APK/JAR/JS bundle). A source
     * with one of these kinds but no repository/locator can only be recovered by
     * side-loading the package.
     */
    private val PACKAGE_BACKED_KINDS = setOf(
        "MIHON",
        "ANIYOMI",
        "IREADER",
        "TSUNDOKU",
        "JAR",
        "CLOUDSTREAM",
        "LEGADO",
        "TVBOX",
        "JS",
    )

    fun deriveStatus(
        origin: SourceOriginEntity,
        snapshot: SourceRuntimeSnapshot,
    ): SourceRecoveryStatus {
        val installed = snapshot.isInstalled(origin.sourceKey)

        if (installed) {
            // 1) Package installed, but its current signature differs from the last seen one.
            //    Only when origin recorded a digest AND the snapshot can produce one —
            //    never guess on missing information.
            val recorded = origin.signingDigest
            val current = snapshot.currentSigningDigest(origin.sourceKey)
            if (origin.packageName != null && recorded != null && current != null && current != recorded) {
                return SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED
            }

            // 2) Installed, and either no signature change or not enough info to compare.
            return SourceRecoveryStatus.RESOLVED
        }

        // 3) An original repository is recorded: recover from it.
        if (origin.repositoryUrl != null) {
            return SourceRecoveryStatus.REPOSITORY_REQUIRED
        }

        // 4) Non-extension source with a locator: re-import the locator.
        if (origin.locator != null) {
            return SourceRecoveryStatus.REIMPORT_REQUIRED
        }

        // 5) Package-backed origin without a repository: side-load the package.
        if (origin.packageName != null || origin.kind in PACKAGE_BACKED_KINDS) {
            return SourceRecoveryStatus.SIDELOAD_REQUIRED
        }

        // 6) Unknown kind with no locator information at all: only "missing" remains.
        return SourceRecoveryStatus.MISSING
    }
}
