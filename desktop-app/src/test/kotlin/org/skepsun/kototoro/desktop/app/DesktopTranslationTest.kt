package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.desktop.runtime.NovelBlock
import org.skepsun.kototoro.desktop.runtime.NovelRun
import org.skepsun.kototoro.desktop.runtime.bookmarkText
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

class DesktopTranslationTest {
    @TempDir lateinit var directory: Path
    private val settings = DesktopTranslationSettings("https://translation.invalid/v1/chat/completions", "fixture", "zh", "key")
    private val source = SourceRef("fixture_novel", "en", "NOVEL")
    private val book = SourceContent(7, "Book", emptySet(), "/book", "", 0f, null, null, emptySet(), null, emptySet(), source)
    private fun chapter(id: Long) = SourceChapter(id, "Chapter $id", id.toFloat(), 0, "/$id", null, 0, null, source)

    private fun client(input: MutableList<JsonElement>, answer: (JsonElement) -> String): DesktopTranslationClient {
        return DesktopTranslationClient(OkHttpClient.Builder().addInterceptor { chain ->
            val payload = Buffer().also { requireNotNull(chain.request().body).writeTo(it) }.readUtf8()
            val user = Json.parseToJsonElement(payload).jsonObject.getValue("messages").jsonArray.last()
                .jsonObject.getValue("content")
            input += user
            val body = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject { put("message", buildJsonObject { put("content", answer(user)) }) })
                })
            }.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
    }

    @Test
    fun `manual edits survive a retranslation and reopening while repeated paragraphs remain independent`() = runBlocking<Unit> {
        val packets = CopyOnWriteArrayList<JsonElement>()
        val provider = client(packets) { """{"0":"机译一","1":"机译二"}""" }
        val paragraphs = listOf("Alice walked.", "Alice walked.").map { NovelBlock.Paragraph(listOf(NovelRun(it))) }
        val keys = desktopTranslationParagraphs(paragraphs)
        assertNotEquals(keys[0].key, keys[1].key)
        val preferencePath = directory.resolve("prefs")
        FileSourcePreferenceStore(preferencePath).use { prefs ->
            val service = DesktopNovelTranslation(prefs, provider)
            service.configure(settings)
            service.saveGlossary(book.id, listOf(DesktopGlossaryTerm("Alice", "爱丽丝")))
            service.translate(book.id, 8L, keys, settings, retranslate = false) { _, _ -> }
            service.edit(book.id, 8L, settings.target, keys[1].key, "手工译文")
            service.translate(book.id, 8L, keys, settings, retranslate = true) { _, _ -> }
            service.translate(book.id, 8L, keys, settings, retranslate = false) { _, _ -> }
            assertEquals(2, packets.size)
            assertTrue(packets.all { "爱丽丝" in it.jsonPrimitive.content })
        }
        FileSourcePreferenceStore(preferencePath).use { prefs ->
            val service = DesktopNovelTranslation(prefs, provider)
            assertEquals("", service.settings.value.apiKey)
            val translated = service.chapter(book.id, 8L, settings.target)
            assertEquals(listOf("机译一", "手工译文"), desktopTranslatedBlocks(paragraphs, keys, translated, false)
                .map(NovelBlock::bookmarkText))
            service.edit(book.id, 8L, settings.target, keys[1].key, null)
            assertEquals("机译二", service.chapter(book.id, 8L, settings.target).forParagraph(keys[1].key))
        }
    }

    @Test
    fun `paused batch honors chapter reorder and cancellation before running`() = runBlocking<Unit> {
        val packets = CopyOnWriteArrayList<JsonElement>()
        FileSourcePreferenceStore(directory.resolve("queue")).use { prefs ->
            val service = DesktopNovelTranslation(prefs, client(packets) { """{"0":"翻译"}""" })
            service.configure(settings)
            val opened = CopyOnWriteArrayList<Long>()
            val queue = DesktopTranslationQueue(this, service) { _, chapter ->
                opened += chapter.id
                listOf(DesktopTranslationParagraph(0, "p", "One paragraph"))
            }
            queue.setPaused(true)
            queue.enqueue(book, (1L..3L).map(::chapter))
            queue.move(DesktopTranslationQueue.Key(book.id, 3), -1)
            queue.cancel(DesktopTranslationQueue.Key(book.id, 1))
            assertTrue(opened.isEmpty())
            queue.setPaused(false)
            withTimeout(5_000) {
                queue.items.first { items -> items.count { it.status == DesktopTranslationQueue.Status.COMPLETE } == 2 }
            }
            assertEquals(listOf(3L, 2L), opened)
            assertEquals(2, packets.size)
        }
    }

    @Test
    fun `correcting a bubble sends its corrected text without another image or the neighboring bubble`() = runBlocking<Unit> {
        val packets = CopyOnWriteArrayList<JsonElement>()
        val provider = client(packets) { content ->
            when {
                content !is kotlinx.serialization.json.JsonPrimitive ->
                    """{"bubbles":[{"x":50,"y":50,"width":450,"height":400,"text":"First bubble"},{"x":550,"y":50,"width":400,"height":400,"text":"Second bubble"}]}"""
                "Corrected" in content.content -> """{"0":"修正后"}"""
                else -> """{"0":"第一段","1":"第二段"}"""
            }
        }
        val original = directory.resolve("page.png")
        val bitmap = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
        bitmap.createGraphics().also { canvas ->
            canvas.color = Color.BLUE
            canvas.fillRect(0, 0, 400, 400)
            canvas.dispose()
        }
        ImageIO.write(bitmap, "png", original.toFile())
        bitmap.flush()
        val image = DesktopReaderImage(original, 400, 400)
        val service = DesktopMangaTranslation(directory.resolve("rendered"), provider)
        service.translate(image, settings)
        val first = service.pages.value.getValue(original)
        assertTrue(first.image.path.toFile().isFile)
        assertEquals(2, first.bubbles.size)
        service.retranslate(image, settings, 0, "Corrected")
        val updated = service.pages.value.getValue(original)
        assertEquals(3, packets.size)
        assertTrue("Corrected" in packets.last().jsonPrimitive.content)
        assertFalse("Second bubble" in packets.last().jsonPrimitive.content)
        assertEquals("修正后", updated.translations["0"])
        assertEquals(first.translations["1"], updated.translations["1"])
        assertEquals(Color.BLUE.rgb, ImageIO.read(original.toFile()).getRGB(25, 25))
    }
    @Test
    fun `local OCR sends only recognized text and can retranslate one region`() = runBlocking<Unit> {
        val packets = CopyOnWriteArrayList<JsonElement>()
        val provider = client(packets) { content ->
            if ("Corrected" in content.jsonPrimitive.content) """{"0":"修订"}"""
            else """{"0":"第一段","1":"第二段"}"""
        }
        val source = directory.resolve("local-page.png")
        BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB).let { bitmap ->
            ImageIO.write(bitmap, "png", source.toFile())
            bitmap.flush()
        }
        val image = DesktopReaderImage(source, 300, 300)
        var ocrCalls = 0
        val service = DesktopMangaTranslation(directory.resolve("local-rendered"), provider) { bytes, ocrSettings ->
            assertTrue(bytes.isNotEmpty())
            assertEquals("/models", ocrSettings.modelDirectory)
            ocrCalls++
            listOf(
                DesktopOcrBubble(50, 50, 400, 400, "第一段文字"),
                DesktopOcrBubble(550, 50, 400, 400, "第二段文字"),
            )
        }
        service.translateLocally(image, settings, DesktopLocalOcrSettings("/models"))
        service.retranslate(image, settings, 0, "Corrected")

        assertEquals(1, ocrCalls)
        assertEquals(2, packets.size)
        assertTrue(packets.all { it is kotlinx.serialization.json.JsonPrimitive })
        assertFalse("第二段文字" in packets.last().jsonPrimitive.content)
        assertEquals(mapOf("0" to "修订", "1" to "第二段"), service.pages.value.getValue(source).translations)
    }

    @Test
    fun `DBNet threshold map separates two text regions and drops noise`() {
        val width = 20
        val height = 20
        val probabilities = FloatArray(width * height)
        for (y in 2..6) for (x in 2..4) probabilities[y * width + x] = 0.99f
        for (y in 12..15) for (x in 14..17) probabilities[y * width + x] = 0.98f
        probabilities[8 * width + 10] = 0.99f
        val regions = desktopDbNetRegions(probabilities, width, height)
        assertEquals(2, regions.size)
        assertTrue(regions[0].contains(3, 4))
        assertTrue(regions[1].contains(15, 13))
    }

    @Test
    fun `pinned ONNX models detect and recognize text on comic samples`() = runBlocking<Unit> {
        val models = System.getenv("KOTOTORO_ONNX_OCR_TEST_MODELS")
        val fixtures = System.getenv("KOTOTORO_ONNX_OCR_TEST_FIXTURES")
        assumeTrue(!models.isNullOrBlank() && !fixtures.isNullOrBlank())
        val expectedText = mapOf(
            "ja-comic.png" to setOf("約束を忘れない", "明日は晴れる"),
            "zh-comic.png" to setOf("请打开这扇门", "明天见"),
        )
        for ((file, expected) in expectedText) {
            val bytes = java.nio.file.Files.readAllBytes(Path.of(fixtures).resolve(file))
            val result = OnnxDesktopOcr.recognize(bytes, DesktopLocalOcrSettings(requireNotNull(models)))
            assertEquals(expected, result.map(DesktopOcrBubble::text).toSet(), file)
            assertTrue(result.all(DesktopOcrBubble::isValid))
        }
    }
}
