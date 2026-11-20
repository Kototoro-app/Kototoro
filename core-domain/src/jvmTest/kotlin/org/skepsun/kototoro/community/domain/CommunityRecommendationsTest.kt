package org.skepsun.kototoro.community.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommunityRecommendationsTest {

    private fun sim(id: String, score: Double, title: String? = id.uppercase()) = CommunitySimilarWork(id, title, score, coRaters = 5)

    @Test
    fun `a rating says how much the reader likes a work, a favourite or a finished read says they like it`() {
        assertEquals(4.5, CommunityRecommendations.affinity(10, isFavourite = false, readFraction = null))
        assertEquals(-2.5, CommunityRecommendations.affinity(3, isFavourite = true, readFraction = 1f), "their rating wins")
        assertEquals(CommunityRecommendations.FAVOURITE_AFFINITY, CommunityRecommendations.affinity(null, true, 1f))
        assertEquals(CommunityRecommendations.FINISHED_AFFINITY, CommunityRecommendations.affinity(null, false, 0.95f))
        assertEquals(CommunityRecommendations.STARTED_AFFINITY, CommunityRecommendations.affinity(null, false, 0.4f))
        assertNull(CommunityRecommendations.affinity(null, false, 0.1f))
        assertNull(CommunityRecommendations.affinity(null, false, null))
    }

    @Test
    fun `works similar to several liked ones rank first, with the seeds that brought them`() {
        val similar = mapOf(
            "liked" to listOf(sim("x", 0.8), sim("y", 0.5)),
            "loved" to listOf(sim("x", 0.4), sim("z", 0.9)),
        )
        val seeds = listOf(CommunitySeed("liked", "Liked", 2.0), CommunitySeed("loved", "Loved", 4.5))
        val ranked = CommunityRecommendations.rank(seeds, { similar[it].orEmpty() }, known = emptySet())
        assertEquals(listOf("z", "x", "y"), ranked.map { it.groupId })
        assertEquals(0.9 * 4.5, ranked[0].score, 1e-9)
        assertEquals(listOf("Loved", "Liked"), ranked[1].because.map { it.title }, "x came from both, the loved one most")
        assertEquals("X", ranked[1].title)
    }

    @Test
    fun `a disliked work holds back what resembles it, and the library and excluded works are never recommended`() {
        val similar = mapOf(
            "liked" to listOf(sim("a", 0.6), sim("b", 0.6), sim("owned", 0.9), sim("adult", 0.9), sim("disliked", 0.9)),
            "disliked" to listOf(sim("b", 0.6)),
        )
        val seeds = listOf(CommunitySeed("liked", null, 2.0), CommunitySeed("disliked", null, -2.5))
        val ranked = CommunityRecommendations.rank(seeds, { similar[it].orEmpty() }, known = setOf("owned"), excluded = { it.id == "adult" })
        assertEquals(listOf("a"), ranked.map { it.groupId }, "b is held back below zero; seeds themselves are not recommended")
    }

    @Test
    fun `one work in the library twice counts once, at its strongest`() {
        val similar = mapOf("g" to listOf(sim("x", 1.0)))
        val ranked = CommunityRecommendations.rank(
            listOf(CommunitySeed("g", null, 0.5), CommunitySeed("g", null, 4.5)), { similar[it].orEmpty() }, emptySet(),
        )
        assertEquals(4.5, ranked.single().score, 1e-9)
        assertTrue(CommunityRecommendations.rank(emptyList(), { emptyList() }, emptySet()).isEmpty())
    }
}
