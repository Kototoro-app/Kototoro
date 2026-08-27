package org.skepsun.kototoro.list.domain

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.data.TrackingSiteCacheRepository
import org.skepsun.kototoro.tracker.domain.TrackingRepository

class ContentListMapperTrackingBatchTest {

    private val trackingSiteCacheRepository = mockk<TrackingSiteCacheRepository>()
    private val settings = mockk<AppSettings> {
        every { getContentListBadges() } returns 0
        every { isTrackerEnabled } returns false
    }
    private val mapper = ContentListMapper(
        context = mockk<Context>(relaxed = true),
        settings = settings,
        trackingRepository = mockk<TrackingRepository>(relaxed = true),
        historyRepository = mockk<HistoryRepository>(relaxed = true),
        favouritesRepository = mockk<FavouritesRepository>(relaxed = true),
        localContentIndex = mockk<LocalContentIndex>(relaxed = true),
        dataRepository = mockk<ContentDataRepository>(relaxed = true),
        trackingSiteCacheRepository = trackingSiteCacheRepository,
        db = mockk<MangaDatabase>(relaxed = true),
    )

    @Test
    fun `requested list prefetches tracking display metadata without per item queries`() = runTest {
        val service = ScrobblerService.entries.first()
        val keys = setOf(service.id to 101L, service.id to 102L)
        coEvery { trackingSiteCacheRepository.readDetailsSummaries(any()) } returns emptyMap()

        mapper.toRequestedListModelList(
            requests = keys.mapIndexed { index, (_, remoteId) ->
                ContentListMapper.ListModelRequest(
                    manga = content(index.toLong() + 1L),
                    metadataSelectionOverride = ContentDataRepository.MetadataSourceSelection.Tracking(
                        serviceId = service.id,
                        remoteId = remoteId,
                    ),
                    useMetadataSelectionOverride = true,
                    manualOverride = null,
                    useManualOverride = true,
                )
            },
            mode = ListMode.GRID,
            flags = ContentListMapper.NO_FAVORITE or
                ContentListMapper.NO_PROGRESS or
                ContentListMapper.NO_COUNTER,
            pinnedIds = emptySet(),
        )

        coVerify(exactly = 1) {
            trackingSiteCacheRepository.readDetailsSummaries(match { it.toSet() == keys })
        }
        coVerify(exactly = 0) { trackingSiteCacheRepository.readDetails(any(), any()) }
    }

    private fun content(id: Long) = Content(
        id = id,
        title = "Title $id",
        altTitles = emptySet(),
        url = "/$id",
        publicUrl = "https://example.test/$id",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = TestSource,
    )

    private object TestSource : ContentSource {
        override val name: String = "TEST"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }
}
