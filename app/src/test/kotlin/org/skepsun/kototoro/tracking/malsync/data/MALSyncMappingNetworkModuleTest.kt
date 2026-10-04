package org.skepsun.kototoro.tracking.malsync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONException
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.network.createKtorOkHttpClient
import org.skepsun.kototoro.tracking.malsync.MALSyncEntry
import org.skepsun.kototoro.tracking.malsync.MALSyncKind
import org.skepsun.kototoro.tracking.malsync.MALSyncMapping
import org.skepsun.kototoro.tracking.malsync.MALSyncService
import java.util.concurrent.TimeUnit

class MALSyncMappingNetworkModuleTest {
    private fun localClient(server: MockWebServer): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()

    @Test
    fun `JSON projection skips missing and malformed site and entry structures`() {
        for (json in listOf("{}", """{"Sites":null}""", """{"Sites":[]}""")) {
            assertEquals(emptyList<MALSyncEntry>(), parseMALSyncEntries(json))
        }
        val result = parseMALSyncEntries("""{"Sites":{
            "Anilist":{"1":null,"2":[],"3":{"identifier":"9","title":"Title","url":"/path"}},
            "Kitsu":null,"Bangumi":[]
        }}""")
        assertEquals(listOf(MALSyncEntry("Anilist", "3", "9", "Title", "/path")), result)
    }

    @Test
    fun `JSON projection preserves platform string coercion for scalar metadata`() {
        assertEquals(listOf(MALSyncEntry("Anilist", "1", "8", "12", "false")),
            parseMALSyncEntries("""{"Sites":{"Anilist":{"1":{"identifier":8,"title":12,"url":false}}}}"""))
    }

    @Test
    fun `null and object coercion remain delegated to platform JSONObject`() {
        val text = """{"Sites":{"Anilist":{"1":{"identifier":null,"title":{"name":"nested"},"url":null}}}}"""
        val original = JSONObject(text).getJSONObject("Sites").getJSONObject("Anilist").getJSONObject("1")
        val projected = parseMALSyncEntries(text).single()
        assertEquals(original.optString("identifier"), projected.identifier)
        assertEquals(original.optString("title"), projected.title)
        assertEquals(original.optString("url"), projected.url)
    }

    @Test
    fun `malformed JSON is still reported to repository fallback`() {
        assertThrows(JSONException::class.java) { parseMALSyncEntries("bad JSON") }
    }

    @Test
    fun `real engine reads cross site response and retains original GET path`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"Sites":{
                "MAL":{"42":{"identifier":"42"}},
                "Anilist":{"slug":{"identifier":"8","title":"葬送","url":"https://fixture.test/8"}},
                "Kitsu":{"12":{"identifier":"bad","title":"  ","url":""}},
                "Bangumi":{"9":{}},"MangaUpdates":{"10":{}},"unknown":{"11":{}}
            }}""").setHeader("Content-Type", "application/json; charset=utf-8"))
            createKtorOkHttpClient(localClient(server)).use { http ->
                val results = createMALSyncMappingApi(http).resolve(MALSyncService.MAL, 42, MALSyncKind.MANGA)
                assertEquals(setOf(MALSyncService.ANILIST, MALSyncService.KITSU,
                    MALSyncService.BANGUMI, MALSyncService.MANGAUPDATES), results.map { it.service }.toSet())
                assertEquals(MALSyncMapping(MALSyncService.ANILIST, 8, "葬送", "https://fixture.test/8"),
                    results.single { it.service == MALSyncService.ANILIST })
                val kitsu = results.single { it.service == MALSyncService.KITSU }
                assertEquals(12L, kitsu.remoteId)
                assertNull(kitsu.title)
                assertNull(kitsu.url)
            }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/mal/manga/42", request.path)
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test
    fun `real HTTP errors are empty and invalid success body remains parse failure`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(404).setBody("not JSON"))
            server.enqueue(MockResponse().setBody("not JSON"))
            createKtorOkHttpClient(localClient(server)).use { http ->
                val api = createMALSyncMappingApi(http)
                assertEquals(emptyList<MALSyncMapping>(), api.resolve(MALSyncService.MAL, 1, MALSyncKind.MANGA))
                assertTrue(runCatching { api.resolve(MALSyncService.MAL, 2, MALSyncKind.ANIME) }
                    .exceptionOrNull() is JSONException)
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `active mapping request cancellation stops real network call`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            createKtorOkHttpClient(localClient(server)).use { http ->
                val api = createMALSyncMappingApi(http)
                val job = launch(Dispatchers.Default) { api.resolve(MALSyncService.MAL, 1, MALSyncKind.MANGA) }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                withTimeout(5000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
            assertEquals(1, server.requestCount)
        }
    }
}
