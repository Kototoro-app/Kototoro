package org.skepsun.kototoro.favourites.domain

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaDao
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityGraphDao
import org.skepsun.kototoro.entitygraph.data.EntityPrefsRecord
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.favourites.data.FavouriteCategoriesDao
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouritesDao
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.data.WorkFavouritesDao
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class EntityOrganizeRepositoryTest {

    private val workFavouritesDao = mockk<WorkFavouritesDao>()
    private val favouritesDao = mockk<FavouritesDao>(relaxed = true)
    private val mangaDao = mockk<MangaDao>()
    private val categoriesDao = mockk<FavouriteCategoriesDao>()
    private val entityGraphDao = mockk<EntityGraphDao>()
    private val sourcesRepository = mockk<ContentSourcesRepository>()
    private val db = mockk<MangaDatabase> {
        every { getWorkFavouritesDao() } returns workFavouritesDao
        every { getFavouritesDao() } returns favouritesDao
        every { getMangaDao() } returns mangaDao
        every { getFavouriteCategoriesDao() } returns categoriesDao
        every { getEntityGraphDao() } returns entityGraphDao
    }
    private val repository = EntityOrganizeRepository(db, sourcesRepository)

    @Test
    fun `old favourites table is not an entity organize input`() = runTest {
        coEvery { workFavouritesDao.findActive() } returns emptyList()

        assertTrue(repository.listFavouriteContents().isEmpty())
        assertTrue(repository.listOrganizableWorks().isEmpty())

        verify(exactly = 0) { db.getFavouritesDao() }
    }

    @Test
    fun `work favourites with anchors are exposed as organizable works`() = runTest {
        coEvery { workFavouritesDao.findActive() } returns listOf(
            workFavourite(entityId = 7L, categoryId = 2L, anchorMangaId = 100L),
        )
        coEvery { entityGraphDao.findEntitiesByIds(listOf(7L)) } returns listOf(entity(7L, "Work A"))
        coEvery { entityGraphDao.findEntityPrefsByIds(listOf(7L)) } returns listOf(
            prefs(entityId = 7L, preferredLocalMangaId = 101L),
        )
        coEvery { entityGraphDao.findActiveLocalBindingsByEntities(listOf(7L)) } returns listOf(
            binding(entityId = 7L, mangaId = 100L, state = EntityBindingState.CONFIRMED),
            binding(entityId = 7L, mangaId = 101L, state = EntityBindingState.MANUAL),
        )
        coEvery { mangaDao.findWithTagsByIds(any<Collection<Long>>()) } answers {
            val ids = firstArg<Collection<Long>>()
            listOfNotNull(
                if (100L in ids) mangaWithTags(100L, "Source A", "Anchor") else null,
                if (101L in ids) mangaWithTags(101L, "Source B", "Preferred") else null,
            )
        }
        coEvery { categoriesDao.findByIds(listOf(2L)) } returns listOf(category(2))

        val works = repository.listOrganizableWorks()
        val contents = repository.listFavouriteContents()

        assertEquals(1, works.size)
        assertEquals(7L, works.single().entityId)
        assertEquals(101L, works.single().preferredMangaId)
        assertEquals(setOf(2L), works.single().favouriteCategoryIds)
        assertEquals(setOf(100L, 101L), works.single().projections.mapTo(LinkedHashSet()) { it.mangaId })
        assertEquals(EntityBindingState.MANUAL, works.single().projections.first { it.mangaId == 101L }.bindingState)
        assertEquals(EntityBindingCreatedBy.USER, works.single().projections.first { it.mangaId == 101L }.bindingCreatedBy)
        assertTrue(works.single().projections.first { it.mangaId == 100L }.isFavouriteAnchor)
        assertTrue(works.single().projections.first { it.mangaId == 101L }.isPreferred)
        assertEquals(listOf(100L), contents.map { it.manga.id })

        verify(exactly = 0) { db.getFavouritesDao() }
    }

    @Test
    fun `duplicate projections are grouped and canonical projection preserved with duplicateCount`() = runTest {
        coEvery { workFavouritesDao.findActive() } returns listOf(
            workFavourite(entityId = 9L, categoryId = 1L, anchorMangaId = 101L),
        )
        coEvery { entityGraphDao.findEntitiesByIds(listOf(9L)) } returns listOf(entity(9L, "Duplicated Work"))
        coEvery { entityGraphDao.findEntityPrefsByIds(listOf(9L)) } returns listOf(
            prefs(entityId = 9L, preferredLocalMangaId = 102L),
        )
        // 3 duplicate bindings with same source and url, plus 1 distinct source
        coEvery { entityGraphDao.findActiveLocalBindingsByEntities(listOf(9L)) } returns listOf(
            binding(entityId = 9L, mangaId = 101L, state = EntityBindingState.CONFIRMED),
            binding(entityId = 9L, mangaId = 102L, state = EntityBindingState.MANUAL),
            binding(entityId = 9L, mangaId = 103L, state = EntityBindingState.CONFIRMED),
            binding(entityId = 9L, mangaId = 200L, state = EntityBindingState.CONFIRMED),
        )
        coEvery { mangaDao.findWithTagsByIds(any<Collection<Long>>()) } answers {
            val ids = firstArg<Collection<Long>>()
            listOfNotNull(
                if (101L in ids) mangaWithTags(101L, "source_dup", "Dup Title", "/same_path") else null,
                if (102L in ids) mangaWithTags(102L, "source_dup", "Dup Title", "/same_path") else null,
                if (103L in ids) mangaWithTags(103L, "source_dup", "Dup Title", "/same_path") else null,
                if (200L in ids) mangaWithTags(200L, "source_other", "Other Title", "/other_path") else null,
            )
        }
        coEvery { categoriesDao.findByIds(listOf(1L)) } returns listOf(category(1))

        val works = repository.listOrganizableWorks()

        assertEquals(1, works.size)
        val work = works.single()
        assertEquals(9L, work.entityId)
        assertTrue(work.hasDuplicateProjections)
        assertEquals(2, work.totalDuplicateProjections) // 3 duplicates for source_dup - 1 = 2 extra
        assertEquals(2, work.projections.size) // 1 grouped for source_dup, 1 for source_other

        val dupGroup = work.projections.first { it.source == "source_dup" }
        assertEquals(102L, dupGroup.mangaId) // preferred projection chosen
        assertEquals(3, dupGroup.duplicateCount)
        assertTrue(dupGroup.isPreferred)

        val otherProj = work.projections.first { it.source == "source_other" }
        assertEquals(200L, otherProj.mangaId)
        assertEquals(1, otherProj.duplicateCount)
    }

    private fun workFavourite(
        entityId: Long,
        categoryId: Long,
        anchorMangaId: Long?,
    ): WorkFavouriteEntity {
        return WorkFavouriteEntity(
            entityId = entityId,
            categoryId = categoryId,
            anchorMangaId = anchorMangaId,
            sortKey = 0,
            isPinned = false,
            createdAt = 1L,
            deletedAt = 0L,
            updatedAt = 1L,
        )
    }

    private fun entity(id: Long, title: String): EntityRecord {
        return EntityRecord(
            id = id,
            type = EntityType.WORK.name,
            primaryName = title,
            aliases = null,
            createdAt = 1L,
            lastAccessed = 1L,
            accessCount = 0,
        )
    }

    private fun prefs(entityId: Long, preferredLocalMangaId: Long?): EntityPrefsRecord {
        return EntityPrefsRecord(
            entityId = entityId,
            preferredLocalMangaId = preferredLocalMangaId,
            titleOverride = null,
            coverUrlOverride = null,
            contentRatingOverride = null,
            readingStatus = null,
            metadataSourceKind = null,
            metadataBindingSource = null,
            metadataBindingExternalId = null,
            metadataSourceService = null,
            metadataSourceRemoteId = null,
            updatedAt = 1L,
        )
    }

    private fun binding(
        entityId: Long,
        mangaId: Long,
        state: EntityBindingState,
    ): EntityBindingRecord {
        return EntityBindingRecord(
            entityId = entityId,
            source = "local_manga",
            externalId = mangaId.toString(),
            confidence = 1f,
            isPrimary = true,
            state = state.name,
            createdBy = EntityBindingCreatedBy.USER.name,
            updatedAt = 1L,
        )
    }

    private fun mangaWithTags(id: Long, source: String, title: String, url: String = "/$id"): MangaWithTags {
        return MangaWithTags(
            manga = MangaEntity(
                id = id,
                title = title,
                altTitles = null,
                url = url,
                publicUrl = "https://example.org$url",
                rating = -1f,
                isNsfw = false,
                contentRating = null,
                coverUrl = "",
                largeCoverUrl = null,
                state = null,
                authors = null,
                source = source,
            ),
            tags = emptyList(),
        )
    }

    private fun category(id: Int): FavouriteCategoryEntity {
        return FavouriteCategoryEntity(
            categoryId = id,
            createdAt = 1L,
            sortKey = id,
            title = "Category $id",
            order = "",
            track = false,
            isVisibleInLibrary = true,
            deletedAt = 0L,
        )
    }

    private class TestContentSource(
        override val name: String,
        override val locale: String = "en",
        override val contentType: ContentType = ContentType.MANGA,
    ) : ContentSource
}
