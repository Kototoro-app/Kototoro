package org.skepsun.kototoro.core.db.migrations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.migrations.ProjectionOwnershipMigrationResolver.RawWorkFavourite
import org.skepsun.kototoro.core.db.migrations.ProjectionOwnershipMigrationResolver.RawWorkHistory
import org.skepsun.kototoro.core.db.migrations.ProjectionOwnershipMigrationResolver.ResolvedFavourite
import org.skepsun.kototoro.core.db.migrations.ProjectionOwnershipMigrationResolver.ResolvedHistory

class ProjectionOwnershipMigrationResolverTest {

    @Test
    fun `history owner resolves to anchor when anchor actually owns chapter`() {
        val raw = RawWorkHistory(
            entityId = 100L,
            anchorMangaId = 1L,
            createdAt = 1000L,
            updatedAt = 2000L,
            chapterId = 555L,
            page = 10,
            scroll = 0f,
            percent = 0.5f,
            deletedAt = 0L,
            chaptersCount = 20,
            parentChapterId = null,
        )

        val resolvedMangaId = ProjectionOwnershipMigrationResolver.resolveHistoryOwnerMangaId(
            history = raw,
            anchorOwnsChapter = true,
            candidates = listOf(1L, 2L),
            chapterOwnerLookup = { mId, cId -> mId == 1L && cId == 555L },
        )

        assertEquals(1L, resolvedMangaId)
    }

    @Test
    fun `history owner resolves to candidate B when anchor is A but chapter belongs to B`() {
        // Crucial bug prevention test:
        // Work preferred projection = A (1L), but user was reading Manga B (2L) where chapterId 555L belongs.
        val raw = RawWorkHistory(
            entityId = 100L,
            anchorMangaId = 1L,
            createdAt = 1000L,
            updatedAt = 2000L,
            chapterId = 555L,
            page = 10,
            scroll = 0f,
            percent = 0.5f,
            deletedAt = 0L,
            chaptersCount = 20,
            parentChapterId = null,
        )

        val resolvedMangaId = ProjectionOwnershipMigrationResolver.resolveHistoryOwnerMangaId(
            history = raw,
            anchorOwnsChapter = false, // Anchor 1L does NOT own chapter 555L
            candidates = listOf(1L, 2L),
            chapterOwnerLookup = { mId, cId ->
                // Chapter 555L belongs to Manga 2L, not 1L!
                mId == 2L && cId == 555L
            },
        )

        assertEquals(2L, resolvedMangaId, "Should correctly identify Manga 2L as the true owner of chapter 555L")
    }

    @Test
    fun `history owner resolves via epub internal chapter mapping`() {
        val raw = RawWorkHistory(
            entityId = 100L,
            anchorMangaId = 1L,
            createdAt = 1000L,
            updatedAt = 2000L,
            chapterId = 9999L, // internal chapter
            page = 5,
            scroll = 0f,
            percent = 0.2f,
            deletedAt = 0L,
            chaptersCount = 50,
            parentChapterId = 8888L, // different parent chapter indicates EPUB internal chapter
        )

        val resolvedMangaId = ProjectionOwnershipMigrationResolver.resolveHistoryOwnerMangaId(
            history = raw,
            anchorOwnsChapter = false,
            candidates = listOf(1L, 3L),
            chapterOwnerLookup = { _, _ -> false },
            epubOwnerLookup = { candidates, internalCId ->
                if (internalCId == 9999L && 3L in candidates) 3L else null
            },
        )

        assertEquals(3L, resolvedMangaId, "Should resolve to EPUB internal chapter owner Manga 3L")
    }

    @Test
    fun `history owner resolves via reading sessions when chapters are evicted`() {
        val raw = RawWorkHistory(
            entityId = 100L,
            anchorMangaId = 1L,
            createdAt = 1000L,
            updatedAt = 2000L,
            chapterId = 777L,
            page = 1,
            scroll = 0f,
            percent = 0.1f,
            deletedAt = 0L,
            chaptersCount = 10,
            parentChapterId = null,
        )

        val resolvedMangaId = ProjectionOwnershipMigrationResolver.resolveHistoryOwnerMangaId(
            history = raw,
            anchorOwnsChapter = false,
            candidates = listOf(1L, 4L),
            chapterOwnerLookup = { _, _ -> false }, // No chapters found in chapters cache table
            readingSessionLookup = { candidates, cId ->
                if (cId == 777L && 4L in candidates) 4L else null
            },
        )

        assertEquals(4L, resolvedMangaId, "Should resolve to Manga 4L from reading session history")
    }

    @Test
    fun `history owner falls back to anchor when no other evidence exists`() {
        val raw = RawWorkHistory(
            entityId = 100L,
            anchorMangaId = 1L,
            createdAt = 1000L,
            updatedAt = 2000L,
            chapterId = 777L,
            page = 1,
            scroll = 0f,
            percent = 0.1f,
            deletedAt = 0L,
            chaptersCount = 10,
            parentChapterId = null,
        )

        val resolvedMangaId = ProjectionOwnershipMigrationResolver.resolveHistoryOwnerMangaId(
            history = raw,
            anchorOwnsChapter = false,
            candidates = listOf(1L, 5L),
            chapterOwnerLookup = { _, _ -> false },
            readingSessionLookup = { _, _ -> null },
        )

        assertEquals(1L, resolvedMangaId, "Should safely fall back to anchorMangaId")
    }

    @Test
    fun `favourites merge preserves pinned and active status`() {
        val favA = ResolvedFavourite(
            mangaId = 10L,
            categoryId = 1L,
            sortKey = 5,
            isPinned = false,
            createdAt = 1000L,
            deletedAt = 0L, // active
            updatedAt = 2000L,
        )

        val favB = ResolvedFavourite(
            mangaId = 10L,
            categoryId = 1L,
            sortKey = 8,
            isPinned = true, // pinned
            createdAt = 1500L,
            deletedAt = 0L,
            updatedAt = 3000L, // newer
        )

        val merged = ProjectionOwnershipMigrationResolver.mergeFavourites(favA, favB)

        assertEquals(10L, merged.mangaId)
        assertEquals(1L, merged.categoryId)
        assertTrue(merged.isPinned, "Pinned should be true if either row is pinned")
        assertEquals(1000L, merged.createdAt, "CreatedAt should take the earliest timestamp")
        assertEquals(3000L, merged.updatedAt, "UpdatedAt should take the latest timestamp")
        assertEquals(8, merged.sortKey, "SortKey should take the latest row's sortKey")
        assertEquals(0L, merged.deletedAt, "Active row should keep deletedAt = 0")
    }

    @Test
    fun `favourites merge keeps active state if older was active and newer was deleted earlier`() {
        val activeFav = ResolvedFavourite(
            mangaId = 10L,
            categoryId = 1L,
            sortKey = 1,
            isPinned = false,
            createdAt = 1000L,
            deletedAt = 0L, // active
            updatedAt = 5000L, // updated recently
        )

        val deletedFav = ResolvedFavourite(
            mangaId = 10L,
            categoryId = 1L,
            sortKey = 2,
            isPinned = false,
            createdAt = 500L,
            deletedAt = 2000L, // deleted in the past
            updatedAt = 2000L,
        )

        val merged = ProjectionOwnershipMigrationResolver.mergeFavourites(activeFav, deletedFav)

        assertEquals(0L, merged.deletedAt, "Active record must win over past deletion")
        assertEquals(5000L, merged.updatedAt)
    }

    @Test
    fun `histories merge keeps newest progress with earliest creation date`() {
        val histOld = ResolvedHistory(
            mangaId = 20L,
            createdAt = 1000L,
            updatedAt = 2000L,
            chapterId = 10L,
            page = 1,
            scroll = 0f,
            percent = 0.1f,
            deletedAt = 0L,
            chaptersCount = 100,
            parentChapterId = null,
        )

        val histNew = ResolvedHistory(
            mangaId = 20L,
            createdAt = 1500L,
            updatedAt = 4000L,
            chapterId = 25L,
            page = 12,
            scroll = 0.5f,
            percent = 0.25f,
            deletedAt = 0L,
            chaptersCount = 100,
            parentChapterId = null,
        )

        val merged = ProjectionOwnershipMigrationResolver.mergeHistories(histOld, histNew)

        assertEquals(1000L, merged.createdAt, "Should keep earliest created_at")
        assertEquals(4000L, merged.updatedAt, "Should keep latest updated_at")
        assertEquals(25L, merged.chapterId, "Should keep latest chapterId")
        assertEquals(12, merged.page, "Should keep latest page")
        assertEquals(0.25f, merged.percent, "Should keep latest percent")
    }
}
