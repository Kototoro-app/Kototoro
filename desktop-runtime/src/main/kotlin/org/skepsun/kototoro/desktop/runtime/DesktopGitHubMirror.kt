package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.core.prefs.GitHubMirrorEntry
import org.skepsun.kototoro.core.prefs.GitHubMirrorProbeResult
import org.skepsun.kototoro.core.prefs.GitHubMirrorProbeState
import org.skepsun.kototoro.core.prefs.GitHubMirrorStrategy
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
 *
 * [probe] measures what the catalog cannot know: which of 28 proxies actually answers from this machine, and how fast.
 * It never uses the selected mirror — the sweep tests the whole list, like Android's performance sweep.
 */
class DesktopGitHubMirror(
    private val preferences: SourcePreferences,
    /** The probe target; a parameter so the tests can play the network with a loopback server. */
    private val probeUrl: String = GitHubMirrorCatalog.PROBE_URL,
) {
    private val mutableSettings = MutableStateFlow(DesktopGitHubMirrorSettings.from(preferences.snapshot()))
    val settings: StateFlow<DesktopGitHubMirrorSettings> = mutableSettings.asStateFlow()

    private val mutableProbeResults = MutableStateFlow<Map<String, GitHubMirrorProbeResult>>(emptyMap())

    /** Reachability of each mirror, keyed by entry id; emptied when a new sweep starts. */
    val probeResults: StateFlow<Map<String, GitHubMirrorProbeResult>> = mutableProbeResults.asStateFlow()
    private val mutableProbeState = MutableStateFlow<GitHubMirrorProbeState>(GitHubMirrorProbeState.Idle)
    val probeState: StateFlow<GitHubMirrorProbeState> = mutableProbeState.asStateFlow()

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

    /**
     * Fetches a tiny GitHub-hosted file through every given mirror in parallel and records how long each took. A slow
     * mirror counts as unavailable rather than cancelling the sweep: one dead host must not hide the other 27 rows.
     */
    suspend fun probe(entries: List<GitHubMirrorEntry> = options) {
        if (entries.isEmpty()) return
        if (mutableProbeState.value is GitHubMirrorProbeState.Running) return
        mutableProbeResults.value = emptyMap()
        mutableProbeState.value = GitHubMirrorProbeState.Running(0, entries.size)
        try {
            val results = LinkedHashMap<String, GitHubMirrorProbeResult>(entries.size)
            val lock = Mutex()
            coroutineScope {
                entries.map { entry ->
                    async {
                        val result = probeEntry(entry)
                        lock.withLock {
                            results[entry.id] = result
                            mutableProbeResults.value = results.toMap()
                            mutableProbeState.value = GitHubMirrorProbeState.Running(results.size, entries.size)
                        }
                    }
                }.awaitAll()
            }
            val fastest = results.entries.filter { it.value.isAvailable }.minByOrNull { it.value.latencyMillis ?: Long.MAX_VALUE }
            mutableProbeState.value = GitHubMirrorProbeState.Finished(
                available = results.values.count { it.isAvailable },
                total = results.size,
                fastestId = fastest?.key,
                fastestMillis = fastest?.value?.latencyMillis,
            )
        } finally {
            if (mutableProbeState.value is GitHubMirrorProbeState.Running) {
                // Cancelled mid-sweep: keep the rows that finished, stop reporting progress.
                mutableProbeState.value = GitHubMirrorProbeState.Idle
            }
        }
    }

    private suspend fun probeEntry(entry: GitHubMirrorEntry): GitHubMirrorProbeResult {
        val url = GitHubMirrorCatalog.apply(probeUrl, entry)
        val startedAt = System.nanoTime()
        return try {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
                .GET()
                .build()
            // Interruptible so cancelling the sweep aborts the socket instead of waiting for the timeout; the headers
            // are the answer, because a mirror that redirects to a dead host never gets this far.
            runInterruptible(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.discarding()) }
            GitHubMirrorProbeResult((System.nanoTime() - startedAt) / 1_000_000L, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GitHubMirrorProbeResult(null, false)
        }
    }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private companion object {
        /** Android uses 6 s per mirror; longer only turns a dead host into a slow row. */
        const val PROBE_TIMEOUT_MS = 6_000L
    }
}
