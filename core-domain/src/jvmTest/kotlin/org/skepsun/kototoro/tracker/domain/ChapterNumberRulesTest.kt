package org.skepsun.kototoro.tracker.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChapterNumberRulesTest {

    private fun assertNumber(work: String, chapter: String, expected: Float) =
        assertEquals(expected, ChapterNumberRules.recognize(work, chapter)!!, 0.001f, chapter)

    @Test
    fun `recognizes the cases Mihon's ChapterRecognition covers`() {
        assertNumber("Mokushiroku Alice", "Mokushiroku Alice Vol.1 Ch.4: Misrepresentation", 4f)
        assertNumber("Mokushiroku Alice", "Mokushiroku Alice Vol. 1 Ch. 4: Misrepresentation", 4f)
        assertNumber("Mokushiroku Alice", "Mokushiroku Alice Vol.1 Ch.4.1: Misrepresentation", 4.1f)
        assertNumber("Mokushiroku Alice", "Mokushiroku Alice Vol.1 Ch.4.b: Misrepresentation", 4.2f)
        assertNumber("Mokushiroku Alice", "Mokushiroku Alice Vol.1 Ch.4.extra: Misrepresentation", 4.99f)
        assertNumber("Bleach", "Bleach 567 Down With Snowwhite", 567f)
        assertNumber("Bleach", "Bleach 567.a Down With Snowwhite", 567.1f)
        assertNumber("Solanin", "Solanin 028 Vol. 2", 28f)
        assertNumber("Onepunch-Man", "Onepunch-Man Punch Ver002 028.extra", 28.99f)
        assertNumber("random", "Vol.1 Ch.5v.2: Alones", 5f)
        assertNumber("Ayame 14", "Ayame 14 1 - The summer of 14", 1f)
        assertNumber("random", "Vol.001 Ch.003: Kaguya Doesn't Know Much", 3f)
        assertNumber("Ansatsu Kyoushitsu", "Ansatsu Kyoushitsu 011v002: Assembly Time", 11f)
        assertNumber("One-punch Man", "Mag Version 195.5", 195.5f)
        assertNumber("random", "Fairy Tail 404: 00:00", 404f)
        assertNull(ChapterNumberRules.recognize("random", "Foo"))
        assertNull(ChapterNumberRules.recognize("random", null))
    }

    @Test
    fun `recognizes numbers in Chinese and Japanese titles`() {
        assertNumber("海贼王", "第1100话 新时代", 1100f)
        assertNumber("某作品", "第12.5话", 12.5f)
        assertNumber("某作品", "第3卷 第27话", 27f)
    }

    @Test
    fun `numbers the source gave are trusted unless they only count positions`() {
        data class Chapter(val title: String, val number: Float)
        val given = listOf(Chapter("Prologue", 0f), Chapter("A", 1f), Chapter("B", 1.5f), Chapter("C", 2f))
        assertEquals(listOf(null, 1f, 1.5f, 2f), ChapterNumberRules.trusted(given, "Work", Chapter::title, Chapter::number))

        // Numbered 1..n in order: positions, so the titles say what the chapters are.
        val positional = listOf(Chapter("Chapter 10", 1f), Chapter("Chapter 11", 2f), Chapter("Extra", 3f))
        assertEquals(listOf(10f, 11f, null),
            ChapterNumberRules.trusted(positional, "Work", Chapter::title, Chapter::number))

        // A number two chapters share names neither of them.
        val shared = listOf(Chapter("Side story", 5f), Chapter("Bonus", 5f), Chapter("Next", 6f))
        assertEquals(listOf(null, null, 6f), ChapterNumberRules.trusted(shared, "Work", Chapter::title, Chapter::number))
    }
}
