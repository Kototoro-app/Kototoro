package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.data.LibraryTrackingId
import org.skepsun.kototoro.migration.domain.DuplicateGroupingRules

class DuplicateGroupingRulesTest {

    private fun row(id: Long, title: String, alt: String? = null, type: String? = "MANGA", readTo: Float? = null) =
        LibraryRow(
            id = id, title = title, altTitles = alt, source = "S$id", contentType = type, coverUrl = "",
            chaptersCount = 10, trackResult = null, trackCheckTime = null, trackError = null,
            historyPercent = null, historyChapterNumber = readTo,
        )

    private fun group(rows: List<LibraryRow>, fuzzy: Double? = null) = DuplicateGroupingRules.group(
        rows, emptySet(), emptySet(),
        keysOf = { title, alt ->
            (listOf(title) + alt).map { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKC).lowercase()
                .filter(Char::isLetterOrDigit) }.filter(String::isNotEmpty).toSet()
        },
        familyOf = { it.removePrefix("HENTAI_") },
        fuzzyThreshold = fuzzy,
    )

    @Test
    fun `fuzzy matching joins near titles above the threshold only`() {
        val rows = listOf(row(1, "Sousou no Frieren"), row(2, "Sousou no Furiiren"), row(3, "Frieren", type = "NOVEL"))
        assertTrue(group(rows).isEmpty())
        val fuzzy = group(rows, 0.8).single()
        assertEquals(setOf(1L, 2L), fuzzy.entries.map { it.row.id }.toSet())
        assertTrue(fuzzy.fuzzy)
        assertTrue(group(rows, 0.95).isEmpty())
        // Exactly at the threshold: one edit in five characters is 0.8, whatever the floating-point rounding.
        assertEquals(1, group(listOf(row(1, "abcde"), row(2, "abcxe")), 0.8).size)
        assertEquals(1, group(listOf(row(1, "abcdefghijklmnopqrst"), row(2, "abcdefghijklmnopqXYZ")), 0.5 + 0.05 * 7).size)
        assertFalse(group(listOf(row(1, "Frieren"), row(2, "frieren")), 0.8).single().fuzzy)
    }

    @Test
    fun `different numbers never match fuzzily`() {
        assertTrue(group(listOf(row(1, "Attack on Titan Season 2"), row(2, "Attack on Titan Season 3")), 0.6).isEmpty())
        assertTrue(group(listOf(row(1, "进击的巨人"), row(2, "进击的巨人2")), 0.6).isEmpty())
        assertTrue(group(listOf(row(1, "进击的巨人第二季"), row(2, "进击的巨人第三季")), 0.6).isEmpty())
        assertEquals(1, group(listOf(row(1, "进击的巨人第二季"), row(2, "进击之巨人第二季")), 0.8).size)
        assertEquals(1, group(listOf(row(1, "Attack on Titan 02"), row(2, "Attack on Titan: 2")), 0.9).size)
    }

    /** Calibration set for the default threshold (0.8): spellings of one work join, sequels and spin-offs do not. */
    @Test
    fun `default threshold separates spellings of one work from related works`() {
        val same = listOf(
            "Sousou no Frieren" to "Sousou no Furiiren", "Frieren Sousou no" to "Sousou no Frieren",
            "The Apothecary Diaries" to "Apothecary Diaries", "Shingeki no Kyojin" to "Shingeki no Kyoujin",
            "关于我转生变成史莱姆这档事" to "关于我转生成史莱姆这件事", "辉夜大小姐想让我告白" to "辉夜大小姐想让我告白～天才们的恋爱头脑战～",
            "Kaguya-sama: Love Is War" to "Kaguya-sama: Love is War (Official)", "我独自升级" to "我独自升级（Solo Leveling）",
            "Kimetsu no Yaiba" to "Kimetsu no Yaiba [Colored]", "Boku no Hero Academia" to "Boku no Hīrō Akademia",
            "Tensei shitara Slime Datta Ken" to "Tensei Shitara Suraimu Datta Ken",
        )
        val different = listOf(
            "进击的巨人" to "进击的巨人 无悔的选择", "One Piece" to "One Punch-Man", "魔法少女小圆" to "魔法少女奈叶",
            "Love Live!" to "Love Live! Sunshine!!", "海贼王" to "海贼王者", "Dragon Ball" to "Dragon Ball Super",
            "Tokyo Ghoul" to "Tokyo Ghoul:re", "我的英雄学院" to "我的英雄学院 非法英雄", "Kingdom" to "Kingdom Hearts",
            "Monster" to "Monsters", "Re:Zero kara Hajimeru Isekai Seikatsu" to "Re:Zero kara Hajimeru Break Time",
            "Yuru Camp" to "Yuru Yuri", "Spy x Family" to "Spy Classroom", "Blue Lock" to "Blue Period",
        )
        for ((a, b) in same) assertEquals(1, group(listOf(row(1, a), row(2, b)), 0.8).size, "$a | $b")
        for ((a, b) in different) assertTrue(group(listOf(row(1, a), row(2, b)), 0.8).isEmpty(), "$a | $b")
    }

    @Test
    fun `confirmed tracking entries are exact while automatic matches need fuzzy matching`() {
        val rows = listOf(row(1, "间谍过家家"), row(2, "SPY×FAMILY"), row(3, "其他"), row(4, "动画版", type = "VIDEO"))
        val ids = listOf(
            LibraryTrackingId(1, 3, 50, manual = true), LibraryTrackingId(2, 3, 50, manual = true),
            LibraryTrackingId(3, 2, 7, manual = false), LibraryTrackingId(1, 2, 7, manual = false),
            // The same number on another family is another remote entry (MAL anime and manga ids are distinct).
            LibraryTrackingId(4, 3, 50, manual = true),
        )
        fun grouped(fuzzy: Double?) = DuplicateGroupingRules.group(rows, emptySet(), emptySet(),
            { title, alt -> (listOf(title) + alt).map { it.lowercase() }.toSet() }, { it }, fuzzy, ids)
        val exact = grouped(null).single()
        assertEquals(setOf(1L, 2L), exact.entries.map { it.row.id }.toSet())
        assertFalse(exact.fuzzy)
        val fuzzy = grouped(0.95).single()
        assertEquals(setOf(1L, 2L, 3L), fuzzy.entries.map { it.row.id }.toSet())
        assertTrue(fuzzy.fuzzy)
    }

    @Test
    fun `platform keys and families decide the groups`() {
        val groups = group(listOf(row(1, "Frieren"), row(2, "frieren!"), row(3, "Frieren", type = "NOVEL"),
            row(4, "Other", alt = "FRIEREN"), row(5, "Untyped", type = null)))
        assertEquals(setOf(1L, 2L, 4L), groups.single().entries.map { it.row.id }.toSet())
        assertEquals("1,2,4", groups.single().key)
    }

    @Test
    fun `only the furthest duplicate beyond the kept entry donates progress`() {
        val keep = row(1, "a", readTo = 5f)
        assertEquals(3L, DuplicateGroupingRules.progressDonor(keep, listOf(row(2, "a", readTo = 7f),
            row(3, "a", readTo = 9f), row(4, "a", readTo = 2f)))?.id)
        assertNull(DuplicateGroupingRules.progressDonor(keep, listOf(row(2, "a", readTo = 4f))))
        assertTrue(DuplicateGroupingRules.isFurther(row(2, "a", readTo = 6f), keep))
    }
}
