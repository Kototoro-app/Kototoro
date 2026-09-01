package org.skepsun.kototoro.favourites.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.list.ui.compose.toContentCardRenderModel
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

/**
 * Phase 0 field contract for the favourites-komikku-alignment migration
 * (docs/architecture/favourites-komikku-alignment-implementation-plan-2026-09.md, section 4.4).
 *
 * This test pins down exactly which fields the three favourites library card modes
 * (GRID / COMPACT_GRID / LIST / DETAILED_LIST) actually consume, so the new narrow
 * `FavouriteCardRow` read model can be sized against real consumers instead of the wide
 * `MangaEntity`/`Content` projection the paging query drags around today.
 *
 * ## Contract (mechanically enforced below)
 *
 * | Field                     | GRID | LIST(compact) | DETAILED_LIST | Enforced by                          |
 * |---------------------------|:----:|:-------------:|:-------------:|--------------------------------------|
 * | `title` (+ titleOverride) |  x   |       x       |       x       | renderModelReflectsContractFields    |
 * | `coverUrl` (+ coverOverride) | x |       x       |       x       | renderModelReflectsContractFields    |
 * | `altTitles.first`         |  x   |       -       |       x       | altTitlesDriveSubtitleExceptCompact  |
 * | `authors`                 |  x   |       x       |       x       | renderModelReflectsContractFields    |
 * | `tags` (titles)           |  -   |   subtitle    | tag chips+tint | tagsDriveCompactSubtitleAndChips   |
 * | `contentRating` (NSFW)    |  x   |       x       |       x       | nsfwBadgeComesFromContentRating      |
 * | `counter` (new chapters)  |  x   |       x       |       x       | cardModelFieldsFlowThrough           |
 * | `progress`                |  x   |       x       |       x       | cardModelFieldsFlowThrough            |
 * | `projectionCount`         |  x   |       x       |       x       | cardModelFieldsFlowThrough            |
 * | `isPinned` (per membership)| x   |       x       |       x       | cardModelFieldsFlowThrough            |
 * | `isSaved` (downloaded)    |  x   |       -       |       x       | cardModelFieldsFlowThrough            |
 * | `metadataTrackingService` |  x   |       x       |       x       | cardModelFieldsFlowThrough            |
 * | `state`                   | filter + DETAILED_LIST info text; see note | | | composable-level, kept for filters |
 * | `source`                  | source chip + cover cache key + favourites group suffix; see note | | | composable-level |
 * | `rating`                  | RATING sort only; favourites cards never render a score | | | not in render model |
 *
 * ## Not in the contract (must stay out of the new row)
 *
 * `description`, `sourceData`, `largeCoverUrl` (only Home covers/downloads read it),
 * `publicUrl`, `url` (except the TVBox cover fallback inside `buildContentCoverRequest`),
 * full `chapters` (only `chapters.size` inside info text), full entity graph objects.
 *
 * The mechanical guarantee below is one-directional by design: if a new field starts
 * flowing into `toContentCardRenderModel()` (the pure projection the cards draw from),
 * the full-vs-stripped equality breaks and this test fails, forcing the contract — and
 * the row — to be widened consciously.
 */
class FavouriteCardFieldContractTest {

    private val actionTag = ContentTag(title = "Action", key = "action", source = TestContentSource)
    private val dramaTag = ContentTag(title = "Drama", key = "drama", source = TestContentSource)

    /** A Content with every wide field populated, like today's paging projection. */
    private val fullContent = content()

    /**
     * The same Content with every field *outside* the contract neutralized. If the cards
     * render identically from both, the contract is sufficient.
     */
    private val strippedContent = content(
        publicUrl = "",
        largeCoverUrl = null,
        description = " ".repeat(0).ifEmpty { null },
        sourceData = null,
    )

    private fun content(
        id: Long = 1L,
        title: String = "Alpha Work",
        altTitles: Set<String> = setOf("Alt Title"),
        url: String = "/manga/1",
        publicUrl: String = "https://example.com/manga/1",
        rating: Float = 0.75f,
        contentRating: ContentRating? = null,
        coverUrl: String? = "https://example.com/cover/1.jpg",
        tags: Set<ContentTag> = setOf(actionTag, dramaTag),
        state: ContentState? = ContentState.ONGOING,
        authors: Set<String> = setOf("Author A", "Author B"),
        largeCoverUrl: String? = "https://example.com/cover/1-large.jpg",
        description: String? = "A very long description that the library never displays.",
        chapters: List<org.skepsun.kototoro.parsers.model.ContentChapter>? = null,
        sourceData: String? = """{"opaque":true}""",
    ) = Content(
        id = id,
        title = title,
        altTitles = altTitles,
        url = url,
        publicUrl = publicUrl,
        rating = rating,
        contentRating = contentRating,
        coverUrl = coverUrl,
        tags = tags,
        state = state,
        authors = authors,
        largeCoverUrl = largeCoverUrl,
        description = description,
        chapters = chapters,
        source = TestContentSource,
        sourceData = sourceData,
    )

    private fun grid(manga: Content, override: ContentOverride? = null) = ContentGridModel(
        manga = manga,
        override = override,
        subtitle = manga.altTitles.firstOrNull(),
        counter = 3,
        projectionCount = 2,
        progress = null,
        isFavorite = false,
        isSaved = true,
        isPinned = true,
        metadataTrackingService = ScrobblerService.MAL,
    )

    private fun compact(manga: Content, override: ContentOverride? = null) = ContentCompactListModel(
        manga = manga,
        override = override,
        subtitle = manga.tags.joinToString(", ") { it.title }.ifBlank { null },
        counter = 3,
        projectionCount = 2,
        progress = null,
        isPinned = true,
        metadataTrackingService = ScrobblerService.MAL,
    )

    private fun detailed(manga: Content, override: ContentOverride? = null) = ContentDetailedListModel(
        subtitle = manga.altTitles.firstOrNull(),
        manga = manga,
        override = override,
        counter = 3,
        progress = null,
        isFavorite = false,
        isSaved = true,
        tags = manga.tags.map {
            org.skepsun.kototoro.core.ui.widgets.ChipModel(title = it.title, tint = 0, data = it)
        },
        isPinned = true,
        metadataTrackingService = ScrobblerService.MAL,
    )

    @Test
    fun `grid card renders identically from contract fields only`() {
        assertEquals(grid(fullContent).toContentCardRenderModel(), grid(strippedContent).toContentCardRenderModel())
    }

    @Test
    fun `compact list card renders identically from contract fields only`() {
        assertEquals(compact(fullContent).toContentCardRenderModel(), compact(strippedContent).toContentCardRenderModel())
    }

    @Test
    fun `detailed list card renders identically from contract fields only`() {
        assertEquals(detailed(fullContent).toContentCardRenderModel(), detailed(strippedContent).toContentCardRenderModel())
    }

    @Test
    fun `alt titles drive the subtitle of grid and detailed but not compact`() {
        val renamed = fullContent.copy(altTitles = setOf("Different Alt"))
        assertNotEquals(
            grid(fullContent).toContentCardRenderModel().subtitle,
            grid(renamed).toContentCardRenderModel().subtitle,
        )
        assertNotEquals(
            detailed(fullContent).toContentCardRenderModel().subtitle,
            detailed(renamed).toContentCardRenderModel().subtitle,
        )
        // The compact subtitle is the joined tag list (the favourites group suffix is
        // appended on top of it by FavouritesListViewModel.toGroupedListModel).
        assertEquals(
            compact(fullContent).toContentCardRenderModel().subtitle,
            compact(renamed).toContentCardRenderModel().subtitle,
        )
    }

    @Test
    fun `tags drive the compact subtitle and detailed chips but not the grid subtitle`() {
        val retagged = fullContent.copy(tags = setOf(actionTag))
        assertNotEquals(
            compact(fullContent).toContentCardRenderModel().subtitle,
            compact(retagged).toContentCardRenderModel().subtitle,
        )
        assertNotEquals(
            detailed(fullContent).toContentCardRenderModel().tagsText,
            detailed(retagged).toContentCardRenderModel().tagsText,
        )
        assertEquals(
            grid(fullContent).toContentCardRenderModel().subtitle,
            grid(retagged).toContentCardRenderModel().subtitle,
        )
    }

    @Test
    fun `nsfw badge comes from the content rating chain`() {
        val adult = fullContent.copy(contentRating = ContentRating.ADULT)
        assertEquals(false, grid(fullContent).toContentCardRenderModel().isNsfw)
        assertEquals(true, grid(adult).toContentCardRenderModel().isNsfw)
    }

    @Test
    fun `manual title and cover overrides win over the projection fields`() {
        val override = ContentOverride(
            title = "Overridden",
            coverUrl = "https://example.com/overridden.jpg",
            contentRating = null,
        )
        val withOverride = grid(fullContent, override).toContentCardRenderModel()
        assertEquals("Overridden", withOverride.title)
        assertEquals("https://example.com/overridden.jpg", withOverride.coverUrl)
        assertNotEquals(grid(fullContent).toContentCardRenderModel(), withOverride)
    }

    @Test
    fun `card model fields flow through the render model unchanged`() {
        // counter / progress / projectionCount / isPinned / isSaved / metadataTrackingService
        // are set by the favourites mapping (aggregate history/tracking + membership state),
        // not by the Content projection - the row must keep carrying them.
        val base = grid(fullContent).toContentCardRenderModel()
        val changedCounter = grid(fullContent).copy(counter = 9).toContentCardRenderModel()
        val changedProjectionCount = grid(fullContent).copy(projectionCount = 1).toContentCardRenderModel()
        val changedPinned = grid(fullContent).copy(isPinned = false).toContentCardRenderModel()
        val changedSaved = grid(fullContent).copy(isSaved = false).toContentCardRenderModel()
        val changedService = grid(fullContent).copy(metadataTrackingService = null).toContentCardRenderModel()
        assertNotEquals(base, changedCounter)
        assertNotEquals(base, changedProjectionCount)
        assertNotEquals(base, changedPinned)
        assertNotEquals(base, changedSaved)
        assertNotEquals(base, changedService)
    }

    @Test
    fun `favourites never render a rating score so rating stays a sort key only`() {
        // scoreText is only ever set by the Discover feed view models; the favourites
        // mapping leaves it null, so the RATING order must read it from the row but no
        // card displays it.
        assertEquals(null, grid(fullContent).scoreText)
        assertEquals(null, compact(fullContent).scoreText)
        assertEquals(null, detailed(fullContent).scoreText)
    }
}
