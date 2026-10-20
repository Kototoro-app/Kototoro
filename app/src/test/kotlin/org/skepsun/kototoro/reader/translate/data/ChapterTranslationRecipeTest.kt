package org.skepsun.kototoro.reader.translate.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A stored chapter translation is only "ready" while the recipe that produced it still matches the
 * settings. Changing one produced-pixel input must invalidate it; changing nothing must stay stable, or
 * the chapter list would flag every finished chapter as stale on each redraw.
 */
class ChapterTranslationRecipeTest {

    @Test
    fun `fingerprint is stable for the same recipe`() {
        assertEquals(recipe().fingerprint(), recipe().fingerprint())
    }

    @Test
    fun `changing the translation backend invalidates the fingerprint`() {
        val llm = recipe(backendEndpoint = "https://api.example.com/v1", backendModel = "gemini-2.5-flash")
        val otherModel = recipe(backendEndpoint = "https://api.example.com/v1", backendModel = "gpt-4o-mini")

        assertNotEquals(llm.fingerprint(), otherModel.fingerprint())
    }

    @Test
    fun `changing the language pair invalidates the fingerprint`() {
        val toChinese = recipe(targetLang = "zh")
        val toEnglish = recipe(targetLang = "en")

        assertNotEquals(toChinese.fingerprint(), toEnglish.fingerprint())
    }

    @Test
    fun `changing the bubble layout invalidates the fingerprint`() {
        val balanced = recipe(bubbleGroupingTuning = "BALANCED")
        val strict = recipe(bubbleGroupingTuning = "STRICT")

        assertNotEquals(balanced.fingerprint(), strict.fingerprint())
    }

    @Test
    fun `engine label names the ocr engine and the translation backend`() {
        val label = recipe(
            ocrEngine = "PADDLE",
            ocrRecognitionModel = "manga_48px_ctc_onnx",
            backendPreset = "GEMINI",
            backendEndpoint = "https://example.com",
            backendModel = "gemini-2.5-flash",
        ).engineLabel()

        assertTrue(label.contains("Paddle"), label)
        assertTrue(label.contains("GEMINI"), label)
        assertTrue(label.contains("gemini-2.5-flash"), label)
    }

    @Test
    fun `mlkit only recipe has no backend model in its label`() {
        val label = recipe(ocrEngine = "MLKIT", backendEndpoint = "", backendOnnxModel = "manga_48px_ctc_onnx")
            .engineLabel()

        assertTrue(label.contains("MLKit"), label)
        assertTrue(label.contains("ONNX"), label)
    }

    @Test
    fun `legacy record without fingerprint is not flagged stale`() {
        val state = resolveChapterTranslationState(
            record = record(fingerprint = ""),
            key = key(fingerprint = "abc123"),
        )

        assertTrue(state.isPrepared)
        assertFalse(state.isStale)
    }

    @Test
    fun `record from another recipe is flagged stale`() {
        val state = resolveChapterTranslationState(
            record = record(fingerprint = "oldrecipe"),
            key = key(fingerprint = "newrecipe"),
        )

        assertTrue(state.isPrepared)
        assertTrue(state.isStale)
    }

    @Test
    fun `record matching the current recipe stays ready`() {
        val state = resolveChapterTranslationState(
            record = record(fingerprint = "same"),
            key = key(fingerprint = "same"),
        )

        assertFalse(state.isStale)
        assertEquals(2, state.version)
    }

    private fun recipe(
        ocrEngine: String = "MLKIT",
        ocrRecognitionModel: String = "",
        backendPreset: String = "CUSTOM",
        backendEndpoint: String = "",
        backendModel: String = "gpt-4o-mini",
        backendOnnxModel: String = "",
        bubbleGroupingTuning: String = "BALANCED",
        sourceLang: String = "ja",
        targetLang: String = "zh",
    ) = ChapterTranslationRecipe(
        ocrEngine = ocrEngine,
        ocrMode = "BASIC",
        ocrDetectionModel = "",
        ocrRecognitionModel = ocrRecognitionModel,
        ocrDetectionMaxSide = 1280,
        ocrDetectionThreshold = 0.3f,
        ocrMinBoxSize = 8,
        ocrRecognitionThreshold = 0.5f,
        ocrRecognitionMaxWidth = 1024,
        ocrRecognitionBatchSize = 4,
        backendPreset = backendPreset,
        backendEndpoint = backendEndpoint,
        backendModel = backendModel,
        backendOnnxModel = backendOnnxModel,
        isBubbleDetectorEnabled = true,
        isBubbleGroupingEnabled = true,
        bubbleGroupingTuning = bubbleGroupingTuning,
        isQualityFilterEnabled = true,
        renderStyle = "COMPACT_OVERLAY",
        overlayCompactness = "BALANCED",
        sourceLang = sourceLang,
        targetLang = targetLang,
    )

    private fun record(fingerprint: String) = ChapterTranslationStore.Record(
        sourceLang = "ja",
        targetLang = "zh",
        pages = 18,
        preparedAt = 1_700_000_000_000L,
        fingerprint = fingerprint,
        engineLabel = "MLKit → MLKit",
        version = 2,
    )

    private fun key(fingerprint: String) = ChapterTranslationKey(
        fingerprint = fingerprint,
        engineLabel = "MLKit → MLKit",
        sourceLang = "ja",
        targetLang = "zh",
    )
}
