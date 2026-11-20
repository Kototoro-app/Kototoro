package org.skepsun.kototoro.community.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommunityCommentThreadsTest {

    private data class C(val id: String, val parent: String?, val at: Long, val author: String = "a", val text: String = "hi")

    private fun threads(vararg items: C) = CommunityCommentThreads.build(items.toList(), C::id, C::parent, C::at)
        .map { thread -> thread.root.id to thread.replies.map { it.id } }

    @Test
    fun `replies at any depth sit under the comment that started the thread, oldest first`() {
        assertEquals(
            listOf("root" to listOf("r1", "r2", "r1a")),
            threads(C("root", null, 1), C("r1", "root", 2), C("r1a", "r1", 5), C("r2", "root", 3)),
        )
    }

    @Test
    fun `the thread with the newest activity comes first and an orphan reply stands alone`() {
        assertEquals(
            listOf("old" to listOf("late"), "orphan" to emptyList(), "new" to emptyList()),
            threads(C("old", null, 1), C("new", null, 5), C("late", "old", 9), C("orphan", "gone", 7)),
        )
    }

    @Test
    fun `a parent cycle does not loop`() {
        assertEquals(2, CommunityCommentThreads.build(listOf(C("x", "y", 1), C("y", "x", 2)), C::id, C::parent, C::at).flatMap { listOf(it.root) + it.replies }.size)
    }

    @Test
    fun `blocked authors, events and words are hidden, and only an author's own deletion counts`() {
        val items = listOf(
            C("1", null, 1, author = "spammer"),
            C("2", null, 2, text = "Buy CHEAP watches"),
            C("3", null, 3),
            C("4", null, 4, author = "b"),
            C("5", null, 5, author = "c"),
        )
        val filter = CommunityCommentFilter(setOf("spammer"), setOf("3"), listOf("cheap watches")) + CommunityCommentFilter()
        val deletions = mapOf("b" to setOf("4"), "x" to setOf("5"))
        assertEquals(
            listOf("5"),
            CommunityCommentThreads.visible(items, filter, deletions, C::id, C::author, C::text).map { it.id },
        )
    }
}
