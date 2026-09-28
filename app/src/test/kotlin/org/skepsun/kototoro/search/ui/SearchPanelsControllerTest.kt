package org.skepsun.kototoro.search.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class SearchPanelsControllerTest {

    private data object TestSource : ContentSource {
        override val name = "test"
        override val locale = ""
        override val contentType = ContentType.MANGA
    }

    private fun content(id: Long, title: String = "Work $id") = Content(
        id = id,
        title = title,
        altTitles = emptySet(),
        url = "/work/$id",
        publicUrl = "https://example.test/work/$id",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = TestSource,
    )

    @Test
    fun `filter defaults to the setting, but a saved value wins`() {
        assertFalse(SearchPanelsController(SavedStateHandle(), defaultFilterOpen = false).state.value.filterOpen)
        val saved = SavedStateHandle(mapOf("search_panels_filter_open" to true))
        assertTrue(SearchPanelsController(saved, defaultFilterOpen = false).state.value.filterOpen)
    }

    @Test
    fun `panel state is written to the saved state handle`() {
        val handle = SavedStateHandle()
        val controller = SearchPanelsController(handle, defaultFilterOpen = false)
        controller.setFilterOpen(true)
        controller.openPreview(content(7))
        assertEquals(true, handle.get<Boolean>("search_panels_filter_open"))
        assertEquals(7L, handle.get<Long>("search_panels_preview_id"))

        controller.closePreview()
        assertNull(controller.state.value.previewContentId)
        assertNull(controller.state.value.previewContent)
        assertNull(handle.get<Long>("search_panels_preview_id"))
    }

    @Test
    fun `loaded details only replace the preview they belong to`() {
        val controller = SearchPanelsController(SavedStateHandle(), defaultFilterOpen = false)
        controller.openPreview(content(1))
        controller.updatePreviewContent(content(2, "Other"))
        assertEquals("Work 1", controller.state.value.previewContent?.title)
        controller.updatePreviewContent(content(1, "Loaded"))
        assertEquals("Loaded", controller.state.value.previewContent?.title)
    }

    @Test
    fun `an empty list never closes the preview`() {
        val controller = SearchPanelsController(SavedStateHandle(), defaultFilterOpen = false)
        controller.openPreview(content(3))
        controller.restorePreviewFrom(emptyList())
        assertEquals(3L, controller.state.value.previewContentId)
        assertEquals("Work 3", controller.state.value.previewContent?.title)
    }

    @Test
    fun `after process death the preview content is picked up from the list`() {
        val handle = SavedStateHandle(mapOf("search_panels_preview_id" to 5L))
        val controller = SearchPanelsController(handle, defaultFilterOpen = false)
        assertNull(controller.state.value.previewContent)
        controller.restorePreviewFrom(listOf(content(4), content(5)))
        assertEquals(5L, controller.state.value.previewContent?.id)
    }
}
