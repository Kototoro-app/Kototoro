package org.skepsun.kototoro.scrobbling.mangabaka.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingDao
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent

class MangaBakaRepositoryTest {
    private val storage = mockk<ScrobblerStorage>(relaxed = true)
    private val dao = mockk<ScrobblingDao>(relaxed = true)
    private val db = mockk<MangaDatabase> { every { getScrobblingDao() } returns dao }
    private val auth = mockk<MangaBakaAuth>()
    private val content = ScrobblerContent(377, "ONE PIECE", null, null, "https://mangabaka.org/377", "manga")
    private val entry = """{"status":200,"data":{"id":92,"series_id":377,"state":"rereading",
        "progress_chapter":12,"rating":75,"note":"Keep"}}"""

    private fun repository(server: MockWebServer): MangaBakaRepository {
        every { storage.accessToken } returns "mb-fixture"
        return MangaBakaRepository(MangaBakaApi(OkHttpClient(), server.url("/")), storage, auth, db)
    }

    @Test
    fun `binding an existing remote entry preserves its personal data without a write request`() = runBlocking {
        MockWebServer().use { server ->
            val preview = ScrobblingEntity(8, 92, 0, 377, "rereading", 12, "Keep", 0.75f)
            coEvery { dao.findAllByTargetId(8, 377) } returns listOf(preview)
            server.enqueue(MockResponse().setBody(entry))
            repository(server).createRate(55, content)
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
            coVerify { dao.upsert(match {
                it.mangaId == 55L && it.targetId == 377L && it.id == 92 && it.chapter == 12 &&
                    it.rating == 0.75f && it.status == "rereading" && it.comment == "Keep"
            }) }
            coVerify { dao.delete(preview) }
        }
    }

    @Test
    fun `failed remote write never advances local progress`() {
        MockWebServer().use { server ->
            val old = ScrobblingEntity(8, 92, 55, 377, "reading", 12, null, 0.75f)
            coEvery { dao.findByLocalManga(8, 55) } returns old
            server.enqueue(MockResponse().setResponseCode(429))
            val repository = repository(server)
            assertThrows(MangaBakaHttpException::class.java) { runBlocking { repository.updateRate(92, 55, 13) } }
            coVerify(exactly = 0) { dao.upsert(any<ScrobblingEntity>()) }
        }
    }

    @Test
    fun `failed later library page leaves previous local snapshot untouched`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":200,"pagination":{"next":"next"},"data":[{
                "entry":{"id":92,"series_id":377},"series":{"id":377,"title":"ONE PIECE"}
            }]}"""))
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = repository(server)
            assertThrows(MangaBakaHttpException::class.java) { runBlocking { repository.syncLibraryFromRemote() } }
            verify(exactly = 0) { db.getScrobblingDao() }
        }
    }

    @Test
    fun `invalid PAT cannot replace a working session`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val repository = repository(server)
            assertThrows(MangaBakaHttpException::class.java) { runBlocking { repository.authorize("mb-invalid") } }
            verify(exactly = 0) { storage.accessToken = any() }
            verify(exactly = 0) { storage.refreshToken = any() }
        }
    }

    @Test
    fun `score edit preserves considering and uses series ID instead of library entry ID`() = runBlocking {
        MockWebServer().use { server ->
            val old = ScrobblingEntity(8, 92, 55, 377, "considering", 12, "Keep", 0.75f)
            coEvery { dao.findByLocalManga(8, 55) } returns old
            every { storage["rating_step"] } returns "25"
            server.enqueue(MockResponse().setBody("""{"status":200,"data":true}"""))
            server.enqueue(MockResponse().setBody(entry.replace("rereading", "considering")))
            repository(server).updateRate(92, 55, 0.9f, null, null)
            val request = server.takeRequest()
            assertEquals("/v1/my/library/377", request.path)
            assertEquals(Json.parseToJsonElement("""{"rating":100}""").jsonObject,
                Json.parseToJsonElement(request.body.readUtf8()).jsonObject)
            coVerify { dao.upsert(match { it.status == "considering" && it.chapter == 12 && it.comment == "Keep" }) }
        }
    }
}
