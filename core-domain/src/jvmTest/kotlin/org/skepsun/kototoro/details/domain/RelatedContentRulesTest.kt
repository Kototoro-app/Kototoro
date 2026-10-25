package org.skepsun.kototoro.details.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelatedContentRulesTest {

    @Test
    fun `searchQueries keep the title, the alternative titles and their words`() {
        assertEquals(
            listOf("某作品", "Another name", "Another", "name"),
            RelatedContentRules.searchQueries("某作品", listOf("Another name")),
        )
    }

    @Test
    fun `searchQueries drop single characters and duplicates, and stop at six`() {
        assertEquals(
            listOf("Alpha Beta", "Alpha", "Beta"),
            RelatedContentRules.searchQueries("Alpha Beta", listOf("Alpha Beta")),
        )
        assertEquals(
            listOf("t1", "t2", "t3", "t4", "t5", "t6"),
            RelatedContentRules.searchQueries("t1", listOf("t2", "t3", "t4", "t5", "t6", "t7")),
        )
    }

    @Test
    fun `searchQueries answer nothing for a blank title`() {
        assertEquals(emptyList<String>(), RelatedContentRules.searchQueries(" ", emptyList()))
    }

    @Test
    fun `a candidate answers a query through either of its titles`() {
        assertTrue(RelatedContentRules.isSearchCandidate("work", RelatedCandidate("The Work", emptyList())))
        assertTrue(RelatedContentRules.isSearchCandidate("work", RelatedCandidate("别的名字", listOf("Other Work"))))
        assertFalse(RelatedContentRules.isSearchCandidate("work", RelatedCandidate("别的名字", emptyList())))
    }

    @Test
    fun `the narrowest result set wins, and no result stays empty`() {
        val narrow = RelatedContentRules.smallestResult(listOf(listOf("a", "b", "c"), listOf("a")))
        assertEquals(listOf("a"), narrow)
        val none = RelatedContentRules.smallestResult(emptyList<List<String>>())
        assertEquals(emptyList<String>(), none)
    }
}
