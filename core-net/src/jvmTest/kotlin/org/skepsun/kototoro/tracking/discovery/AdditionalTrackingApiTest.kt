package org.skepsun.kototoro.tracking.discovery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdditionalTrackingApiTest {
    @Test
    fun `Bangumi endpoint override covers public sections without redirecting account credentials`() = runTest {
        val publicPaths = mutableListOf<String>()
        var accountRequests = 0
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val response = if (request.url.host == "mirror.invalid") {
                assertNull(request.headers[HttpHeaders.Authorization])
                publicPaths += path
                when {
                    path == "/v0/search/subjects" -> """{"data":[{"id":42,"name":"作品","type":1}]}"""
                    path == "/v0/subjects/42" -> """{"id":42,"name":"作品","type":1}"""
                    else -> "[]"
                }
            } else {
                assertEquals("api.bgm.tv", request.url.host)
                assertEquals("Bearer private-token", request.headers[HttpHeaders.Authorization])
                accountRequests++
                """{"type":3,"ep_status":8}"""
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = PublicTrackingApi(client, bangumiBaseUrl = "https://mirror.invalid/")
            assertEquals(42L, api.search(PublicTrackingSite.BANGUMI, "作品", "MANGA").single().remoteId)
            assertEquals(42L, api.details(PublicTrackingSite.BANGUMI, 42, "MANGA").remoteId)
            api.browse(PublicTrackingSite.BANGUMI, "MANGA", true, 1)
            api.supplement(PublicTrackingSite.BANGUMI, 42, "MANGA")
            val entry = TrackingAccountApi(client, emptyMap()).entry(
                TrackingSession(5, "private-token", userId = "1"), 42, "MANGA")
            assertEquals(8, entry?.progress)
            assertEquals(1, accountRequests)
            assertEquals(setOf("/v0/search/subjects", "/v0/subjects/42", "/v0/subjects/42/characters",
                "/v0/subjects/42/persons", "/v0/subjects/42/subjects"), publicPaths.toSet())
        } finally { client.close() }
    }

    @Test
    fun `site IDs retain Android persistence identity and capabilities exclude unsupported media`() {
        assertEquals((1..7).toList(), PublicTrackingSite.entries.map { it.id })
        assertFalse(PublicTrackingSite.SIMKL.supports("MANGA"))
        assertFalse(PublicTrackingSite.MANGAUPDATES.supports("VIDEO"))
        assertFalse(PublicTrackingSite.ANILIST.supports("UNKNOWN"))
    }

    @Test
    fun `MangaUpdates uses accepted ranking keys and preserves 64 bit series identities`() = runTest {
        val orders = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            orders += body.str("orderby").orEmpty()
            respond("""{"results":[{"record":{"series_id":9876543212345,"title":"作品","type":"Manhwa",
                "image":{"url":{"original":"https://image.invalid/full"}},"url":"https://www.mangaupdates.com/series/abc"}}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = PublicTrackingApi(client)
            val item = api.browse(PublicTrackingSite.MANGAUPDATES, "MANGA", true, 1).single()
            api.browse(PublicTrackingSite.MANGAUPDATES, "MANGA", false, 1)
            assertEquals(listOf("score", "rating"), orders)
            assertEquals(9876543212345, item.remoteId)
            assertEquals("https://image.invalid/full", item.coverUrl)
        } finally { client.close() }
    }

    @Test
    fun `SIMKL movie and television searches retain endpoint context through detail resolution`() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("test-client", request.url.parameters["client_id"])
            val json = """{"ids":{"simkl":42},"title":"作品","poster":"cover"}"""
            respond(if (request.url.encodedPath.startsWith("/search/")) "[$json]" else json,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = PublicTrackingApi(client, mapOf(7 to "test-client"))
            for (domain in listOf("tv", "movies")) {
                val found = api.videos("空 格 & ?", domain, 2).single()
                val details = api.details(PublicTrackingSite.SIMKL, found.remoteId, "VIDEO", found.remoteType!!)
                assertEquals(domain, details.remoteType)
                assertEquals("https://simkl.com/$domain/42", details.url)
            }
        } finally { client.close() }
    }

    @Test
    fun `catalog metadata cannot disguise a manga as a novel because a novel was requested`() = runTest {
        val responses = mapOf(
            PublicTrackingSite.SHIKIMORI to """{"id":42,"name":"漫画","kind":"manga"}""",
            PublicTrackingSite.MAL to """{"id":42,"title":"漫画","media_type":"manga"}""",
            PublicTrackingSite.KITSU to """{"data":{"id":"42","attributes":{"canonicalTitle":"漫画","subtype":"manga"}}}""",
            PublicTrackingSite.MANGAUPDATES to """{"series_id":42,"title":"漫画","type":"Manhwa"}""",
        )
        for ((site, response) in responses) {
            val client = HttpClient(MockEngine {
                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            })
            try {
                assertEquals("MANGA", PublicTrackingApi(client, mapOf(3 to "id")).details(site, 42, "NOVEL").mediaKind,
                    "$site must preserve the server's explicit manga classification")
            } finally { client.close() }
        }
    }
}
