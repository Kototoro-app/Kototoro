package org.skepsun.kototoro.desktop.player

import com.sun.jna.Platform
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MpvMacOpenGlRendererTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `CGL renders correctly oriented frames executes GLSL resizes and releases its context`() {
        assumeTrue(Platform.isMac(), "CGL requires macOS")
        val library = MpvLocator.find()
        assumeTrue(library != null, "no libmpv configured")
        val previous = MacOpenGl.instance.CGLGetCurrentContext()
        val renderer = Executors.newSingleThreadExecutor()
        val player = MpvPlayer(requireNotNull(library), options = mapOf("ao" to "null"),
            renderMode = MpvRenderMode.MACOS_OPENGL)
        try {
            assertEquals(previous, MacOpenGl.instance.CGLGetCurrentContext())
            player.load(MpvStream(MpvProbeVideo.write(directory.resolve("colors.y4m")).toUri().toString()))
            fun waitForImage(width: Int, height: Int, condition: (BufferedImage) -> Boolean): BufferedImage {
                val deadline = System.nanoTime() + 15_000_000_000L
                var samples = ""
                while (System.nanoTime() < deadline) {
                    val frame = renderer.submit<BufferedImage> { player.renderFrame(width, height) }.get(5, TimeUnit.SECONDS)
                    assertEquals(width, frame.width)
                    assertEquals(height, frame.height)
                    if (condition(frame)) return frame
                    samples = listOf(frame.getRGB(width / 2, height / 6), frame.getRGB(width / 2, height * 5 / 6))
                        .joinToString { Integer.toHexString(it) }
                    frame.flush()
                    Thread.sleep(30)
                }
                error("CGL pixels did not match: $samples ${player.state.value}")
            }
            fun red(rgb: Int) = rgb shr 16 and 255
            fun green(rgb: Int) = rgb shr 8 and 255
            fun blue(rgb: Int) = rgb and 255
            val original = waitForImage(160, 120) { frame ->
                red(frame.getRGB(80, 20)) > 150 && blue(frame.getRGB(80, 100)) > 150
            }
            original.flush()
            val shader = directory.resolve("green.glsl")
            Files.writeString(shader, """
                //!HOOK MAIN
                //!BIND HOOKED
                //!DESC CGL integration test
                vec4 hook() { return vec4(0.0, 1.0, 0.0, 1.0); }
            """.trimIndent())
            player.setShaders(listOf(shader))
            waitForImage(320, 240) { frame ->
                green(frame.getRGB(160, 120)) > 150 && red(frame.getRGB(160, 120)) < 50
            }.flush()
            player.setShaders(emptyList())
            waitForImage(160, 120) { frame -> red(frame.getRGB(80, 20)) > 150 }.flush()
            assertEquals(previous, MacOpenGl.instance.CGLGetCurrentContext())
        } finally {
            renderer.shutdown()
            assertTrue(renderer.awaitTermination(10, TimeUnit.SECONDS))
            player.close()
        }
        player.close()
        assertEquals(previous, MacOpenGl.instance.CGLGetCurrentContext())
        assertThrows(MpvException::class.java) { player.renderFrame(16, 16) }
    }
}
