package org.skepsun.kototoro.desktop.player

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class MpvSoftwareRendererTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: java.nio.file.Path

    @Test
    fun `render API produces colored pixels resizes and closes before the player`() {
        val library = MpvLocator.find()
        assumeTrue(library != null, "no libmpv configured")
        val player = MpvPlayer(requireNotNull(library), options = mapOf("ao" to "null"), renderMode = MpvRenderMode.SOFTWARE)
        player.use {
            it.load(MpvStream(MpvProbeVideo.write(directory.resolve("colors.y4m")).toUri().toString()))
            val deadline = System.nanoTime() + 15_000_000_000L
            var colored = false
            while (System.nanoTime() < deadline && !colored) {
                val frame = it.renderFrame(160, 120)
                assertEquals(160, frame.width)
                assertEquals(120, frame.height)
                colored = (0 until 120 step 10).any { y ->
                    (0 until 160 step 10).any { x ->
                        val rgb = frame.getRGB(x, y)
                        ((rgb shr 16) and 255) != ((rgb shr 8) and 255)
                    }
                }
                frame.flush()
                if (!colored) Thread.sleep(30)
            }
            assertTrue(colored, "No video pixels rendered: ${it.state.value}")
            it.renderFrame(320, 180).also { frame ->
                assertEquals(320, frame.width)
                assertEquals(180, frame.height)
                frame.flush()
            }
            it.seek(1.0)
            it.setPaused(true)
            assertThrows(MpvException::class.java) { it.setShaders(listOf(java.nio.file.Path.of("unused.glsl"))) }
        }
        player.close()
        assertThrows(MpvException::class.java) { player.renderFrame(16, 16) }
    }
}
