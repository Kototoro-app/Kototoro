package org.skepsun.kototoro.scrobbling.discord.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DiscordRpcVideoTest {

    @Test
    fun `computeVideoRpcTimestamps computes start and end while playing`() {
        val (start, end) = computeVideoRpcTimestamps(
            nowMs = 1_000_000L,
            positionMs = 60_000L,
            durationMs = 1_440_000L,
            isPlaying = true,
            playbackSpeed = 1f,
        )
        assertEquals(940_000L, start)
        assertEquals(2_380_000L, end)
    }

    @Test
    fun `computeVideoRpcTimestamps scales by playback speed`() {
        val (start, end) = computeVideoRpcTimestamps(
            nowMs = 1_000_000L,
            positionMs = 120_000L,
            durationMs = 600_000L,
            isPlaying = true,
            playbackSpeed = 2f,
        )
        assertEquals(940_000L, start)
        assertEquals(1_240_000L, end)
    }

    @Test
    fun `computeVideoRpcTimestamps omits end timestamp when paused or duration unknown`() {
        val (pausedStart, pausedEnd) = computeVideoRpcTimestamps(
            nowMs = 1_000_000L,
            positionMs = 60_000L,
            durationMs = 1_440_000L,
            isPlaying = false,
            playbackSpeed = 1f,
            fallbackStartMs = 900_000L,
        )
        assertEquals(900_000L, pausedStart)
        assertNull(pausedEnd)

        val (unknownStart, unknownEnd) = computeVideoRpcTimestamps(
            nowMs = 1_000_000L,
            positionMs = 60_000L,
            durationMs = 0L,
            isPlaying = true,
            playbackSpeed = 1f,
        )
        assertEquals(1_000_000L, unknownStart)
        assertNull(unknownEnd)
    }

    @Test
    fun `resolveVideoRpcStateText prefers episode count for multi-episode series`() {
        val state = resolveVideoRpcStateText(
            title = "Frieren",
            episodeTitle = "Episode 3",
            episodeNumber = 3,
            episodesTotal = 28,
            formatEpisodeOfTotal = { ep, total -> "Episode $ep of $total" },
        )
        assertEquals("Episode 3 of 28", state)
    }

    @Test
    fun `resolveVideoRpcStateText uses episode title for single item when distinct`() {
        val state = resolveVideoRpcStateText(
            title = "Spirited Away",
            episodeTitle = "1080p BluRay",
            episodeNumber = 1,
            episodesTotal = 1,
            formatEpisodeOfTotal = { ep, total -> "Episode $ep of $total" },
        )
        assertEquals("1080p BluRay", state)
    }

    @Test
    fun `buildDiscordButtons filters non-http urls and oversized labels`() {
        val buttons = buildDiscordButtons(
            listOf(
                "Watch on Kototoro" to "https://kototoro.app/manga/1",
                "Local" to "file:///storage/emulated/0/video.mp4",
                "A".repeat(33) to "https://example.com/too-long",
                "Watch on Source" to "http://example.com/anime/1",
            ),
        )
        assertEquals(2, buttons.size)
        assertEquals("Watch on Kototoro", buttons[0]["name"])
        assertEquals("https://kototoro.app/manga/1", buttons[0]["url"])
        assertEquals("Watch on Source", buttons[1]["name"])
        assertEquals("http://example.com/anime/1", buttons[1]["url"])
    }
}
