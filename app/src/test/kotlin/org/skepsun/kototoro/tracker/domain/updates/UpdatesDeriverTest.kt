package org.skepsun.kototoro.tracker.domain.updates

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.longHashCode

/**
 * Pure-function tests for the in-memory updates derivation
 * (history-updates-feed komikku-alignment plan, Phase U3).
 */
class UpdatesDeriverTest {

    private val dramaTagId = "drama_TEST".longHashCode()

    private fun group(
        uiId: Long,
        entityId: Long? = uiId,
        lastChapterDate: Long? = uiId * 10L,
        totalNewChapters: Int = 2,
        isNsfw: Boolean = false,
        tags: List<UpdateCardTag> = emptyList(),
        categoryIds: Set<Long> = emptySet(),
        sourceGroupFlags: Int = 1,
        sourceOriginFlags: Int = 1,
    ) = UpdateGroupRow(
        uiId = uiId,
        entityId = entityId,
        preferredLocalMangaId = null,
        mangaIds = listOf(uiId + 1000L),
        totalNewChapters = totalNewChapters,
        lastChapterDate = lastChapterDate,
        isPinned = false,
        categoryIds = categoryIds,
        displayMangaId = uiId + 1000L,
        title = "Group $uiId",
        altTitle = null,
        coverUrl = null,
        author = null,
        sourceName = "TEST",
        contentType = ContentType.MANGA,
        publicationState = null,
        isNsfw = isNsfw,
        rating = -1f,
        tags = tags,
        overrideTitle = null,
        overrideCoverUrl = null,
        metadataTrackingService = null,
        metadataTrackingTitle = null,
        metadataTrackingCoverUrl = null,
        sourceGroupFlags = sourceGroupFlags,
        sourceOriginFlags = sourceOriginFlags,
        displayContentTypeOrdinal = ContentType.MANGA.ordinal,
    )

    private fun snapshot(groups: List<UpdateGroupRow>) = UpdatesSnapshot(groups = groups)

    private fun input(
        groups: List<UpdateGroupRow>,
        filters: Set<ListFilterOption> = emptySet(),
        excludedNsfw: Boolean = false,
        tagBlacklist: GlobalTagBlacklist = GlobalTagBlacklist(emptyList()),
        groupTab: BrowseGroupTab = BrowseGroupTab.All,
        sourceTags: Set<SourceTag> = emptySet(),
    ) = UpdatesDeriver.Input(
        snapshot = snapshot(groups),
        filters = filters,
        excludedNsfw = excludedNsfw,
        tagBlacklist = tagBlacklist,
        groupTab = groupTab,
        sourceTags = sourceTags,
    )

    @Test
    fun `orders by last chapter date descending`() {
        val groups = listOf(
            group(uiId = 1, lastChapterDate = 100),
            group(uiId = 2, lastChapterDate = 900),
            group(uiId = 3, lastChapterDate = 500),
        )

        val derived = UpdatesDeriver.derive(input(groups))

        assertEquals(listOf(2L, 3L, 1L), derived.visibleGroups.map { it.uiId })
    }

    @Test
    fun `new chapters break date ties descending`() {
        val groups = listOf(
            group(uiId = 1, lastChapterDate = 500, totalNewChapters = 1),
            group(uiId = 2, lastChapterDate = 500, totalNewChapters = 7),
        )

        val derived = UpdatesDeriver.derive(input(groups))

        assertEquals(listOf(2L, 1L), derived.visibleGroups.map { it.uiId })
    }

    @Test
    fun `null dates sort after dated groups`() {
        val groups = listOf(
            group(uiId = 1, lastChapterDate = null),
            group(uiId = 2, lastChapterDate = 10),
        )

        val derived = UpdatesDeriver.derive(input(groups))

        assertEquals(listOf(2L, 1L), derived.visibleGroups.map { it.uiId })
    }

    @Test
    fun `stable order keeps snapshot order on full ties`() {
        val groups = listOf(
            group(uiId = 1, lastChapterDate = 500, totalNewChapters = 3),
            group(uiId = 2, lastChapterDate = 500, totalNewChapters = 3),
        )

        val derived = UpdatesDeriver.derive(input(groups))

        assertEquals(listOf(1L, 2L), derived.visibleGroups.map { it.uiId })
    }

    @Test
    fun `nsfw exclusion hides nsfw groups`() {
        val groups = listOf(
            group(uiId = 1, isNsfw = true),
            group(uiId = 2, isNsfw = false),
        )

        val derived = UpdatesDeriver.derive(input(groups, excludedNsfw = true))

        assertEquals(listOf(2L), derived.visibleGroups.map { it.uiId })
        assertTrue(derived.hasActiveFilters)
    }

    @Test
    fun `tag blacklist hides groups with blacklisted tags`() {
        val groups = listOf(
            group(uiId = 1, tags = listOf(UpdateCardTag(dramaTagId, "Drama"))),
            group(uiId = 2, tags = listOf(UpdateCardTag(999L, "Comedy"))),
        )

        val derived = UpdatesDeriver.derive(input(groups, tagBlacklist = GlobalTagBlacklist(listOf("Drama"))))

        assertEquals(listOf(2L), derived.visibleGroups.map { it.uiId })
    }

    @Test
    fun `tag quick filter matches on tag identity`() {
        val groups = listOf(
            group(uiId = 1, tags = listOf(UpdateCardTag(dramaTagId, "Drama"))),
            group(uiId = 2, tags = listOf(UpdateCardTag(999L, "Comedy"))),
        )
        val tagOption = ListFilterOption.Tag(
            ContentTag(title = "Drama", key = "drama", source = TestSource),
        )

        val derived = UpdatesDeriver.derive(input(groups, filters = setOf(tagOption)))

        assertEquals(listOf(1L), derived.visibleGroups.map { it.uiId })
    }

    @Test
    fun `favourite quick filter matches groups in that category`() {
        val groups = listOf(
            group(uiId = 1, categoryIds = setOf(7L)),
            group(uiId = 2, categoryIds = setOf(8L)),
            group(uiId = 3, categoryIds = emptySet()),
        )
        val favouriteOption = ListFilterOption.Favorite(
            org.skepsun.kototoro.core.model.FavouriteCategory(
                id = 7L,
                title = "Reading",
                sortKey = 0,
                order = org.skepsun.kototoro.list.domain.ListSortOrder.NEWEST,
                createdAt = java.time.Instant.EPOCH,
                isTrackingEnabled = false,
                isVisibleInLibrary = true,
            ),
        )

        val derived = UpdatesDeriver.derive(input(groups, filters = setOf(favouriteOption)))

        assertEquals(listOf(1L), derived.visibleGroups.map { it.uiId })
    }

    @Test
    fun `empty snapshot yields empty derivation`() {
        val derived = UpdatesDeriver.derive(input(emptyList()))

        assertTrue(derived.visibleGroups.isEmpty())
        assertTrue(!derived.hasActiveFilters)
    }

    @Test
    fun `thousands of groups derive within budget`() {
        val groups = (1L..10_000L).map { group(uiId = it, lastChapterDate = (it * 7) % 10_000) }

        val startedAt = System.currentTimeMillis()
        val derived = UpdatesDeriver.derive(input(groups))
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals(10_000, derived.visibleGroups.size)
        assertTrue(elapsed < 1500, "10k groups derived in ${elapsed}ms")
    }

    private data object TestSource : ContentSource {
        override val name: String = "TEST"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }
}
