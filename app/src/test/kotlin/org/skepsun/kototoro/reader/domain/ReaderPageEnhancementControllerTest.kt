package org.skepsun.kototoro.reader.domain

import android.net.Uri
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderOcrEngine
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.core.prefs.ReaderTranslationPipelineMode
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.translate.domain.ReaderPageTranslationProcessor

class ReaderPageEnhancementControllerTest {

    private val settings = mockk<AppSettings>(relaxed = true) {
        every { isReaderTranslationEnabled } returns true
        every { readerTranslationOcrEngine } returns ReaderOcrEngine.PADDLE
        every { readerTranslationPipelineMode } returns ReaderTranslationPipelineMode.TWO_STAGE
        every { readerTranslationMode } returns ReaderTranslationMode.API_ONLY
    }
    private val translationProcessor = mockk<ReaderPageTranslationProcessor> {
        coEvery { peekRendered(any(), any(), any(), any()) } returns null
    }
    private val controller = ReaderPageEnhancementController(settings, translationProcessor)

    @Test
    fun `content zip page is converted before translation`() = runTest {
        val sourceUri = uri("content+zip")
        val convertedUri = uri("file")

        val prepared = controller.preparePage(page, sourceUri) { convertedUri }

        prepared.translationSourceUri shouldBe convertedUri
        prepared.displayUri shouldBe convertedUri
        prepared.shouldScheduleTranslation shouldBe true
    }

    @Test
    fun `file zip page is converted before translation`() = runTest {
        val convertedUri = uri("file")

        val prepared = controller.preparePage(page, uri("file+zip")) { convertedUri }

        prepared.translationSourceUri shouldBe convertedUri
    }

    @Test
    fun `plain file page is not converted`() = runTest {
        val sourceUri = uri("file")

        val prepared = controller.preparePage(page, sourceUri) { error("must not convert $it") }

        prepared.translationSourceUri shouldBe sourceUri
    }

    @Test
    fun `translation bookKey is forwarded to processor and reflected in work signature`() = runTest {
        val sourceUri = uri("file")
        val renderedUri = uri("file")
        val bookKey = "TEST:42"
        coEvery { translationProcessor.process(any(), any(), any(), any(), any()) } returns renderedUri
        coEvery { translationProcessor.retranslateBubble(any(), any(), any(), any(), any()) } returns true

        val beforeSignature = controller.currentWorkSignature()
        controller.setTranslationLanguageContext(
            translatedLanguage = "en",
            sourceLanguage = "ja",
            branch = null,
            bookKey = bookKey,
        )
        val afterSignature = controller.currentWorkSignature()
        afterSignature shouldNotBe beforeSignature

        controller.preparePage(page, sourceUri) { error("must not convert $it") }
        controller.scheduleTranslation(page, sourceUri, this) {}
        advanceUntilIdle()
        controller.retranslateBubble(page.id, 0, "orig", "corrected") shouldBe true

        coVerify { translationProcessor.peekRendered(page, sourceUri, any(), bookKey) }
        coVerify { translationProcessor.process(page, sourceUri, any(), any(), bookKey) }
        coVerify { translationProcessor.retranslateBubble(page.id, 0, "orig", "corrected", bookKey) }
    }

    private fun uri(scheme: String) = mockk<Uri> {
        every { this@mockk.scheme } returns scheme
    }

    private val page = ContentPage(
        id = 1L,
        url = "page.jpg",
        preview = null,
        source = TestSource,
    )

    private data object TestSource : ContentSource {
        override val name: String = "TEST"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }
}
