package org.skepsun.kototoro.scrobbling.mal.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class MALMediaType(val endpoint: String, val discoveryFields: String) {
    ANIME("anime", "alternative_titles,mean,num_episodes,status,start_season"),
    MANGA("manga", "alternative_titles,mean,num_chapters,status,start_date"),
}

/** Read-only discovery protocol. The platform supplies authentication and parses the returned JSON text. */
class MALDiscoveryApi(private val httpClient: HttpClient, private val clientId: String) {
    suspend fun search(query: String, offset: Int, mediaType: MALMediaType): String {
        val request = request(mediaType).apply {
            url.parameters.append("offset", offset.toString())
            appendDiscoveryParameters(mediaType)
            // Preserve the original UTF-16 length limit, including its boundary behavior.
            url.parameters.append("q", query.take(64))
        }
        return request.execute()
    }

    suspend fun ranking(
        mediaType: MALMediaType,
        rankingType: String = "all",
        limit: Int = 20,
        offset: Int = 0,
    ): String {
        val request = request(mediaType, "ranking").apply {
            url.parameters.append("ranking_type", rankingType)
            appendPagingAndDiscovery(mediaType, limit, offset)
        }
        return request.execute()
    }

    suspend fun seasonalAnime(
        year: Int,
        season: String,
        sort: String = "anime_num_list_users",
        limit: Int = 20,
        offset: Int = 0,
    ): String {
        val request = request(MALMediaType.ANIME, "season", year.toString(), season).apply {
            url.parameters.append("sort", sort)
            appendPagingAndDiscovery(MALMediaType.ANIME, limit, offset)
        }
        return request.execute()
    }

    private fun request(mediaType: MALMediaType, vararg path: String) = HttpRequestBuilder().apply {
        url(BASE_API_URL)
        url.appendPathSegments(listOf(mediaType.endpoint) + path, encodeSlash = true)
        method = HttpMethod.Get
        header("X-MAL-CLIENT-ID", clientId)
        // Legacy parseJson consumes any status left by the platform interceptor, including error JSON.
        expectSuccess = false
    }

    private fun HttpRequestBuilder.appendPagingAndDiscovery(mediaType: MALMediaType, limit: Int, offset: Int) {
        url.parameters.append("limit", limit.toString())
        url.parameters.append("offset", offset.toString())
        appendDiscoveryParameters(mediaType)
    }

    private fun HttpRequestBuilder.appendDiscoveryParameters(mediaType: MALMediaType) {
        url.parameters.append("nsfw", "true")
        url.parameters.append("fields", mediaType.discoveryFields)
    }

    private suspend fun HttpRequestBuilder.execute(): String {
        currentCoroutineContext().ensureActive()
        return httpClient.prepareRequest(this).execute { it.bodyAsText() }
    }

    private companion object {
        const val BASE_API_URL = "https://api.myanimelist.net/v2"
    }
}
