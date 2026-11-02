package org.skepsun.kototoro.scrobbling.mangabaka.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class MangaBakaApiTest {
    private val series = """{"id":377,"title":"ONE PIECE","type":"manga",
        "cover":{"x350":{"x1":"https://example.org/cover.jpg"}}}"""

    @Test
    fun `search escapes query paginates and never sends credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":200,"data":[$series]}"""))
            val results = MangaBakaApi(OkHttpClient(), server.url("/")).search("A & B", 20)
            assertEquals(377L, results.single().id)
            assertEquals("https://example.org/cover.jpg", results.single().cover)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("A & B", request.requestUrl!!.queryParameter("q"))
            assertEquals("2", request.requestUrl!!.queryParameter("page"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("x-api-key"))
            assertNull(request.requestUrl!!.queryParameter("type_not"))
        }
    }

    @Test
    fun `public detail rating uses ten point scale while personal rating stays normalized`() = runBlocking {
        MockWebServer().use { server ->
            val api = MangaBakaApi(OkHttpClient(), server.url("/"))
            for ((remoteScore, expectedScore) in listOf(70 to 7f, 5 to 0.5f, 100 to 10f, 64.5 to 6.5f)) {
                server.enqueue(MockResponse().setBody(
                    """{"status":200,"data":{"id":377,"title":"ONE PIECE","type":"manga","rating":$remoteScore}}""",
                ))
                // DetailsViewModel divides this public score by 10 for Content.rating;
                // the cover badge multiplies Content.rating by 10 again.
                assertEquals(expectedScore, api.details(377).score)
            }
            val personal = parseMangaBakaLibraryEntry(Json.parseToJsonElement("""{
                "id":92,"series_id":377,"rating":70
            }""").jsonObject)
            assertEquals(0.7f, personal.rating)
        }
    }

    @Test
    fun `v2 titles and covers are parsed without depending on deprecated v1 title`() {
        val series = Json.parseToJsonElement("""{
            "id":42,"type":"novel","titles":[{"title":"Alt"},{"title":"Primary","is_primary":true}],
            "cover":{"x350":"https://example.org/novel.jpg"}
        }""").jsonObject
        val result = parseMangaBakaSeries(series)
        assertEquals("Primary", result.name)
        assertEquals("novel", result.mediaType)
        assertEquals("https://example.org/novel.jpg", result.cover)
    }

    @Test
    fun `library reads all pages without following next URL with credentials`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { index ->
                val next = if (index == 0) "\"https://untrusted.example/?page=2\"" else "null"
                server.enqueue(MockResponse().setBody("""{"status":200,"pagination":{"next":$next},"data":[{
                    "entry":{"id":${index + 1},"series_id":377,"state":"considering",
                        "rating":null,"progress_chapter":null},
                    "series":$series
                }]}"""))
            }
            val result = MangaBakaApi(OkHttpClient(), server.url("/")).library("mb-fixture")
            assertEquals(2, result.size)
            assertEquals("considering", result.first().state)
            assertEquals(0, result.first().chapter)
            assertEquals(0f, result.first().rating)
            repeat(2) { page ->
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("${page + 1}", request.requestUrl!!.queryParameter("page"))
                assertEquals("/v2/my/library", request.requestUrl!!.encodedPath)
                assertEquals("mb-fixture", request.getHeader("x-api-key"))
                assertNull(request.getHeader("Authorization"))
            }
        }
    }

    @Test
    fun `malformed second page fails rather than returning a partial library`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":200,"pagination":{"next":"next"},"data":[]}"""))
            assertThrows(IOException::class.java) {
                runBlocking { MangaBakaApi(OkHttpClient(), server.url("/")).library("mb-fixture") }
            }
        }
    }

    @Test
    fun `missing library entry alone returns null while auth and rate limit failures propagate`() {
        MockWebServer().use { server ->
            val api = MangaBakaApi(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(runBlocking { api.entry(377, "oauth-fixture") })
            assertEquals("Bearer oauth-fixture", server.takeRequest().getHeader("Authorization"))
            for (status in listOf(401, 429, 500)) {
                server.enqueue(MockResponse().setResponseCode(status))
                val error = assertThrows(MangaBakaHttpException::class.java) {
                    runBlocking { api.entry(377, "oauth-fixture") }
                }
                assertEquals(status, error.statusCode)
            }
        }
    }

    @Test
    fun `HTTP success with false data or error status is not a successful write`() {
        MockWebServer().use { server ->
            val api = MangaBakaApi(OkHttpClient(), server.url("/"))
            for (body in listOf("""{"status":200,"data":false}""", """{"status":400,"data":true}""")) {
                server.enqueue(MockResponse().setBody(body))
                assertThrows(IOException::class.java) {
                    runBlocking { api.patch(377, "mb-fixture", mangaBakaProgressPatch(2)) }
                }
            }
        }
    }

    @Test
    fun `progress PATCH only contains chapter and represents zero as null`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":200,"data":true}"""))
            MangaBakaApi(OkHttpClient(), server.url("/")).patch(377, "mb-fixture", mangaBakaProgressPatch(0))
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/v1/my/library/377", request.path)
            assertEquals("""{"progress_chapter":null}""", request.body.readUtf8())
        }
    }

    @Test
    fun `score edits respect rating steps without overwriting other remote fields`() {
        val old = MangaBakaLibraryEntry(1, 377, "considering", 12, 0.5f, "Keep note")
        val patch = mangaBakaInfoPatch(old, 0.86f, null, null, 25)
        assertEquals(mapOf("rating" to JsonPrimitive(75)), patch)
        assertFalse("state" in patch)
        assertFalse("progress_chapter" in patch)
        assertFalse("is_private" in patch)
        assertFalse("start_date" in patch)
        assertEquals(JsonNull, mangaBakaInfoPatch(old, 0f, null, null, 1)["rating"])
        assertTrue(mangaBakaInfoPatch(old, 0.5f, null, null, 1).isEmpty())
        assertEquals(JsonPrimitive(25), mangaBakaInfoPatch(old, 0.01f, null, null, 25)["rating"])
    }

    @Test
    fun `PKCE follows the RFC 7636 S256 test vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            mangaBakaCodeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }
}
