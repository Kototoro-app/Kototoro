package org.skepsun.kototoro.core.ui.chapters

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChapterGroupingTest {
    private data class Chapter(val id: Long, val branch: String?, val volume: Int = 0, val scanlator: String? = null)

    private val zh = ChapterBranchLocale("中文", "中文 (中国)")

    private fun preferred(chapters: List<Chapter>, history: Long? = null, locales: List<ChapterBranchLocale> = listOf(zh)) =
        resolvePreferredChapterBranch(chapters, { it.branch }, { it.id }, history, locales)

    private val mixed = listOf(
        Chapter(1, "English"), Chapter(2, "English"), Chapter(3, "English"),
        Chapter(4, "中文翻译组"), Chapter(5, "中文翻译组"),
        Chapter(6, null),
    )

    @Test
    fun `history branch wins, then the locale match, then the largest branch`() {
        assertEquals(null, preferred(mixed, history = 6))
        assertEquals("中文翻译组", preferred(mixed))
        assertEquals("English", preferred(mixed, locales = listOf(ChapterBranchLocale("日本語", "日本語 (日本)"))))
        assertEquals("English", preferred(mixed, history = 99, locales = emptyList()))
        assertNull(preferred(emptyList()))
        assertEquals("Only", preferred(listOf(Chapter(1, "Only"))))
    }

    @Test
    fun `branch options appear only for several branches and the list falls back to the largest branch`() {
        assertEquals(emptyList<ChapterBranchOption>(), chapterBranchOptions(listOf(Chapter(1, "A")), { it.branch }))
        assertEquals(
            listOf(ChapterBranchOption(null, 1), ChapterBranchOption("English", 3), ChapterBranchOption("中文翻译组", 2)),
            chapterBranchOptions(mixed, { it.branch }),
        )
        assertEquals(listOf(4L, 5L), chaptersOfBranch(mixed, { it.branch }, "中文翻译组").map { it.id })
        assertEquals(listOf(1L, 2L, 3L), chaptersOfBranch(mixed, { it.branch }, "removed").map { it.id })
        val single = listOf(Chapter(1, "A"), Chapter(2, "A"))
        assertEquals(single, chaptersOfBranch(single, { it.branch }, "other"))
    }

    @Test
    fun `an inconsistent comparator keeps source order instead of failing`() {
        val chaos = Comparator<String?> { _, _ -> if (Math.random() < .5) -1 else 1 }
        val many = (0 until 64).map { Chapter(it.toLong(), "b$it") }
        assertEquals(64, chapterBranchOptions(many, { it.branch }, chaos).size)
    }

    @Test
    fun `volume headers follow Android's volume and group-name rules`() {
        val flat = listOf(Chapter(1, null), Chapter(2, null))
        assertTrue(withVolumeSections(flat, { it.volume }, { it.scanlator }).all { it is ChapterSection.Item })

        val volumes = listOf(Chapter(1, null, 0), Chapter(2, null, 1), Chapter(3, null, 1), Chapter(4, null, 2))
        assertEquals(
            listOf("H0", 1L, "H1", 2L, 3L, "H2", 4L),
            withVolumeSections(volumes, { it.volume }, { it.scanlator }).map {
                when (it) { is ChapterSection.Header -> "H${it.volume}"; is ChapterSection.Item -> it.chapter.id }
            },
        )

        val groups = listOf(Chapter(1, null, 0, "第一部"), Chapter(2, null, 0, "第一部"), Chapter(3, null, 0, "第二部"))
        assertEquals(
            listOf("第一部", 1L, 2L, "第二部", 3L),
            withVolumeSections(groups, { it.volume }, { it.scanlator }).map {
                when (it) { is ChapterSection.Header -> it.customName; is ChapterSection.Item -> it.chapter.id }
            },
        )
    }
}
