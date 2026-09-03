package org.skepsun.kototoro.favourites.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

/**
 * Pure card mapping of the favourites library snapshot
 * (favourites-komikku-alignment Phase 5, `buildFavouriteCardModel`).
 *
 * Pins the card contract the aggregate chain used to provide — entity-id item identity,
 * membership-scoped pin, tracking counter, history progress, download badge, projection
 * count, per-mode subtitles and the manual override chain — without a device, a database
 * or a `Content` projection.
 */
class FavouritesCardMapperTest {

    private fun row(
        entityId: Long = 1L,
        displayMangaId: Long? = 100L + entityId,
        title: String = "Alpha Work",
        altTitle: String? = "Alpha Alt",
        coverUrl: String? = "https://cover/$entityId",
        author: String? = "Author A",
        sourceName: String = "TEST",
        contentType: ContentType? = ContentType.MANGA,
        publicationState: ContentState? = ContentState.ONGOING,
        isNsfw: Boolean = false,
        newChapters: Int = 0,
        progressPercent: Float? = null,
        progressTotalChapters: Int? = null,
        projectionCount: Int = 1,
        displayTags: List<FavouriteCardTag> = listOf(FavouriteCardTag(11L, "Action"), FavouriteCardTag(12L, "Comedy")),
        isDownloaded: Boolean = false,
        overrideTitle: String? = null,
        overrideCoverUrl: String? = null,
        metadataTrackingService: Int? = null,
        metadataTrackingTitle: String? = null,
        metadataTrackingCoverUrl: String? = null,
    ) = FavouriteCardRow(
        entityId = entityId,
        displayMangaId = displayMangaId,
        localMangaIds = setOfNotNull(displayMangaId),
        title = title,
        altTitle = altTitle,
        coverUrl = coverUrl,
        author = author,
        sourceName = sourceName,
        sourceGroupFlags = 0,
        sourceOriginFlags = 0,
        contentType = contentType,
        publicationState = publicationState,
        isNsfw = isNsfw,
        rating = -1f,
        readingStatus = ScrobblingStatus.PLANNED,
        newChapters = newChapters,
        lastChapterDate = 0L,
        progressPercent = progressPercent,
        progressTotalChapters = progressTotalChapters,
        lastReadAt = null,
        projectionCount = projectionCount,
        projectionSourceNames = setOf(sourceName),
        tagIds = displayTags.mapTo(LinkedHashSet()) { it.tagId },
        displayTags = displayTags,
        isDownloaded = isDownloaded,
        hasBrokenProjection = displayMangaId == null,
        overrideTitle = overrideTitle,
        overrideCoverUrl = overrideCoverUrl,
        metadataTrackingService = metadataTrackingService,
        metadataTrackingTitle = metadataTrackingTitle,
        metadataTrackingCoverUrl = metadataTrackingCoverUrl,
        isPinned = false,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun request(
        source: FavouriteCardRow = row(),
        mode: ListMode = ListMode.GRID,
        isPinned: Boolean = false,
        groupSuffix: String? = "Current projection: Test",
        progressMode: ProgressIndicatorMode = ProgressIndicatorMode.PERCENT_READ,
        brokenTitle: String = "Missing projection",
        tagTint: (String) -> Int = { 0 },
    ) = FavouriteCardModelRequest(
        row = source,
        mode = mode,
        progressMode = progressMode,
        isPinned = isPinned,
        groupSuffix = groupSuffix,
        brokenTitle = brokenTitle,
        tagTint = tagTint,
    )

    @Test
    fun `item identity is the entity id in every card mode`() {
        for (mode in ListMode.entries) {
            val model = buildFavouriteCardModel(request(mode = mode))
            assertEquals(1L, model.id, "id of $mode")
        }
        // Representative projection / cover / title changes never move a card.
        val before = buildFavouriteCardModel(request()) as ContentGridModel
        val after = buildFavouriteCardModel(
            request(source = row(coverUrl = "https://other", title = "Renamed", displayMangaId = 999L)),
        ) as ContentGridModel
        assertEquals(before.id, after.id)
    }
    @Test
    fun `grid modes never read the group suffix`() {
        // The batch mapper skips building the suffix for these modes because it is not part
        // of the model. This is that assumption: if a grid card ever starts showing the
        // projection suffix, mapping a whole library has to format it again.
        for (mode in listOf(ListMode.GRID, ListMode.COMPACT_GRID)) {
            val withSuffix = buildFavouriteCardModel(request(mode = mode, groupSuffix = "Current: Test"))
            val withoutSuffix = buildFavouriteCardModel(request(mode = mode, groupSuffix = null))
            assertEquals(withSuffix, withoutSuffix, "$mode must not depend on groupSuffix")
        }
        // The list modes do show it, so the skip is limited to the grid.
        for (mode in listOf(ListMode.LIST, ListMode.DETAILED_LIST)) {
            val withSuffix = buildFavouriteCardModel(request(mode = mode, groupSuffix = "Current: Test"))
            val withoutSuffix = buildFavouriteCardModel(request(mode = mode, groupSuffix = null))
            assertNotEquals(withSuffix, withoutSuffix, "$mode must keep the projection suffix")
        }
    }


    @Test
    fun `grid card carries counter progress projection pin and download badges`() {
        val model = buildFavouriteCardModel(
            request(
                source = row(newChapters = 4, progressPercent = 0.5f, progressTotalChapters = 20, projectionCount = 3, isDownloaded = true),
                isPinned = true,
            ),
        ) as ContentGridModel
        assertEquals(4, model.counter)
        assertEquals(3, model.projectionCount)
        assertTrue(model.isPinned)
        assertTrue(model.isSaved)
        assertFalse(model.isFavorite, "the favourites page never shows the heart badge")
        assertEquals(0.5f, model.progress?.percent)
        assertEquals("Alpha Alt", model.subtitle, "grid subtitle is the alt title, no projection suffix")
        assertEquals("Alpha Work", model.title)
        assertEquals("https://cover/1", model.coverUrl)
        assertEquals("Author A", model.manga.authors.joinToString())
    }

    @Test
    fun `tracking metadata authority supplies title cover and badge`() {
        val authority = ScrobblerService.entries.last()
        val model = buildFavouriteCardModel(
            request(
                source = row(
                    title = "Source Title",
                    coverUrl = "https://cover/1",
                    metadataTrackingService = authority.id,
                    metadataTrackingTitle = "Cached Site Title",
                    metadataTrackingCoverUrl = "https://site/cover",
                ),
            ),
        ) as ContentGridModel
        assertEquals("Cached Site Title", model.title)
        assertEquals("https://site/cover", model.coverUrl)
        assertEquals(authority, model.metadataTrackingService)
    }

    @Test
    fun `manual override wins over the authority field by field`() {
        val model = buildFavouriteCardModel(
            request(
                source = row(
                    overrideTitle = "Manual Title",
                    metadataTrackingService = ScrobblerService.entries.first().id,
                    metadataTrackingTitle = "Cached Site Title",
                    metadataTrackingCoverUrl = "https://site/cover",
                ),
            ),
        ) as ContentGridModel
        assertEquals("Manual Title", model.title, "the manual override keeps the title")
        assertEquals("https://site/cover", model.coverUrl, "the authority still fills the free field")
        assertEquals(
            ScrobblerService.entries.first().id,
            model.metadataTrackingService?.id,
            "the badge follows the authority regardless of the manual override",
        )
    }

    @Test
    fun `without an authority there is no badge and the projection display stays`() {
        val model = buildFavouriteCardModel(request()) as ContentGridModel
        assertNull(model.metadataTrackingService)
        assertEquals("Alpha Work", model.title)
        assertEquals("https://cover/1", model.coverUrl)
    }

    @Test
    fun `completed reading zeroes the new-chapter counter`() {
        val model = buildFavouriteCardModel(
            request(source = row(newChapters = 7, progressPercent = 0.99999f, progressTotalChapters = 10)),
        ) as ContentGridModel
        assertEquals(0, model.counter, "a finished work shows no new-chapter badge")
        assertEquals(ReadingProgress.PROGRESS_COMPLETED, model.progress?.percent)

        val almost = buildFavouriteCardModel(
            request(source = row(newChapters = 7, progressPercent = 0.99f, progressTotalChapters = 10)),
        ) as ContentGridModel
        assertEquals(7, almost.counter, "below the completion threshold the counter stays")
    }

    @Test
    fun `without history there is no progress badge and the counter stays`() {
        val model = buildFavouriteCardModel(request(source = row(newChapters = 2))) as ContentGridModel
        assertNull(model.progress)
        assertEquals(2, model.counter)
    }

    @Test
    fun `progress mode without chapter counts is dropped as invalid`() {
        val model = buildFavouriteCardModel(
            request(
                source = row(progressPercent = 0.4f, progressTotalChapters = 0),
                progressMode = ProgressIndicatorMode.CHAPTERS_READ,
            ),
        ) as ContentGridModel
        assertNull(model.progress)
    }

    @Test
    fun `list card subtitle joins the tag line with the projection suffix`() {
        val model = buildFavouriteCardModel(request(mode = ListMode.LIST)) as ContentCompactListModel
        assertEquals("Action, Comedy · Current projection: Test", model.subtitle)

        val withoutTags = buildFavouriteCardModel(
            request(mode = ListMode.LIST, source = row(displayTags = emptyList())),
        ) as ContentCompactListModel
        assertEquals("Current projection: Test", withoutTags.subtitle)

        val withoutSuffix = buildFavouriteCardModel(
            request(mode = ListMode.LIST, groupSuffix = null),
        ) as ContentCompactListModel
        assertEquals("Action, Comedy", withoutSuffix.subtitle)
    }

    @Test
    fun `detailed card subtitle is the alt title plus the suffix and chips come from the row tags`() {
        val model = buildFavouriteCardModel(
            request(mode = ListMode.DETAILED_LIST, tagTint = { if (it == "Comedy") 7 else 0 }),
        ) as ContentDetailedListModel
        assertEquals("Alpha Alt · Current projection: Test", model.subtitle)
        assertEquals(listOf<CharSequence>("Action", "Comedy"), model.tags.map { it.title })
        assertEquals(listOf(0, 7), model.tags.map { it.tint })
        assertFalse(model.isSaved)
    }

    @Test
    fun `manual overrides win and are dropped when the row carries none`() {
        val overridden = buildFavouriteCardModel(
            request(source = row(overrideTitle = "Chosen", overrideCoverUrl = "https://chosen")),
        )
        assertEquals("Chosen", overridden.title)
        assertEquals("https://chosen", overridden.coverUrl)
        assertEquals("Alpha Work", overridden.manga.title, "the stub keeps the raw projection title")
        assertNull(buildFavouriteCardModel(request()).override)
    }

    @Test
    fun `rows without a display projection stay visible with a placeholder title`() {
        val model = buildFavouriteCardModel(request(source = row(displayMangaId = null, title = "", coverUrl = null)))
        assertEquals("Missing projection", model.title)
        assertEquals(1L, model.id, "the entity id still identifies the broken row")
        assertEquals(1L, model.manga.id, "a broken stub falls back to the entity id")
    }

    @Test
    fun `the nsfw badge follows the persisted row flag`() {
        val adult = buildFavouriteCardModel(request(source = row(isNsfw = true)))
        assertEquals(ContentRating.ADULT, adult.manga.contentRating)
        assertTrue(adult.manga.isNsfw())

        val safe = buildFavouriteCardModel(request(source = row(isNsfw = false)))
        assertEquals(ContentRating.SAFE, safe.manga.contentRating)
        assertFalse(safe.manga.isNsfw())
    }

    @Test
    fun `the stub content stays narrow`() {
        val model = buildFavouriteCardModel(request())
        assertNull(model.manga.description)
        assertNull(model.manga.sourceData)
        assertNull(model.manga.chapters)
        assertEquals("", model.manga.url)
        assertEquals("", model.manga.publicUrl)
        assertEquals("TEST", model.manga.source.name)
        assertEquals(ContentState.ONGOING, model.manga.state)
    }
}
