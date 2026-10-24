package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.core.prefs.GitHubMirrorEntry
import org.skepsun.kototoro.core.prefs.GitHubMirrorStrategy
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences

/**
 * Android's `github_mirror` preference: which proxy serves GitHub downloads, stored by entry id under the same key so
 * one store shape survives on both platforms.
 *
 * The id is *not* validated on read: a store written by a newer build (or by Android, which also accepts mirrors from
 * a remote manifest) keeps its value, and [entry] falls back to the direct connection for anything this build does not
 * know, which is what Android's `legacyAliases` resolution amounts to.
 */
data class DesktopGitHubMirrorSettings(
    val id: String = GitHubMirrorCatalog.NATIVE.id,
) {
    /** The mirror this id resolves to; the direct connection for an unknown or blank id. */
    val entry: GitHubMirrorEntry
        get() = GitHubMirrorCatalog.builtinById(id.trim()) ?: GitHubMirrorCatalog.NATIVE

    val isNative: Boolean get() = entry.strategy == GitHubMirrorStrategy.NATIVE

    fun toPreferences(): Map<String, SourcePreferenceValue?> =
        mapOf(KEY to SourcePreferenceValue.Text(entry.id))

    companion object {
        /** Android's own key for the same preference. */
        const val KEY = "github_mirror"

        fun from(values: Map<String, SourcePreferenceValue>): DesktopGitHubMirrorSettings =
            DesktopGitHubMirrorSettings(
                id = (values[KEY] as? SourcePreferenceValue.Text)?.value.orEmpty()
                    .ifBlank { GitHubMirrorCatalog.NATIVE.id },
            )
    }
}

/**
 * The GitHub download mirror the Windows host applies to every GitHub URL it fetches on the user's behalf: release
 * assets for application updates and super-resolution models, and the raw repository files an extension repository is
 * indexed from. Like [DesktopProxy] it reads the live value on each call, so choosing a mirror takes effect on the next
 * download instead of after a restart.
 *
 * The catalog (and its rewriting rules) is shared with Android from `core-net`; the release *API* (`api.github.com`)
 * is deliberately left alone because no mirror proxies it, so a mirror makes the downloads reachable, not the
 * version check that names them.
 */
class DesktopGitHubMirror(private val preferences: SourcePreferences) {
    private val mutableSettings = MutableStateFlow(DesktopGitHubMirrorSettings.from(preferences.snapshot()))
    val settings: StateFlow<DesktopGitHubMirrorSettings> = mutableSettings.asStateFlow()

    /** The built-in mirrors, direct connection first — the list the settings dialog shows. */
    val options: List<GitHubMirrorEntry> get() = GitHubMirrorCatalog.builtin

    fun save(value: DesktopGitHubMirrorSettings): Boolean {
        val persisted = preferences.edit(SourcePreferenceEdit(changes = value.toPreferences()))
        mutableSettings.value = value
        return persisted
    }

    fun reload() {
        mutableSettings.value = DesktopGitHubMirrorSettings.from(preferences.snapshot())
    }

    /** Rewrite one GitHub URL through the current mirror; the identity for the direct connection. */
    fun rewrite(url: String): String = GitHubMirrorCatalog.apply(url, mutableSettings.value.entry)
}
