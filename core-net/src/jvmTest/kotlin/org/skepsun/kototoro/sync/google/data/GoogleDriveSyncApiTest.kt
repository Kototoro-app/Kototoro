package org.skepsun.kototoro.sync.google.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncApiException

class GoogleDriveSyncApiTest {

    private suspend fun withApi(
        handler: MockRequestHandler,
        test: suspend (GoogleDriveSyncApi, MockEngine) -> Unit,
    ) {
        val engine = MockEngine(handler)
        val client = HttpClient(engine) {
            expectSuccess = true // Each protocol request must opt out to retain the application exception contract.
            followRedirects = false
        }
        try {
            test(GoogleDriveSyncApi(client), engine)
        } finally {
            client.close()
            engine.close()
        }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(action: suspend () -> Unit): T {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue(error is T, "Expected ${T::class.simpleName}, got $error")
        return error as T
    }

    @Test
    fun `all sync generations retain query fields and bearer authorization`() = runTest {
        withApi({ request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("https://www.googleapis.com/drive/v3/files", request.url.toString().substringBefore('?'))
            assertEquals("appDataFolder", request.url.parameters["spaces"])
            assertEquals("files(id,name,createdTime,modifiedTime,version)", request.url.parameters["fields"])
            assertEquals("createdTime", request.url.parameters["orderBy"])
            assertEquals("100", request.url.parameters["pageSize"])
            assertEquals("Bearer fixture-token", request.headers[HttpHeaders.Authorization])
            respond("""{"files":[{"id":"f1","name":"葬送","unknown":true}],"unknown":42}""")
        }) { api, engine ->
            val current = api.findCurrentSyncFiles("fixture-token").single()
            assertEquals("f1", current.id)
            assertEquals("葬送", current.name)
            assertNull(current.createdTime)
            assertNull(current.modifiedTime)
            assertNull(current.version)
            api.findWorkV2SyncFiles("fixture-token")
            api.findLegacySyncFiles("fixture-token")
            assertEquals(
                listOf("kototoro_sync_content_v3.json", "kototoro_sync_work_v2.json", "kototoro_sync.json")
                    .map { "name = '$it' and trashed = false" },
                engine.requestHistory.map { it.url.parameters["q"] },
            )
        }
    }

    @Test
    fun `empty and missing file lists remain empty`() = runTest {
        for (body in listOf("", " \n ", "{}", "{\"files\":[]}")) {
            withApi({ respond(body) }) { api, _ ->
                assertEquals(emptyList<GoogleDriveSyncApi.DriveFile>(), api.findCurrentSyncFiles("token"))
            }
        }
    }

    @Test
    fun `remote version remains nullable and preserves string values`() = runTest {
        for ((body, expected) in listOf("{\"version\":\"123\"}" to "123", "{}" to null, "" to null)) {
            withApi({ request ->
                assertEquals("/drive/v3/files/file-id", request.url.encodedPath)
                assertEquals("version", request.url.parameters["fields"])
                respond(body)
            }) { api, _ -> assertEquals(expected, api.getFileVersion("token", "file-id")) }
        }
    }

    @Test
    fun `download preserves arbitrary bytes and requests media`() = runTest {
        val bytes = byteArrayOf(0, 1, 2, 127, -1, -128)
        withApi({ request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("media", request.url.parameters["alt"])
            respond(bytes)
        }) { api, _ -> assertArrayEquals(bytes, api.download("token", "file-id")) }
    }

    @Test
    fun `updating an existing file uses PATCH and retains JSON bytes`() = runTest {
        val bytes = "{\"title\":\"葬送\"}".encodeToByteArray()
        withApi({ request ->
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("/upload/drive/v3/files/existing", request.url.encodedPath)
            assertEquals("media", request.url.parameters["uploadType"])
            assertEquals("id", request.url.parameters["fields"])
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            val content = request.body as OutgoingContent.ByteArrayContent
            assertEquals("application/json; charset=UTF-8", content.contentType.toString())
            assertArrayEquals(bytes, content.bytes())
            respond("{\"id\":\"returned-id\"}")
        }) { api, engine ->
            assertEquals("returned-id", api.upload("token", bytes, "existing"))
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `new upload creates appDataFolder metadata before uploading content`() = runTest {
        var count = 0
        withApi({ request ->
            count++
            if (count == 1) {
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/drive/v3/files", request.url.encodedPath)
                assertEquals(
                    """{"name":"kototoro_sync_content_v3.json","parents":["appDataFolder"]}""",
                    (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                )
                respond("{\"id\":\"created-id\"}")
            } else {
                assertEquals(HttpMethod.Patch, request.method)
                assertEquals("/upload/drive/v3/files/created-id", request.url.encodedPath)
                respond("")
            }
        }) { api, _ ->
            assertEquals("created-id", api.upload("token", byteArrayOf(1), null))
            assertEquals(2, count)
        }
    }

    @Test
    fun `empty creation response fails without uploading`() = runTest {
        withApi({ respond(" ") }) { api, engine ->
            val error = expectFailure<GoogleDriveSyncApiException> { api.upload("token", byteArrayOf(1), null) }
            assertEquals(0, error.code)
            assertEquals("Failed to create Google Drive sync file", error.message)
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `delete accepts both success and missing file without consuming the response body`() = runTest {
        for (status in listOf(HttpStatusCode.NoContent, HttpStatusCode.NotFound)) {
            val body = ByteChannel()
            withApi({ request ->
                assertEquals(HttpMethod.Delete, request.method)
                assertEquals("/drive/v3/files/gone", request.url.encodedPath)
                respond(body, status)
            }) { api, engine ->
                api.delete("token", "gone")
                assertEquals(1, engine.requestHistory.size)
                assertTrue(body.isClosedForRead)
            }
        }
    }

    @Test
    fun `HTTP errors retain code and body and do not retry`() = runTest {
        val statuses = listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable)
        for (status in statuses) {
            withApi({ respond("failure-detail", status) }) { api, engine ->
                val error = expectFailure<GoogleDriveSyncApiException> { api.findCurrentSyncFiles("token") }
                assertEquals(status.value, error.code)
                assertEquals("Drive API error ${status.value}: failure-detail", error.message)
                assertEquals(1, engine.requestHistory.size)
            }
        }
    }

    @Test
    fun `blank error body falls back to reason phrase`() = runTest {
        withApi({ respond("  ", HttpStatusCode.Forbidden) }) { api, _ ->
            val error = expectFailure<GoogleDriveSyncApiException> { api.download("token", "file-id") }
            assertEquals("Drive API error 403: Forbidden", error.message)
        }
    }

    @Test
    fun `response body read failure is not treated as a send failure`() = runTest {
        var count = 0
        val body = ByteChannel().apply { cancel(IOException("broken body")) }
        withApi({ count++; respond(body) }) { api, _ ->
            val error = expectFailure<IOException> { api.download("token", "file-id") }
            assertEquals("broken body", error.message)
            assertEquals(1, count)
        }
    }

    @Test
    fun `unreadable error body retains the HTTP failure without retrying`() = runTest {
        val body = ByteChannel().apply { cancel(IOException("broken error body")) }
        withApi({ respond(body, HttpStatusCode.Forbidden) }) { api, engine ->
            val error = expectFailure<GoogleDriveSyncApiException> { api.download("token", "file-id") }
            assertEquals("Drive API error 403: Forbidden", error.message)
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `malformed JSON is not retried`() = runTest {
        withApi({ respond("not-json") }) { api, engine ->
            expectFailure<SerializationException> { api.findCurrentSyncFiles("token") }
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `network IOException before response retries once`() = runTest {
        var count = 0
        withApi({
            count++
            if (count == 1) throw IOException("temporary network failure")
            respond("{\"version\":\"42\"}")
        }) { api, _ ->
            assertEquals("42", api.getFileVersion("token", "file-id"))
            assertEquals(2, count)
        }
    }

    @Test
    fun `two network failures preserve operation context and final cause`() = runTest {
        var count = 0
        withApi({ count++; throw IOException("failure-$count") }) { api, _ ->
            val error = expectFailure<IOException> { api.getFileVersion("token", "file-id") }
            assertEquals("Failed to get Drive sync file version: failure-2", error.message)
            assertEquals("failure-2", error.cause?.message)
            assertEquals(2, count)
        }
    }

    @Test
    fun `cancellation before response propagates without retry`() = runTest {
        var count = 0
        withApi({ count++; throw CancellationException("cancelled") }) { api, _ ->
            expectFailure<CancellationException> { api.findCurrentSyncFiles("token") }
            assertEquals(1, count)
        }
    }

    @Test
    fun `cancellation while reading an error body is not swallowed`() = runTest {
        val body = ByteChannel().apply { cancel(CancellationException("cancelled error read")) }
        withApi({ respond(body, HttpStatusCode.Forbidden) }) { api, engine ->
            expectFailure<CancellationException> { api.findCurrentSyncFiles("token") }
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `injected client stays usable after a failed protocol call`() = runTest {
        var count = 0
        withApi({
            count++
            if (count == 1) respond("no", HttpStatusCode.Forbidden) else respond("{}")
        }) { api, _ ->
            expectFailure<GoogleDriveSyncApiException> { api.findCurrentSyncFiles("token") }
            assertEquals(emptyList<GoogleDriveSyncApi.DriveFile>(), api.findCurrentSyncFiles("token"))
            assertEquals(2, count)
        }
    }
}
