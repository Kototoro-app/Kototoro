package org.skepsun.kototoro.backups.webdav

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebDavBackupClientTest {
    private val endpoint = WebDavEndpoint("https://fixture.test/", "/backups/") { "Basic fixture" }

    private suspend fun withClient(
        handler: MockRequestHandler,
        parser: (String) -> List<WebDavResource> = { emptyList() },
        test: suspend (WebDavBackupClient, MockEngine) -> Unit,
    ) {
        val engine = MockEngine(handler)
        val http = HttpClient(engine) { expectSuccess = true; followRedirects = false }
        try {
            test(WebDavBackupClient(http, http, parser), engine)
        } finally {
            http.close()
            engine.close()
        }
    }

    @Test
    fun `probe sends authenticated depth zero XML and ignores response body`() = runTest {
        withClient({ request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            assertEquals("https://fixture.test/backups/", request.url.toString())
            assertEquals("0", request.headers["Depth"])
            assertEquals("Basic fixture", request.headers[HttpHeaders.Authorization])
            assertEquals("application/xml; charset=utf-8", request.body.contentType.toString())
            val xml = request.body.toByteArray().decodeToString()
            assertTrue(xml.contains("<D:displayname/>"))
            assertTrue(!xml.contains("getlastmodified"))
            respond("not XML", HttpStatusCode.MultiStatus)
        }, parser = { error("Probe must not parse XML") }) { client, _ -> client.probe(endpoint) }
    }

    @Test
    fun `listing sends depth one and classifies parsed resources`() = runTest {
        withClient({ request ->
            assertEquals("1", request.headers["Depth"])
            assertTrue(request.body.toByteArray().decodeToString().contains("<D:getcontentlength/>"))
            respond("fixture XML", HttpStatusCode.MultiStatus)
        }, parser = { xml ->
            assertEquals("fixture XML", xml)
            listOf(WebDavResource("plain.zip", 0, 2), WebDavResource("kototoro-v3-work-v8-a.zip", 1, 3))
        }) { client, _ ->
            assertEquals(listOf(8, null), client.list(endpoint).map { it.dataVersion })
        }
    }

    @Test
    fun `missing listing is empty without parsing`() = runTest {
        withClient(
            { respond("missing", HttpStatusCode.NotFound) },
            parser = { error("Not found must not parse") },
        ) { client, _ ->
            assertEquals(emptyList<WebDavBackupFile>(), client.list(endpoint))
        }
    }

    @Test
    fun `metadata errors preserve operation status and do not retry`() = runTest {
        for ((operation, name) in listOf<suspend (WebDavBackupClient) -> Unit>(
            { it.probe(endpoint) }, { it.list(endpoint) }, { it.delete(endpoint, "a.zip") },
        ).zip(listOf("connection", "PROPFIND", "delete"))) {
            withClient({ respond("error", HttpStatusCode.Forbidden) }) { client, engine ->
                val error = runCatching { operation(client) }.exceptionOrNull()
                assertEquals("WebDAV $name failed: 403 Forbidden", error?.message)
                assertEquals(1, engine.requestHistory.size)
            }
        }
    }

    @Test
    fun `parser failure is not retried`() = runTest {
        withClient(
            { respond("bad XML", HttpStatusCode.MultiStatus) },
            parser = { throw IllegalArgumentException("XML") },
        ) { client, engine ->
            assertTrue(runCatching { client.list(endpoint) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `delete accepts success and not found`() = runTest {
        for (status in listOf(HttpStatusCode.NoContent, HttpStatusCode.NotFound)) {
            withClient({ request ->
                assertEquals(HttpMethod.Delete, request.method)
                assertEquals("/backups/a.zip", request.url.encodedPath)
                respond("ignored", status)
            }) { client, _ -> client.delete(endpoint, "a.zip") }
        }
    }

    @Test
    fun `upload replays writer and refreshes credentials after HTTP failure`() = runTest {
        val bytes = byteArrayOf(0, -1, 127, -128)
        var attempts = 0
        var writes = 0
        val config = WebDavEndpoint("https://fixture.test", "") { "Basic attempt-$attempts" }
        withClient({ request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("application/zip", request.body.contentType.toString())
            assertEquals(bytes.size.toLong(), request.body.contentLength)
            assertEquals("Basic attempt-$attempts", request.headers[HttpHeaders.Authorization])
            assertArrayEquals(bytes, request.body.toByteArray())
            attempts++
            respond("server busy", if (attempts < 3) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Created)
        }) { client, _ ->
            client.upload(config, "a.zip", bytes.size.toLong()) { writes++; it.writeFully(bytes) }
            assertEquals(3, writes)
            assertEquals(3000L, testScheduler.currentTime)
        }
    }

    @Test
    fun `upload stops after three failures with bounded trimmed preview`() = runTest {
        withClient({ respond("  " + "x".repeat(1100) + "  ", HttpStatusCode.InternalServerError) }) { client, engine ->
            val error = runCatching { client.upload(endpoint, "a.zip", 0) {} }.exceptionOrNull()
            assertEquals(
                "WebDAV upload failed: 500 Internal Server Error. Response: " + "x".repeat(1024) + "...",
                error?.message,
            )
            assertEquals(3, engine.requestHistory.size)
            assertEquals(3000L, testScheduler.currentTime)
        }
    }

    @Test
    fun `upload retries transport failure and keeps original exception`() = runTest {
        var attempts = 0
        val failure = IOException("offline")
        withClient({ attempts++; throw failure }) { client, _ ->
            val error = runCatching { client.upload(endpoint, "a.zip", 0) {} }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals("offline", error?.message)
            assertEquals(3, attempts)
        }
    }

    @Test
    fun `upload cancellation does not retry or delay`() = runTest {
        var attempts = 0
        withClient({ attempts++; throw CancellationException("cancel") }) { client, _ ->
            val error = runCatching { client.upload(endpoint, "a.zip", 0) {} }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertEquals(1, attempts)
            assertEquals(0L, testScheduler.currentTime)
        }
    }

    @Test
    fun `download delivers binary channel`() = runTest {
        val bytes = byteArrayOf(0, -1, -128, 127)
        withClient({ request ->
            assertEquals(HttpMethod.Get, request.method)
            respond(bytes)
        }) { client, _ ->
            client.download(endpoint, "a.zip") { assertArrayEquals(bytes, it.toByteArray()) }
        }
    }

    @Test
    fun `download validates status before opening destination`() = runTest {
        withClient({ respond("missing", HttpStatusCode.NotFound) }) { client, engine ->
            val error = runCatching {
                client.download(endpoint, "a.zip") { error("Must not open output") }
            }.exceptionOrNull()
            assertEquals("WebDAV download failed: 404 Not Found", error?.message)
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `download writer failure releases unread response without retry`() = runTest {
        val body = ByteChannel()
        withClient({ respond(body) }) { client, engine ->
            val failure = IOException("disk full")
            val error = runCatching { client.download(endpoint, "a.zip") { throw failure } }.exceptionOrNull()
            assertTrue(error === failure)
            assertTrue(body.isClosedForRead)
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `probe releases unread streaming response`() = runTest {
        val body = ByteChannel()
        withClient({ respond(body, HttpStatusCode.MultiStatus) }) { client, _ ->
            client.probe(endpoint)
            assertTrue(body.isClosedForRead)
        }
    }

    @Test
    fun `cancelled download callback releases response`() = runTest {
        val body = ByteChannel()
        withClient({ respond(body) }) { client, _ ->
            val failure = runCatching {
                client.download(endpoint, "a.zip") { throw CancellationException("cancel") }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(body.isClosedForRead)
        }
    }
}
