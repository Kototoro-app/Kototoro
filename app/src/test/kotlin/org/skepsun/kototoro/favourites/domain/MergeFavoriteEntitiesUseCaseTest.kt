package org.skepsun.kototoro.favourites.domain

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.TrackingSiteDao
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.work.domain.WorkIdentity
import org.skepsun.kototoro.work.domain.WorkMigrationState
import org.skepsun.kototoro.work.domain.WorkResolver

class MergeFavoriteEntitiesUseCaseTest {

    @Test
    fun `fuzzy title similarity ignores separator-only differences`() {
        assertEquals(
            1f,
            mergeCandidateTitleSimilarity("one piece", "onepiece"),
        )
    }

    @Test
    fun `fuzzy title similarity accepts reordered tokens`() {
        assertEquals(
            1f,
            mergeCandidateTitleSimilarity("hero academia", "academia hero"),
        )
    }

    @Test
    fun `fuzzy title similarity accepts title with extra descriptive tokens`() {
        assertTrue(
            mergeCandidateTitleSimilarity("one piece", "one piece digital colored comics") >= 0.9f,
        )
    }

    @Test
    fun `fuzzy title similarity accepts minor spelling differences`() {
        assertTrue(
            mergeCandidateTitleSimilarity("fullmetal alchemist", "fullmetal alchmist") >= 0.9f,
        )
    }

    @Test
    fun `fuzzy title similarity accepts common simplified and traditional title variants`() {
        assertTrue(
            mergeCandidateTitleSimilarity("我心裡危險的東西", "我心里危险的东西") >= 0.9f,
        )
    }

    @Test
    fun `fuzzy title similarity ignores common archive and translation noise`() {
        assertTrue(
            mergeCandidateTitleSimilarity("终末的后宫（补档）", "终末的后宫") >= 0.9f,
        )
    }

    @Test
    fun `fuzzy title similarity keeps unrelated titles below minimum fuzzy threshold`() {
        assertTrue(
            mergeCandidateTitleSimilarity("one piece", "naruto") < 0.8f,
        )
    }

    @Test
    fun `fuzzy title similarity keeps shared franchise with different subtitle at fuzzy boundary`() {
        assertTrue(
            mergeCandidateTitleSimilarity("终末的后宫（补档）", "终末的后宫幻想版") >= 0.9f,
        )
    }

    @Test
    fun `alias candidate ignores alias key not supported by bound content title`() = runTest {
        val entityGraphRepository = mockk<EntityGraphRepository>()
        coEvery { entityGraphRepository.findLocalReadingBinding(any()) } returns null
        coEvery { entityGraphRepository.findLocalReadingBindingsByMangaIds(any()) } returns emptyMap()
        coEvery { entityGraphRepository.getEntitiesByIds(any()) } returns listOf(
            Entity(
                id = 5L,
                type = EntityType.WORK,
                primaryName = "Negai Ai: Hajimete Doushi no Hajirai Yuugi",
                aliases = listOf("黑月的耶尔克纳赫特"),
                createdAt = 0L,
                lastAccessed = 0L,
                accessCount = 0,
            ),
        )

        val useCase = mergeUseCase(
            entityGraphRepository = entityGraphRepository,
            entity = Entity(
                id = 5L,
                type = EntityType.WORK,
                primaryName = "Negai Ai: Hajimete Doushi no Hajirai Yuugi",
                aliases = listOf("黑月的耶尔克纳赫特"),
                createdAt = 0L,
                lastAccessed = 0L,
                accessCount = 0,
            ),
        )

        val groups = useCase.buildCandidateGroups(
            contents = listOf(
                content(
                    id = 1L,
                    title = "Negai Ai: Hajimete Doushi no Hajirai Yuugi",
                    sourceName = "RAWKUMA",
                ),
                content(
                    id = 2L,
                    title = "黑月的耶尔克纳赫特",
                    sourceName = "KOMIIC",
                ),
            ),
        )

        assertTrue(groups.none { group -> group.id.contains(":alias:") })
    }

    @Test
    fun `alias candidate accepts high similarity bound title variant without fuzzy option`() = runTest {
        val entityGraphRepository = mockk<EntityGraphRepository>()
        coEvery { entityGraphRepository.findLocalReadingBinding(any()) } returns null
        coEvery { entityGraphRepository.findLocalReadingBindingsByMangaIds(any()) } returns emptyMap()
        coEvery { entityGraphRepository.getEntitiesByIds(any()) } returns listOf(
            Entity(
                id = 291L,
                type = EntityType.WORK,
                primaryName = "魔王的女儿过于温柔！",
                aliases = listOf("魔王的女儿太温柔了！！"),
                createdAt = 0L,
                lastAccessed = 0L,
                accessCount = 0,
            ),
        )

        val useCase = mergeUseCase(
            entityGraphRepository = entityGraphRepository,
            entity = Entity(
                id = 291L,
                type = EntityType.WORK,
                primaryName = "魔王的女儿过于温柔！",
                aliases = listOf("魔王的女儿太温柔了！！"),
                createdAt = 0L,
                lastAccessed = 0L,
                accessCount = 0,
            ),
        )

        val groups = useCase.buildCandidateGroups(
            contents = listOf(
                content(
                    id = 1L,
                    title = "魔王的女儿过于温柔！",
                    sourceName = "KOMIIC",
                ),
                content(
                    id = 2L,
                    title = "魔王的女儿太温柔了！！",
                    sourceName = "MH1234",
                ),
            ),
        )

        val aliasGroup = groups.single { group -> group.id.contains(":alias:") }
        assertEquals(setOf(1L, 2L), aliasGroup.mangaIds)
        assertTrue(aliasGroup.matchScore >= DEFAULT_FUZZY_MERGE_THRESHOLD)
    }

    private fun mergeUseCase(
        entityGraphRepository: EntityGraphRepository? = null,
        entity: Entity,
    ): MergeFavoriteEntitiesUseCase {
        val trackingSiteDao = mockk<TrackingSiteDao>()
        coEvery { trackingSiteDao.findLinksByEntityIds(any<List<Long>>()) } returns emptyList()
        coEvery { trackingSiteDao.findLinksByMangaIds(any<List<Long>>()) } returns emptyList()

        val database = mockk<MangaDatabase> {
            every { getTrackingSiteDao() } returns trackingSiteDao
        }
        val effectiveEntityGraphRepository = entityGraphRepository ?: mockk<EntityGraphRepository>().also {
            coEvery { it.findLocalReadingBindingsByMangaIds(any()) } returns emptyMap()
            coEvery { it.getEntitiesByIds(any()) } returns listOf(entity)
        }
        val workResolver = mockk<WorkResolver>()
        coEvery { workResolver.resolveManyByMangaIds(any()) } answers {
            firstArg<Collection<Long>>().associateWith { mangaId ->
                WorkIdentity(
                    entityId = if (mangaId == 1L) entity.id else null,
                    requestedMangaId = mangaId,
                    preferredMangaId = mangaId,
                    localMangaIds = setOf(mangaId),
                    migrationState = WorkMigrationState.VALID,
                )
            }
        }
        return MergeFavoriteEntitiesUseCase(
            database = database,
            entityGraphRepository = effectiveEntityGraphRepository,
            contentDataRepository = mockk<ContentDataRepository>(relaxed = true),
            workResolver = workResolver,
        )
    }

    private fun content(
        id: Long,
        title: String,
        sourceName: String,
    ): Content {
        return Content(
            id = id,
            title = title,
            altTitles = emptySet(),
            url = "/$id",
            publicUrl = "https://example.org/$id",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = TestContentSource(sourceName),
        )
    }

    private data class TestContentSource(
        override val name: String,
        override val locale: String = "en",
        override val contentType: ContentType = ContentType.MANGA,
    ) : ContentSource
}
