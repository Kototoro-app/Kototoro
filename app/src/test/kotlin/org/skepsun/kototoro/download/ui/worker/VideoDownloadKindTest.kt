package org.skepsun.kototoro.download.ui.worker

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.video.player.PlaybackMediaKind

/**
 * Issue #508: an HLS stream whose URL does not end in `.m3u8` was downloaded as a plain file,
 * saving the few-kilobyte playlist as the video. The player recognised the same stream through
 * the declared kind or the manifest content, so it played online but the download stayed black.
 */
class VideoDownloadKindTest {

    @Test
    fun `declared hls is downloaded as hls without probing`() = runBlocking {
        var probes = 0
        val isHls = shouldDownloadVideoAsHls(PlaybackMediaKind.HLS, "https://cdn.example/play?id=1") {
            probes++
            null
        }
        assertTrue(isHls)
        assertEquals(0, probes)
    }

    @Test
    fun `m3u8 url is downloaded as hls without probing`() = runBlocking {
        var probes = 0
        val isHls = shouldDownloadVideoAsHls(PlaybackMediaKind.AUTO, "https://cdn.example/index.m3u8?t=1") {
            probes++
            null
        }
        assertTrue(isHls)
        assertEquals(0, probes)
    }

    @Test
    fun `unknown kind serving a playlist is downloaded as hls`() = runBlocking {
        val isHls = shouldDownloadVideoAsHls(PlaybackMediaKind.AUTO, "https://cdn.example/stream/42") {
            "﻿#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index\n"
        }
        assertTrue(isHls)
    }

    @Test
    fun `unknown kind serving media is downloaded directly`() = runBlocking {
        val isHls = shouldDownloadVideoAsHls(PlaybackMediaKind.AUTO, "https://cdn.example/stream/42") {
            "\u0000\u0000\u0000\u0018ftypmp42"
        }
        assertFalse(isHls)
    }

    @Test
    fun `failed probe falls back to a direct download`() = runBlocking {
        assertFalse(shouldDownloadVideoAsHls(PlaybackMediaKind.AUTO, "https://cdn.example/stream/42") { null })
    }

    @Test
    fun `declared progressive is downloaded directly without probing`() = runBlocking {
        var probes = 0
        val isHls = shouldDownloadVideoAsHls(PlaybackMediaKind.PROGRESSIVE, "https://cdn.example/video") {
            probes++
            "#EXTM3U"
        }
        assertFalse(isHls)
        assertEquals(0, probes)
    }
}
