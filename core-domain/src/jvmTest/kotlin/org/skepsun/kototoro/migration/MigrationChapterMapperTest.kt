package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.MigrationChapter
import org.skepsun.kototoro.migration.domain.MigrationChapterMapper

class MigrationChapterMapperTest {

    @Test
    fun `chapter number identity includes volume`() {
        val old = listOf(MigrationChapter(1, 2, 1f, null, 0))
        val target = listOf(MigrationChapter(10, 1, 1f, null, 0), MigrationChapter(20, 2, 1f, null, 0))
        assertEquals(mapOf(1L to 20L), MigrationChapterMapper.map(old, target))
    }

    @Test
    fun `equal sized fallback branches retain first appearance order`() {
        val old = listOf(MigrationChapter(1, 0, 0f, "missing", 0))
        val target = listOf(MigrationChapter(10, 0, 0f, "B", 0), MigrationChapter(20, 0, 0f, "A", 0))
        assertEquals(mapOf(1L to 10L), MigrationChapterMapper.map(old, target))
    }

    @Test
    fun `index fallback keeps legacy end clamping for negative and oversized indices`() {
        val target = listOf(MigrationChapter(10, 0, 1f, null, 0), MigrationChapter(20, 0, 2f, null, 0))
        assertEquals(20L, MigrationChapterMapper.idAtIndex(target, -1))
        assertEquals(20L, MigrationChapterMapper.idAtIndex(target, 99))
    }
}
