package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.desktop.runtime.DesktopPackageVersion
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateChannel
import java.util.Properties

/**
 * What this copy of the application is: the version name its package was published under, the MSI package version and
 * the release channel it came from.
 *
 * It is stamped at build time (`:desktop-app:prepareDesktopVersion`) instead of being guessed at run time: the update
 * check compares this version against the published releases, so a wrong answer here would either hide an update or
 * offer the same build again. A checkout without the generated resource — a test classpath, a probe — falls back to
 * [UNKNOWN] and never offers an update.
 */
data class DesktopAppVersion(
    val versionName: String,
    val packageVersion: String,
    val channel: DesktopUpdateChannel,
) {
    val version: DesktopPackageVersion = DesktopPackageVersion.parse(versionName)

    /** `2.3.1`, or `2.3.1 · nightly` when the channel changes what is installed. */
    fun display(): String = if (channel == DesktopUpdateChannel.RELEASE) versionName else "$versionName · ${channel.assetSegment}"

    companion object {
        val UNKNOWN = DesktopAppVersion("", "", DesktopUpdateChannel.RELEASE)

        fun load(resource: String = "desktop-version.properties"): DesktopAppVersion {
            val properties = Properties()
            val stream = DesktopAppVersion::class.java.classLoader.getResourceAsStream(resource) ?: return UNKNOWN
            stream.use { properties.load(it) }
            val versionName = properties.getProperty("versionName").orEmpty().trim()
            if (versionName.isEmpty()) return UNKNOWN
            return DesktopAppVersion(
                versionName = versionName,
                packageVersion = properties.getProperty("packageVersion").orEmpty().ifEmpty { versionName },
                channel = DesktopUpdateChannel.from(properties.getProperty("channel")),
            )
        }
    }
}
