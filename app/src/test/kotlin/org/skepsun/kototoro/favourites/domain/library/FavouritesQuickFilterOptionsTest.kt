package org.skepsun.kototoro.favourites.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

/**
 * In-memory quick-filter chips of the favourites library (plan section 6.3): the static
 * options follow settings, the tag/source chips count the entities of one category — and
 * the tag identity must round-trip onto the id the in-memory filter matches on.
 */
class FavouritesQuickFilterOptionsTest {

    private val dramaId = "drama_TEST".longHashCode()
    private val actionId = "action_TEST".longHashCode()
    private val comedyId = "comedy_TEST".longHashCode()

    private fun facet(id: Long, key: String) = FavouriteFacetTag(
        tagId = id,
        title = key.replaceFirstChar { it.uppercaseChar() },
        key = key,
        source = "TEST",
    )

    private fun row(
        entityId: Long,
        sourceName: String = "TEST",
        tagIds: Set<Long> = emptySet(),
    ) = FavouriteCardRow(
        entityId = entityId,
        displayMangaId = 10_000L + entityId,
        localMangaIds = setOf(10_000L + entityId),
        title = "Work $entityId",
        altTitle = null,
        coverUrl = null,
        author = null,
        sourceName = sourceName,
        sourceGroupFlags = 0,
        sourceOriginFlags = 0,
        contentType = ContentType.MANGA,
        publicationState = null,
        isNsfw = false,
        rating = -1f,
        readingStatus = ScrobblingStatus.PLANNED,
        newChapters = 0,
        lastChapterDate = 0L,
        progressPercent = null,
        progressTotalChapters = null,
        lastReadAt = null,
        projectionCount = 1,
        projectionSourceNames = setOf(sourceName),
        tagIds = tagIds,
        displayTags = emptyList(),
        isDownloaded = false,
        hasBrokenProjection = false,
        overrideTitle = null,
        overrideCoverUrl = null,
        metadataTrackingService = null,
        metadataTrackingTitle = null,
        metadataTrackingCoverUrl = null,
        isPinned = false,
        createdAt = entityId,
        updatedAt = entityId,
    )

    private fun membership(entityId: Long, categoryId: Long) = FavouriteMembership(
        entityId = entityId,
        categoryId = categoryId,
        isPinned = false,
        sortKey = 0,
        createdAt = entityId,
        updatedAt = entityId,
    )

    private fun options(
        categoryId: Long,
        rows: Map<Long, FavouriteCardRow>,
        memberships: Map<Long, List<FavouriteMembership>>,
        metadata: FavouriteQuickFilterMetadata,
        excludeNsfw: Boolean = false,
        isTrackerEnabled: Boolean = true,
    ) = buildFavouritesFilterOptions(
        FavouritesQuickFilterInput(
            categoryId = categoryId,
            membershipsByCategory = memberships,
            allEntityIds = rows.keys.sorted(),
            rows = rows,
            metadata = metadata,
            excludeNsfw = excludeNsfw,
            isTrackerEnabled = isTrackerEnabled,
        ),
    )

    @Test
    fun `tag chips are the three most used tags of the category`() {
        val rows = mapOf(
            1L to row(1, tagIds = setOf(dramaId, actionId)),
            2L to row(2, tagIds = setOf(dramaId, actionId)),
            3L to row(3, tagIds = setOf(dramaId, comedyId)),
        )
        val memberships = mapOf(
            10L to listOf(membership(1, 10), membership(2, 10), membership(3, 10)),
            // The other category uses an unrelated tag: its chips must stay out of 10.
            11L to listOf(membership(1, 11)),
        )
        val metadata = FavouriteQuickFilterMetadata(
            tags = listOf(facet(actionId, "action"), facet(dramaId, "drama"), facet(comedyId, "comedy")),
            tagEntityCounts = mapOf(dramaId to 3, actionId to 2, comedyId to 1),
            sources = listOf("TEST"),
            sourceEntityCounts = mapOf("TEST" to 3),
        )
        val tags = options(10L, rows, memberships, metadata).filterIsInstance<ListFilterOption.Tag>()
        assertEquals(listOf(dramaId, actionId, comedyId), tags.map { it.tagId }, "by entity count")

        val other = options(11L, rows, memberships, metadata).filterIsInstance<ListFilterOption.Tag>()
        // Both stay at one entity, so the title tie-breaker (ignoring case) decides.
        assertEquals(listOf(actionId, dramaId), other.map { it.tagId }, "category 11 sees entity 1 only")
    }

    @Test
    fun `the chip identity equals the id the filter matches on`() {
        val rows = mapOf(1L to row(1, tagIds = setOf(dramaId)))
        val options = options(
            categoryId = FavouriteCategory.NO_ID,
            rows = rows,
            memberships = emptyMap(),
            metadata = FavouriteQuickFilterMetadata(
                tags = listOf(facet(dramaId, "drama")),
                tagEntityCounts = mapOf(dramaId to 1),
                sources = listOf("TEST"),
                sourceEntityCounts = mapOf("TEST" to 1),
            ),
        )
        val tag = options.filterIsInstance<ListFilterOption.Tag>().single()
        assertEquals(dramaId, tag.tagId, "chip id must be the facet tag id, not a rehash")
        assertEquals("Drama", tag.titleText)
    }

    @Test
    fun `the all slice counts every favourite and the limit holds`() {
        val rows = (1L..8L).associateWith { id -> row(id, tagIds = setOf("tag$id".longHashCode())) }
        val metadata = FavouriteQuickFilterMetadata(
            tags = rows.keys.map { facet("tag$it".longHashCode(), "tag$it") },
            tagEntityCounts = rows.keys.associateWith { 1 },
            sources = listOf("TEST"),
            sourceEntityCounts = mapOf("TEST" to 8),
        )
        val tags = options(FavouriteCategory.NO_ID, rows, emptyMap(), metadata)
            .filterIsInstance<ListFilterOption.Tag>()
        assertEquals(3, tags.size, "legacy chip limit")
    }

    @Test
    fun `settings gate the static options`() {
        val rows = mapOf(1L to row(1))
        val metadata = FavouriteQuickFilterMetadata(
            tags = emptyList(),
            tagEntityCounts = emptyMap(),
            sources = listOf("TEST"),
            sourceEntityCounts = mapOf("TEST" to 1),
        )
        val all = options(FavouriteCategory.NO_ID, rows, emptyMap(), metadata)
        assertTrue(ListFilterOption.Macro.NEW_CHAPTERS in all, "tracker on offers the new-chapters chip")
        assertTrue(ListFilterOption.SFW in all, "rating chips are offered while nsfw is allowed")
        assertTrue(ListFilterOption.Macro.NSFW in all)

        val gated = options(
            FavouriteCategory.NO_ID,
            rows,
            emptyMap(),
            metadata,
            excludeNsfw = true,
            isTrackerEnabled = false,
        )
        assertTrue(ListFilterOption.Macro.NEW_CHAPTERS !in gated, "tracker off hides new chapters")
        assertTrue(ListFilterOption.SFW !in gated, "an nsfw-excluded library hides the rating chips")
        assertTrue(ListFilterOption.Macro.NSFW !in gated)
        assertTrue(ListFilterOption.Macro.MULTI_PROJECTION in gated, "the entity chips always stay")
        assertTrue(gated.any { it is ListFilterOption.Source }, "the plain source chip stays")
    }
}
