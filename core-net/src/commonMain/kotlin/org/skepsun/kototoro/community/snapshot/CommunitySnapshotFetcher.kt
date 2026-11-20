package org.skepsun.kototoro.community.snapshot

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One downloaded snapshot file. [notModified] means the copy matching the `etag` passed in is still current. */
data class CommunitySnapshotFile(val text: String?, val etag: String?, val notModified: Boolean)

class CommunitySnapshotException(val status: Int) : Exception("Community snapshot request failed with HTTP $status")

/**
 * Plain GETs of the static community index (plan §6.3). Parsing and deciding what to fetch belong to core-domain's
 * CommunitySnapshotReader; this only moves text, conditionally when the caller still has a copy.
 */
class CommunitySnapshotFetcher(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    /** The app routes GitHub-hosted downloads through the reader's mirror choice; identity by default. */
    private val rewriteUrl: (String) -> String = { it },
) {

    /** [path] such as `v1/manifest.json`; null when the file does not exist (a shard nothing landed in). */
    suspend fun fetch(path: String, etag: String? = null): CommunitySnapshotFile? {
        currentCoroutineContext().ensureActive()
        return httpClient.prepareGet(rewriteUrl(baseUrl.trimEnd('/') + "/" + path.trimStart('/'))) {
            expectSuccess = false
            etag?.let { header(HttpHeaders.IfNoneMatch, it) }
        }.execute { response ->
            when {
                response.status == HttpStatusCode.NotModified -> CommunitySnapshotFile(null, etag, notModified = true)
                response.status == HttpStatusCode.NotFound -> null
                response.status.isSuccess() ->
                    CommunitySnapshotFile(response.bodyAsText(), response.headers[HttpHeaders.ETag], notModified = false)
                else -> throw CommunitySnapshotException(response.status.value)
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://kototoro-app.github.io/community-index/"

        /** The same files from the `gh-pages` branch; GitHub mirrors rewrite this form, not `*.github.io`. */
        const val RAW_BASE_URL = "https://raw.githubusercontent.com/Kototoro-app/community-index/gh-pages/"
    }
}
