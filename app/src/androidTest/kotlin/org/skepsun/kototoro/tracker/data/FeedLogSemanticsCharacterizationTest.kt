package org.skepsun.kototoro.tracker.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.favourites.data.FavouriteLibrarySeed
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.longHashCode

/**
 * Phase F0 of the history/updates/feed komikku-alignment plan
 * (docs/architecture/history-updates-feed-komikku-alignment-plan-2026-09.md).
 *
 * Characterizes the legacy feed paging chain at the DAO boundary:
 * `TrackLogsDao.pagingAll` is the feed's primary source (showAllUpdates = false),
 * so its ordering, window and SQL-level filter semantics must survive the migration
 * to a narrow read projection + in-memory derivation untouched.
 */
@RunWith(AndroidJUnit4::class)
class FeedLogSemanticsCharacterizationTest {

    private lateinit var db: MangaDatabase
    private lateinit var sql: SupportSQLiteDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
        sql = db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertLog(
        mangaId: Long,
        entityId: Long?,
        createdAt: Long,
        unread: Boolean = true,
        ownerId: Long = entityId ?: -mangaId,
    ) {
        sql.execSQL(
            "INSERT INTO track_logs(owner_id, manga_id, entity_id, chapters, created_at, unread) " +
                "VALUES (?, ?, ?, 'New chapters', ?, ?)",
            arrayOf<Any?>(ownerId, mangaId, entityId, createdAt, if (unread) 1 else 0),
        )
    }

    private suspend fun pageIds(
        limit: Int = 100,
        filters: Set<ListFilterOption> = emptySet(),
    ): List<Long> {
        val source = db.getTrackLogsDao().pagingAll(limit, filters)
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = limit, placeholdersEnabled = false),
        )
        @Suppress("UNCHECKED_CAST")
        val page = result as PagingSource.LoadResult.Page<Int, TrackLogEntity>
        return page.data.map { it.mangaId }
    }

    @Test
    fun ordersPinnedFirstThenCreatedAtThenIdDescending() = runTest {
        // Entity 10 is pinned via a favourite; its log is the oldest of the three.
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Pinned work")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertFavourite(sql, 10, 1, anchorMangaId = 101, pinned = true)
        FavouriteLibrarySeed.insertCategory(sql, 1, "Reading")
        insertLog(mangaId = 101, entityId = 10, createdAt = 100)

        // Entity 20 is favourited but not pinned: pinned wins even though the log is newer.
        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 201, "Unpinned work")
        FavouriteLibrarySeed.insertBinding(sql, 20, 201)
        FavouriteLibrarySeed.insertFavourite(sql, 20, 1, anchorMangaId = 201)
        insertLog(mangaId = 201, entityId = 20, createdAt = 300)

        // Unbound log: no entity at all, newest timestamp.
        FavouriteLibrarySeed.insertManga(sql, 999, "Unbound work")
        insertLog(mangaId = 999, entityId = null, createdAt = 500)

        assertEquals(
            listOf(101L, 999L, 201L),
            pageIds(),
        )
    }

    @Test
    fun idBreaksTiesWhenCreatedAtMatches() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 50, "A")
        FavouriteLibrarySeed.insertManga(sql, 60, "B")
        insertLog(mangaId = 50, entityId = null, createdAt = 400)
        insertLog(mangaId = 60, entityId = null, createdAt = 400)

        // Higher track_logs.id first: the later-inserted row wins the tie.
        assertEquals(listOf(60L, 50L), pageIds())
    }

    @Test
    fun limitBoundsTheWindowToTheNewestRows() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 10, "A")
        FavouriteLibrarySeed.insertManga(sql, 20, "B")
        FavouriteLibrarySeed.insertManga(sql, 30, "C")
        insertLog(mangaId = 10, entityId = null, createdAt = 100)
        insertLog(mangaId = 20, entityId = null, createdAt = 200)
        insertLog(mangaId = 30, entityId = null, createdAt = 300)

        assertEquals(listOf(30L, 20L), pageIds(limit = 2))
    }

    @Test
    fun entityIdColumnTakesPrecedenceOverBindingLookupForPinning() = runTest {
        // The log's entity_id column (20, favourited+pinned) does not match the manga's
        // binding (entity 10, not favourited): the COALESCE must resolve to 20.
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 101, "Bound to 10")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertManga(sql, 102, "Stated 20")
        FavouriteLibrarySeed.insertFavourite(sql, 20, 1, anchorMangaId = 102, pinned = true)
        FavouriteLibrarySeed.insertCategory(sql, 1, "Reading")
        insertLog(mangaId = 102, entityId = 20, createdAt = 100)
        insertLog(mangaId = 101, entityId = null, createdAt = 900)

        assertEquals(listOf(102L, 101L), pageIds())
    }

    @Test
    fun favouriteFilterKeepsOnlyFavouritedEntitiesWithAnAnchor() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 1, "Reading")
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Favourited")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertFavourite(sql, 10, 1, anchorMangaId = 101)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100)

        // Favourited but anchor is NULL: favouriteExistsExpr requires a non-null anchor.
        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 201, "No anchor")
        FavouriteLibrarySeed.insertBinding(sql, 20, 201)
        FavouriteLibrarySeed.insertFavourite(sql, 20, 1, anchorMangaId = null)
        insertLog(mangaId = 201, entityId = 20, createdAt = 200)

        // No entity, no favourite.
        FavouriteLibrarySeed.insertManga(sql, 301, "Unbound")
        insertLog(mangaId = 301, entityId = null, createdAt = 300)

        assertEquals(listOf(101L), pageIds(filters = setOf(ListFilterOption.Macro.FAVORITE)))
    }

    @Test
    fun categoryFilterScopesToTheSelectedFavouriteCategory() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 1, "Reading")
        FavouriteLibrarySeed.insertCategory(sql, 2, "Plan to read")
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 101, "In Reading")
        FavouriteLibrarySeed.insertManga(sql, 201, "In Plan to read")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertBinding(sql, 20, 201)
        FavouriteLibrarySeed.insertFavourite(sql, 10, 1, anchorMangaId = 101)
        FavouriteLibrarySeed.insertFavourite(sql, 20, 2, anchorMangaId = 201)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100)
        insertLog(mangaId = 201, entityId = 20, createdAt = 200)

        val readingOnly = pageIds(
            filters = setOf(
                ListFilterOption.Favorite(
                    org.skepsun.kototoro.core.model.FavouriteCategory(
                        id = 1L,
                        title = "Reading",
                        sortKey = 0,
                        order = ListSortOrder.NEWEST,
                        createdAt = java.time.Instant.EPOCH,
                        isTrackingEnabled = false,
                        isVisibleInLibrary = true,
                    ),
                ),
            ),
        )
        assertEquals(listOf(101L), readingOnly)
    }

    @Test
    fun nsfwFilterEvaluatesTheRepresentativeLocalManga() = runTest {
        // Anchor manga is SFW, the entity's preferred local projection is NSFW: the
        // representative (preferred) decides, so the NSFW filter keeps the row.
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor", nsfw = false)
        FavouriteLibrarySeed.insertManga(sql, 105, "Preferred", nsfw = true)
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(sql, 10, preferredLocalMangaId = 105)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100)

        // Entity 20: anchor NSFW but preferred SFW -> excluded by the NSFW filter.
        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 201, "Anchor", nsfw = true)
        FavouriteLibrarySeed.insertManga(sql, 205, "Preferred", nsfw = false)
        FavouriteLibrarySeed.insertBinding(sql, 20, 201)
        FavouriteLibrarySeed.insertPrefs(sql, 20, preferredLocalMangaId = 205)
        insertLog(mangaId = 201, entityId = 20, createdAt = 200)

        assertEquals(listOf(101L), pageIds(filters = setOf(ListFilterOption.Macro.NSFW)))
    }

    @Test
    fun tagFilterMatchesOnTheRepresentativeLocalManga() = runTest {
        val dramaTagId = "drama_TEST".longHashCode()
        FavouriteLibrarySeed.insertTag(sql, dramaTagId, "Drama")

        // The tag hangs on the preferred projection, not on the logged anchor.
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertManga(sql, 105, "Preferred")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(sql, 10, preferredLocalMangaId = 105)
        FavouriteLibrarySeed.insertMangaTag(sql, 105, dramaTagId)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100)

        // Entity 20 carries the tag on the anchor but has a preferred projection
        // without it: the representative decides, so this row is filtered out.
        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 201, "Anchor")
        FavouriteLibrarySeed.insertManga(sql, 205, "Preferred")
        FavouriteLibrarySeed.insertBinding(sql, 20, 201)
        FavouriteLibrarySeed.insertPrefs(sql, 20, preferredLocalMangaId = 205)
        FavouriteLibrarySeed.insertMangaTag(sql, 201, dramaTagId)
        insertLog(mangaId = 201, entityId = 20, createdAt = 200)

        val tagOption = ListFilterOption.Tag(
            ContentTag(title = "Drama", key = "drama", source = TestSource),
        )
        assertEquals(listOf(101L), pageIds(filters = setOf(tagOption)))
        assertTrue(tagOption.tagId == dramaTagId)
    }

    private data object TestSource : ContentSource {
        override val name: String = "TEST"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }
}
