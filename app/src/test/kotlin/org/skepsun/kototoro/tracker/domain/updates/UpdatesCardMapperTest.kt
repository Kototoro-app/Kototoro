package org.skepsun.kototoro.tracker.domain.updates

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

/**
 * Pure card mapping of the updates snapshot
 * (history-updates-feed komikku-alignment Phase U4, `buildUpdateCardModel`).
 *
 * Pins the card contract the paging chain used to provide — group ui id as the
 * list item identity, summed new-chapter counter, projection-count suffix,
 * per-mode subtitles, the manual-override-then-metadata chain and the
 * tracking-service badge — without a device, a database or a `Content` lookup.
 */
class UpdatesCardMapperTest {

    private fun group(
        uiId: Long = 1L,
        entityId: Long? = uiId,
        preferredLocalMangaId: Long? = null,
        mangaIds: List<Long> = listOf(100L + uiId),
        totalNewChapters: Int = 4,
        lastChapterDate: Long? = 900L,
        isPinned: Boolean = false,
        displayMangaId: Long? = mangaIds.first(),
        title: String = "Alpha Work",
        altTitle: String? = "Alpha Alt",
        coverUrl: String? = "https://cover/$uiId",
        author: String? = "Author A",
        sourceName: String = "TEST",
        publicationState: ContentState? = ContentState.ONGOING,
        isNsfw: Boolean = false,
        rating: Float = 0.75f,
        tags: List<UpdateCardTag> = listOf(UpdateCardTag(11L, "Action"), UpdateCardTag(12L, "Comedy")),
        categoryIds: Set<Long> = emptySet(),
        overrideTitle: String? = null,
        overrideCoverUrl: String? = null,
        metadataTrackingService: Int? = null,
        metadataTrackingTitle: String? = null,
        metadataTrackingCoverUrl: String? = null,
    ) = UpdateGroupRow(
        uiId = uiId,
        entityId = entityId,
        preferredLocalMangaId = preferredLocalMangaId,
        mangaIds = mangaIds,
        totalNewChapters = totalNewChapters,
        lastChapterDate = lastChapterDate,
        isPinned = isPinned,
        categoryIds = categoryIds,
        displayMangaId = displayMangaId,
        title = title,
        altTitle = altTitle,
        coverUrl = coverUrl,
        author = author,
        sourceName = sourceName,
        contentType = ContentType.MANGA,
        publicationState = publicationState,
        isNsfw = isNsfw,
        rating = rating,
        tags = tags,
        overrideTitle = overrideTitle,
        overrideCoverUrl = overrideCoverUrl,
        metadataTrackingService = metadataTrackingService,
        metadataTrackingTitle = metadataTrackingTitle,
        metadataTrackingCoverUrl = metadataTrackingCoverUrl,
        sourceGroupFlags = 1,
        sourceOriginFlags = 1,
        displayContentTypeOrdinal = ContentType.MANGA.ordinal,
    )

    private fun request(
        row: UpdateGroupRow,
        mode: ListMode = ListMode.LIST,
        groupSuffix: String? = "Projection: TEST",
    ) = UpdateCardModelRequest(
        group = row,
        mode = mode,
        groupSuffix = groupSuffix,
        brokenTitle = "(broken)",
        tagTint = { 0 },
    )

    @Test
    fun `grid mode carries counter and group identity`() {
        val model = buildUpdateCardModel(request(group(uiId = -1, totalNewChapters = 6), mode = ListMode.GRID))

        assertTrue(model is ContentGridModel)
        model as ContentGridModel
        assertEquals(-1L, model.id)
        assertEquals(6, model.counter)
        assertEquals(1, model.projectionCount)
        assertEquals("Alpha Work", model.manga.title)
        assertEquals("https://cover/-1", model.manga.coverUrl)
    }

    @Test
    fun `list mode joins tags subtitle and projection suffix`() {
        val model = buildUpdateCardModel(request(group(uiId = 5), mode = ListMode.LIST))

        assertTrue(model is ContentCompactListModel)
        model as ContentCompactListModel
        assertEquals(5L, model.id)
        assertEquals(4, model.counter)
        assertEquals("Action, Comedy · Projection: TEST", model.subtitle)
    }

    @Test
    fun `detailed mode uses alt title subtitle with chips`() {
        val model = buildUpdateCardModel(request(group(uiId = 7), mode = ListMode.DETAILED_LIST))

        assertTrue(model is ContentDetailedListModel)
        model as ContentDetailedListModel
        assertEquals("Alpha Alt · Projection: TEST", model.subtitle)
        assertEquals(listOf("Action", "Comedy"), model.tags.map { it.title })
        assertNull(model.progress)
    }

    @Test
    fun `manual override wins over metadata authority`() {
        val row = group(
            overrideTitle = "Manual title",
            overrideCoverUrl = "https://manual/cover",
            metadataTrackingTitle = "Site title",
            metadataTrackingCoverUrl = "https://site/cover",
        )

        val model = buildUpdateCardModel(request(row))

        assertEquals("Manual title", model.override?.title)
        assertEquals("https://manual/cover", model.override?.coverUrl)
    }

    @Test
    fun `metadata authority fills the fields the manual override leaves null`() {
        val row = group(
            metadataTrackingService = ScrobblerService.MAL.id,
            metadataTrackingTitle = "Site title",
            metadataTrackingCoverUrl = "https://site/cover",
        )

        val model = buildUpdateCardModel(request(row))

        assertEquals("Site title", model.override?.title)
        assertEquals("https://site/cover", model.override?.coverUrl)
        assertEquals(ScrobblerService.MAL, model.metadataTrackingService)
    }

    @Test
    fun `no override yields null instead of empty`() {
        val model = buildUpdateCardModel(request(group()))

        assertNull(model.override)
        assertNull(model.metadataTrackingService)
    }

    @Test
    fun `blank title falls back to the broken projection placeholder`() {
        val model = buildUpdateCardModel(request(group(title = "  ")))

        assertEquals("(broken)", model.manga.title)
    }

    @Test
    fun `multi projection group keeps every manga id for removal`() {
        val row = group(mangaIds = listOf(101L, 102L, 103L), displayMangaId = 102L)

        val model = buildUpdateCardModel(request(row, mode = ListMode.GRID))

        assertEquals(3, (model as ContentGridModel).projectionCount)
    }
}
