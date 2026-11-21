package org.skepsun.kototoro.community.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommunityHighlightsTest {

    @Test
    fun `lists rated works best first and leaves out unrated, untitled and adult ones`() {
        val works = listOf(
            work("a", "A", score = 8.0, count = 3),
            work("b", "B", score = 9.0, count = 1),
            work("c", "C", score = 9.0, count = 2),
            work("unrated", "Unrated", score = null, count = 0),
            work("untitled", null, score = 9.5, count = 4),
            work("adult", "Adult", score = 9.9, count = 5, nsfw = true),
        )
        assertEquals(listOf("c", "b", "a"), CommunityHighlights.rated(works, allowNsfw = false, limit = 10).map { it.id })
        assertEquals("adult", CommunityHighlights.rated(works, allowNsfw = true, limit = 1).single().id)
    }

    @Test
    fun `merges charts of every medium and reads the works files only while the index is small`() {
        val comic = CommunityChart("all", "comic", false, listOf(CommunityChartEntry("x", "X", 8.0, 9)))
        val novel = CommunityChart("all", "novel", false, listOf(CommunityChartEntry("y", "Y", 9.0, 6), CommunityChartEntry("x", "X", 8.0, 9)))
        assertEquals(listOf("y", "x"), CommunityHighlights.merge(listOf(comic, null, novel), limit = 5).map { it.id })

        val small = manifest((0 until 3).map { "v1/works/0$it.json" } + "v1/keys/00.json")
        assertEquals(listOf("v1/works/00.json", "v1/works/01.json", "v1/works/02.json"), CommunityHighlights.worksShardPaths(small))
        val big = manifest((0..CommunityHighlights.MAX_FALLBACK_SHARDS).map { "v1/works/%02x.json".format(it) })
        assertTrue(CommunityHighlights.worksShardPaths(big).isEmpty())
    }

    @Test
    fun `folds ten-point ratings into five stars`() {
        assertEquals(listOf(1, 0, 2, 0, 7), CommunityHighlights.starBuckets(listOf(1, 0, 0, 0, 2, 0, 0, 0, 3, 4)))
        assertEquals(listOf(0, 0, 0, 0, 0), CommunityHighlights.starBuckets(emptyList()))
    }

    private fun work(id: String, title: String?, score: Double?, count: Int, nsfw: Boolean = false) = CommunityWorkSummary(
        id = id,
        medium = "comic",
        title = title,
        rating = score?.let { CommunityRatingSummary(it, it, count, count.toDouble(), List(10) { 0 }) },
        nsfw = nsfw,
    )

    private fun manifest(paths: List<String>) = CommunitySnapshotManifest(
        generatedAt = 0, relays = emptyList(), powRating = 0, powComment = 0, minChartWeight = 5.0,
        files = paths.associateWith { "hash" }, stats = CommunitySnapshotStats(0, 0, 0, 0, 0),
    )
}
