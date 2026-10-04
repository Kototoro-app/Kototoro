package org.skepsun.kototoro.backups.ui.periodical

import io.ktor.client.engine.okhttp.OkHttpConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.core.prefs.AppSettings
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class WebDavNetworkModuleTest {
    private fun settings(server: MockWebServer): AppSettings = mockk<AppSettings>(relaxed = true).also {
        every { it.backupWebDavServerUrl } returns server.url("/dav/").toString()
        every { it.backupWebDavRemotePath } returns "/backups/"
        every { it.backupWebDavUsername } returns "fixture-ü"
        every { it.backupWebDavPassword } returns "fixture-password"
        every { it.backupWebDavDataVersion } returns 8
        every { it.periodicalBackupRemoteMaxCount } returns 0
    }

    private suspend fun withUploader(server: MockWebServer, test: suspend (WebDavBackupUploader) -> Unit) {
        val base = OkHttpClient.Builder().build()
        createWebDavHttpClient(base, 30).use { metadata ->
            createWebDavHttpClient(base, 120).use { transfer ->
                val protocol = WebDavBackupClient(metadata, transfer, ::parseWebDavResponse)
                test(WebDavBackupUploader(settings(server), protocol))
            }
        }
    }

    @Test
    fun `clients preserve base proxy cookies interceptors and read write settings`() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890))
        val cookieJar = mockk<CookieJar>()
        val base = OkHttpClient.Builder().proxy(proxy).cookieJar(cookieJar)
            .connectTimeout(7, TimeUnit.SECONDS).readTimeout(11, TimeUnit.SECONDS).writeTimeout(13, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .addInterceptor { it.proceed(it.request()) }.build()
        for (timeout in listOf(30L, 120L)) {
            createWebDavHttpClient(base, timeout).use { client ->
                val configured = (client.engine.config as OkHttpConfig).preconfigured!!
                assertSame(proxy, configured.proxy)
                assertSame(cookieJar, configured.cookieJar)
                assertEquals(base.interceptors, configured.interceptors)
                assertEquals(7000, configured.connectTimeoutMillis)
                assertEquals(11000, configured.readTimeoutMillis)
                assertEquals(13000, configured.writeTimeoutMillis)
                assertEquals(timeout * 1000, configured.callTimeoutMillis.toLong())
                assertTrue(
                    !configured.followRedirects && !configured.followSslRedirects &&
                        !configured.retryOnConnectionFailure,
                )
            }
        }
    }

    @Test
    fun `upload reopens file on retry with original bytes and basic authorization`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(201))
            val bytes = ByteArray(200_000) { it.toByte() }
            val file = File.createTempFile("webdav-upload-", ".zip").apply { deleteOnExit(); writeBytes(bytes) }
            withUploader(server) { uploader -> uploader.uploadBackup(file) }
            val first = server.takeRequest(5, TimeUnit.SECONDS)!!
            val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("PUT", first.method)
            assertEquals(first.path, second.path)
            assertTrue(first.path!!.matches(Regex("/dav/backups/kototoro-v3-work-v8-[0-9]{8}-[0-9]{6}\\.zip")))
            assertEquals(Credentials.basic("fixture-ü", "fixture-password"), first.getHeader("Authorization"))
            assertEquals("application/zip", first.getHeader("Content-Type"))
            assertEquals(bytes.size.toString(), first.getHeader("Content-Length"))
            assertArrayEquals(bytes, first.body.readByteArray())
            assertArrayEquals(bytes, second.body.readByteArray())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `download streams file while HTTP failure leaves destination unchanged`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = ByteArray(200_000) { it.toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setResponseCode(404))
            val file = File.createTempFile("webdav-download-", ".zip").apply { deleteOnExit() }
            withUploader(server) { uploader ->
                uploader.downloadBackup("a%20b.zip", file)
                assertArrayEquals(bytes, file.readBytes())
                assertEquals("WebDAV download failed: 404 Client Error",
                    runCatching { uploader.downloadBackup("missing.zip", file) }.exceptionOrNull()?.message)
                assertArrayEquals(bytes, file.readBytes())
            }
            assertEquals("/dav/backups/a%20b.zip", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        }
    }

    @Test
    fun `actual XML listing maps Date and latest prefers V3 with a single listing`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val xml = """<D:multistatus xmlns:D="DAV:">
                <D:response><D:href>/dav/backups/kototoro-v3-work-v8-old.zip</D:href><D:propstat><D:prop>
                  <D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified>
                  <D:getcontentlength>42</D:getcontentlength>
                </D:prop></D:propstat></D:response>
                <D:response><D:href>/dav/backups/kototoro-v2-data-v7-new.zip</D:href><D:propstat><D:prop>
                  <D:getlastmodified>Thu, 22 Oct 2015 07:28:00 GMT</D:getlastmodified>
                </D:prop></D:propstat></D:response>
            </D:multistatus>"""
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            withUploader(server) { uploader ->
                val selected = uploader.getLatestBackup()!!
                assertEquals(RemoteNamespace.V3, selected.namespace)
                assertEquals(3, selected.writerGeneration)
                assertEquals(8, selected.dataVersion)
                assertEquals(1445412480000L, selected.lastModified.time)
                assertEquals(42L, selected.size)
            }
            assertEquals(1, server.requestCount)
            assertEquals("1", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Depth"))
        }
    }

    @Test
    fun `cross origin WebDAV redirect strips basic credentials`() = runBlocking {
        MockWebServer().use { origin ->
            MockWebServer().use { target ->
                origin.start(); target.start()
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/probe")))
                target.enqueue(MockResponse().setResponseCode(207))
                withUploader(origin) { it.sendTestConnection() }
                assertNotNull(origin.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                assertNull(target.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            }
        }
    }

    @Test
    fun `body disconnect fails download without retry`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("x".repeat(100_000)).setBodyDelay(100, TimeUnit.MILLISECONDS)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val file = File.createTempFile("webdav-disconnect-", ".zip").apply { deleteOnExit() }
            withUploader(server) { uploader ->
                val error = runCatching { uploader.downloadBackup("a.zip", file) }.exceptionOrNull()
                assertTrue(error is java.io.IOException)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `active transfer cancellation releases network request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val file = File.createTempFile("webdav-cancel-", ".zip").apply { deleteOnExit() }
            withUploader(server) { uploader ->
                val job = launch(Dispatchers.Default) { uploader.downloadBackup("a.zip", file) }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                withTimeout(5000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
            assertEquals(1, server.requestCount)
        }
    }
}
