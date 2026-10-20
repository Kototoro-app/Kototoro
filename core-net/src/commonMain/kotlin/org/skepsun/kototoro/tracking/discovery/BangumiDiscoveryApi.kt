package org.skepsun.kototoro.tracking.discovery

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Public read protocol shared with Android; the caller retains its client, auth and mirror policy. */
class BangumiDiscoveryApi(private val client: HttpClient) {
    suspend fun calendar(baseUrl: String = BASE_URL): String =
        client.prepareGet("${baseUrl.trimEnd('/')}/calendar") {
            expectSuccess = true
        }.execute { it.bodyAsText() }

    suspend fun search(query: String, offset: Int, isAnime: Boolean, baseUrl: String = BASE_URL): String =
        client.preparePost("${baseUrl.trimEnd('/')}/v0/search/subjects") {
            expectSuccess = true
            url.parameters.append("limit", "10")
            url.parameters.append("offset", offset.toString())
            header("Content-Type", "application/json")
            setBody(JsonObject(mapOf(
                "keyword" to JsonPrimitive(query),
                "filter" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive(if (isAnime) 2 else 1))))),
            )).toString())
        }.execute { it.bodyAsText() }

    suspend fun details(id: Long, baseUrl: String = BASE_URL): String {
        require(id > 0)
        return client.prepareGet("${baseUrl.trimEnd('/')}/v0/subjects/$id") {
            expectSuccess = true
        }.execute { it.bodyAsText() }
    }

    companion object { const val BASE_URL = "https://api.bgm.tv" }
}
