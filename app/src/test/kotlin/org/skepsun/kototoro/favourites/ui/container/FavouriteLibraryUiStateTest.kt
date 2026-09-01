package org.skepsun.kototoro.favourites.ui.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.favourites.domain.library.FavouriteCardRow
import org.skepsun.kototoro.favourites.domain.library.FavouriteLibrarySnapshot
import org.skepsun.kototoro.favourites.domain.library.FavouriteMembership
import org.skepsun.kototoro.favourites.domain.library.FavouriteQuickFilterMetadata
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.space.domain.SpaceId

/**
 * Phase 4 ViewModel-level tests (favourites-komikku-alignment plan, section 10.3),
 * expressed against the pure [buildFavouriteLibraryUiState] assembly the container
 * pipeline delegates to: loading/empty are distinct, filter switches keep the row map,
 * category slices share rows, and derivation inputs never touch the database.
 */
class FavouriteLibraryUiStateTest {

    private val spacePolicy = object : SpaceContentPolicy {
        override fun allowedTypes(spaceId: SpaceId) = setOf(ContentType.MANGA)
        override fun spaceFor(contentType: ContentType?) = null
        override fun accepts(spaceId: SpaceId, contentType: ContentType?) = contentType in allowedTypes(spaceId)
        override fun allowedSourceNames(spaceId: SpaceId): Set<String>? = null
        override fun observeAllowedSourceNames(spaceId: SpaceId) = kotlinx.coroutines.flow.flowOf(null)
    }

    private fun row(
        entityId: Long,
        title: String = "Work $entityId",
        newChapters: Int = 0,
        tagIds: Set<Long> = emptySet(),
        contentType: ContentType? = ContentType.MANGA,
    ) = FavouriteCardRow(
        entityId = entityId,
        displayMangaId = entityId + 10_000L,
        localMangaIds = setOf(entityId + 10_000L),
        title = title,
        altTitle = null,
        coverUrl = null,
        author = null,
        sourceName = "TEST",
        sourceGroupFlags = 0,
        sourceOriginFlags = 0,
        contentType = contentType,
        publicationState = null,
        isNsfw = false,
        rating = -1f,
        readingStatus = org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus.PLANNED,
        newChapters = newChapters,
        lastChapterDate = 0L,
        progressPercent = null,
        progressTotalChapters = null,
        lastReadAt = null,
        projectionCount = 1,
        projectionSourceNames = setOf("TEST"),
        tagIds = tagIds,
        displayTags = emptyList(),
        isDownloaded = false,
        hasBrokenProjection = false,
        overrideTitle = null,
        overrideCoverUrl = null,
        isPinned = false,
        createdAt = 100L - entityId,
        updatedAt = 100L - entityId,
    )

    private fun snapshotOf(vararg rows: FavouriteCardRow): FavouriteLibrarySnapshot {
        val rowsByEntityId = rows.associateBy { it.entityId }
        return FavouriteLibrarySnapshot(
            rowsByEntityId = rowsByEntityId,
            allEntityIds = rowsByEntityId.keys.sorted(),
            membershipsByCategory = mapOf(
                10L to rows.take(2).map { FavouriteMembership(it.entityId, 10L, false, 0, it.createdAt, it.updatedAt) },
                11L to rows.drop(2).map { FavouriteMembership(it.entityId, 11L, false, 0, it.createdAt, it.updatedAt) },
            ),
            quickFilterMetadata = FavouriteQuickFilterMetadata.Empty,
        )
    }

    @Test
    fun `loading and empty are distinct states`() {
        // Before the first snapshot: not initialized, therefore NOT empty
        val initial = FavouriteLibraryUiState()
        assertFalse(initial.isInitialized)
        assertFalse(initial.isEmpty)

        // An empty snapshot is initialized and empty
        val empty = buildFavouriteLibraryUiState(FavouriteLibrarySnapshot.Empty, FavouriteLibraryParams(), spacePolicy)
        assertTrue(empty.isInitialized)
        assertTrue(empty.isEmpty)

        // A populated snapshot is initialized and not empty
        val populated = buildFavouriteLibraryUiState(
            snapshotOf(row(1)),
            FavouriteLibraryParams(),
            spacePolicy,
        )
        assertTrue(populated.isInitialized)
        assertFalse(populated.isEmpty)
    }

    @Test
    fun `filter switch keeps the row map and only changes visible ids`() {
        val snapshot = snapshotOf(row(1), row(2), row(3, newChapters = 5))
        val before = buildFavouriteLibraryUiState(snapshot, FavouriteLibraryParams(), spacePolicy)
        assertEquals(3, before.totalCount)

        val filtered = buildFavouriteLibraryUiState(
            snapshot,
            FavouriteLibraryParams(filters = setOf(ListFilterOption.Macro.NEW_CHAPTERS)),
            spacePolicy,
        )

        // rows survive the filter: only the visible id list changes
        assertEquals(before.rowsByEntityId, filtered.rowsByEntityId)
        assertEquals(listOf(3L), filtered.visibleIdsByCategory[org.skepsun.kototoro.favourites.domain.library.FavouriteLibraryAllCategoryId])
        assertEquals(1, filtered.totalCount)
        // the library itself is not empty — the filter result is
        assertFalse(filtered.isEmpty)
    }

    @Test
    fun `category slices share the same row map`() {
        val snapshot = snapshotOf(row(1), row(2), row(3))
        val state = buildFavouriteLibraryUiState(snapshot, FavouriteLibraryParams(), spacePolicy)

        val all = state.visibleIdsByCategory.getValue(org.skepsun.kototoro.favourites.domain.library.FavouriteLibraryAllCategoryId)
        val cat10 = state.visibleIdsByCategory.getValue(10L)
        val cat11 = state.visibleIdsByCategory.getValue(11L)
        assertEquals(3, all.size)
        assertEquals(2, cat10.size)
        assertEquals(1, cat11.size)
        assertTrue(cat10.all { it in state.rowsByEntityId })
        assertTrue(cat11.all { it in state.rowsByEntityId })
        // the synthetic All slice (-1) is part of the counts, matching the tab badges
        assertEquals(mapOf(-1L to 3, 10L to 2, 11L to 1), state.categoryCounts)
    }

    @Test
    fun `per category order overrides the default and the all slice uses the default`() {
        val snapshot = snapshotOf(row(1, title = "B"), row(2, title = "A"), row(3, title = "C"))
        val state = buildFavouriteLibraryUiState(
            snapshot,
            FavouriteLibraryParams(
                defaultOrder = ListSortOrder.NEWEST,
                ordersByCategory = mapOf(10L to ListSortOrder.ALPHABETIC),
            ),
            spacePolicy,
        )
        // cat 10 alphabetical: A(2) before B(1); all slice keeps NEWEST (createdAt desc:
        // row1 createdAt=99, row2=98, row3=97 -> 1,2,3)
        assertEquals(listOf(2L, 1L), state.visibleIdsByCategory.getValue(10L))
        assertEquals(listOf(1L, 2L, 3L), state.visibleIdsByCategory.getValue(org.skepsun.kototoro.favourites.domain.library.FavouriteLibraryAllCategoryId))
    }

    @Test
    fun `space policy and preset flow through the params`() {
        val snapshot = snapshotOf(
            row(1, contentType = ContentType.MANGA),
            row(2, contentType = ContentType.NOVEL),
            row(3, contentType = ContentType.MANGA, title = "Preset miss"),
        )
        // space: only MANGA types
        val spaced = buildFavouriteLibraryUiState(
            snapshot,
            FavouriteLibraryParams(spaceId = SpaceId("manga-space")),
            spacePolicy,
        )
        assertEquals(setOf(1L, 3L), spaced.visibleIdsByCategory.getValue(org.skepsun.kototoro.favourites.domain.library.FavouriteLibraryAllCategoryId).toSet())

        // preset: all rows display from TEST, so a TEST preset keeps everything visible
        val preset = org.skepsun.kototoro.explore.data.SourcePreset(
            id = 1L,
            title = "Test preset",
            languages = emptySet(),
            sources = setOf("TEST"),
            createdAt = 0L,
            sortKey = 0,
        )
        val presetState = buildFavouriteLibraryUiState(
            snapshot,
            FavouriteLibraryParams(preset = preset),
            spacePolicy,
        )
        assertEquals(3, presetState.totalCount)
    }

    @Test
    fun `tag filter uses the deterministic tag entity id`() {
        val dramaTagId = "drama_TEST".longHashCode()
        val snapshot = snapshotOf(row(1, tagIds = setOf(dramaTagId)), row(2))
        val state = buildFavouriteLibraryUiState(
            snapshot,
            FavouriteLibraryParams(
                filters = setOf(
                    ListFilterOption.Tag(ContentTag(title = "Drama", key = "drama", source = TestContentSource)),
                ),
            ),
            spacePolicy,
        )
        assertEquals(listOf(1L), state.visibleIdsByCategory.getValue(org.skepsun.kototoro.favourites.domain.library.FavouriteLibraryAllCategoryId))
    }
}
