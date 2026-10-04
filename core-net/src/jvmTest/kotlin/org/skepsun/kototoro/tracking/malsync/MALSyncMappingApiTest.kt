package org.skepsun.kototoro.tracking.malsync

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

class MALSyncMappingApiTest {
    private suspend fun withApi(
        handler: MockRequestHandler,
        parser: (String) -> List<MALSyncEntry> = { emptyList() },
        test: suspend (MALSyncMappingApi, MockEngine) -> Unit,
    ) {
        val engine = MockEngine(handler)
        val client = HttpClient(engine) { expectSuccess = true; followRedirects = false }
        try {
            test(MALSyncMappingApi(client, parser), engine)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test
    fun `all supported service and media paths keep unauthenticated GET contract`() = runTest {
        withApi({ request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("api.malsync.moe", request.url.host)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("", request.url.encodedQuery)
            respond("{}")
        }) { api, engine ->
            val sources = MALSyncService.entries.filter { it.apiPath != null }
            for (source in sources) for (kind in MALSyncKind.entries) api.resolve(source, 42, kind)
            assertEquals(
                sources.flatMap { source -> MALSyncKind.entries.map { "/${source.apiPath}/${it.slug}/42" } },
                engine.requestHistory.map { it.url.encodedPath },
            )
        }
    }

    @Test
    fun `unsupported sources do not issue a request`() = runTest {
        withApi({ error("Unsupported source must not request") }) { api, engine ->
            for (source in listOf(MALSyncService.BANGUMI, MALSyncService.MANGAUPDATES)) {
                assertEquals(emptyList<MALSyncMapping>(), api.resolve(source, 1, MALSyncKind.ANIME))
            }
            assertEquals(0, engine.requestHistory.size)
        }
    }

    @Test
    fun `successful response is projected and mapped through shared rules`() = runTest {
        withApi({ respond("fixture JSON") }, parser = {
            assertEquals("fixture JSON", it)
            listOf(MALSyncEntry("MAL", "1", null, null, null),
                MALSyncEntry("Anilist", "2", "8", "Title", "https://fixture.test"))
        }) { api, _ ->
            assertEquals(listOf(MALSyncMapping(MALSyncService.ANILIST, 8, "Title", "https://fixture.test")),
                api.resolve(MALSyncService.MAL, 1, MALSyncKind.MANGA))
        }
    }

    @Test
    fun `HTTP errors return empty and release unread response without parsing or retry`() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
            val body = ByteChannel()
            withApi({ respond(body, status) }, parser = { error("Error must not parse") }) { api, engine ->
                assertEquals(emptyList<MALSyncMapping>(), api.resolve(MALSyncService.MAL, 1, MALSyncKind.MANGA))
                assertTrue(body.isClosedForRead)
                assertEquals(1, engine.requestHistory.size)
            }
        }
    }

    @Test
    fun `successful non 200 statuses retain original success handling`() = runTest {
        withApi({ respond("fixture", HttpStatusCode.Accepted) }, parser = { emptyList() }) { api, engine ->
            assertEquals(emptyList<MALSyncMapping>(), api.resolve(MALSyncService.KITSU, -1, MALSyncKind.ANIME))
            assertEquals("/kitsu/anime/-1", engine.requestHistory.single().url.encodedPath)
        }
    }

    @Test
    fun `network failure propagates without a protocol retry`() = runTest {
        var attempts = 0
        withApi({ attempts++; throw IOException("offline") }) { api, _ ->
            val error = runCatching { api.resolve(MALSyncService.MAL, 1, MALSyncKind.MANGA) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals("offline", error?.message)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `parser failure propagates without retry`() = runTest {
        withApi({ respond("bad JSON") }, parser = { throw IllegalArgumentException("JSON") }) { api, engine ->
            val error = runCatching { api.resolve(MALSyncService.MAL, 1, MALSyncKind.MANGA) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `body read failure propagates without retry`() = runTest {
        val body = ByteChannel().apply { cancel(IOException("broken body")) }
        withApi({ respond(body) }) { api, engine ->
            val error = runCatching { api.resolve(MALSyncService.MAL, 1, MALSyncKind.MANGA) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `cancellation propagates without retry`() = runTest {
        var attempts = 0
        withApi({ attempts++; throw CancellationException("cancel") }) { api, _ ->
            val error = runCatching { api.resolve(MALSyncService.MAL, 1, MALSyncKind.MANGA) }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertEquals(1, attempts)
        }
    }
}
