package org.skepsun.kototoro.scrobbling.mal.data

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage

class MALDiscoveryRepositoryTest {
    private val api = mockk<MALDiscoveryApi>()
    private val context = mockk<Context>().also {
        every { it.getString(R.string.mal_clientId) } returns "fixture-client"
    }
    private val repository = MALRepository(
        context, mockk<OkHttpClient>(), mockk<ScrobblerStorage>(), mockk<MangaDatabase>(), api,
    )
    private val anime = """{"data":[{"node":{
        "id":8,"title":"English","alternative_titles":{"ja":"日本語","en":"English"},
        "main_picture":{"large":"https://fixture.test/cover"},"mean":8.4,
        "num_episodes":12,"status":"currently_airing","start_season":{"year":2026,"season":"fall"}
    }}]}"""
    private val manga = """{"data":[{"node":{
        "id":9,"title":"Manga","mean":0,"num_chapters":30,"status":"finished","start_date":"2020-01-02"
    }}]}"""

    @Test
    fun `search retains alternate title matching and anime display metadata`() = runBlocking {
        coEvery { api.search("日本語", 7, MALMediaType.ANIME) } returns anime
        val content = repository.findContent("日本語", 7, true).single()
        assertEquals(8L, content.id)
        assertEquals("English", content.name)
        assertEquals("日本語", content.altName)
        assertEquals("日本語", content.primaryTitle)
        assertEquals("English", content.secondaryTitle)
        assertEquals("https://fixture.test/cover", content.cover)
        assertEquals("https://myanimelist.net/anime/8", content.url)
        assertEquals("anime", content.mediaType)
        assertEquals("currently airing · Fall 2026", content.subtitle)
        assertEquals("EP 12", content.progressText)
        assertEquals(8.4f, content.score)
        assertEquals(10f, content.scoreMax)
        assertTrue(content.isBestMatch)
    }

    @Test
    fun `manga search retains progress date and missing score behavior`() = runBlocking {
        coEvery { api.search("manga", 0, MALMediaType.MANGA) } returns manga
        val content = repository.findContent("manga", 0, false).single()
        assertEquals("manga", content.mediaType)
        assertEquals("https://myanimelist.net/manga/9", content.url)
        assertEquals("finished · 2020-01-02", content.subtitle)
        assertEquals("CH 30", content.progressText)
        assertNull(content.score)
        assertNull(content.altName)
        assertTrue(content.isBestMatch)
    }

    @Test
    fun `discovery anime search keeps its original non best match mapping`() = runBlocking {
        coEvery { api.search("English", 0, MALMediaType.ANIME) } returns anime
        assertFalse(repository.searchAnime("English", 0).single().isBestMatch)
    }

    @Test
    fun `ranking and seasonal methods preserve defaults and custom arguments`() = runBlocking {
        coEvery { api.ranking(any(), any(), any(), any()) } returns anime
        coEvery { api.seasonalAnime(any(), any(), any(), any(), any()) } returns anime
        repository.getAnimeRanking()
        repository.getMangaRanking("bypopularity", 10, 30)
        repository.getSeasonalAnime(2026, "fall")
        coVerify(exactly = 1) { api.ranking(MALMediaType.ANIME, "all", 20, 0) }
        coVerify(exactly = 1) { api.ranking(MALMediaType.MANGA, "bypopularity", 10, 30) }
        coVerify(exactly = 1) { api.seasonalAnime(2026, "fall", "anime_num_list_users", 20, 0) }
        assertEquals("manga", repository.getMangaRanking().single().mediaType)
    }

    @Test
    fun `missing data remains empty for ranking but invalid for searches`() = runBlocking {
        coEvery { api.ranking(any(), any(), any(), any()) } returns """{"error":"fixture"}"""
        coEvery { api.search(any(), any(), any()) } returns """{"error":"fixture"}"""
        assertTrue(repository.getAnimeRanking().isEmpty())
        assertTrue(runCatching { repository.findContent("title", 0, true) }
            .exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { repository.searchAnime("title", 0) }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `malformed JSON remains a platform parsing failure`() = runBlocking {
        coEvery { api.ranking(any(), any(), any(), any()) } returns "bad JSON"
        assertTrue(runCatching { repository.getAnimeRanking() }.exceptionOrNull() is JSONException)
    }

    @Test
    fun `discovery cancellation is propagated by all repository paths`() = runBlocking {
        coEvery { api.search(any(), any(), any()) } throws CancellationException("cancel")
        coEvery { api.ranking(any(), any(), any(), any()) } throws CancellationException("cancel")
        coEvery { api.seasonalAnime(any(), any(), any(), any(), any()) } throws CancellationException("cancel")
        val calls = listOf<suspend () -> Unit>(
            { repository.findContent("title", 0, false) }, { repository.searchAnime("title", 0) },
            { repository.getAnimeRanking() }, { repository.getMangaRanking() },
            { repository.getSeasonalAnime(2026, "fall") },
        )
        for (call in calls) assertTrue(runCatching { call() }.exceptionOrNull() is CancellationException)
    }
}
