package org.skepsun.kototoro.desktop.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.backups.webdav.parseWebDavResources
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.nio.file.Files
import java.util.Base64

/**
 * The Windows WebDAV layer against a scripted server: the shared protocol runs here exactly as it does on Android, so
 * these tests check the parts Windows adds (credentials, streaming to and from a file, the remote name, retention).
 */
class DesktopWebDavTest {
    private val settings = DesktopWebDavSettings(
        serverUrl = "https://fixture.test/dav/",
        remotePath = "backups",
        username = "me",
        password = "s3cret",
        maxCount = 2,
    )

    /** A directory listing with the given files, newest first. */
    private fun listing(vararg files: String): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
        append("<D:response><D:href>/dav/backups/</D:href><D:propstat><D:prop>")
        append("<D:displayname>backups</D:displayname>")
        append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        for (file in files) {
            val (name, date, size) = file.split('|')
            append("<D:response><D:href>/dav/backups/$name</D:href><D:propstat><D:prop>")
            append("<D:displayname>$name</D:displayname>")
            append("<D:getlastmodified>$date</D:getlastmodified>")
            append("<D:getcontentlength>$size</D:getcontentlength>")
            append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        }
        append("</D:multistatus>")
    }

    private fun withClient(handler: MockRequestHandler, test: suspend (DesktopWebDavClient, MockEngine) -> Unit) {
        val engine = MockEngine(handler)
        val http = HttpClient(engine) { expectSuccess = true; followRedirects = false }
        try {
            // The app's own wiring: the shared client with the shared response reader.
            runBlocking { test(DesktopWebDavClient(WebDavBackupClient(http, http, ::parseWebDavResources)), engine) }
        } finally {
            http.close()
            engine.close()
        }
    }

    @Test
    fun `the endpoint joins the directory once and authenticates with basic credentials`() {
        withClient({ request ->
            assertEquals("https://fixture.test/dav/backups/a.zip", request.url.toString())
            val expected = "Basic " + Base64.getEncoder().encodeToString("me:s3cret".toByteArray())
            assertEquals(expected, request.headers[HttpHeaders.Authorization])
            respond("", HttpStatusCode.OK)
        }) { client, _ ->
            client.delete(settings, "a.zip")
        }
    }

    @Test
    fun `an empty user name sends no credentials, as on Android`() {
        withClient({ request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("", HttpStatusCode.NoContent)
        }) { client, _ ->
            client.delete(settings.copy(username = "", password = ""), "a.zip")
            client.delete(settings.copy(username = "", password = "orphan"), "a.zip")
        }
    }

    @Test
    fun `an upload streams the file under the shared remote name`() {
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val file = Files.createTempFile("webdav-upload-", ".zip")
        try {
            Files.write(file, bytes)
            withClient({ request ->
                assertEquals(HttpMethod.Put, request.method)
                assertTrue(
                    Regex("""/dav/backups/kototoro-v3-work-v1-\d{8}-\d{6}\.zip""").matches(request.url.encodedPath),
                    request.url.encodedPath,
                )
                assertArrayEquals(bytes, request.body.toByteArray())
                respond("", HttpStatusCode.Created)
            }) { client, _ ->
                client.upload(settings, file, client.remoteName(1, "20261021-101500"))
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a listing becomes restorable files and the newest one is the suggested restore`() {
        withClient({ request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            respond(
                listing(
                    "kototoro-v3-work-v1-20261022-090000.zip|Thu, 22 Oct 2026 09:00:00 GMT|2048",
                    "kototoro-v3-work-v1-20261021-090000.zip|Wed, 21 Oct 2026 09:00:00 GMT|1024",
                    "kototoro-v2-data-v12-20261020-235959.zip|Tue, 20 Oct 2026 23:59:59 GMT|512",
                ),
                HttpStatusCode.MultiStatus,
            )
        }) { client, _ ->
            val files = client.list(settings)
            assertEquals(3, files.size)
            assertEquals(2048L, files.first().size)
            assertEquals("kototoro-v3-work-v1-20261022-090000.zip", client.latest(files)?.name)
        }
    }

    @Test
    fun `retention deletes only what the shared rule retires`() {
        val deleted = mutableListOf<String>()
        withClient({ request ->
            when (request.method) {
                HttpMethod("PROPFIND") -> respond(
                    listing(
                        "kototoro-v3-work-v1-20261022-090000.zip|Thu, 22 Oct 2026 09:00:00 GMT|2",
                        "kototoro-v3-work-v1-20261021-090000.zip|Wed, 21 Oct 2026 09:00:00 GMT|2",
                        "kototoro-v3-work-v1-20261020-090000.zip|Tue, 20 Oct 2026 09:00:00 GMT|2",
                        "kototoro-v3-work-v1-20261019-090000.zip|Mon, 19 Oct 2026 09:00:00 GMT|2",
                        // Another writer generation is not this client's to delete.
                        "kototoro-v2-data-v12-20261018-090000.zip|Sun, 18 Oct 2026 09:00:00 GMT|2",
                    ),
                    HttpStatusCode.MultiStatus,
                )
                HttpMethod.Delete -> {
                    deleted += request.url.encodedPath.substringAfterLast('/')
                    respond("", HttpStatusCode.NoContent)
                }
                else -> error("unexpected ${request.method}")
            }
        }) { client, _ ->
            // `maxCount = 2`: the two newest V3 files stay, the two older ones are retired.
            assertEquals(
                listOf("kototoro-v3-work-v1-20261020-090000.zip", "kototoro-v3-work-v1-20261019-090000.zip"),
                client.trim(settings),
            )
        }
        assertEquals(
            listOf("kototoro-v3-work-v1-20261020-090000.zip", "kototoro-v3-work-v1-20261019-090000.zip"),
            deleted,
        )
    }

    @Test
    fun `retention is off when no count is kept`() {
        withClient({ error("no request expected when retention is off") }) { client, engine ->
            assertEquals(emptyList<String>(), client.trim(settings.copy(maxCount = 0)))
            assertEquals(0, engine.requestHistory.size)
        }
    }

    @Test
    fun `a download writes the bytes to the destination file`() {
        val bytes = ByteArray(9_000) { (it % 127).toByte() }
        val destination = Files.createTempFile("webdav-download-", ".zip")
        try {
            withClient({ request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/dav/backups/kototoro-v3-work-v1-20261022-090000.zip", request.url.encodedPath)
                respond(bytes)
            }) { client, _ ->
                client.download(settings, "kototoro-v3-work-v1-20261022-090000.zip", destination)
            }
            assertArrayEquals(bytes, Files.readAllBytes(destination))
        } finally {
            Files.deleteIfExists(destination)
        }
    }

    @Test
    fun `a refused download leaves no partial file behind`() {
        val destination = Files.createTempFile("webdav-download-", ".zip")
        try {
            withClient({ respond("forbidden", HttpStatusCode.Forbidden) }) { client, _ ->
                val error = runCatching { client.download(settings, "a.zip", destination) }.exceptionOrNull()
                assertEquals("WebDAV download failed: 403 Forbidden", error?.message)
            }
            assertFalse(Files.exists(destination))
        } finally {
            Files.deleteIfExists(destination)
        }
    }

    @Test
    fun `the settings survive a preference round trip and a fresh copy starts unconfigured`() {
        val saved = settings.toPreferences()
        assertEquals(settings, DesktopWebDavSettings.from(saved.mapValues { it.value!! }))
        assertEquals(DesktopWebDavSettings(), DesktopWebDavSettings.from(emptyMap()))
        assertFalse(DesktopWebDavSettings.from(emptyMap()).configured)
        assertTrue(settings.configured)
    }

    @Test
    fun `a stored count of the wrong type falls back to the default instead of failing`() {
        val older = mapOf(
            DesktopWebDavSettings.KEY_SERVER to SourcePreferenceValue.Text("https://fixture.test"),
            DesktopWebDavSettings.KEY_MAX_COUNT to SourcePreferenceValue.Text("3"),
        )
        val restored = DesktopWebDavSettings.from(older)
        assertEquals("https://fixture.test", restored.serverUrl)
        assertEquals(3, restored.maxCount)
    }
}
