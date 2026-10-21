package org.skepsun.kototoro.scrobbling.mangabaka.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.TrackingSiteDao
import org.skepsun.kototoro.core.db.entity.TrackingSiteItemEntity
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.data.TrackingSiteCacheRepository

class MangaBakaCacheScoreTest {
    private val dao = mockk<TrackingSiteDao>()
    private val db = mockk<MangaDatabase> { every { getTrackingSiteDao() } returns dao }
    private val prefs = mockk<SharedPreferences>()
    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } returns prefs
    }
    private val cache = TrackingSiteCacheRepository(db, context)

    private fun entity(rating: Float, max: Float?) = TrackingSiteItemEntity(
        service = 8, remoteId = 605242, title = "Fixture", altTitles = null,
        primaryTitle = null, secondaryTitle = null, rating = rating, scoreMax = max,
        rank = null, summary = null, progressText = null, updatedAtText = null,
        tags = null, year = null, authors = null, coverUrl = null,
        totalEpisodes = null, publishDate = null, siteUrl = null, cachedAt = 0, updatedAt = 0,
    )

    @Test
    fun `legacy cached detail score is rejected so corrected API is fetched`() = runBlocking {
        coEvery { dao.findItem(8, 605242) } returns entity(0.645f, null)
        every { prefs.getString(any(), null) } returns """{"score":0.645,"description":"Cached detail"}"""
        assertNull(cache.readDetails(ScrobblerService.MANGABAKA, 605242))
    }

    @Test
    fun `percent search cache produces ten point details without relying on value magnitude`() = runBlocking {
        every { prefs.getString(any(), null) } returns """{"scoreScale":10,"scoreVersion":1}"""
        for ((rating, expected) in listOf(65f to 6.5f, 5f to 0.5f, 64.5f to 6.5f)) {
            coEvery { dao.findItem(8, 605242) } returns entity(rating, 100f)
            assertEquals(expected, cache.readDetails(ScrobblerService.MANGABAKA, 605242)?.score)
        }
    }

    @Test
    fun `corrected ten point details are not converted again`() = runBlocking {
        coEvery { dao.findItem(8, 605242) } returns entity(6.5f, 10f)
        every { prefs.getString(any(), null) } returns """{"scoreScale":10,"scoreVersion":1}"""
        assertEquals(6.5f, cache.readDetails(ScrobblerService.MANGABAKA, 605242)?.score)
    }
}
