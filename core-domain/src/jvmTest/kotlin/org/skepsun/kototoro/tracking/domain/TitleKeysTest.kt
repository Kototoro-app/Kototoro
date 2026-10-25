package org.skepsun.kototoro.tracking.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TitleKeysTest {

    @Test
    fun `normalize folds case, punctuation and repeated whitespace`() {
        assertEquals("spy family", TitleKeys.normalize("  SPY×FAMILY!! "))
        assertEquals("某作品 第二季", TitleKeys.normalize("某作品（第二季）…"))
    }

    @Test
    fun `a trailing source name is dropped when it names one of the sources`() {
        assertEquals("某作品", TitleKeys.normalize("某作品（Kototoro）", listOf("Kototoro")))
        assertEquals("某作品", TitleKeys.normalize("某作品 (kototoro)", listOf("Kototoro", "Other")))
    }

    @Test
    fun `any other bracketed suffix stays part of the title`() {
        assertEquals("某作品 第二季", TitleKeys.normalize("某作品（第二季）", listOf("Kototoro")))
        assertEquals("某作品", TitleKeys.normalize("某作品", listOf("Kototoro")))
    }

    @Test
    fun `a blank suffix never splits a title`() {
        assertEquals("某作品（）", TitleKeys.stripTrailingSourceSuffix("某作品（）", listOf("Kototoro")))
        assertEquals("某作品", TitleKeys.normalize("某作品（）", listOf("Kototoro")))
    }
}
