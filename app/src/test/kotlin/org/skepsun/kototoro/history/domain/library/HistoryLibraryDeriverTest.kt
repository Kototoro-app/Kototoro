package org.skepsun.kototoro.history.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.history.domain.buildHistorySnapshotFilterOptions
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Pure-function tests for the in-memory history derivation
 * (history-updates-feed komikku-alignment plan, Phase H0/H3: the 10 SQL sort
 * orders re-pinned in memory, the SQL filter re-play, the space/tab filter).
 */
class HistoryLibraryDeriverTest {

    @Test
    fun `quick filter metadata is derived from the loaded snapshot`() {
        val options = buildHistorySnapshotFilterOptions(
            listOf(
                row(entityId = 1, tags = listOf(HistoryCardTag("Drama", "drama"))),
                row(
                    entityId = 2,
                    tags = listOf(HistoryCardTag("Drama", "drama"), HistoryCardTag("Action", "action")),
                ),
                row(
                    entityId = 3,
                    sourceName = "LOCAL",
                    tags = listOf(HistoryCardTag("Offline", "offline")),
                ),
            ),
        )

        val tags = options.filterIsInstance<ListFilterOption.Tag>()
        val sources = options.filterIsInstance<ListFilterOption.Source>()
        assertEquals(listOf("Drama", "Action", "Offline"), tags.map { it.tag.title })
        assertEquals(listOf("TEST", "LOCAL"), sources.map { it.mangaSource.name })
    }

    private fun row(
        entityId: Long,
        updatedAt: Long = entityId * 10L,
        createdAt: Long = entityId,
        percent: Float = 0.5f,
        newChapters: Int = 0,
        lastChapterDate: Long? = null,
        title: String = "Work $entityId",
        isNsfw: Boolean = false,
        isFavourite: Boolean = false,
        isDownloaded: Boolean = false,
        categoryIds: Set<Long> = emptySet(),
        contentType: ContentType? = ContentType.MANGA,
        tags: List<HistoryCardTag> = emptyList(),
        bindings: List<HistoryBinding> = emptyList(),
        localMangaIds: List<Long> = listOf(entityId + 1000L),
        sourceName: String = "TEST",
    ) = HistoryCardEntry(
        uiId = -((entityId shl 8) or ((contentType ?: ContentType.MANGA).ordinal + 1).toLong()),
        entityId = entityId,
        anchorMangaId = entityId + 1000L,
        preferredLocalMangaId = localMangaIds.firstOrNull(),
        displayMangaId = localMangaIds.firstOrNull(),
        updatedAt = updatedAt,
        createdAt = createdAt,
        percent = percent,
        chaptersCount = 0,
        chapterId = 0,
        newChapters = newChapters,
        lastChapterDate = lastChapterDate,
        isFavourite = isFavourite,
        isPinned = false,
        isDownloaded = isDownloaded,
        categoryIds = categoryIds,
        contentType = contentType,
        displayContentTypeOrdinal = (contentType ?: ContentType.MANGA).ordinal,
        localMangaIds = localMangaIds,
        bindings = bindings,
        title = title,
        altTitle = null,
        coverUrl = null,
        largeCoverUrl = null,
        author = null,
        sourceName = sourceName,
        publicationState = null,
        isNsfw = isNsfw,
        rating = -1f,
        tags = tags,
        overrideTitle = null,
        overrideCoverUrl = null,
        metadataTrackingService = null,
        metadataTrackingTitle = null,
        metadataTrackingCoverUrl = null,
        sourceGroupFlags = 1,
        sourceOriginFlags = 1,
    )

    private fun input(
        rows: List<HistoryCardEntry>,
        order: ListSortOrder = ListSortOrder.LAST_READ,
        filters: Set<ListFilterOption> = emptySet(),
        excludedNsfw: Boolean = false,
        tagBlacklist: GlobalTagBlacklist = GlobalTagBlacklist(emptyList()),
        groupTab: BrowseGroupTab = BrowseGroupTab.All,
        sourceTags: Set<org.skepsun.kototoro.explore.ui.model.SourceTag> = emptySet(),
        presetSources: Set<String>? = null,
        space: HistoryLibraryDeriver.SpaceScope? = null,
    ) = HistoryLibraryDeriver.Input(
        snapshot = HistorySnapshot(rows = rows),
        order = order,
        filters = filters,
        excludedNsfw = excludedNsfw,
        tagBlacklist = tagBlacklist,
        groupTab = groupTab,
        sourceTags = sourceTags,
        presetSources = presetSources,
        space = space,
    )

    // ------------------------------------------------------------ sort orders

    @Test
    fun `last read orders by updated date descending`() {
        val rows = listOf(row(1, updatedAt = 100), row(2, updatedAt = 900), row(3, updatedAt = 500))

        val derived = HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.LAST_READ))

        assertEquals(listOf(2L, 3L, 1L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `long ago read orders by updated date ascending`() {
        val rows = listOf(row(1, updatedAt = 100), row(2, updatedAt = 900))

        val derived = HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.LONG_AGO_READ))

        assertEquals(listOf(1L, 2L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `newest and oldest order by created date`() {
        val rows = listOf(row(1, createdAt = 100), row(2, createdAt = 900))

        assertEquals(
            listOf(2L, 1L),
            HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.NEWEST)).visibleRows.map { it.entityId },
        )
        assertEquals(
            listOf(1L, 2L),
            HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.OLDEST)).visibleRows.map { it.entityId },
        )
    }

    @Test
    fun `progress and unread order by percent`() {
        val rows = listOf(row(1, percent = 0.9f), row(2, percent = 0.1f))

        assertEquals(
            listOf(1L, 2L),
            HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.PROGRESS)).visibleRows.map { it.entityId },
        )
        assertEquals(
            listOf(2L, 1L),
            HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.UNREAD)).visibleRows.map { it.entityId },
        )
    }

    @Test
    fun `new chapters orders by new chapters then last chapter date`() {
        val rows = listOf(
            row(1, newChapters = 2, lastChapterDate = 100),
            row(2, newChapters = 5, lastChapterDate = 50),
            row(3, newChapters = 5, lastChapterDate = 700),
        )

        val derived = HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.NEW_CHAPTERS))

        assertEquals(listOf(3L, 2L, 1L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `updated orders by last chapter date`() {
        val rows = listOf(
            row(1, lastChapterDate = 100),
            row(2, lastChapterDate = 900),
        )

        val derived = HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.UPDATED))

        assertEquals(listOf(2L, 1L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `alphabetic orders case-insensitively and reverses`() {
        val rows = listOf(row(1, title = "beta"), row(2, title = "Alpha"), row(3, title = "gamma"))

        assertEquals(
            listOf(2L, 1L, 3L),
            HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.ALPHABETIC))
                .visibleRows.map { it.entityId },
        )
        assertEquals(
            listOf(3L, 1L, 2L),
            HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.ALPHABETIC_REVERSE))
                .visibleRows.map { it.entityId },
        )
    }

    @Test
    fun `entity id breaks ties ascending`() {
        val rows = listOf(
            row(2, updatedAt = 500),
            row(1, updatedAt = 500),
        )

        val derived = HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.LAST_READ))

        assertEquals(listOf(1L, 2L), derived.visibleRows.map { it.entityId })
    }

    // ------------------------------------------------------------ filters

    @Test
    fun `completed macro matches completed progress`() {
        val rows = listOf(row(1, percent = 1f), row(2, percent = 0.4f))

        val derived = HistoryLibraryDeriver.derive(
            input(rows, filters = setOf(ListFilterOption.Macro.COMPLETED)),
        )

        assertEquals(listOf(1L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `new chapters macro needs pending chapters`() {
        val rows = listOf(row(1, newChapters = 3), row(2, newChapters = 0))

        val derived = HistoryLibraryDeriver.derive(
            input(rows, filters = setOf(ListFilterOption.Macro.NEW_CHAPTERS)),
        )

        assertEquals(listOf(1L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `favourite macros match on category membership`() {
        val rows = listOf(
            row(1, categoryIds = setOf(7L)),
            row(2, categoryIds = emptySet()),
        )

        assertEquals(
            listOf(1L),
            HistoryLibraryDeriver.derive(input(rows, filters = setOf(ListFilterOption.Macro.FAVORITE)))
                .visibleRows.map { it.entityId },
        )
        assertEquals(
            listOf(2L),
            HistoryLibraryDeriver.derive(input(rows, filters = setOf(ListFilterOption.NOT_FAVORITE)))
                .visibleRows.map { it.entityId },
        )
    }

    @Test
    fun `nsfw macro and inverted exclusion`() {
        val rows = listOf(row(1, isNsfw = true), row(2, isNsfw = false))

        assertEquals(
            listOf(1L),
            HistoryLibraryDeriver.derive(input(rows, filters = setOf(ListFilterOption.Macro.NSFW)))
                .visibleRows.map { it.entityId },
        )
        assertEquals(
            listOf(2L),
            HistoryLibraryDeriver.derive(input(rows, excludedNsfw = true)).visibleRows.map { it.entityId },
        )
    }

    @Test
    fun `tag filter matches on title and key`() {
        val rows = listOf(
            row(1, tags = listOf(HistoryCardTag("Drama", "drama_1"))),
            row(2, tags = listOf(HistoryCardTag("Drama", "drama_2"))),
        )
        val option = ListFilterOption.Tag(
            org.skepsun.kototoro.parsers.model.ContentTag(
                title = "Drama",
                key = "drama_2",
                source = org.skepsun.kototoro.core.model.ContentSource("TEST"),
            ),
        )

        val derived = HistoryLibraryDeriver.derive(input(rows, filters = setOf(option)))

        assertEquals(listOf(2L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `tag blacklist hides blacklisted titles`() {
        val rows = listOf(
            row(1, tags = listOf(HistoryCardTag("Drama", "d"))),
            row(2, tags = listOf(HistoryCardTag("Comedy", "c"))),
        )

        val derived = HistoryLibraryDeriver.derive(
            input(rows, tagBlacklist = GlobalTagBlacklist(listOf("Drama"))),
        )

        assertEquals(listOf(2L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `preset source filter constrains by source name`() {
        val rows = listOf(row(1, sourceName = "A"), row(2, sourceName = "B"))

        val derived = HistoryLibraryDeriver.derive(input(rows, presetSources = setOf("A")))

        assertEquals(listOf(1L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `space scope requires an allowed binding type and source`() {
        val rows = listOf(
            row(1, bindings = listOf(HistoryBinding(101L, "A", ContentType.NOVEL))),
            row(2, bindings = listOf(HistoryBinding(201L, "A", ContentType.MANGA))),
            row(3, bindings = listOf(HistoryBinding(301L, "B", ContentType.NOVEL))),
        )
        val space = HistoryLibraryDeriver.SpaceScope(
            allowedTypes = setOf(ContentType.NOVEL),
            classifiedTypes = setOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO),
            allowedSources = setOf("A"),
        )

        val derived = HistoryLibraryDeriver.derive(input(rows, space = space))

        // row 2: wrong type; row 3: type ok but source not allowed
        assertEquals(listOf(1L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `classified type outside the allowed set excludes the entity`() {
        val rows = listOf(
            row(1, bindings = listOf(HistoryBinding(101L, "A", ContentType.NOVEL))),
            row(
                2,
                bindings = listOf(
                    HistoryBinding(201L, "A", ContentType.NOVEL),
                    HistoryBinding(202L, "A", ContentType.VIDEO),
                ),
            ),
        )
        val space = HistoryLibraryDeriver.SpaceScope(
            allowedTypes = setOf(ContentType.NOVEL),
            classifiedTypes = setOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO),
            allowedSources = null,
        )

        val derived = HistoryLibraryDeriver.derive(input(rows, space = space))

        // entity 2 has a classified type (VIDEO) outside the allowed set
        assertEquals(listOf(1L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `type tab matches the authoritative entity type`() {
        val rows = listOf(
            row(1, contentType = ContentType.NOVEL),
            row(2, contentType = ContentType.MANGA),
        )

        val derived = HistoryLibraryDeriver.derive(
            input(rows, groupTab = BrowseGroupTab.Novel),
        )

        assertEquals(listOf(1L), derived.visibleRows.map { it.entityId })
    }

    @Test
    fun `empty snapshot yields empty derivation`() {
        val derived = HistoryLibraryDeriver.derive(input(emptyList()))

        assertTrue(derived.visibleRows.isEmpty())
        assertTrue(!derived.hasActiveFilters)
    }

    @Test
    fun `ten thousand rows derive within budget`() {
        val rows = (1L..10_000L).map { row(it, updatedAt = (it * 7) % 10_000) }

        val startedAt = System.currentTimeMillis()
        val derived = HistoryLibraryDeriver.derive(input(rows, order = ListSortOrder.LAST_READ))
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals(10_000, derived.visibleRows.size)
        assertTrue(elapsed < 1500, "10k rows derived in ${elapsed}ms")
    }

    @Test
    fun downloadedFilterKeepsOnlyRowsWithLocalDownloads() {
        val rows = listOf(
            row(entityId = 1, isDownloaded = true),
            row(entityId = 2, isDownloaded = false),
            row(entityId = 3, isDownloaded = true),
        )
        val derived = HistoryLibraryDeriver.derive(
            input(
                rows,
                order = ListSortOrder.ALPHABETIC,
                filters = setOf(ListFilterOption.Downloaded),
            ),
        )
        assertEquals(listOf(1L, 3L), derived.visibleRows.map { it.entityId })
    }
}
