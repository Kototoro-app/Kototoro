package org.skepsun.kototoro.list.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.ListFilterCriteria
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.model.TagBlacklist
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.history.domain.library.HistoryBinding
import org.skepsun.kototoro.history.domain.library.HistoryCardEntry
import org.skepsun.kototoro.history.domain.library.HistoryCardTag
import org.skepsun.kototoro.history.domain.library.HistoryLibraryDeriver
import org.skepsun.kototoro.history.domain.library.HistorySnapshot
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.tracker.domain.feed.FeedCardRow
import org.skepsun.kototoro.tracker.domain.feed.FeedDeriver
import org.skepsun.kototoro.tracker.domain.feed.FeedSnapshot
import org.skepsun.kototoro.tracker.domain.feed.FeedUpdateRow
import org.skepsun.kototoro.tracker.domain.updates.UpdateCardTag
import org.skepsun.kototoro.tracker.domain.updates.UpdateGroupRow
import org.skepsun.kototoro.tracker.domain.updates.UpdatesDeriver
import org.skepsun.kototoro.tracker.domain.updates.UpdatesSnapshot

/** Rules invoked with shared value types only, without Android resources or taxonomy services. */
class SharedListDerivationTest {

    @Test
    fun `history tag matching uses title and key rather than tag id`() {
        val snapshot = HistorySnapshot(listOf(
            historyRow(1, tags = listOf(HistoryCardTag("Drama", "drama"))),
            historyRow(2, tags = listOf(HistoryCardTag("Drama", "other"))),
        ))
        val result = HistoryLibraryDeriver.derive(HistoryLibraryDeriver.Input(
            snapshot = snapshot,
            filters = setOf(ListFilterCriteria.Tag(999, title = "Drama", key = "drama")),
        ))
        assertEquals(listOf(1L), result.visibleRows.map { it.entityId })
        assertTrue(result.hasActiveFilters)
        val missingIdentity = HistoryLibraryDeriver.derive(HistoryLibraryDeriver.Input(
            snapshot = snapshot, filters = setOf(ListFilterCriteria.Tag(999)),
        ))
        assertTrue(missingIdentity.visibleRows.isEmpty())
    }

    @Test
    fun `history page search narrows rows by title and tags and counts as a filter`() {
        val snapshot = HistorySnapshot(listOf(
            historyRow(1, title = "Frieren"),
            historyRow(2, title = "Dungeon Meshi", tags = listOf(HistoryCardTag("Fantasy", "fantasy"))),
            historyRow(3, title = "Blue Period"),
        ))

        val byTitle = HistoryLibraryDeriver.derive(HistoryLibraryDeriver.Input(snapshot = snapshot, query = "frie"))
        val byTag = HistoryLibraryDeriver.derive(HistoryLibraryDeriver.Input(snapshot = snapshot, query = "fantasy"))
        val blank = HistoryLibraryDeriver.derive(HistoryLibraryDeriver.Input(snapshot = snapshot, query = " "))

        assertEquals(listOf(1L), byTitle.visibleRows.map { it.entityId })
        assertTrue(byTitle.hasActiveFilters)
        assertEquals(listOf(2L), byTag.visibleRows.map { it.entityId })
        assertEquals(3, blank.visibleRows.size)
    }

    @Test
    fun `history completion retains its threshold and entity id tie breaker`() {
        val result = HistoryLibraryDeriver.derive(HistoryLibraryDeriver.Input(
            snapshot = HistorySnapshot(listOf(
                historyRow(3, percent = 0.99998f, updatedAt = 500),
                historyRow(2, percent = 1f, updatedAt = 500),
                historyRow(1, percent = 0.99999f, updatedAt = 500),
            )),
            filters = setOf(ListFilterCriteria.Macro.COMPLETED),
        ))
        assertEquals(listOf(1L, 2L), result.visibleRows.map { it.entityId })
        assertFalse(isReadingCompleted(Float.NaN))
    }

    @Test
    fun `updates filters match tag ids and preserve snapshot order on ties`() {
        val result = UpdatesDeriver.derive(UpdatesDeriver.Input(
            snapshot = UpdatesSnapshot(listOf(
                updateGroup(2, lastChapterDate = 50, tags = listOf(UpdateCardTag(7, "Renamed"))),
                updateGroup(1, lastChapterDate = 50, tags = listOf(UpdateCardTag(7, "Drama"))),
                updateGroup(3, lastChapterDate = 50, tags = listOf(UpdateCardTag(8, "Drama"))),
            )),
            filters = setOf(ListFilterCriteria.Tag(7, title = "Ignored", key = "ignored")),
        ))
        assertEquals(listOf(2L, 1L), result.visibleGroups.map { it.uiId })
    }

    @Test
    fun `all three lists consume a platform supplied blacklist matcher`() {
        val blacklist = TagBlacklist { it == "Blocked" }
        val history = HistoryLibraryDeriver.derive(HistoryLibraryDeriver.Input(
            snapshot = HistorySnapshot(listOf(historyRow(1, tags = listOf(HistoryCardTag("Blocked", "blocked"))))),
            tagBlacklist = blacklist,
        ))
        val updates = UpdatesDeriver.derive(UpdatesDeriver.Input(
            snapshot = UpdatesSnapshot(listOf(updateGroup(1, tags = listOf(UpdateCardTag(7, "Blocked"))))),
            tagBlacklist = blacklist,
        ))
        val feed = FeedDeriver.derive(FeedDeriver.Input(
            snapshot = FeedSnapshot(listOf(feedRow(1, tagTitles = listOf("Blocked"))), emptyMap()),
            tagBlacklist = blacklist,
        ))
        assertTrue(history.visibleRows.isEmpty())
        assertTrue(updates.visibleGroups.isEmpty())
        assertTrue(feed.visibleRows.isEmpty())
        // The blacklist alone does not change the existing empty-state filter indicator.
        assertFalse(history.hasActiveFilters)
        assertFalse(updates.hasActiveFilters)
        assertFalse(feed.hasActiveFilters)
    }

    @Test
    fun `updates and feed page search match text before limiting`() {
        val updates = UpdatesDeriver.derive(UpdatesDeriver.Input(
            snapshot = UpdatesSnapshot(listOf(
                updateGroup(1),
                updateGroup(2, tags = listOf(UpdateCardTag(7, "Isekai"))),
            )),
            query = "isekai",
        ))
        val feed = FeedDeriver.derive(FeedDeriver.Input(
            snapshot = FeedSnapshot(listOf(
                feedRow(1, title = "Frieren", createdAt = 100),
                feedRow(2, title = "Blue Period", createdAt = 900),
            ), emptyMap()),
            query = "frieren",
            feedLimit = 1,
        ))

        assertEquals(listOf(2L), updates.visibleGroups.map { it.uiId })
        assertTrue(updates.hasActiveFilters)
        assertEquals(listOf(1L), feed.visibleRows.map { it.logId })
        assertTrue(feed.hasActiveFilters)
    }

    @Test
    fun `feed joins category lookup keys before sorting and limiting`() {
        val result = FeedDeriver.derive(FeedDeriver.Input(
            snapshot = FeedSnapshot(listOf(
                feedRow(1, createdAt = 900),
                feedRow(2, createdAt = 100, isPinned = true),
                feedRow(3, createdAt = 800),
            ), emptyMap()),
            filters = setOf(ListFilterCriteria.Favorite(7)),
            mangaCategoryIdsByFeedKey = mapOf(
                "entity:1" to setOf(7L),
                "manga:1002" to setOf(7L),
                "TEST|https://example.com/3" to setOf(8L),
            ),
            feedLimit = 1,
        ))
        assertEquals(listOf(2L), result.visibleRows.map { it.logId })
    }

    @Test
    fun `feed show all suppresses duplicate owners and emits synthetic identity`() {
        val result = FeedDeriver.derive(FeedDeriver.Input(
            snapshot = FeedSnapshot(
                rows = listOf(feedRow(1, ownerId = 10)),
                updateRowsByOwnerId = mapOf(10L to pendingUpdate(10), 20L to pendingUpdate(20)),
            ),
            showAllUpdates = true,
        ))
        assertEquals(listOf(-20L, 1L), result.visibleRows.map { it.logId })
        assertEquals(2, result.visibleRows.first().chapters.size)
        assertEquals(20L, result.visibleRows.first().ownerId)
    }

    @Test
    fun `source flags and persisted history type remain independent`() {
        val row = historyRow(1, contentType = ContentType.NOVEL)
        val result = HistoryLibraryDeriver.derive(HistoryLibraryDeriver.Input(
            snapshot = HistorySnapshot(listOf(row)),
            groupTab = BrowseGroupTab.Novel,
            sourceTags = setOf(SourceTag.BUILTIN),
        ))
        assertEquals(listOf(1L), result.visibleRows.map { it.entityId })
        assertEquals(5, OriginGroup.MIHON.ordinal)
        assertEquals(3, ContentGroup.HENTAI_MANGA.ordinal)
        assertEquals(BrowseGroupTab.All, BrowseGroupTab.fromId("json"))
        assertEquals(setOf(SourceTag.LEGADO), SourceTag.fromIds(listOf("json")))
    }

    @Test
    fun `sort persistence and iteration retain legacy enum ordering`() {
        assertEquals(ListSortOrder.PROGRESS, ListSortOrder("PROGRESS", ListSortOrder.NEWEST))
        assertEquals(ListSortOrder.NEWEST, ListSortOrder("unknown", ListSortOrder.NEWEST))
        assertEquals(ListSortOrder.HISTORY.sortedBy { it.ordinal }, ListSortOrder.HISTORY.toList())
        assertEquals(ListSortOrder.FAVORITES.sortedBy { it.ordinal }, ListSortOrder.FAVORITES.toList())
        assertFalse(ListSortOrder.MANUAL in ListSortOrder.favourites(-1))
        assertTrue(ListSortOrder.MANUAL in ListSortOrder.favourites(1))
    }

    private fun historyRow(
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

    private fun updateGroup(
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

    private fun feedRow(
        logId: Long,
        title: String = "Log $logId",
        anchorMangaId: Long = logId + 1000L,
        ownerId: Long = logId,
        entityId: Long? = logId,
        createdAt: Long = logId * 10L,
        unread: Boolean = true,
        isPinned: Boolean = false,
        sourceName: String = "TEST",
        displayUrl: String = "https://example.com/$logId",
        isNsfw: Boolean = false,
        tagIds: Set<Long> = emptySet(),
        tagTitles: List<String> = emptyList(),
        displayMangaId: Long? = logId + 1000L,
    ) = FeedCardRow(
        logId = logId,
        anchorMangaId = anchorMangaId,
        ownerId = ownerId,
        entityId = entityId,
        preferredLocalMangaId = null,
        chapters = listOf("New chapters"),
        createdAt = createdAt,
        unread = unread,
        isPinned = isPinned,
        displayMangaId = displayMangaId,
        title = title,
        altTitle = null,
        coverUrl = null,
        author = null,
        sourceName = sourceName,
        displayUrl = displayUrl,
        contentType = ContentType.MANGA,
        publicationState = null,
        isNsfw = isNsfw,
        rating = -1f,
        tagIds = tagIds,
        tagTitles = tagTitles,
        overrideTitle = null,
        overrideCoverUrl = null,
        sourceGroupFlags = 1 shl ContentGroup.MANGA.ordinal,
        sourceOriginFlags = 1 shl OriginGroup.NATIVE.ordinal,
    )

    private fun pendingUpdate(
        ownerId: Long,
        mangaId: Long = ownerId,
        entityId: Long? = ownerId,
        newChapters: Int = 2,
        lastChapterDate: Long = ownerId * 10L,
        lastCheckTime: Long = ownerId,
        isPinned: Boolean = false,
        sourceName: String = "TEST",
        isNsfw: Boolean = false,
        title: String = "Update $ownerId",
        displayMangaId: Long? = ownerId + 1000L,
    ) = FeedUpdateRow(
        ownerId = ownerId,
        mangaId = mangaId,
        entityId = entityId,
        preferredLocalMangaId = null,
        newChapters = newChapters,
        lastChapterDate = lastChapterDate,
        lastCheckTime = lastCheckTime,
        lastChapterId = 42L,
        isPinned = isPinned,
        displayMangaId = displayMangaId,
        title = title,
        coverUrl = null,
        sourceName = sourceName,
        isNsfw = isNsfw,
    )
}
