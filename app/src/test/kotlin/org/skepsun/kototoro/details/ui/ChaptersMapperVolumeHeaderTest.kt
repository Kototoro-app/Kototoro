package org.skepsun.kototoro.details.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class ChaptersMapperVolumeHeaderTest {

    private fun chapter(volume: Int, scanlator: String? = null) = ContentChapter(
        id = volume.toLong() + 1,
        title = "c",
        number = 1f,
        volume = volume,
        url = "u$volume",
        scanlator = scanlator,
        uploadDate = 0L,
        branch = null,
        source = TestSource,
    )

    private data object TestSource : ContentSource {
        override val name = "test"
        override val locale = ""
        override val contentType = ContentType.MANGA
    }

    @Test
    fun `no volumes and no groups means no headers`() {
        assertFalse(shouldShowVolumeHeaders(listOf(chapter(0), chapter(0))))
    }

    @Test
    fun `any volume or group shows headers`() {
        assertTrue(shouldShowVolumeHeaders(listOf(chapter(0), chapter(2))))
        assertTrue(shouldShowVolumeHeaders(listOf(chapter(0, scanlator = "Team"))))
    }
}
