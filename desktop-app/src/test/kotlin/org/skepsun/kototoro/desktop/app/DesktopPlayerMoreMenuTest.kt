package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.video.ui.compose.VideoPlayerMoreSection

/**
 * The player's 更多 menu is the way the low-frequency controls stay reachable when the row is too narrow for them, so
 * it must follow the file: an entry appears only for something the video can actually do.
 */
class DesktopPlayerMoreMenuTest {

    @Test
    fun `upscaling is always offered because it is the viewer's switch, not the file's`() {
        val items = desktopPlayerMoreItems(DesktopPlayerMoreCapabilities())
        // The speed is offered even for a single-stream file: the control row gives its label up at the narrow tier.
        assertEquals(
            listOf(DesktopPlayerMoreItem.ENHANCEMENT, DesktopPlayerMoreItem.SPEED, DesktopPlayerMoreItem.PLAYBACK_INFO),
            items,
        )
    }

    @Test
    fun `playback info sorts after the actions, and only the diagnostics section is last`() {
        val items = desktopPlayerMoreItems(
            DesktopPlayerMoreCapabilities(streamCount = 3, audioTrackCount = 2, subtitleTrackCount = 4),
        )
        assertEquals(DesktopPlayerMoreItem.PLAYBACK_INFO, items.last())
        assertEquals(VideoPlayerMoreSection.DIAGNOSTICS, desktopPlayerMoreSection(items.last()))
        assertTrue(items.dropLast(1).none { desktopPlayerMoreSection(it) == VideoPlayerMoreSection.DIAGNOSTICS })
    }

    @Test
    fun `diagnostics rows keep their order and say dash when mpv answered nothing`() {
        val rows = desktopPlayerDiagnosticsRows(emptyMap())
        assertEquals(7, rows.size)
        assertTrue(rows.all { it.second == desktopText("videoInfo.unavailable") })
        assertEquals(desktopText("videoInfo.decoder"), rows.first().first)
        assertEquals(desktopText("videoInfo.version"), rows.last().first)
    }

    @Test
    fun `diagnostics rows read the properties and join the resolution`() {
        val rows = desktopPlayerDiagnosticsRows(
            mapOf(
                "hwdec-current" to "videotoolbox",
                "vo" to "gpu",
                "video-codec" to "h264",
                "audio-codec-name" to "aac",
                "width" to "1920",
                "height" to "1080",
                "container-fps" to "23.976",
                "mpv-version" to "mpv 0.40.0",
            ),
        )
        val byLabel = rows.toMap()
        assertEquals("videotoolbox", byLabel[desktopText("videoInfo.decoder")])
        assertEquals("1920 × 1080", byLabel[desktopText("videoInfo.resolution")])
        assertEquals("mpv 0.40.0", byLabel[desktopText("videoInfo.version")])
    }

    @Test
    fun `a half-known resolution falls back to the dash instead of showing one number`() {
        val rows = desktopPlayerDiagnosticsRows(mapOf("width" to "1920"))
        assertEquals(
            desktopText("videoInfo.unavailable"),
            rows.toMap()[desktopText("videoInfo.resolution")],
        )
    }

    @Test
    fun `a single stream, a single audio track and no subtitles add no entries`() {
        val items = desktopPlayerMoreItems(
            DesktopPlayerMoreCapabilities(streamCount = 1, audioTrackCount = 1, subtitleTrackCount = 0),
        )
        assertFalse(DesktopPlayerMoreItem.QUALITY in items)
        assertFalse(DesktopPlayerMoreItem.AUDIO in items)
        assertFalse(DesktopPlayerMoreItem.SUBTITLES in items)
    }

    @Test
    fun `choices the file offers appear in a stable order`() {
        val items = desktopPlayerMoreItems(
            DesktopPlayerMoreCapabilities(streamCount = 3, audioTrackCount = 2, subtitleTrackCount = 4),
        )
        assertEquals(
            listOf(
                DesktopPlayerMoreItem.ENHANCEMENT,
                DesktopPlayerMoreItem.QUALITY,
                DesktopPlayerMoreItem.SPEED,
                DesktopPlayerMoreItem.AUDIO,
                DesktopPlayerMoreItem.SUBTITLES,
                DesktopPlayerMoreItem.PLAYBACK_INFO,
            ),
            items,
        )
    }

    @Test
    fun `each entry has its own stable tag`() {
        val tags = DesktopPlayerMoreItem.entries.map(::desktopPlayerMoreTag)
        assertEquals(tags.size, tags.toSet().size)
        assertTrue(tags.all { it.startsWith("video-more:") })
    }
}
