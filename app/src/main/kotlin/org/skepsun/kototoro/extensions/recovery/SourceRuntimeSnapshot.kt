package org.skepsun.kototoro.extensions.recovery

/**
 * Strict runtime view of installed sources, injected into [SourceRecoveryRepository].
 *
 * This is deliberately a *query-only* seam with no guessing: every method returns
 * exactly what the backing runtime (Tsundoku/Mihon extension manager, package manager)
 * can prove, and `null`/`false` for anything it cannot determine.
 *
 * The production implementation is wired by the main session in Phase 2A+ once the
 * Tsundoku/Mihon managers expose their installed catalog; [DefaultSourceRuntimeSnapshot]
 * is the no-op placeholder used until then.
 */
interface SourceRuntimeSnapshot {

    /** Whether the runtime snapshot currently contains the given [sourceKey]. */
    fun isInstalled(sourceKey: String): Boolean

    /** Signing digest of the currently installed package, or `null` when unavailable. */
    fun currentSigningDigest(sourceKey: String): String?

    /** Package name of the installed package for [sourceKey] — only when strictly known. */
    fun packageNameFor(sourceKey: String): String?
}

/**
 * Default no-op snapshot: nothing is installed, nothing can be probed.
 *
 * Kept as the constructor default for [SourceRecoveryRepository] so the domain layer
 * stays assemble-able and unit-testable before the real manager snapshot lands.
 */
class DefaultSourceRuntimeSnapshot : SourceRuntimeSnapshot {

    override fun isInstalled(sourceKey: String): Boolean = false

    override fun currentSigningDigest(sourceKey: String): String? = null

    override fun packageNameFor(sourceKey: String): String? = null
}
