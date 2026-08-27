package org.skepsun.kototoro.list.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class ContentCardSourceMetadataTest {

    @Test
    fun `badge metadata uses resolved source and normalized language`() {
        val original = TestSource(name = "MIHON_42", locale = "")
        val resolved = TestSource(name = "MIHON_42", locale = "pt-BR")

        val metadata = contentCardSourceMetadata(
            originalSource = original,
            resolvedSource = resolved,
        )

        assertSame(original, metadata.originalSource)
        assertSame(resolved, metadata.resolvedSource)
        assertEquals("PT", metadata.languageText)
    }

    @Test
    fun `badge metadata omits unknown language`() {
        val source = TestSource(name = "LOCAL", locale = "")

        val metadata = contentCardSourceMetadata(
            originalSource = source,
            resolvedSource = source,
        )

        assertEquals(null, metadata.languageText)
    }

    private data class TestSource(
        override val name: String,
        override val locale: String,
        override val contentType: ContentType = ContentType.MANGA,
    ) : ContentSource
}
