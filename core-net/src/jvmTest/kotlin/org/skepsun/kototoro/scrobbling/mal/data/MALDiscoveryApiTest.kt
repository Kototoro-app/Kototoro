package org.skepsun.kototoro.scrobbling.mal.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MALDiscoveryApiTest {
    private suspend fun withApi(handler: MockRequestHandler, test: suspend (MALDiscoveryApi, MockEngine) -> Unit) {
        val engine = MockEngine(handler)
        val client = HttpClient(engine) { expectSuccess = true; followRedirects = false }
        try {
            test(MALDiscoveryApi(client, "fixture-client"), engine)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test
    fun `search keeps anime and manga fields query offset and public client id`() = runTest {
        withApi({ request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("api.myanimelist.net", request.url.host)
            assertEquals("fixture-client", request.headers["X-MAL-CLIENT-ID"])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("true", request.url.parameters["nsfw"])
            assertEquals("41", request.url.parameters["offset"])
            assertEquals("葬送 + & / ? # %", request.url.parameters["q"])
            assertNull(request.url.parameters["limit"])
            respond("""{"data":[]}""")
        }) { api, engine ->
            for (media in MALMediaType.entries) api.search("葬送 + & / ? # %", 41, media)
            assertEquals(listOf("/v2/anime", "/v2/manga"), engine.requestHistory.map { it.url.encodedPath })
            assertEquals(listOf("alternative_titles,mean,num_episodes,status,start_season",
                "alternative_titles,mean,num_chapters,status,start_date"),
                engine.requestHistory.map { it.url.parameters["fields"] })
        }
    }

    @Test
    fun `search keeps 64 UTF16 unit limit without trimming query`() = runTest {
        withApi({ respond("{}") }) { api, engine ->
            api.search(" " + "x".repeat(80), -3, MALMediaType.MANGA)
            assertEquals(" " + "x".repeat(63), engine.requestHistory.single().url.parameters["q"])
            assertEquals("-3", engine.requestHistory.single().url.parameters["offset"])
        }
    }

    @Test
    fun `supplementary characters count as two UTF16 units`() = runTest {
        withApi({ respond("{}") }) { api, engine ->
            api.search("🦊".repeat(40), 0, MALMediaType.ANIME)
            assertEquals("🦊".repeat(32), engine.requestHistory.single().url.parameters["q"])
        }
    }

    @Test
    fun `empty query remains present`() = runTest {
        withApi({ respond("{}") }) { api, engine ->
            api.search("", 0, MALMediaType.ANIME)
            assertEquals("", engine.requestHistory.single().url.parameters["q"])
        }
    }

    @Test
    fun `ranking defaults preserve both media paths`() = runTest {
        withApi({ respond("{}") }) { api, engine ->
            for (media in MALMediaType.entries) api.ranking(media)
            assertEquals(listOf("/v2/anime/ranking", "/v2/manga/ranking"),
                engine.requestHistory.map { it.url.encodedPath })
            for (request in engine.requestHistory) {
                assertEquals("all", request.url.parameters["ranking_type"])
                assertEquals("20", request.url.parameters["limit"])
                assertEquals("0", request.url.parameters["offset"])
                assertEquals("true", request.url.parameters["nsfw"])
                assertEquals("fixture-client", request.headers["X-MAL-CLIENT-ID"])
                assertNull(request.url.parameters["q"])
            }
        }
    }

    @Test
    fun `ranking preserves custom values without adding validation or clamping`() = runTest {
        withApi({ respond("{}") }) { api, engine ->
            api.ranking(MALMediaType.MANGA, "by popularity&favorite", -1, -8)
            val url = engine.requestHistory.single().url
            assertEquals("by popularity&favorite", url.parameters["ranking_type"])
            assertEquals("-1", url.parameters["limit"])
            assertEquals("-8", url.parameters["offset"])
        }
    }

    @Test
    fun `seasonal defaults and custom values preserve single encoded season segment`() = runTest {
        withApi({ respond("{}") }) { api, engine ->
            api.seasonalAnime(2026, "fall")
            api.seasonalAnime(2025, "spring/summer", "anime_score", 9, 18)
            val first = engine.requestHistory[0].url
            assertEquals("/v2/anime/season/2026/fall", first.encodedPath)
            assertEquals("anime_num_list_users", first.parameters["sort"])
            assertEquals("20", first.parameters["limit"])
            assertEquals("0", first.parameters["offset"])
            val second = engine.requestHistory[1].url
            assertEquals("/v2/anime/season/2025/spring%2Fsummer", second.encodedPath)
            assertEquals("anime_score", second.parameters["sort"])
            assertEquals("9", second.parameters["limit"])
            assertEquals("18", second.parameters["offset"])
        }
    }

    @Test
    fun `remaining HTTP statuses return raw text for platform parsing`() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden,
            HttpStatusCode.NotFound, HttpStatusCode.InternalServerError)) {
            withApi({ respond("""{"error":"fixture"}""", status) }) { api, engine ->
                assertEquals("""{"error":"fixture"}""", api.ranking(MALMediaType.ANIME))
                assertEquals(1, engine.requestHistory.size)
            }
        }
    }

    @Test
    fun `empty and malformed JSON text are returned for existing platform semantics`() = runTest {
        for (text in listOf("", "not JSON", "[]")) {
            withApi({ respond(text) }) { api, _ -> assertEquals(text, api.ranking(MALMediaType.ANIME)) }
        }
    }

    @Test
    fun `transport failure propagates without retry`() = runTest {
        var attempts = 0
        withApi({ attempts++; throw IOException("offline") }) { api, _ ->
            val error = runCatching { api.ranking(MALMediaType.ANIME) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `body read failure releases response without retry`() = runTest {
        val body = ByteChannel().apply { cancel(IOException("broken body")) }
        withApi({ respond(body) }) { api, engine ->
            assertTrue(runCatching { api.ranking(MALMediaType.ANIME) }.exceptionOrNull() is IOException)
            assertTrue(body.isClosedForRead)
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `cancellation propagates without retry`() = runTest {
        var attempts = 0
        withApi({ attempts++; throw CancellationException("cancel") }) { api, _ ->
            assertTrue(runCatching { api.search("title", 0, MALMediaType.MANGA) }
                .exceptionOrNull() is CancellationException)
            assertEquals(1, attempts)
        }
    }
}
