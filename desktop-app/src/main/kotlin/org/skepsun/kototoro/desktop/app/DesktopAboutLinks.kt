package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.desktop.runtime.DesktopUpdateChannel
import java.awt.Desktop
import java.net.URI

/**
 * Which release channel this copy checks.
 *
 * The stamped channel says where the running package came from, and it wins: a nightly install never offers a release
 * package (the updater would hand it a downgrade), and a release install follows the nightly repository only when the
 * user asked for it — Android's `allow_unstable_updates` switch, which is disabled on a nightly build for the same
 * reason it is forced on there.
 */
internal fun desktopUpdateChannel(stamped: DesktopAppVersion, unstable: Boolean): DesktopUpdateChannel =
    if (stamped.channel == DesktopUpdateChannel.NIGHTLY || unstable) {
        DesktopUpdateChannel.NIGHTLY
    } else {
        DesktopUpdateChannel.RELEASE
    }

/** True when the unstable switch can be changed at all: a nightly copy is unstable by definition. */
internal fun desktopUnstableEditable(stamped: DesktopAppVersion): Boolean =
    stamped.channel == DesktopUpdateChannel.RELEASE

/**
 * One row of Android's "support" group on the about screen.
 *
 * Android opens these through an intent; the desktop opens the system browser with the same published URL, so the
 * row's summary is the URL itself and nothing is hidden behind a link that may not exist.
 */
internal data class DesktopAboutLink(
    /** Stable tag suffix, so a probe can address the row without depending on the language. */
    val key: String,
    val title: String,
    val url: String,
) {
    val openable: Boolean
        get() = runCatching { URI(url).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)
}

/** Android's `about_group_support` rows, in its order; the labels are the application's own resources. */
internal fun desktopAboutLinks(): List<DesktopAboutLink> = listOf(
    Triple("manual", "user_manual", "url_user_manual"),
    Triple("github", "source_code", "url_github"),
    Triple("donate", "about_donate", "url_donate"),
    Triple("afdian", "about_donate_afdian", "url_afdian"),
    Triple("discord", "about_discord", "url_discord"),
).map { (key, title, url) -> DesktopAboutLink(key, AndroidStrings[title], AndroidStrings[url]) }

/**
 * Opens [url] in the system browser without letting a missing browser crash the window; returns whether it opened.
 *
 * A packaged copy has a desktop, but a bare JVM (a probe, a CI runner) may not, and `Desktop.browse` then throws.
 */
internal fun openDesktopUrl(url: String): Boolean {
    val target = runCatching { URI(url) }.getOrNull() ?: return false
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE)) return false
    return runCatching { desktop.browse(target) }.isSuccess
}
