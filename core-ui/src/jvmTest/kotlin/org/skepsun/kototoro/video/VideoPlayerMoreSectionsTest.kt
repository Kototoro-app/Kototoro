package org.skepsun.kototoro.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.video.ui.compose.VideoPlayerMoreSection
import org.skepsun.kototoro.video.ui.compose.orderVideoPlayerMoreItems

class VideoPlayerMoreSectionsTest {

    private data class Entry(val name: String, val section: VideoPlayerMoreSection)

    private fun order(vararg entries: Entry): List<String> =
        orderVideoPlayerMoreItems(entries.toList()) { it.section }.map { it.name }

    @Test
    fun `diagnostics go last even when the host put them in the middle`() {
        assertEquals(
            listOf("quality", "screenshot", "settings", "info"),
            order(
                Entry("quality", VideoPlayerMoreSection.FREQUENT),
                Entry("info", VideoPlayerMoreSection.DIAGNOSTICS),
                Entry("screenshot", VideoPlayerMoreSection.TOOLS),
                Entry("settings", VideoPlayerMoreSection.TOOLS),
            ),
        )
    }

    @Test
    fun `items inside a section keep the host's order`() {
        assertEquals(
            listOf("screenshot", "note", "download", "info"),
            order(
                Entry("screenshot", VideoPlayerMoreSection.TOOLS),
                Entry("note", VideoPlayerMoreSection.TOOLS),
                Entry("download", VideoPlayerMoreSection.TOOLS),
                Entry("info", VideoPlayerMoreSection.DIAGNOSTICS),
            ),
        )
    }

    @Test
    fun `an already ordered menu is returned unchanged`() {
        val entries = listOf(
            Entry("quality", VideoPlayerMoreSection.FREQUENT),
            Entry("screenshot", VideoPlayerMoreSection.TOOLS),
            Entry("info", VideoPlayerMoreSection.DIAGNOSTICS),
        )
        assertEquals(entries, orderVideoPlayerMoreItems(entries) { it.section })
    }

    @Test
    fun `a menu without diagnostics is untouched`() {
        assertEquals(
            listOf("quality", "audio", "subtitles"),
            order(
                Entry("quality", VideoPlayerMoreSection.FREQUENT),
                Entry("audio", VideoPlayerMoreSection.TOOLS),
                Entry("subtitles", VideoPlayerMoreSection.TOOLS),
            ),
        )
    }

    @Test
    fun `empty and single-entry menus are returned as they are`() {
        assertEquals(emptyList<String>(), orderVideoPlayerMoreItems(emptyList<Entry>()) { it.section })
        assertEquals(
            listOf("info"),
            order(Entry("info", VideoPlayerMoreSection.DIAGNOSTICS)),
        )
    }
}
