package org.skepsun.kototoro.extensions.repo

import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.core.prefs.GitHubMirrorEntry
import org.skepsun.kototoro.core.prefs.GitHubMirrorStrategy

/**
 * Central place to rewrite GitHub-hosted URLs through the user-selected
 * download mirror. Only applies to URLs that are actually fetched over the
 * network; canonical repository identity (baseUrl / fingerprint) is always
 * derived from the original URL, so picking a mirror never duplicates repos.
 *
 * Two entries exist:
 *  - [applyGitHubMirror] with a built-in [AppSettings.GitHubMirror] — used by
 *    tests and simple call sites;
 *  - [applyGitHubMirror] with a [GitHubMirrorEntry] — used by services that
 *    resolve the full (built-in + remotely synced) mirror list so runtime-added
 *    mirrors also take effect.
 */
fun applyGitHubMirror(url: String, mirror: AppSettings.GitHubMirror): String {
    val entry = GitHubMirrorCatalog.builtinById(mirror.value)
        ?: GitHubMirrorCatalog.NATIVE
    return applyGitHubMirror(url, entry)
}

fun applyGitHubMirror(url: String, entry: GitHubMirrorEntry): String = GitHubMirrorCatalog.apply(url, entry)

/**
 * Download URLs to try for a GitHub-hosted archive, in order.
 *
 * Two cases where a jsDelivr-style CDN cannot deliver the file:
 *  - **Release assets** (`github.com/…/releases/download/…`): release binaries are not part
 *    of the git tree, so there is nothing for `/gh/<repo>@<ref>/<path>` to serve. Mihon-style
 *    repos are in this case (their branch only carries index files + `release-assets.json`).
 *  - **`.jar` files**: jsDelivr answers `403 Forbidden` for them as a file-type policy.
 *    Verified: same repo + same ref serves `index.min.json` with 200 but `apk/plugin.jar`
 *    with 403, the branch name and a pinned commit SHA both 403 (so it is not ref syntax
 *    or CDN caching), the file is far below the 20 MB limit, and an unrelated popular
 *    repo (`google/gson`) 403s the same way.
 *
 * Everything else (plain raw files, `.js` bundles, other archives) is served fine by
 * jsDelivr, so the user's own choice stays first there. PREFIX / HOST_REPLACE mirrors
 * proxy both raw trees and releases, so they are always queued as fallbacks, with the
 * direct GitHub URL as the final resort.
 */
fun gitHubArchiveCandidates(url: String, selected: GitHubMirrorEntry, catalog: List<GitHubMirrorEntry>): List<String> {
    val path = url.substringBefore('?')
    val isGitHub = path.startsWith("https://github.com/") || path.startsWith("https://raw.githubusercontent.com/")
    if (!isGitHub) return listOf(applyGitHubMirror(url, selected))
    val isReleaseAsset = path.startsWith("https://github.com/") && path.contains("/releases/download/")
    val isJar = path.endsWith(".jar", ignoreCase = true)
    val isArchive = isReleaseAsset || isJar || ARCHIVE_SUFFIXES.any { path.endsWith(it, ignoreCase = true) }
    if (!isArchive) return listOf(applyGitHubMirror(url, selected))

    val ordered = ArrayList<String>()
    if (selected.canServeGitHubArchive(isReleaseAsset, isJar)) {
        ordered += applyGitHubMirror(url, selected)
    }
    catalog.forEach { entry ->
        // NATIVE rewrites to the plain URL, which is appended once at the very end.
        if (entry.id != selected.id &&
            entry.strategy != GitHubMirrorStrategy.NATIVE &&
            entry.canServeGitHubArchive(isReleaseAsset, isJar)
        ) {
            ordered += applyGitHubMirror(url, entry)
        }
    }
    ordered += url // direct is always the final fallback
    return ordered.distinct()
}

private fun GitHubMirrorEntry.canServeGitHubArchive(isReleaseAsset: Boolean, isJar: Boolean): Boolean = when (strategy) {
    GitHubMirrorStrategy.PREFIX, GitHubMirrorStrategy.HOST_REPLACE, GitHubMirrorStrategy.NATIVE -> true
    GitHubMirrorStrategy.JSDELIVR -> !isReleaseAsset && !isJar
}

/** Archive shapes that deserve fallback candidates; `.jar` is additionally blocked by jsDelivr. */
private val ARCHIVE_SUFFIXES = listOf(".apk", ".apks", ".xapk", ".zip", ".cs3", ".lnplugin")
