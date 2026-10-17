package org.skepsun.kototoro.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.tracking.domain.TrackingChapter
import org.skepsun.kototoro.tracking.domain.resolveAbsoluteChapterNumber

class AbsoluteChapterResolverTest {
    @Test
    fun `season numbering and duplicate translation branches share the Android chronology`() {
        val first = (1..12).map { TrackingChapter(it.toLong(), it.toFloat(), "A", "S1 第 $it 集", "/s1/$it") }
        val second = (1..3).map { TrackingChapter(100L + it, it.toFloat(), "A", "S2 第 $it 集", "/s2/$it") }
        val unrelated = TrackingChapter(900, 999f, "B", "另一分支", "/b")
        val chapters = first + second + unrelated
        assertEquals(15, resolveAbsoluteChapterNumber(chapters, second.last()))
        assertEquals(999, resolveAbsoluteChapterNumber(chapters, unrelated))
    }

    @Test
    fun `newest first order and missing chapter numbers keep stable progress`() {
        val chapters = (1..12).map { TrackingChapter(it.toLong(), it.toFloat(), null, null, "/$it") }.reversed()
        assertEquals(8, resolveAbsoluteChapterNumber(chapters, chapters.first { it.id == 8L }))
        val unnumbered = (1..3).map { TrackingChapter(it.toLong(), 0f, null, null, "/$it") }
        assertEquals(3, resolveAbsoluteChapterNumber(unnumbered, unnumbered.last()))
    }
}
