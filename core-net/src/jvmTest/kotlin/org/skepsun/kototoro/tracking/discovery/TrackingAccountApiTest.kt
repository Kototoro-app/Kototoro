package org.skepsun.kototoro.tracking.discovery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrackingAccountApiTest {
    private val configs = (1..7).associateWith { TrackingClientConfig("client-$it", "secret-$it") }

    @Test
    fun `manual tracking creates entries in every service and an absent rating remains unrated`() = runTest {
        for (service in 1..7) {
            val calls = mutableListOf<String>()
            val client = HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                calls += path
                assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
                when (service) {
                    1 -> {
                        val rate = json(request.body.toByteArray().decodeToString()).obj("user_rate")
                        assertEquals("watching", rate.str("status"))
                        assertEquals("Anime", rate.str("target_type"))
                        assertEquals(12, rate.integer("episodes"))
                        respondJson("""{"id":80,"status":"watching","episodes":12,"score":0}""")
                    }
                    2 -> respondJson("""{"data":{"SaveMediaListEntry":{"id":80,"status":"CURRENT","progress":12,"score":0}}}""")
                    3 -> {
                        val form = parseQueryString(request.body.toByteArray().decodeToString())
                        assertEquals("watching", form["status"])
                        assertEquals("12", form["num_watched_episodes"])
                        respondJson("{}")
                    }
                    4 -> {
                        val data = json(request.body.toByteArray().decodeToString()).obj("data")
                        assertEquals("42", data.obj("relationships").obj("anime").obj("data").str("id"))
                        assertEquals("1", data.obj("relationships").obj("user").obj("data").str("id"))
                        assertEquals(JsonNull, data.obj("attributes")["ratingTwenty"])
                        respondJson("""{"data":{"id":"80","attributes":{"status":"current","progress":12}}}""")
                    }
                    5 -> {
                        val body = json(request.body.toByteArray().decodeToString())
                        assertEquals(3, body.integer("type")); assertEquals(12, body.integer("ep_status"))
                        respondJson("{}")
                    }
                    6 -> if (path.endsWith("/rating")) respondJson("{}", HttpStatusCode.NotFound) else respondJson("{}")
                    else -> if (path.endsWith("/ratings/remove")) respondJson("""{"not_found":{"shows":[{"ids":{"simkl":42}}]}}""")
                        else respondJson("{}")
                }
            })
            try {
                val value = TrackingAccountApi(client, configs).update(TrackingSession(service, "token", userId = "1"),
                    if (service == 6) "MANGA" else "VIDEO", TrackingEntry(42, status = "READING", progress = 12), false)
                assertEquals("READING", value.status)
                assertEquals(12, value.progress)
                assertEquals(0f, value.score)
                assertTrue(calls.isNotEmpty())
            } finally { client.close() }
        }
    }

    @Test
    fun `refresh rotates tokens without losing the verified account identity`() = runTest {
        val client = HttpClient(MockEngine { request ->
            val fields = parseQueryString(request.body.toByteArray().decodeToString())
            assertEquals("refresh_token", fields["grant_type"])
            assertEquals("previous-refresh", fields["refresh_token"])
            respondJson("""{"access_token":"new-token","refresh_token":"rotated-refresh"}""")
        })
        try {
            val previous = TrackingSession(2, "old-token", "previous-refresh", "42", "Reader")
            val result = TrackingAccountApi(client, configs).refresh(previous)
            assertEquals(previous.copy(accessToken = "new-token", refreshToken = "rotated-refresh"), result)
        } finally { client.close() }
    }

    @Test
    fun `SIMKL logical failure is not acknowledged as a successful progress write`() = runTest {
        val client = HttpClient(MockEngine {
            respondJson("""{"not_found":{"shows":[{"ids":{"simkl":42}}]}}""")
        })
        try {
            org.junit.jupiter.api.assertThrows<IllegalStateException> {
                kotlinx.coroutines.runBlocking {
                    TrackingAccountApi(client, configs).advance(TrackingSession(7, "token", userId = "1"),
                        "VIDEO", TrackingEntry(42, progress = 1), 2)
                }
            }
        } finally { client.close() }
    }

    @Test
    fun `OAuth binds state redirect and MAL verifier to the registered client`() {
        val client = HttpClient(MockEngine { error("Authorization URL must not make a request") })
        try {
            val api = TrackingAccountApi(client, configs)
            for ((service, host) in mapOf(1 to "shikimori-auth", 2 to "anilist-auth", 3 to "mal-auth", 5 to "bangumi-auth", 7 to "simkl-auth")) {
                val url = Url(api.authorizationUrl(service, "random-state", "x".repeat(64)))
                assertEquals("random-state", url.parameters["state"])
                assertEquals("kototoro://$host", url.parameters["redirect_uri"])
                assertEquals("client-$service", url.parameters["client_id"])
                assertNull(url.parameters["client_secret"])
                if (service == 3) assertEquals("x".repeat(64), url.parameters["code_challenge"])
            }
        } finally { client.close() }
    }

    @Test
    fun `password login verifies identity and never retains the password`() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/token")) {
                val form = parseQueryString(request.body.toByteArray().decodeToString())
                assertEquals("correct;with&symbols", form["password"])
                respondJson("""{"access_token":"test-token","refresh_token":"refresh"}""")
            } else {
                assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
                respondJson("""{"data":[{"id":"42","attributes":{"name":"Reader"}}]}""")
            }
        })
        try {
            val session = TrackingAccountApi(client, configs).login(4, "me@example.invalid", "correct;with&symbols")
            assertEquals("42", session.userId)
            assertEquals("Reader", session.username)
            assertFalse(Json.encodeToString(session).contains("correct"))
        } finally { client.close() }
    }

    @Test
    fun `automatic advance sends only progress for each service and preserves the returned record`() = runTest {
        for (service in 1..7) {
            var calls = 0
            val client = HttpClient(MockEngine { request ->
                calls++
                assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
                val text = request.body.toByteArray().decodeToString()
                assertFalse(text.contains("secret note"))
                assertFalse(text.contains("rating"))
                assertFalse(text.contains("score"))
                when (service) {
                    1 -> assertEquals("""{"user_rate":{"episodes":12}}""", text)
                    2 -> {
                        val query = json(text).str("query")!!
                        assertTrue(query.contains("progress: 12")); assertFalse(query.contains("status:"))
                    }
                    3 -> assertEquals("num_watched_episodes=12", text)
                    4 -> assertEquals(setOf("progress"), json(text).obj("data").obj("attributes").keys)
                    5 -> assertEquals("""{"ep_status":12}""", text)
                    6 -> assertEquals("POST", request.method.value)
                    7 -> {
                        val show = json(text).arr("shows").single().jsonObject
                        assertEquals(listOf(11, 12), show.arr("episodes").map { it.jsonObject.integer("number") })
                        assertNull(show["to"]); assertNull(show["status"])
                    }
                }
                if (service == 2) respondJson("""{"data":{"SaveMediaListEntry":{"id":80}}}""") else respondJson("{}")
            })
            try {
                val before = TrackingEntry(42, 80, "COMPLETED", 10, 9f, "secret note")
                val after = TrackingAccountApi(client, configs).advance(TrackingSession(service, "token", userId = "1"),
                    if (service == 6) "MANGA" else "VIDEO", before, 12)
                assertEquals(before.copy(progress = 12), after)
                assertEquals(1, calls)
            } finally { client.close() }
        }
    }

    @Test
    fun `manual AniList update uses raw score independently of account score format and escapes notes`() = runTest {
        val client = HttpClient(MockEngine { request ->
            val query = json((request.body as TextContent).text).str("query")!!
            assertTrue(query.contains("scoreRaw: 80"))
            assertTrue(query.contains(JsonPrimitive("line\n\"quoted\"").toString()))
            respondJson("""{"data":{"SaveMediaListEntry":{"id":80,"status":"CURRENT","progress":12,"score":8,"notes":"ok"}}}""")
        })
        try {
            val result = TrackingAccountApi(client, configs).update(TrackingSession(2, "token", userId = "1"), "MANGA",
                TrackingEntry(42, status = "READING", progress = 12, score = 8f, note = "line\n\"quoted\""), true)
            assertEquals(8f, result.score)
            assertEquals("READING", result.status)
        } finally { client.close() }
    }

    @Test
    fun `missing Bangumi collection is distinct from unauthorized access`() = runTest {
        val client = HttpClient(MockEngine { request ->
            respondJson("{}", if (request.url.encodedPath.endsWith("/42")) HttpStatusCode.NotFound else HttpStatusCode.Unauthorized)
        })
        try {
            val api = TrackingAccountApi(client, configs)
            assertNull(api.entry(TrackingSession(5, "token", userId = "1"), 42, "MANGA"))
            org.junit.jupiter.api.assertThrows<io.ktor.client.plugins.ClientRequestException> {
                kotlinx.coroutines.runBlocking { api.entry(TrackingSession(5, "token", userId = "1"), 43, "MANGA") }
            }
        } finally { client.close() }
    }

    @Test
    fun `MangaUpdates reads array lists and preserves an existing user rating`() = runTest {
        val client = HttpClient(MockEngine { request ->
            respondJson(when {
                request.url.encodedPath.endsWith("/lists") -> """[{"list_id":0,"type":"read"}]"""
                request.url.encodedPath.endsWith("/search") -> """{"results":[{"record":{"series":{"id":42},"status":{"chapter":12}}}]}"""
                else -> """{"rating":9}"""
            })
        })
        try {
            val entry = TrackingAccountApi(client, configs).entry(TrackingSession(6, "token", userId = "1"), 42, "MANGA")!!
            assertEquals("READING", entry.status); assertEquals(9f, entry.score); assertEquals(12, entry.progress)
        } finally { client.close() }
    }

    @Test
    fun `unsupported media and invalid progress fail before any write`() = runTest {
        val client = HttpClient(MockEngine { error("Must not send a write") })
        try {
            val api = TrackingAccountApi(client, configs)
            for (entry in listOf(TrackingEntry(0), TrackingEntry(42, progress = -1), TrackingEntry(42, score = Float.NaN))) {
                org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                    kotlinx.coroutines.runBlocking { api.update(TrackingSession(2, "token"), "MANGA", entry, true) }
                }
            }
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                kotlinx.coroutines.runBlocking { api.update(TrackingSession(7, "token"), "MANGA", TrackingEntry(42), false) }
            }
        } finally { client.close() }
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun MockRequestHandleScope.respondJson(value: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(value, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
}
