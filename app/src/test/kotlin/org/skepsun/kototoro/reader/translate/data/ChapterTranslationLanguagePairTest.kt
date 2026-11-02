package org.skepsun.kototoro.reader.translate.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The chapter translate job and the "translated" badge of the chapter list resolve the language pair
 * through the same helper; if they diverged, a finished job would never light up the badge.
 */
class ChapterTranslationLanguagePairTest {

    @Test
    fun `auto source language follows the content language`() {
        val pair = resolveChapterTranslationLanguagePair(
            preferredSourceLanguage = "auto",
            contentLanguage = "ja",
            targetLanguage = "zh",
        )

        assertEquals("ja" to "zh", pair)
    }

    @Test
    fun `explicit source language wins over the content language`() {
        val pair = resolveChapterTranslationLanguagePair(
            preferredSourceLanguage = "ko",
            contentLanguage = "ja",
            targetLanguage = "zh",
        )

        assertEquals("ko" to "zh", pair)
    }

    @Test
    fun `missing target language falls back to chinese`() {
        val pair = resolveChapterTranslationLanguagePair(
            preferredSourceLanguage = "ja",
            contentLanguage = null,
            targetLanguage = null,
        )

        assertEquals("ja" to "zh", pair)
    }

    @Test
    fun `target language is normalized to a plain tag`() {
        val pair = resolveChapterTranslationLanguagePair(
            preferredSourceLanguage = "ja",
            contentLanguage = "ja",
            targetLanguage = "ZH-Hans",
        )

        assertEquals("ja" to "zh", pair)
    }
}
