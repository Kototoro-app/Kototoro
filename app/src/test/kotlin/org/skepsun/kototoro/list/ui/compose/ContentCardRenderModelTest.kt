package org.skepsun.kototoro.list.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class ContentCardRenderModelTest {

    @Test
    fun `detailed content is projected to immutable card text and badge values`() {
        val content = Content(
            id = 42L,
            title = "Original title",
            altTitles = emptySet(),
            url = "/item/42",
            publicUrl = "https://example.test/item/42",
            rating = 0.8f,
            contentRating = null,
            coverUrl = "https://example.test/cover.jpg",
            tags = emptySet(),
            state = null,
            authors = linkedSetOf("Author A", "Author B"),
            source = TestSource,
        )
        val item = ContentDetailedListModel(
            manga = content,
            override = null,
            subtitle = "Subtitle",
            supportingText = "Supporting",
            counter = 3,
            projectionCount = 2,
            progress = null,
            isFavorite = true,
            isSaved = false,
            tags = listOf(ChipModel(title = "Action"), ChipModel(title = "Drama")),
            isPinned = true,
            scoreText = "8.0",
        )

        val renderModel = item.toContentCardRenderModel()

        assertEquals(42L, renderModel.id)
        assertEquals("Original title", renderModel.title)
        assertEquals("Author A, Author B", renderModel.authorText)
        assertEquals("Action, Drama", renderModel.tagsText)
        assertEquals("Subtitle", renderModel.subtitle)
        assertEquals("Supporting", renderModel.supportingText)
        assertTrue(renderModel.isFavorite)
        assertTrue(renderModel.isPinned)
        assertEquals(3, renderModel.counter)
        assertEquals(2, renderModel.projectionCount)
        assertEquals("8.0", renderModel.scoreText)
    }

    private object TestSource : ContentSource {
        override val name: String = "TEST"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }
}
