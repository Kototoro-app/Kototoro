package org.skepsun.kototoro.desktop.player

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real libmpv without any window or sound device (opt-in: `-PlibmpvDirectory=<dir with libmpv-2.dll>`). Media is made
 * on the spot from ffmpeg's lavfi test source, so nothing binary is committed and no network is used.
 */
class MpvPlayerTest {
    @TempDir lateinit var directory: Path

    private fun library(): Path {
        val found = MpvLocator.find()
        assumeTrue(found != null, "no libmpv configured")
        return found!!
    }

    private fun waitFor(timeoutMillis: Long = 15_000, message: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError(message())
            Thread.sleep(50)
        }
    }

    /** A short MJPEG clip encoded by libmpv itself. */
    private fun clip(library: Path, seconds: Int): Path {
        val output = directory.resolve("clip.mkv")
        MpvPlayer(library, options = mapOf("o" to output.toString(), "ovc" to "mjpeg", "of" to "matroska",
            "keep-open" to "no", "ao" to "null")).use { encoder ->
            encoder.load(MpvStream("av://lavfi:testsrc=duration=$seconds:size=160x120:rate=10"))
            waitFor(message = { "encoding did not finish: ${encoder.state.value}" }) {
                Files.isRegularFile(output) && encoder.property("idle-active") == "yes"
            }
        }
        assertTrue(Files.size(output) > 1000)
        return output
    }

    @Test
    fun `a generated stream loads plays pauses and seeks`() {
        MpvPlayer(library()).use { player ->
            player.load(MpvStream("av://lavfi:testsrc=duration=30:size=160x120:rate=25", title = "测试"))
            waitFor(message = { "not loaded: ${player.state.value}" }) { player.state.value.loaded }
            waitFor(message = { "no duration: ${player.state.value}" }) { player.state.value.duration > 29 }
            waitFor(message = { "not playing: ${player.state.value}" }) { player.state.value.position > 0.3 }
            player.setPaused(true)
            waitFor(message = { "not paused" }) { player.state.value.paused }
            player.seek(20.0)
            waitFor(message = { "seek ignored: ${player.state.value}" }) { player.state.value.position in 19.5..21.0 }
            player.setSpeed(1.5)
            player.setVolume(40)
            waitFor(message = { "controls ignored: ${player.state.value}" }) {
                player.state.value.speed == 1.5 && player.state.value.volume == 40
            }
            assertEquals("测试", player.property("media-title"))
        }
    }

    @Test
    fun `headers reach the server and a start position is honoured`() {
        val library = library()
        val clip = Files.readAllBytes(clip(library, 6))
        val seen = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/video.mkv") { exchange ->
                seen += "${exchange.requestHeaders.getFirst("Referer")}|${exchange.requestHeaders.getFirst("User-Agent")}"
                if (exchange.requestHeaders.getFirst("Referer") != "https://site.invalid/") {
                    exchange.sendResponseHeaders(403, -1); exchange.close(); return@createContext
                }
                // Range requests are answered with the whole body; mpv copes.
                exchange.responseHeaders.add("Content-Type", "video/x-matroska")
                exchange.sendResponseHeaders(200, clip.size.toLong())
                exchange.responseBody.use { it.write(clip) }
            }
            start()
        }
        try {
            MpvPlayer(library).use { player ->
                val url = "http://127.0.0.1:${server.address.port}/video.mkv"
                player.load(MpvStream(url, mapOf("Referer" to "https://site.invalid/", "User-Agent" to "KototoroTest/1"),
                    startSeconds = 3.0))
                waitFor(message = { "not loaded: ${player.state.value} $seen" }) { player.state.value.loaded }
                waitFor(message = { "start ignored: ${player.state.value}" }) { player.state.value.position >= 2.5 }
                assertTrue(seen.all { it == "https://site.invalid/|KototoroTest/1" }, seen.toString())
                // Without the header the site refuses and the player reports it instead of hanging.
                player.load(MpvStream(url))
                waitFor(message = { "no error: ${player.state.value}" }) { player.state.value.error != null }
                assertNotNull(player.state.value.error)
            }
        } finally {
            server.stop(0)
        }
    }
    @Test
    fun `enhancement presets unpack the bundled shaders and reach mpv's shader list`() {
        val shaders = MpvShaderLibrary(directory.resolve("shaders"))
        for (mode in MpvEnhancementMode.entries) {
            val files = shaders.files(MpvEnhancement(mode))
            assertEquals(mode.shaders.size, files.size)
            files.forEach { assertTrue(Files.size(it) > 500, it.toString()) }
        }
        // FSR's sharpness follows Android's slider: 2 × (1 − value) stops.
        val sharp = shaders.files(MpvEnhancement(MpvEnhancementMode.FSR, 1f)).single()
        val soft = shaders.files(MpvEnhancement(MpvEnhancementMode.FSR, 0.5f)).single()
        assertTrue(Files.readString(sharp).contains("#define SHARPNESS 0.00"))
        assertTrue(Files.readString(soft).contains("#define SHARPNESS 1.00"))
        MpvPlayer(library()).use { player ->
            val quality = shaders.files(MpvEnhancement(MpvEnhancementMode.ANIME4K_QUALITY))
            player.setShaders(quality)
            assertEquals(quality.map { it.toAbsolutePath().toString() },
                player.property("glsl-shaders").orEmpty().split(java.io.File.pathSeparator))
            player.setShaders(emptyList())
            assertEquals("", player.property("glsl-shaders"))
        }
    }
    @Test
    fun `Anime4K image presets render offscreen at their scale and change the picture`() {
        val library = library()
        val input = directory.resolve("page.png")
        val source = java.awt.image.BufferedImage(120, 180, java.awt.image.BufferedImage.TYPE_INT_RGB)
        source.createGraphics().apply {
            color = java.awt.Color.WHITE; fillRect(0, 0, 120, 180)
            color = java.awt.Color.BLACK; drawOval(10, 10, 100, 150); drawLine(0, 0, 119, 179); dispose()
        }
        javax.imageio.ImageIO.write(source, "png", input.toFile())
        val enhancer = MpvImageEnhancer(library, MpvShaderLibrary(directory.resolve("shaders")))
        val plain = directory.resolve("plain.png")
        // Reference: the same offscreen path without shaders (bilinear-ish scaling only).
        MpvPlayer(library, options = mapOf("o" to plain.toString(), "of" to "image2", "ovc" to "png", "frames" to "1",
            "vf" to "gpu=w=240:h=360", "keep-open" to "no")).use { player ->
            player.load(MpvStream(input.toString()))
            val deadline = System.currentTimeMillis() + 30_000
            while (player.property("idle-active") != "yes" && System.currentTimeMillis() < deadline) Thread.sleep(25)
        }
        val reference = javax.imageio.ImageIO.read(plain.toFile())
        for (preset in Anime4KImagePreset.entries) {
            val output = directory.resolve("${preset.name}.png")
            val started = System.nanoTime()
            enhancer.enhance(input, output, 120, 180, preset)
            val image = javax.imageio.ImageIO.read(output.toFile())
            assertEquals(120 * preset.scale, image.width, preset.name)
            assertEquals(180 * preset.scale, image.height, preset.name)
            if (preset.scale == 2) {
                var different = 0
                for (x in 0 until 240 step 3) for (y in 0 until 360 step 3) if (image.getRGB(x, y) != reference.getRGB(x, y)) different++
                assertTrue(different > 100, "${preset.name} looks like plain scaling ($different)")
            }
            println("${preset.name} ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }
}