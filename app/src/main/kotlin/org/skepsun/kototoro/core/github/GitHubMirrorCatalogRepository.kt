package org.skepsun.kototoro.core.github

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.core.prefs.GitHubMirrorEntry
import org.skepsun.kototoro.core.prefs.GitHubMirrorManifest

/** Suffix shown next to a mirror label once a probe result exists, e.g. " · 213 ms" / " · timeout". */
fun GitHubMirrorProbeResult?.latencyLabel(context: Context): String = when {
    this == null -> ""
    isAvailable -> " · ${latencyMillis ?: "?"} ms"
    else -> " · ${context.getString(R.string.mirror_probe_timeout)}"
}

sealed interface GitHubMirrorSyncState {
    data object Idle : GitHubMirrorSyncState
    data object Refreshing : GitHubMirrorSyncState

    data class Success(
        val version: String,
        val mirrorCount: Int,
        val timestamp: Long,
    ) : GitHubMirrorSyncState

    /**
     * Every candidate answered HTTP 404: no list has been published for this
     * branch yet. That is not a failure — the built-in catalog is complete and
     * keeps working — so it must not be reported as one.
     */
    data class NoManifest(
        val timestamp: Long,
    ) : GitHubMirrorSyncState

    data class Failed(
        val error: String?,
        val timestamp: Long,
    ) : GitHubMirrorSyncState
}

/** Result of a single-mirror connectivity test. */
data class GitHubMirrorProbeResult(
    val latencyMillis: Long?,
    val isAvailable: Boolean,
)

sealed interface GitHubMirrorProbeState {
    data object Idle : GitHubMirrorProbeState

    data class Running(
        val completed: Int,
        val total: Int,
    ) : GitHubMirrorProbeState

    data class Finished(
        val available: Int,
        val total: Int,
        val fastestId: String?,
        val fastestMillis: Long?,
    ) : GitHubMirrorProbeState
}

data class GitHubMirrorCatalogMeta(
    val version: String? = null,
    val updatedAt: String? = null,
    val lastRefreshAt: Long = 0L,
)

/**
 * Loads, persists and refreshes the GitHub download mirror list, and runs
 * on-demand connectivity tests against the mirrors.
 *
 * The manifest is fetched with a plain http client (never through a mirror)
 * from a chain of non-GitHub endpoints (jsDelivr CDNs) with the GitHub raw
 * file as a last resort, so updating the mirror list never depends on GitHub
 * itself being reachable. Both sync and probing are user-cancellable.
 */
@Singleton
class GitHubMirrorCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @BaseHttpClient private val httpClient: OkHttpClient,
    private val settings: AppSettings,
) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("github_mirror_catalog", Context.MODE_PRIVATE)

    private var refreshJob: Job? = null
    private var probeJob: Job? = null

    private val _entries = MutableStateFlow<List<GitHubMirrorEntry>>(GitHubMirrorCatalog.builtin)
    val entries: StateFlow<List<GitHubMirrorEntry>> = _entries.asStateFlow()

    private val _syncState = MutableStateFlow<GitHubMirrorSyncState>(GitHubMirrorSyncState.Idle)
    val syncState: StateFlow<GitHubMirrorSyncState> = _syncState.asStateFlow()

    private val _meta = MutableStateFlow(
        GitHubMirrorCatalogMeta(
            version = prefs.getString(KEY_VERSION, null),
            updatedAt = prefs.getString(KEY_UPDATED_AT, null),
            lastRefreshAt = prefs.getLong(KEY_LAST_REFRESH, 0L),
        ),
    )
    val meta: StateFlow<GitHubMirrorCatalogMeta> = _meta.asStateFlow()

    private val _probeResults = MutableStateFlow<Map<String, GitHubMirrorProbeResult>>(emptyMap())
    val probeResults: StateFlow<Map<String, GitHubMirrorProbeResult>> = _probeResults.asStateFlow()

    private val _probeState = MutableStateFlow<GitHubMirrorProbeState>(GitHubMirrorProbeState.Idle)
    val probeState: StateFlow<GitHubMirrorProbeState> = _probeState.asStateFlow()

    init {
        _entries.value = loadPersisted() ?: GitHubMirrorCatalog.builtin
    }

    /** Default non-GitHub manifest address: jsDelivr mirror of this repo's `docs/github-mirrors.json`. */
    fun defaultSyncUrl(): String = syncCandidateUrls().first()

    /**
     * URLs tried in order when refreshing. A user-provided override is used
     * alone (their explicit choice); otherwise non-GitHub CDNs come first and
     * the GitHub raw file is only a last resort.
     */
    fun syncCandidateUrls(): List<String> {
        settings.githubMirrorSyncUrl?.takeIf(String::isNotBlank)?.let { return listOf(it) }
        return GitHubMirrorCatalog.syncCandidateUrls(
            repository = context.getString(R.string.github_updates_repo),
            branch = context.getString(R.string.github_updates_branch),
        )
    }

    /** Resolve an entry (built-in or remotely synced) by its id. */
    fun entry(id: String?): GitHubMirrorEntry? {
        if (id.isNullOrBlank()) return null
        return _entries.value.firstOrNull { it.id == id } ?: GitHubMirrorCatalog.builtinById(id)
    }

    fun refresh() {
        if (_syncState.value == GitHubMirrorSyncState.Refreshing) return
        refreshJob?.cancel()
        _syncState.value = GitHubMirrorSyncState.Refreshing
        refreshJob = appScope.launch {
            try {
                val failures = mutableListOf<String>()
                var attempted = 0
                var notFound = 0
                for (url in syncCandidateUrls()) {
                    attempted++
                    try {
                        val manifest = fetch(url)
                        val timestamp = System.currentTimeMillis()
                        val saved = saveManifest(manifest, timestamp)
                        if (saved) {
                            // New list arrived: previous latencies are stale.
                            cancelProbes()
                            _probeResults.value = emptyMap()
                            _syncState.value = GitHubMirrorSyncState.Success(
                                manifest.version,
                                _entries.value.size,
                                timestamp,
                            )
                            return@launch
                        } else {
                            failures += "${hostOf(url)}: invalid manifest"
                        }
                    } catch (e: TimeoutCancellationException) {
                        // One slow endpoint must not abort the candidate chain: record it
                        // and keep trying the remaining mirrors.
                        failures += "${hostOf(url)}: timeout"
                    } catch (e: HttpException) {
                        if (e.code == 404) {
                            notFound++
                        }
                        failures += "${hostOf(url)}: HTTP ${e.code}"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures += "${hostOf(url)}: ${shortError(e)}"
                    }
                }
                _syncState.value = if (notFound == attempted) {
                    // Nothing published (yet): keep serving the built-in list quietly.
                    GitHubMirrorSyncState.NoManifest(System.currentTimeMillis())
                } else {
                    GitHubMirrorSyncState.Failed(failures.joinToString(" · "), System.currentTimeMillis())
                }
            } finally {
                if (_syncState.value == GitHubMirrorSyncState.Refreshing) {
                    // Cancelled mid-run: fall back to the previous list silently.
                    _syncState.value = GitHubMirrorSyncState.Idle
                }
            }
        }
    }

    fun cancelSync() {
        refreshJob?.cancel()
        refreshJob = null
        _syncState.value = GitHubMirrorSyncState.Idle
    }

    /**
     * Tests every given mirror in parallel by fetching a tiny GitHub-hosted
     * file through that mirror's rewrite rules. Never follows the user's
     * mirror selection — this probes the whole list.
     */
    fun probeMirrors(entries: List<GitHubMirrorEntry> = _entries.value) {
        if (entries.isEmpty()) return
        if (_probeState.value is GitHubMirrorProbeState.Running) return
        probeJob?.cancel()
        _probeResults.value = emptyMap()
        _probeState.value = GitHubMirrorProbeState.Running(0, entries.size)
        probeJob = appScope.launch {
            try {
                val results = Collections.synchronizedMap(LinkedHashMap<String, GitHubMirrorProbeResult>())
                coroutineScope {
                    entries.map { entry ->
                        async {
                            val result = probe(entry)
                            results[entry.id] = result
                            _probeResults.value = results.toMap()
                            _probeState.value = GitHubMirrorProbeState.Running(results.size, entries.size)
                        }
                    }.awaitAll()
                }
                val finalResults = results.toMap()
                val available = finalResults.values.count { it.isAvailable }
                val fastest = finalResults.values
                    .filter { it.isAvailable && it.latencyMillis != null }
                    .minByOrNull { it.latencyMillis!! }
                val fastestEntry = fastest?.let { f -> finalResults.entries.firstOrNull { it.value === f }?.key }
                _probeState.value = GitHubMirrorProbeState.Finished(available, finalResults.size, fastestEntry, fastest?.latencyMillis)
            } finally {
                if (_probeState.value is GitHubMirrorProbeState.Running) {
                    // Cancelled mid-run: keep whatever completed, go quiet.
                    _probeState.value = GitHubMirrorProbeState.Idle
                }
            }
        }
    }

    fun cancelProbes() {
        probeJob?.cancel()
        probeJob = null
        _probeState.value = GitHubMirrorProbeState.Idle
    }

    private suspend fun probe(entry: GitHubMirrorEntry): GitHubMirrorProbeResult = withContext(Dispatchers.IO) {
        val url = GitHubMirrorCatalog.apply(PROBE_URL, entry)
        val startedAt = System.nanoTime()
        try {
            withTimeout(PROBE_TIMEOUT_MS) {
                val request = Request.Builder()
                    .url(url)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build()
                httpClient.newCall(request).awaitSuccess().use { /* headers received = reachable */ }
            }
            GitHubMirrorProbeResult((System.nanoTime() - startedAt) / 1_000_000L, true)
        } catch (e: TimeoutCancellationException) {
            // A slow mirror is "unavailable", NOT a user cancellation: rethrowing would
            // tear down every sibling probe in the batch and leave most mirrors without
            // any result row at all.
            GitHubMirrorProbeResult(null, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GitHubMirrorProbeResult(null, false)
        }
    }

    suspend fun fetch(url: String): GitHubMirrorManifest = withContext(Dispatchers.IO) {
        withTimeout(FETCH_TIMEOUT_MS) {
            val body = httpClient.newCall(GET(url)).awaitSuccess().use { response ->
                response.body.string()
            }
            GitHubMirrorCatalog.json.decodeFromString<GitHubMirrorManifest>(body)
        }
    }

    private fun saveManifest(manifest: GitHubMirrorManifest, timestamp: Long): Boolean {
        if (manifest.version.isBlank()) return false
        if (manifest.mirrors.isEmpty() || manifest.mirrors.size > MAX_MIRRORS) return false
        if (manifest.mirrors.any { !it.id.matches(ID_PATTERN) }) return false
        val normalized = GitHubMirrorCatalog.normalizeMirrors(manifest.mirrors)
        prefs.edit()
            .putString(KEY_JSON, GitHubMirrorCatalog.json.encodeToString(GitHubMirrorManifest.serializer(), manifest))
            .putString(KEY_VERSION, manifest.version)
            .putString(KEY_UPDATED_AT, manifest.updatedAt)
            .putLong(KEY_LAST_REFRESH, timestamp)
            .apply()
        _entries.value = normalized
        _meta.value = GitHubMirrorCatalogMeta(manifest.version, manifest.updatedAt, timestamp)
        return true
    }

    private fun loadPersisted(): List<GitHubMirrorEntry>? {
        val raw = prefs.getString(KEY_JSON, null) ?: return null
        val manifest = runCatching {
            GitHubMirrorCatalog.json.decodeFromString<GitHubMirrorManifest>(raw)
        }.getOrNull() ?: return null
        if (manifest.version.isBlank() || manifest.mirrors.isEmpty()) return null
        return GitHubMirrorCatalog.normalizeMirrors(manifest.mirrors)
    }

    private fun hostOf(url: String): String =
        runCatching { url.toHttpUrlOrNull()?.host ?: url }.getOrDefault(url.removePrefix("https://"))

    private fun shortError(e: Exception): String = when (e) {
        is HttpException -> "HTTP ${e.code}"
        else -> e.message?.take(80) ?: e.javaClass.simpleName
    }

    private companion object {
        const val KEY_JSON = "manifest_json"
        const val KEY_VERSION = "manifest_version"
        const val KEY_UPDATED_AT = "manifest_updated_at"
        const val KEY_LAST_REFRESH = "manifest_last_refresh"
        const val MAX_MIRRORS = 64
        const val FETCH_TIMEOUT_MS = 10_000L
        const val PROBE_TIMEOUT_MS = 6_000L

        /** Mirror ids are slug-like tokens, e.g. "ghproxy" / "gh-proxy-cf". */
        val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        /** Tiny always-present GitHub file used to test mirror reachability. */
        const val PROBE_URL = "https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/index.min.json"
    }
}
