package org.skepsun.kototoro.tracker.domain.feed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.longHashCode

/**
 * Pure-function tests for the in-memory feed derivation
 * (history-updates-feed komikku-alignment plan, Phase F3). Everything here
 * runs on plain data — the deriver has no I/O, no context and no singletons.
 */
class FeedDeriverTest {

    private val dramaTagId = "drama_TEST".longHashCode()

    private fun row(
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
        sourceGroupFlags = 1 shl groupOrdinal("TEST"),
        sourceOriginFlags = 1 shl originOrdinal("TEST"),
    )

    private fun snapshot(rows: List<FeedCardRow>, updates: List<FeedUpdateRow> = emptyList()) = FeedSnapshot(
        rows = rows,
        updateRowsByOwnerId = updates.associateBy { it.ownerId },
    )

    private fun input(
        rows: List<FeedCardRow>,
        updates: List<FeedUpdateRow> = emptyList(),
        showAllUpdates: Boolean = false,
        feedLimit: Int = 200,
        filters: Set<ListFilterOption> = emptySet(),
        excludedNsfw: Boolean = false,
        tagBlacklist: GlobalTagBlacklist = GlobalTagBlacklist(emptyList()),
        groupTab: BrowseGroupTab = BrowseGroupTab.All,
        presetSourceNames: Set<String>? = null,
        selectedCategoryId: Long? = null,
        mangaCategoryIdsByFeedKey: Map<String, Set<Long>> = emptyMap(),
    ) = FeedDeriver.Input(
        snapshot = snapshot(rows, updates),
        showAllUpdates = showAllUpdates,
        feedLimit = feedLimit,
        filters = filters,
        excludedNsfw = excludedNsfw,
        tagBlacklist = tagBlacklist,
        groupTab = groupTab,
        sourceTags = emptySet(),
        presetSourceNames = presetSourceNames,
        selectedCategoryId = selectedCategoryId,
        mangaCategoryIdsByFeedKey = mangaCategoryIdsByFeedKey,
    )

    private fun update(
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

    // The tests cannot reach SourceGroupManager; the flag ordinals are stable
    // ContentGroup/OriginGroup ordinals, so resolve them through the same enums
    // the deriver compares against.
    private fun groupOrdinal(sourceName: String): Int {
        val group = org.skepsun.kototoro.core.jsonsource.ContentGroup.entries.firstOrNull { it.name == "MANGA" }
        return group?.ordinal ?: 0
    }

    private fun originOrdinal(sourceName: String): Int {
        val origin = org.skepsun.kototoro.core.jsonsource.OriginGroup.entries.firstOrNull { it.name == "EN" }
        return origin?.ordinal ?: 0
    }

    @Test
    fun `orders pinned first then createdAt then logId descending`() {
        val rows = listOf(
            row(logId = 1, createdAt = 100, isPinned = false),
            row(logId = 2, createdAt = 900, isPinned = false),
            row(logId = 3, createdAt = 50, isPinned = true),
            row(logId = 4, createdAt = 700, isPinned = false),
        )

        val derived = FeedDeriver.derive(input(rows))

        assertEquals(listOf(3L, 2L, 4L, 1L), derived.visibleRows.map { it.logId })
    }

    @Test
    fun `logId breaks createdAt ties descending`() {
        val rows = listOf(
            row(logId = 1, createdAt = 500),
            row(logId = 2, createdAt = 500),
        )

        val derived = FeedDeriver.derive(input(rows))

        assertEquals(listOf(2L, 1L), derived.visibleRows.map { it.logId })
    }

    @Test
    fun `feedLimit bounds the window to the newest visible rows`() {
        val rows = (1L..5L).map { row(logId = it, createdAt = it * 10) }

        val derived = FeedDeriver.derive(input(rows, feedLimit = 2))

        assertEquals(listOf(5L, 4L), derived.visibleRows.map { it.logId })
    }

    @Test
    fun `nsfw exclusion hides nsfw rows`() {
        val rows = listOf(
            row(logId = 1, isNsfw = true),
            row(logId = 2, isNsfw = false),
        )

        val derived = FeedDeriver.derive(input(rows, excludedNsfw = true))

        assertEquals(listOf(2L), derived.visibleRows.map { it.logId })
        assertTrue(derived.hasActiveFilters)
    }

    @Test
    fun `tag blacklist hides rows whose tag title is blacklisted`() {
        val rows = listOf(
            row(logId = 1, tagTitles = listOf("Drama")),
            row(logId = 2, tagTitles = listOf("Comedy")),
        )

        val derived = FeedDeriver.derive(input(rows, tagBlacklist = GlobalTagBlacklist(listOf("Drama"))))

        assertEquals(listOf(2L), derived.visibleRows.map { it.logId })
    }

    @Test
    fun `preset scope keeps only the preset sources`() {
        val rows = listOf(
            row(logId = 1, sourceName = "TEST"),
            row(logId = 2, sourceName = "OTHER"),
        )

        val derived = FeedDeriver.derive(input(rows, presetSourceNames = setOf("TEST")))

        assertEquals(listOf(1L), derived.visibleRows.map { it.logId })
    }

    @Test
    fun `selected category scopes by the feed lookup key`() {
        val rows = listOf(
            row(logId = 1),
            row(logId = 2, displayUrl = "https://example.com/other"),
        )
        val categoryIds = mapOf(
            "TEST|https://example.com/1" to setOf(7L),
        )

        val derived = FeedDeriver.derive(input(rows, selectedCategoryId = 7L, mangaCategoryIdsByFeedKey = categoryIds))

        assertEquals(listOf(1L), derived.visibleRows.map { it.logId })
    }

    @Test
    fun `tag quick filter matches on tag identity`() {
        val rows = listOf(
            row(logId = 1, tagIds = setOf(dramaTagId)),
            row(logId = 2, tagIds = setOf(999L)),
        )
        val tagOption = ListFilterOption.Tag(
            ContentTag(title = "Drama", key = "drama", source = TestSource),
        )

        val derived = FeedDeriver.derive(input(rows, filters = setOf(tagOption)))

        assertEquals(listOf(1L), derived.visibleRows.map { it.logId })
    }

    @Test
    fun `showAll merges synthetic pending updates without logs`() {
        val logRow = row(logId = 1, ownerId = 10, createdAt = 500)
        val pending = update(ownerId = 20, lastChapterDate = 300)

        val derived = FeedDeriver.derive(input(listOf(logRow), listOf(pending), showAllUpdates = true))

        assertEquals(listOf(1L, -20L), derived.visibleRows.map { it.logId })
        // the synthetic row keeps the pending count as chapters
        assertEquals(2, derived.visibleRows[1].chapters.size)
    }

    @Test
    fun `showAll deduplicates log rows by owner id`() {
        val logRow = row(logId = 1, ownerId = 10, createdAt = 500)
        val pending = update(ownerId = 10, newChapters = 9)

        val derived = FeedDeriver.derive(input(listOf(logRow), listOf(pending), showAllUpdates = true))

        // the log row wins: no duplicate for owner 10
        assertEquals(listOf(1L), derived.visibleRows.map { it.logId })
    }

    @Test
    fun `synthetic showAll rows keep the pinned flag`() {
        val logRow = row(logId = 1, ownerId = 10, createdAt = 100)
        val pending = update(ownerId = 20, lastChapterDate = 900, isPinned = true)

        val derived = FeedDeriver.derive(input(listOf(logRow), listOf(pending), showAllUpdates = true))

        assertEquals(listOf(-20L, 1L), derived.visibleRows.map { it.logId })
    }

    @Test
    fun `empty input yields an empty derivation`() {
        val derived = FeedDeriver.derive(input(emptyList()))

        assertTrue(derived.visibleRows.isEmpty())
        assertTrue(!derived.hasActiveFilters)
    }

    @Test
    fun `thousands of rows derive within budget`() {
        val rows = (1L..10_000L).map { row(logId = it, createdAt = (it * 7) % 10_000) }

        val startedAt = System.currentTimeMillis()
        // a large feed limit: the window bound is under test, not the default
        val derived = FeedDeriver.derive(input(rows, feedLimit = Int.MAX_VALUE))
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals(10_000, derived.visibleRows.size)
        assertTrue(elapsed < 1500, "10k rows derived in ${elapsed}ms")
    }

    private data object TestSource : ContentSource {
        override val name: String = "TEST"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }
}
