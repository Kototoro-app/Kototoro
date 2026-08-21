package org.skepsun.kototoro.video.player

import eu.kanade.tachiyomi.network.await
import okio.Buffer
import okhttp3.OkHttpClient
import okhttp3.Request

private const val HLS_PROBE_BYTE_LIMIT = 64L * 1024L

internal class HlsManifestProbe(
    private val httpClient: OkHttpClient,
) {
    suspend fun isHls(
        url: String,
        headers: Map<String, String>,
    ): Boolean {
        val request = Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
            }
            .get()
            .build()
        return httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) return@use false
            val buffer = Buffer()
            response.body.source().read(buffer, HLS_PROBE_BYTE_LIMIT)
            looksLikeHlsManifest(buffer.readUtf8())
        }
    }
}

internal fun looksLikeHlsManifest(prefix: String): Boolean {
    return prefix
        .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        .startsWith("#EXTM3U")
}
