package org.skepsun.kototoro.entitygraph.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.entitygraph.data.computeNameHash

class EntityConsolidationPlannerTest {

    private fun entity(
        id: Long,
        name: String,
        contentType: String? = "MANGA",
        nameHash: Long = computeNameHash(name),
        strongKeys: Set<String> = emptySet(),
    ): ConsolidationEntity = ConsolidationEntity(
        entityId = id,
        contentType = contentType,
        primaryName = name,
        nameHash = nameHash,
        strongKeys = strongKeys,
    )

    @Test
    fun `same title across sources is grouped with unsalted member as canonical`() {
        val clean = entity(id = 1L, name = "One Piece")
        val salted = entity(id = 2L, name = "One Piece", nameHash = 987654321L)
        val salted2 = entity(id = 3L, name = "One Piece", nameHash = -42L)

        val groups = buildConsolidationGroups(listOf(clean, salted, salted2))

        assertEquals(1, groups.size)
        assertEquals(1L, groups[0].canonicalEntityId)
        assertEquals(listOf(2L, 3L), groups[0].absorbedEntityIds)
    }

    @Test
    fun `strong projection keys group duplicates with different titles`() {
        val a = entity(id = 1L, name = "Old Title", strongKeys = setOf("MIHON_1|location|https://s/x"))
        val b = entity(id = 2L, name = "New Title", strongKeys = setOf("MIHON_1|location|https://s/x"))

        val groups = buildConsolidationGroups(listOf(a, b))

        assertEquals(1, groups.size)
        assertEquals(1L, groups[0].canonicalEntityId)
        assertEquals(listOf(2L), groups[0].absorbedEntityIds)
    }

    @Test
    fun `different titles without shared keys stay separate`() {
        val a = entity(id = 1L, name = "Berserk")
        val b = entity(id = 2L, name = "Vagabond")

        assertTrue(buildConsolidationGroups(listOf(a, b)).isEmpty())
    }

    @Test
    fun `same title with different content types stay separate`() {
        val a = entity(id = 1L, name = "Fate", contentType = "MANGA")
        val b = entity(id = 2L, name = "Fate", contentType = "VIDEO")

        assertTrue(buildConsolidationGroups(listOf(a, b)).isEmpty())
    }

    @Test
    fun `transitive chains merge into one group`() {
        val a = entity(id = 1L, name = "Solo Leveling")
        val b = entity(id = 2L, name = "solo    leveling") // same normalized name as a
        val c = entity(id = 3L, name = "Solo Leveling", nameHash = 12345L, strongKeys = setOf("K|location|u"))
        val d = entity(id = 4L, name = "Only I Level Up", strongKeys = setOf("K|location|u"))

        val groups = buildConsolidationGroups(listOf(a, b, c, d))

        assertEquals(1, groups.size)
        assertEquals(1L, groups[0].canonicalEntityId)
        assertEquals(listOf(2L, 3L, 4L), groups[0].absorbedEntityIds)
    }

    @Test
    fun `single entity and empty input produce no groups`() {
        assertTrue(buildConsolidationGroups(emptyList()).isEmpty())
        assertTrue(buildConsolidationGroups(listOf(entity(1L, "Lone"))).isEmpty())
    }
}
