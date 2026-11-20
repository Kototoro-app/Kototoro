package org.skepsun.kototoro.reader.domain

import android.net.Uri
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.translate.domain.ReaderPageTranslationProcessor

class ReaderPageEnhancementControllerTest {

    private val settings = mockk<AppSettings> {
        every { isReaderTranslationEnabled } returns true
    }
    private val translationProcessor = mockk<ReaderPageTranslationProcessor> {
        coEvery { peekRendered(any(), any(), any()) } returns null
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
