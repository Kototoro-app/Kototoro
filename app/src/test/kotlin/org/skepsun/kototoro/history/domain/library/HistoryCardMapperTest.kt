package org.skepsun.kototoro.history.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

/**
 * Pure card mapping of the history snapshot
 * (history-updates-feed komikku-alignment Phase H3, `buildHistoryCardModel`).
 */
class HistoryCardMapperTest {

    private fun row(
        entityId: Long = 1L,
        contentType: ContentType? = ContentType.MANGA,
        percent: Float = 0.42f,
        chaptersCount: Int = 120,
        isPinned: Boolean = false,
        title: String = "Alpha Work",
        altTitle: String? = "Alpha Alt",
        coverUrl: String? = "https://cover/1",
        author: String? = "Author A",
        sourceName: String = "TEST",
        isNsfw: Boolean = false,
        tags: List<HistoryCardTag> = listOf(HistoryCardTag("Action", "action"), HistoryCardTag("Comedy", "comedy")),
        localMangaIds: List<Long> = listOf(100L),
        displayMangaId: Long? = 100L,
        overrideTitle: String? = null,
        overrideCoverUrl: String? = null,
        metadataTrackingService: Int? = null,
        metadataTrackingTitle: String? = null,
        metadataTrackingCoverUrl: String? = null,
    ) = HistoryCardEntry(
        uiId = -((entityId shl 8) or ((contentType ?: ContentType.MANGA).ordinal + 1).toLong()),
        entityId = entityId,
        anchorMangaId = localMangaIds.first(),
        preferredLocalMangaId = localMangaIds.firstOrNull(),
        displayMangaId = displayMangaId,
        updatedAt = 900L,
        createdAt = 100L,
        percent = percent,
        chaptersCount = chaptersCount,
        chapterId = 5L,
        newChapters = 2,
        lastChapterDate = null,
        isFavourite = false,
        isPinned = isPinned,
        isDownloaded = false,
        categoryIds = emptySet(),
        contentType = contentType,
        displayContentTypeOrdinal = (contentType ?: ContentType.MANGA).ordinal,
        localMangaIds = localMangaIds,
        bindings = emptyList(),
        title = title,
        altTitle = altTitle,
        coverUrl = coverUrl,
        largeCoverUrl = null,
        author = author,
        sourceName = sourceName,
        publicationState = ContentState.ONGOING,
        isNsfw = isNsfw,
        rating = 0.75f,
        tags = tags,
        overrideTitle = overrideTitle,
        overrideCoverUrl = overrideCoverUrl,
        metadataTrackingService = metadataTrackingService,
        metadataTrackingTitle = metadataTrackingTitle,
        metadataTrackingCoverUrl = metadataTrackingCoverUrl,
        sourceGroupFlags = 1,
        sourceOriginFlags = 1,
    )

    private fun request(
        row: HistoryCardEntry,
        mode: ListMode = ListMode.LIST,
        groupSuffix: String? = "Projection: TEST",
    ) = HistoryCardModelRequest(
        row = row,
        mode = mode,
        progressMode = ProgressIndicatorMode.NONE,
        groupSuffix = groupSuffix,
        brokenTitle = "(broken)",
        tagTint = { 0 },
    )

    @Test
    fun `grid mode carries progress and group identity`() {
        val model = buildHistoryCardModel(request(row(), mode = ListMode.GRID))

        assertTrue(model is ContentGridModel)
        model as ContentGridModel
        assertEquals(row().uiId, model.id)
        assertEquals(0, model.counter)
        assertEquals(1, model.projectionCount)
        assertEquals("Alpha Work", model.manga.title)
        assertNull(model.progress) // ProgressIndicatorMode.NONE
    }

    @Test
    fun `list mode joins tags subtitle and projection suffix`() {
        val model = buildHistoryCardModel(request(row()))

        assertTrue(model is ContentCompactListModel)
        model as ContentCompactListModel
        assertEquals("Action, Comedy · Projection: TEST", model.subtitle)
        assertEquals(row().uiId, model.id)
    }

    @Test
    fun `detailed mode uses alt title subtitle with chips`() {
        val model = buildHistoryCardModel(request(row(), mode = ListMode.DETAILED_LIST))

        assertTrue(model is ContentDetailedListModel)
        model as ContentDetailedListModel
        assertEquals("Alpha Alt · Projection: TEST", model.subtitle)
        assertEquals(listOf("Action", "Comedy"), model.tags.map { it.title })
    }

    @Test
    fun `manual override wins over metadata authority`() {
        val model = buildHistoryCardModel(
            request(
                row(
                    overrideTitle = "Manual title",
                    overrideCoverUrl = "https://manual/cover",
                    metadataTrackingTitle = "Site title",
                    metadataTrackingCoverUrl = "https://site/cover",
                ),
            ),
        )

        assertEquals("Manual title", model.override?.title)
        assertEquals("https://manual/cover", model.override?.coverUrl)
    }

    @Test
    fun `metadata authority fills the fields the manual override leaves null`() {
        val model = buildHistoryCardModel(
            request(
                row(
                    metadataTrackingService = ScrobblerService.MAL.id,
                    metadataTrackingTitle = "Site title",
                    metadataTrackingCoverUrl = "https://site/cover",
                ),
            ),
        )

        assertEquals("Site title", model.override?.title)
        assertEquals("https://site/cover", model.override?.coverUrl)
        assertEquals(ScrobblerService.MAL, model.metadataTrackingService)
    }

    @Test
    fun `blank title falls back to the broken projection placeholder`() {
        val model = buildHistoryCardModel(request(row(title = "  ")))

        assertEquals("(broken)", model.manga.title)
    }
}
