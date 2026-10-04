package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.parserhost.ParserPluginException
import org.skepsun.kototoro.parserhost.ParserPluginFailure
import org.skepsun.kototoro.parsers.bitmap.Rect
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.imageio.ImageIO

class DesktopParserPlatformTest {
    @TempDir lateinit var directory: Path

    private fun png(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    private fun response(body: ByteArray, type: String = "image/png") = Response.Builder()
        .request(Request.Builder().url("https://fixture.invalid/img.png").build()).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").header("Content-Length", body.size.toString())
        .body(body.toResponseBody(type.toMediaType())).build()

    private fun platform() = FileSourcePreferenceStore(directory.resolve("prefs")).let {
        it to DesktopParserPlatform(OkHttpClient(), it)
    }

    @Test
    fun `redrawing rearranges blocks pixel exactly and answers PNG`() {
        val (store, platform) = platform()
        store.use {
            val source = BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB).apply {
                for (x in 0 until 4) for (y in 0 until 2) setRGB(x, y, if (x < 2) 0xFF0000 else 0x0000FF)
            }
            val redrawn = platform.redrawImageResponse(response(png(source))) { bitmap ->
                assertEquals(4, bitmap.width)
                assertEquals(2, bitmap.height)
                // The usual descrambling move: build a new bitmap from swapped halves.
                platform.createBitmap(4, 2).also {
                    it.drawBitmap(bitmap, Rect(2, 0, 4, 2), Rect(0, 0, 2, 2))
                    it.drawBitmap(bitmap, Rect(0, 0, 2, 2), Rect(2, 0, 4, 2))
                }
            }
            assertEquals("image/png", redrawn.header("Content-Type"))
            assertNull(redrawn.header("Content-Length"))
            val result = ImageIO.read(ByteArrayInputStream(redrawn.body.bytes()))
            assertEquals(0x0000FF, result.getRGB(0, 0) and 0xFFFFFF)
            assertEquals(0x0000FF, result.getRGB(1, 1) and 0xFFFFFF)
            assertEquals(0xFF0000, result.getRGB(2, 0) and 0xFFFFFF)
            assertEquals(0xFF0000, result.getRGB(3, 1) and 0xFFFFFF)
        }
    }

    @Test
    fun `an undecodable body and a foreign bitmap fail instead of passing through`() {
        val (store, platform) = platform()
        store.use {
            assertThrows(IOException::class.java) { platform.redrawImageResponse(response(ByteArray(16) { 1 })) { it } }
            val valid = png(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB))
            assertThrows(IllegalArgumentException::class.java) {
                platform.redrawImageResponse(response(valid)) {
                    object : org.skepsun.kototoro.parsers.bitmap.Bitmap {
                        override val width = 1
                        override val height = 1
                        override fun drawBitmap(sourceBitmap: org.skepsun.kototoro.parsers.bitmap.Bitmap, src: Rect, dst: Rect) = Unit
                    }
                }
            }
        }
    }

    @Test
    fun `scripts are unsupported until the platform provides an evaluator`() {
        val (store, platform) = platform()
        store.use {
            assertThrows(UnsupportedOperationException::class.java) { runBlocking { platform.evaluateJs("https://x/", "1") } }
            val withScripts = DesktopParserPlatform(OkHttpClient(), store) { base, script -> "$base|$script" }
            assertEquals("https://x/|1+1", runBlocking { withScripts.evaluateJs("https://x/", "1+1") })
        }
    }

    @Test
    fun `plugin ids drop version and packaging suffixes`() {
        assertEquals("kototoro-parsers", DesktopExtensionFiles.parserPluginId("kototoro-parsers-1.0.jar"))
        assertEquals("kototoro-parsers", DesktopExtensionFiles.parserPluginId("Kototoro-Parsers.JAR"))
        assertEquals("uma", DesktopExtensionFiles.parserPluginId("uma.jar"))
        assertEquals("kotatsu-parsers-redo", DesktopExtensionFiles.parserPluginId("kotatsu-parsers-redo-plugin.jar"))
        assertEquals("kotatsu-parsers", DesktopExtensionFiles.parserPluginId("kotatsu-parsers-1.2.3-SNAPSHOT.jar"))
        assertEquals("parser-plugin", DesktopExtensionFiles.parserPluginId("漫画.jar"))
        assertEquals("parser-plugin", DesktopExtensionFiles.parserPluginId("___.jar"))
    }

    private fun jar(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        JarOutputStream(bytes).use { output ->
            for ((name, data) in entries) {
                output.putNextEntry(JarEntry(name))
                output.write(data)
                output.closeEntry()
            }
        }
    }.toByteArray()

    /** Header-only class files are enough: the inspector reads versions and entry names, it never loads classes. */
    private val classHeader = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 55)

    @Test
    fun `importing a parser plugin stores one immutable copy and keeps the logical id`() = runBlocking<Unit> {
        val root = directory.resolve("data")
        val plugin = Files.write(directory.resolve("kototoro-parsers-1.0.jar"),
            jar("org/skepsun/kototoro/parsers/ContentParserFactoryKt.class" to classHeader))
        FileSourcePreferenceStore(root.resolve("prefs")).use { store ->
            DesktopRepositories(root, store.open("repos")).use { repositories ->
                assertEquals(DesktopExtensionKind.PARSER, DesktopExtensionFiles.kind(plugin))
                val managed = repositories.importParserPlugin(plugin)
                assertEquals("kototoro-parsers", managed.id)
                assertEquals(root.resolve("extensions").resolve("${managed.sha256}.jar"), managed.path)
                assertTrue(Files.isRegularFile(managed.path))
                // The same bytes under another name publish the same artifact.
                val again = repositories.importParserPlugin(Files.copy(plugin, directory.resolve("uma.jar")))
                assertEquals(managed.path, again.path)
                assertEquals("uma", again.id)
                assertEquals(1L, Files.list(root.resolve("extensions")).use { it.count() })

                // A DEX-only plugin is converted first; unconvertible DEX is reported, and nothing is kept.
                val dex = Files.write(directory.resolve("dex.jar"), jar("classes.dex" to ByteArray(8)))
                assertTrue(assertThrows(IOException::class.java) { runBlocking { repositories.importParserPlugin(dex) } }
                    .message.orEmpty().contains("DEX"))
                val unrelated = Files.write(directory.resolve("unrelated.jar"), jar("a.txt" to ByteArray(1)))
                assertEquals(ParserPluginFailure.INVALID_ARCHIVE, assertThrows(ParserPluginException::class.java) {
                    runBlocking { repositories.importParserPlugin(unrelated) }
                }.failure)
                assertEquals(1L, Files.list(root.resolve("extensions")).use { it.count() }, "rejected files are not kept")
            }
        }
    }

    @Test
    fun `a manifest marks a Mihon extension`() {
        val mihon = Files.write(directory.resolve("mihon.jar"), jar("AndroidManifest.xml" to "<manifest/>".toByteArray()))
        assertEquals(DesktopExtensionKind.MIHON, DesktopExtensionFiles.kind(mihon))
    }
}
