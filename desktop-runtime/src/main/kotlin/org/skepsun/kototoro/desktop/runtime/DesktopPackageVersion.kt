package org.skepsun.kototoro.desktop.runtime

import java.util.Locale

/**
 * A Windows package version, ordered the way the Android client orders its versions (`core/github/VersionId`).
 *
 * The Windows packages are named after the application version, so `Kototoro-1.9.9-windows-x64-release.zip` and
 * `Kototoro-N20261022-windows-x64-nightly.zip` are both valid inputs: a nightly carries the build date instead of a
 * semantic triple. A trailing variant (`1.10.0-beta1`) sorts below its release, like Android's alpha/beta/rc weights.
 */
data class DesktopPackageVersion(
    val major: Int,
    val minor: Int,
    val build: Int,
    val variantType: String,
    val variantNumber: Int,
    /** The text this was parsed from, for messages; empty for [UNKNOWN]. */
    val text: String = "",
) : Comparable<DesktopPackageVersion> {

    /** Nightly builds sort under every release of the same date, as Android's `n` variant does. */
    val isNightly: Boolean get() = variantType.equals("n", ignoreCase = true)

    /** True when this is a release rather than a pre-release (alpha/beta/rc/nightly). */
    val isStable: Boolean get() = variantType.isEmpty()

    override fun compareTo(other: DesktopPackageVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        build.compareTo(other.build).takeIf { it != 0 }?.let { return it }
        variantWeight(variantType).compareTo(variantWeight(other.variantType)).takeIf { it != 0 }?.let { return it }
        return variantNumber.compareTo(other.variantNumber)
    }

    private fun variantWeight(type: String) = when (type.lowercase(Locale.ROOT)) {
        "a", "alpha" -> 1
        "b", "beta" -> 2
        "rc" -> 4
        "" -> 8
        else -> 0
    }

    /**
     * Identity is the version itself, not the text it was read from: `N20261022` and `nightly-20261022` are the same
     * build, and a probe comparing a parsed tag with a parsed asset name must not fail on spelling alone.
     */
    override fun equals(other: Any?): Boolean = other is DesktopPackageVersion &&
        major == other.major && minor == other.minor && build == other.build &&
        variantType.equals(other.variantType, ignoreCase = true) && variantNumber == other.variantNumber

    override fun hashCode(): Int = listOf(major, minor, build, variantType.lowercase(Locale.ROOT), variantNumber).hashCode()

    override fun toString(): String = text.ifEmpty { "$major.$minor.$build$variantType$variantNumber" }

    companion object {
        /** Used when the build carries no version resource (a test classpath, or a build without the task). */
        val UNKNOWN = DesktopPackageVersion(0, 0, 0, "", 0, "")

        /**
         * Parses `1.9.9`, `v1.9.9`, `1.10.0-beta1`, `N20261022` and `nightly-20261022`; anything unparsable becomes
         * [UNKNOWN] instead of throwing, so a malformed tag can never break the update check.
         */
        fun parse(raw: String): DesktopPackageVersion {
            val text = raw.trim()
            if (text.isEmpty()) return UNKNOWN
            // Nightly builds carry the UTC date instead of a semantic triple: `N20261022` or `nightly-20261022`.
            val nightlyDate = when {
                text.startsWith("n", ignoreCase = true) && text.getOrNull(1)?.isDigit() == true -> text.drop(1)
                text.startsWith("nightly-", ignoreCase = true) -> text.substringAfter('-')
                else -> null
            }
            if (nightlyDate != null) {
                val date = nightlyDate.takeWhile(Char::isDigit).toIntOrNull()
                return if (date == null) UNKNOWN else DesktopPackageVersion(0, 0, date, "n", 0, text)
            }
            val normalized = text.removePrefix("v")
            if (normalized.firstOrNull()?.isDigit() != true) return UNKNOWN
            val numbers = normalized.substringBeforeLast('-').split('.')
            return DesktopPackageVersion(
                major = numbers.getOrNull(0)?.toIntOrNull() ?: 0,
                minor = numbers.getOrNull(1)?.toIntOrNull() ?: 0,
                build = numbers.getOrNull(2)?.toIntOrNull() ?: 0,
                variantType = normalized.substringAfterLast('-', "").filter(Char::isLetter),
                variantNumber = normalized.substringAfterLast('-', "").filter(Char::isDigit).toIntOrNull() ?: 0,
                text = text,
            )
        }
    }
}

/** Which GitHub release channel the installed package came from; the asset names carry it. */
enum class DesktopUpdateChannel(val assetSegment: String) {
    RELEASE("release"),
    NIGHTLY("nightly"),
    ;

    companion object {
        fun from(value: String?): DesktopUpdateChannel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.assetSegment == value?.lowercase() }
                ?: RELEASE
    }
}

/** One downloadable Windows artifact of a release: the portable ZIP or the MSI. */
data class DesktopUpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val kind: DesktopUpdatePackage,
)

enum class DesktopUpdatePackage { PORTABLE_ZIP, MSI }

/** A release the Windows client may install, already narrowed to this channel and architecture. */
data class DesktopUpdateRelease(
    val version: DesktopPackageVersion,
    val tag: String,
    val notes: String,
    val publishedAt: String?,
    val publishedUrl: String?,
    /** Portable ZIP and MSI of this release; either may be absent (nightlies ship only the ZIP). */
    val packages: Map<DesktopUpdatePackage, DesktopUpdateAsset>,
    /**
     * The release's `.sha256` asset. Every Windows package is published with one, and the client refuses a package
     * whose checksum file is missing or does not list it: an unverifiable download is never installed.
     */
    val checksumsUrl: String?,
) {
    fun packageOf(kind: DesktopUpdatePackage): DesktopUpdateAsset? = packages[kind]
}

/**
 * Everything the update UI needs about the newest offering, including why it is (not) installable.
 *
 * The Windows client installs complete packages only: unlike Android there is no incremental patch, and a package
 * must carry a published SHA-256 — an unverifiable download is reported instead of applied.
 */
data class DesktopAppUpdate(
    val current: DesktopPackageVersion,
    val available: DesktopUpdateRelease,
    val isNewer: Boolean,
    val notes: String,
    val sizeBytes: Long,
) {
    val version: DesktopPackageVersion get() = available.version
}
