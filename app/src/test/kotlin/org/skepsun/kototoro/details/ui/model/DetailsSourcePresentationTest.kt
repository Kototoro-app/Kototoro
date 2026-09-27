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
                role = DetailsSourceRole.METADATA,
                currentContentTitle = "Current Title",
                currentContentSourceName = TestContentSource.name,
                resolvedSourceTitle = "Test Source",
                strings = displayStrings,
            ),
        )

        assertEquals("Local Title", model.title)
        assertEquals("Metadata binding · Test Source", model.subtitle)
    }

    @Test
    fun `reading role labels the option as the reading source`() {
        val option = DetailsSourceOption(
            key = "reading:1",
            source = TestContentSource,
            title = "Work A",
        )

        val model = option.toPresentationModel(
            context = DetailsSourceDisplayContext(
                role = DetailsSourceRole.READING_SOURCE,
                resolvedSourceTitle = "Test Source",
                strings = displayStrings,
            ),
        )

        assertEquals("Work A", model.title)
        assertEquals("Reading source · Test Source", model.subtitle)
    }

    private companion object {
        val displayStrings = DetailsSourceDisplayStrings(
            unavailableText = "Unavailable",
            metadataBindingLabel = "Metadata binding",
            readingSourceLabel = "Reading source",
        )
    }
}
