package org.skepsun.kototoro.details.ui.model

import org.skepsun.kototoro.core.model.TestContentSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DetailsSourcePresentationTest {

    @Test
    fun `metadata role uses binding subtitle and resolved source label`() {
        val option = DetailsSourceOption(
            key = "base:TEST",
            source = TestContentSource,
            title = "Local Title",
        )

        val model = option.toPresentationModel(
            context = DetailsSourceDisplayContext(
                role = DetailsSourceRole.ENTITY_METADATA,
                currentContentTitle = "Current Title",
                currentContentSourceName = TestContentSource.name,
                resolvedSourceTitle = "Test Source",
                strings = displayStrings,
                isSelected = true,
            ),
        )

        assertEquals("Local Title", model.title)
        assertEquals("Metadata binding · Test Source", model.subtitle)
    }

    @Test
    fun `reading role marks selected option as current projection`() {
        val option = DetailsSourceOption(
            key = "reading:1",
            source = TestContentSource,
            title = "Projection A",
        )

        val model = option.toPresentationModel(
            context = DetailsSourceDisplayContext(
                role = DetailsSourceRole.READING_PROJECTION,
                resolvedSourceTitle = "Test Source",
                strings = displayStrings,
                isSelected = true,
            ),
        )

        assertEquals("Projection A", model.title)
        assertEquals("Current projection · Test Source", model.subtitle)
    }

    @Test
    fun `reading role marks non selected option as switchable projection`() {
        val option = DetailsSourceOption(
            key = "reading:2",
            source = TestContentSource,
            title = "Projection B",
        )

        val model = option.toPresentationModel(
            context = DetailsSourceDisplayContext(
                role = DetailsSourceRole.READING_PROJECTION,
                resolvedSourceTitle = "Test Source",
                strings = displayStrings,
                isSelected = false,
            ),
        )

        assertEquals("Projection B", model.title)
        assertEquals("Switchable projection · Test Source", model.subtitle)
    }

    private companion object {
        val displayStrings = DetailsSourceDisplayStrings(
            unavailableText = "Unavailable",
            metadataBindingLabel = "Metadata binding",
            currentProjectionLabel = "Current projection",
            switchableProjectionLabel = "Switchable projection",
        )
    }
}
