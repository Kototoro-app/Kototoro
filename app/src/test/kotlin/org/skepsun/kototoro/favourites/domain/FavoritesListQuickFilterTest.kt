package org.skepsun.kototoro.favourites.domain

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.favourites.domain.library.FavouriteCardRow
import org.skepsun.kototoro.favourites.domain.library.FavouriteFacetTag
import org.skepsun.kototoro.favourites.domain.library.FavouriteLibraryAllCategoryId
import org.skepsun.kototoro.favourites.domain.library.FavouriteQuickFilterMetadata
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.favourites.ui.container.FavouriteLibraryUiState

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesListQuickFilterTest {

    private val tagId = "drama_TEST".longHashCode()

    private fun tag(id: Long) = FavouriteFacetTag(
        tagId = id,
        title = "Drama",
        key = "drama_TEST",
        source = "TEST",
    )

    private fun row(entityId: Long) = FavouriteCardRow(
        entityId = entityId,
        displayMangaId = 10_000L + entityId,
        localMangaIds = setOf(10_000L + entityId),
        title = "Work $entityId",
        altTitle = null,
        coverUrl = null,
        author = null,
        // Blank on purpose: a source chip would build a favicon Uri, which the JVM
        // test classpath cannot construct. Tag chips are pure data and cover the
        // "full set, not just macro" assertion.
        sourceName = "",
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
        projectionSourceNames = setOf("TEST"),
        tagIds = setOf(tagId),
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

    private fun settings() = mockk<AppSettings>(relaxed = true) {
        every { isFavouritesExcludeNsfw } returns false
        every { isTrackerEnabled } returns true
        every { isQuickFilterEnabled } returns true
    }

    @Test
    fun `all-favourites options wait for the initialized snapshot instead of caching macro-only`() = runTest {
        val libraryState = MutableStateFlow(FavouriteLibraryUiState()) // isInitialized = false
        val filter = FavoritesListQuickFilter(
            categoryId = FavouriteLibraryAllCategoryId,
            libraryState = libraryState.asStateFlow(),
            settings = settings(),
            globalFilterState = GlobalFavoritesState(settings()),
        )

        var result: org.skepsun.kototoro.list.ui.model.QuickFilter? = null
        val job = launch { result = filter.filterItem(emptySet()) }
        runCurrent()
        // The snapshot has not arrived yet: the first (permanently cached) evaluation
        // must suspend rather than return the macro-only set.
        assertNull(result)

        libraryState.value = FavouriteLibraryUiState(
            isInitialized = true,
            rowsByEntityId = mapOf(1L to row(1)),
            membershipsByCategory = emptyMap(),
            allEntityIds = listOf(1L),
            quickFilterMetadata = FavouriteQuickFilterMetadata(
                tags = listOf(tag(tagId)),
                tagEntityCounts = mapOf(tagId to 1),
                sources = emptyList(),
                sourceEntityCounts = emptyMap(),
            ),
        )
        runCurrent()
        assertNotNull(result)
        // The full chip set, not just the static macro options (groups carry the
        // reading-state chips; items carry tags and sources).
        val data = result!!.items.mapNotNull { it.data as? ListFilterOption }
        // The full chip set, not just the static macro options.
        assertTrue(data.any { it is ListFilterOption.Tag })
        job.cancel()
    }

    @Test
    fun `options are computed immediately when the snapshot is already initialized`() = runTest {
        val libraryState = MutableStateFlow(
            FavouriteLibraryUiState(
                isInitialized = true,
                rowsByEntityId = mapOf(1L to row(1)),
                membershipsByCategory = emptyMap(),
                allEntityIds = listOf(1L),
                quickFilterMetadata = FavouriteQuickFilterMetadata(
                    tags = listOf(tag(tagId)),
                    tagEntityCounts = mapOf(tagId to 1),
                    sources = emptyList(),
                    sourceEntityCounts = emptyMap(),
                ),
            ),
        )
        val filter = FavoritesListQuickFilter(
            categoryId = FavouriteLibraryAllCategoryId,
            libraryState = libraryState.asStateFlow(),
            settings = settings(),
            globalFilterState = GlobalFavoritesState(settings()),
        )

        val result = filter.filterItem(emptySet())
        assertNotNull(result)
        val data = result!!.items.mapNotNull { it.data as? ListFilterOption }
        assertTrue(data.any { it is ListFilterOption.Tag })
    }
}
