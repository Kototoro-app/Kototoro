package org.skepsun.kototoro.reader.novel.tts

import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.Util
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.tts.player.ExoPlayerController

class TtsPlaybackPitchTest {
    @BeforeEach
    fun setUp() {
        // The JVM Android stubs have no device fingerprint for Media3's emulator detection.
        mockkStatic(Util::class)
        every { Util.isRunningOnEmulator() } returns false
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Util::class)
    }

    @Test
    fun `changing pitch preserves the current playback speed`() {
        var parameters = PlaybackParameters(1.75f, 1f)
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.playbackParameters } answers { parameters }
        every { player.playbackParameters = any() } answers { parameters = firstArg() }
        val controller = ExoPlayerController(player)

        controller.setPitch(0.75f)

        assertEquals(1.75f, parameters.speed)
        assertEquals(0.75f, parameters.pitch)
    }

    @Test
    fun `invalid pitch settings cannot reach the player as unsupported parameters`() {
        var parameters = PlaybackParameters(1.25f, 1f)
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.playbackParameters } answers { parameters }
        every { player.playbackParameters = any() } answers { parameters = firstArg() }
        val controller = ExoPlayerController(player)

        for ((pitch, expected) in listOf(Float.NaN to 1f, Float.POSITIVE_INFINITY to 1f, 0f to 0.5f, 10f to 2f)) {
            controller.setPitch(pitch)
            assertEquals(1.25f, parameters.speed)
            assertEquals(expected, parameters.pitch)
        }
    }
}
