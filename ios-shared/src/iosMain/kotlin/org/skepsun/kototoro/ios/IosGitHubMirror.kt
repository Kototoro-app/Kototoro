package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog

/**
 * Android's GitHub mirrors (the shared catalog in core-net) for extension downloads: the same mirror ids under the
 * same `github_mirror` key, and the same rewrite rules. Repository index addresses and api.github.com stay direct,
 * as on Windows, because an index's final address is the base its relative paths resolve against.
 */
object IosGitHubMirror {
    val mirrors: List<IosMirror> get() = GitHubMirrorCatalog.builtin.map { IosMirror(it.id, it.name, it.note) }

    val nativeId: String get() = GitHubMirrorCatalog.NATIVE.id

    /** The URL the reachability test fetches through each mirror. */
    val probeUrl: String get() = GitHubMirrorCatalog.PROBE_URL

    /** [url] through mirror [id]; unchanged for the direct choice, unknown ids and non-GitHub addresses. */
    fun rewrite(url: String, id: String): String =
        GitHubMirrorCatalog.builtinById(id)?.let { GitHubMirrorCatalog.apply(url, it) } ?: url
}

data class IosMirror(val id: String, val name: String, val note: String?)
