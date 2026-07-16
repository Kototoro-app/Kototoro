package org.skepsun.kototoro.entitygraph.data

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityBindingStrength
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.entitygraph.domain.RelationType
import org.skepsun.kototoro.parsers.model.ContentType

class DefaultEntityBindingMatcherTest {

    private val dao = mockk<EntityGraphDao>()
    private val db = mockk<MangaDatabase> {
        every { getEntityGraphDao() } returns dao
    }
    private val matcher = DefaultEntityBindingMatcher(db)

    @Test
    fun `exact match returns full confidence`() = runTest {
        val left = entity(id = 1L, type = EntityType.WORK, name = "三体")
        val right = entity(id = 2L, type = EntityType.WORK, name = "三体")
        val confidence = matcher.tryBindEntities(left, right)
        assertEquals(1f, confidence)
    }

    @Test
    fun `ignore case match returns case insensitive confidence`() = runTest {
        val left = entity(id = 1L, type = EntityType.WORK, name = "Frieren")
        val right = entity(id = 2L, type = EntityType.WORK, name = "frieren")
        val confidence = matcher.tryBindEntities(left, right)
        assertEquals(1f, confidence)
    }

    @Test
    fun `different types never bind`() = runTest {
        val left = entity(id = 1L, type = EntityType.WORK, name = "Frieren")
        val right = entity(id = 2L, type = EntityType.CHARACTER, name = "Frieren")
        val confidence = matcher.tryBindEntities(left, right)
        assertEquals(0f, confidence)
    }

    @Test
    fun `same title with different work content types never binds`() = runTest {
        val left = entity(id = 1L, type = EntityType.WORK, name = "庙不可言", contentType = ContentType.MANGA)
        val right = entity(id = 2L, type = EntityType.WORK, name = "庙不可言", contentType = ContentType.VIDEO)
        val confidence = matcher.tryBindEntities(left, right)
        assertEquals(0f, confidence)
    }

    @Test
    fun `unknown work content type never auto binds by title`() = runTest {
        val left = entity(id = 1L, type = EntityType.WORK, name = "庙不可言", contentType = null)
        val right = entity(id = 2L, type = EntityType.WORK, name = "庙不可言", contentType = ContentType.VIDEO)
        val confidence = matcher.tryBindEntities(left, right)
        assertEquals(0f, confidence)
    }

    @Test
    fun `shared character context boosts person confidence`() = runTest {
        val left = entity(id = 10L, type = EntityType.PERSON, name = "Kana Hanazawa")
        val right = entity(id = 20L, type = EntityType.PERSON, name = "Kana Hanazawa")
        coEvery { dao.findVisibleIncomingEntityIds(10L, RelationType.VOICED_BY.name) } returns listOf(100L)
        coEvery { dao.findVisibleIncomingEntityIds(20L, RelationType.VOICED_BY.name) } returns listOf(100L)
        coEvery { dao.findVisibleIncomingEntityIds(10L, RelationType.CREATED_BY.name) } returns emptyList()
        coEvery { dao.findVisibleIncomingEntityIds(20L, RelationType.CREATED_BY.name) } returns emptyList()
        val confidence = matcher.tryBindEntities(left, right)
        assertTrue(confidence >= 1f)
    }

    @Test
    fun `short names below 5 chars require exact match`() = runTest {
        val left = entity(id = 1L, type = EntityType.WORK, name = "A")
        val right = entity(id = 2L, type = EntityType.WORK, name = "B")
        val confidence = matcher.tryBindEntities(left, right)
        assertEquals(0f, confidence)
    }

    @Test
    fun `classify uses auto weak and ignore thresholds`() {
        assertEquals(EntityBindingStrength.AUTO_BIND, matcher.classify(0.91f))
        assertEquals(EntityBindingStrength.WEAK_BIND, matcher.classify(0.65f))
        assertEquals(EntityBindingStrength.IGNORE, matcher.classify(0.64f))
    }

    private fun entity(
        id: Long,
        type: EntityType,
        name: String,
        contentType: ContentType? = if (type == EntityType.WORK) ContentType.MANGA else null,
    ): Entity = Entity(
        id = id,
        type = type,
        contentType = contentType,
        primaryName = name,
        aliases = emptyList(),
        createdAt = 1L,
        lastAccessed = 1L,
        accessCount = 1,
    )
}
