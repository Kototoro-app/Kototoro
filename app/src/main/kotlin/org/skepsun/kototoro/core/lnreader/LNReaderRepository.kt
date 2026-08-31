package org.skepsun.kototoro.core.lnreader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager

/**
 * Manages LNReader plugin repositories.
 *
 * Fetches plugin index (plugins.min.json), downloads individual JS bundles,
 * and installs them via JsonSourceManager.
 */
class LNReaderRepository(
    private val httpClient: OkHttpClient,
    private val jsonSourceManager: JsonSourceManager,
) {

    companion object {
        private const val TAG = "LNReaderRepository"

        /** Official LNReader plugin repository (preset/recommended). */
        const val OFFICIAL_REPO_URL =
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"
    }

    /**
     * Fetch and parse the plugin index from a repository URL.
     * Returns a list of available plugins.
     */
    suspend fun fetchPluginIndex(repoUrl: String): Result<List<LNReaderPluginInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(repoUrl).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeException("HTTP ${response.code}: ${response.message}")
                    )
                }

                val body = response.body?.string()
                    ?: return@withContext Result.failure(RuntimeException("Empty response"))
                response.close()

                val plugins = parsePluginIndex(body)
                Log.d(TAG, "Fetched ${plugins.size} plugins from $repoUrl")
                Result.success(plugins)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch plugin index from $repoUrl", e)
                Result.failure(e)
            }
        }

    /**
     * Download a plugin's JS bundle from its URL and install it as a LNREADER source.
     */
    suspend fun installPlugin(plugin: LNReaderPluginInfo): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(plugin.url).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeException("HTTP ${response.code}: ${response.message}")
                    )
                }

                val jsContent = response.body?.string()
                    ?: return@withContext Result.failure(RuntimeException("Empty JS bundle"))
                    response.close()

                    Log.d(TAG, "Downloaded plugin ${plugin.id} (${jsContent.length} bytes)")
                    jsonSourceManager.importLNReaderPlugin(
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to install plugin ${plugin.id}", e)
                    Result.failure(e)
                }
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
