package org.skepsun.kototoro.details.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The shared rule behind Android's `filterCurrentWorkFromRelated` and Windows' related rail: a source must not list the
 * work that asked as its own related work, in any of the three shapes a relisting takes.
 */
class RelatedWorkRulesTest {

    private fun work(
        id: Long,
        title: String = "某作品",
        url: String = "/work/$id",
        publicUrl: String = "https://fixture.invalid/work/$id",
        coverUrl: String? = "https://fixture.invalid/cover/$id.jpg",
        largeCoverUrl: String? = null,
        sourceName: String = "fixture",
    ) = RelatedWorkKey(sourceName, id, url, publicUrl, title, coverUrl, largeCoverUrl)

    private fun filter(seed: RelatedWorkKey, vararg candidates: RelatedWorkKey) =
        RelatedWorkRules.filterCurrentWork(seed, candidates.toList()) { it }.map(RelatedWorkKey::id)

    @Test
    fun `the work itself is dropped by id, by url and by public url`() {
        val seed = work(1)
        assertEquals(
            emptyList<Long>(),
            filter(
                seed,
                work(1),
                work(2, url = "/work/1"),
                work(3, publicUrl = "https://fixture.invalid/work/1"),
            ),
        )
    }

    @Test
    fun `the same title over the same artwork path is the same work`() {
        val seed = work(1, coverUrl = "https://a.example.test/cover/1.jpg")
        val copy = work(2, title = "某作品!!", coverUrl = "https://b.example.test/cover/1.jpg?width=200")
        assertEquals(emptyList<Long>(), filter(seed, copy))
    }

    @Test
    fun `a different title or different artwork stays`() {
        val seed = work(1, coverUrl = "https://a.example.test/cover/1.jpg")
        assertEquals(
            listOf(2L, 3L),
            filter(
                seed,
                work(2, title = "某作品 第二季", coverUrl = "https://a.example.test/cover/1.jpg"),
                work(3, coverUrl = "https://a.example.test/cover/3.jpg"),
            ),
        )
    }

    @Test
    fun `the same id and title on another source is a different work`() {
        val seed = work(1)
        assertEquals(listOf(1L), filter(seed, work(1, sourceName = "other")))
    }

    @Test
    fun `a title without artwork cannot be matched by title alone`() {
        val seed = work(1, coverUrl = null)
        assertEquals(listOf(2L), filter(seed, work(2, coverUrl = null)))
    }
}
