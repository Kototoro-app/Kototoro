package org.skepsun.kototoro.extensions.recovery

import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.InstalledExtensionSignatureValidator
import org.skepsun.kototoro.extensions.runtime.tachiyomi.SourceIdentity

/**
 * Production [SourceRuntimeSnapshot] backed by the live extension managers.
 *
 * Per-ecosystem lookups are injected as `(upstreamSourceId: String) -> packageName?`
 * resolvers (wired in [RecoveryModule] from the Mihon / Aniyomi / IReader / Tsundoku
 * managers), so the class itself stays a pure query function with no Android or
 * extension-library dependencies — plain JVM unit tests drive it with fixture lambdas.
 * Signing digests come from [InstalledExtensionSignatureValidator] so the T5.6
 * signature-confirmation derivation works against the real installed package.
 *
 * Keys that parse to no known Tachiyomi prefix (JAR / CLOUDSTREAM / JSON / TVBox / JS /
 * Legado, or plain display names) report nothing — the recovery derivation then falls back
 * to package/locator/repository channels derived from the recorded origin.
 */
class ManagerBackedSourceRuntimeSnapshot internal constructor(
    private val mihonPackageFor: (String) -> String?,
    private val aniyomiPackageFor: (String) -> String?,
    private val ireaderPackageFor: (String) -> String?,
    private val tsundokuPackageFor: (String) -> String?,
    private val signatureValidator: InstalledExtensionSignatureValidator,
) : SourceRuntimeSnapshot {

    override fun isInstalled(sourceKey: String): Boolean = packageNameFor(sourceKey) != null

    override fun currentSigningDigest(sourceKey: String): String? {
        val packageName = packageNameFor(sourceKey) ?: return null
        return signatureValidator.firstFingerprint(packageName)
    }

    override fun packageNameFor(sourceKey: String): String? {
        val identity = SourceIdentity.fromSourceKey(sourceKey) ?: return null
        val sourceId = identity.sourceId.toString()
        return when (identity.ecosystem) {
            ExternalExtensionType.MIHON -> mihonPackageFor(sourceId)
            ExternalExtensionType.ANIYOMI -> aniyomiPackageFor(sourceId)
            ExternalExtensionType.IREADER -> ireaderPackageFor(sourceId)
            ExternalExtensionType.TSUNDOKU -> tsundokuPackageFor(sourceId)
            ExternalExtensionType.JAR,
            ExternalExtensionType.CLOUDSTREAM,
            -> null
        }
    }
}
