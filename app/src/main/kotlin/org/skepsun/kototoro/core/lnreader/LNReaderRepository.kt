package org.skepsun.kototoro.core.lnreader

import android.util.Log
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.core.github.GitHubMirrorCatalogRepository
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.extensions.repo.gitHubArchiveCandidates

/**
 * Manages LNReader plugin repositories.
 *
 * Fetches plugin index (plugins.min.json), downloads individual JS bundles,
 * and installs them via JsonSourceManager. Both network paths honour the
 * user's GitHub mirror (with fallback candidates) and run as cancellable
 * coroutine calls — a blocking `execute()` used to leave the wizard stuck on
 * "downloading" because cancelling could not interrupt it.
 */
class LNReaderRepository(
    private val httpClient: OkHttpClient,
    private val jsonSourceManager: JsonSourceManager,
    private val settings: AppSettings,
    private val mirrorRepository: GitHubMirrorCatalogRepository,
) {

    companion object {
        private const val TAG = "LNReaderRepository"

        /** Official LNReader plugin repository (preset/recommended). */
        const val OFFICIAL_REPO_URL =
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"
    }

    /** Mirror-aware download candidates for a plugin index / JS bundle URL. */
    private fun downloadCandidates(url: String): List<String> {
        val entry = mirrorRepository.entry(settings.gitHubMirrorId) ?: GitHubMirrorCatalog.NATIVE
        return gitHubArchiveCandidates(url, entry, mirrorRepository.entries.value)
    }

    /**
     * Fetch and parse the plugin index from a repository URL.
     * Returns a list of available plugins.
     */
    suspend fun fetchPluginIndex(repoUrl: String): Result<List<LNReaderPluginInfo>> =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            for (url in downloadCandidates(repoUrl)) {
                try {
                    val body = httpClient.newCall(Request.Builder().url(url).build())
                        .awaitSuccess()
                        .use { response -> response.body?.string() ?: error("Empty response") }
                    val plugins = parsePluginIndex(body)
                    Log.d(TAG, "Fetched ${plugins.size} plugins from $url")
                    return@withContext Result.success(plugins)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Plugin index fetch via $url failed: ${e.message}")
                }
            }
            Result.failure(lastError ?: RuntimeException("No plugin index candidate succeeded"))
        }

    /**
     * Download a plugin's JS bundle from its URL and install it as a LNREADER source.
     */
    suspend fun installPlugin(plugin: LNReaderPluginInfo): Result<Int> =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            for (url in downloadCandidates(plugin.url)) {
                try {
                    val jsContent = httpClient.newCall(Request.Builder().url(url).build())
                        .awaitSuccess()
                        .use { response -> response.body?.string() ?: error("Empty JS bundle") }
                    Log.d(TAG, "Downloaded plugin ${plugin.id} (${jsContent.length} bytes)")
                    return@withContext jsonSourceManager.importLNReaderPlugin(
                        jsContent = jsContent,
                        metadataOverride = LNReaderPluginMetadata(
                            id = plugin.id,
                            name = plugin.name,
                            site = plugin.site,
                            version = plugin.version,
                            lang = plugin.lang,
                            icon = plugin.iconUrl,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Plugin ${plugin.id} download via $url failed: ${e.message}")
                }
            }
            Result.failure(lastError ?: RuntimeException("No plugin download candidate succeeded"))
        }

    private fun parsePluginIndex(json: String): List<LNReaderPluginInfo> {
        val array = JSONArray(json)
        val plugins = mutableListOf<LNReaderPluginInfo>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            plugins.add(
                LNReaderPluginInfo(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    site = obj.optString("site", ""),
                    lang = obj.optString("lang", ""),
                    version = obj.optString("version", ""),
                    url = obj.optString("url", ""),
                    iconUrl = obj.optString("iconUrl", ""),
                )
            )
        }

        return plugins.filter { it.id.isNotBlank() && it.url.isNotBlank() }
    }
}

/**
 * A single plugin entry from the repository index.
 */
data class LNReaderPluginInfo(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val url: String,
    val iconUrl: String,
)
