package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.text.AttributedString
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToInt

@Serializable
internal data class DesktopOcrBubble(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val text: String,
) {
    fun isValid(): Boolean = x in 0..999 && y in 0..999 && width in 1..(1000 - x) &&
        height in 1..(1000 - y) && text.isNotBlank()

    fun paragraph(index: Int) = DesktopTranslationParagraph(index, index.toString(), text)
}

internal data class DesktopTranslatedMangaPage(
    val target: String,
    val bubbles: List<DesktopOcrBubble>,
    val translations: Map<String, String>,
    val image: DesktopReaderImage,
)

internal class DesktopMangaTranslation(
    private val cacheDirectory: Path,
    private val client: DesktopTranslationClient = DesktopTranslationClient(),
    private val localOcr: DesktopPageOcr = OnnxDesktopOcr,
) {
    private val lock = Mutex()
    private val mutablePages = MutableStateFlow<Map<Path, DesktopTranslatedMangaPage>>(emptyMap())
    val pages = mutablePages.asStateFlow()

    suspend fun translate(original: DesktopReaderImage, settings: DesktopTranslationSettings) = lock.withLock {
        checkPageSize(original)
        val image = withContext(Dispatchers.IO) { encodedPng(original, 1_600) }
        val input = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "Find speech bubbles and read their original text. Coordinates use a 0–1000 scale " +
                    "relative to the entire image. Return a JSON object with a bubbles array. Each bubble has " +
                    "integer x, y, width, height and a string text. Returns bounds inside the image. Ignore artwork.")
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", "data:image/png;base64,${Base64.getEncoder().encodeToString(image)}")
                })
            })
        }
        val output = client.complete(settings, input, "Read comic dialogue from the supplied image. Return only JSON.")
        val bubbles = parseDesktopOcrBubbles(output)
        translateBubbles(original, settings, bubbles)
    }

    suspend fun translateLocally(
        original: DesktopReaderImage,
        settings: DesktopTranslationSettings,
        ocrSettings: DesktopLocalOcrSettings,
    ) = lock.withLock {
        checkPageSize(original)
        val image = withContext(Dispatchers.IO) { encodedPng(original, 2500) }
        val bubbles = localOcr.recognize(image, ocrSettings)
        translateBubbles(original, settings, bubbles)
    }

    private fun checkPageSize(original: DesktopReaderImage) {
        require(original.width.toLong() * original.height <= MAXIMUM_PIXELS &&
            maxOf(original.width, original.height) <= DesktopImageDecoder.MAXIMUM_DIMENSION) {
            AndroidStrings["desktop_translation_manga_page_too_large"]
        }
    }

    private suspend fun translateBubbles(
        original: DesktopReaderImage,
        settings: DesktopTranslationSettings,
        bubbles: List<DesktopOcrBubble>,
    ) {
        check(bubbles.isNotEmpty()) { AndroidStrings["desktop_translation_manga_no_bubbles"] }
        val translations = buildMap {
            for (batch in bubbles.mapIndexed { index, bubble -> bubble.paragraph(index) }.chunked(8)) {
                putAll(client.translate(batch, settings, emptyList()))
            }
        }
        publish(original, settings.target, bubbles, translations)
    }

    suspend fun retranslate(
        original: DesktopReaderImage,
        settings: DesktopTranslationSettings,
        index: Int,
        text: String,
    ) = lock.withLock {
        val page = mutablePages.value[original.path]?.takeIf { it.target == settings.target } ?: return@withLock
        val bubble = page.bubbles.getOrNull(index) ?: return@withLock
        val updated = bubble.copy(text = text.trim())
        require(updated.text.isNotEmpty())
        val translated = client.translate(listOf(updated.paragraph(index)), settings, emptyList())
        val bubbles = page.bubbles.toMutableList().apply { this[index] = updated }
        publish(original, settings.target, bubbles, page.translations + translated)
    }

    private suspend fun publish(
        original: DesktopReaderImage,
        target: String,
        bubbles: List<DesktopOcrBubble>,
        results: Map<String, String>,
    ) {
        val result = withContext(Dispatchers.IO) {
            val image = checkNotNull(ImageIO.read(ByteArrayInputStream(encodedPng(original))))
            try {
                val canvas = image.createGraphics()
                try {
                    canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    for ((index, bubble) in bubbles.withIndex()) {
                        val output = results[index.toString()] ?: continue
                        canvas.drawBubble(image.width, image.height, bubble, output)
                    }
                } finally {
                    canvas.dispose()
                }
                Files.createDirectories(cacheDirectory)
                val path = Files.createTempFile(cacheDirectory, "translation-", ".png")
                path.toFile().deleteOnExit()
                check(ImageIO.write(image, "png", path.toFile()))
                original.copy(path = path, regionSupported = true)
            } finally {
                image.flush()
            }
        }
        val page = DesktopTranslatedMangaPage(target, bubbles, results, result)
        val updated = mutablePages.value.entries.filterNot { it.key == original.path }.takeLast(31)
        mutablePages.value = updated.associate { it.key to it.value } + (original.path to page)
    }

    private suspend fun encodedPng(image: DesktopReaderImage, maximumDimension: Int = DesktopImageDecoder.MAXIMUM_DIMENSION): ByteArray =
        DesktopImageDecoder.decode(image.path, MAXIMUM_PIXELS, maximumDimension).use { decoded ->
            Image.makeFromBitmap(decoded).use { skiaImage ->
                checkNotNull(skiaImage.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
            }
        }

    private companion object {
        const val MAXIMUM_PIXELS = 12L * 1024 * 1024
    }
}

@Serializable
private data class DesktopOcrResult(val bubbles: List<DesktopOcrBubble>)

internal fun parseDesktopOcrBubbles(response: String): List<DesktopOcrBubble> =
    Json { ignoreUnknownKeys = true }.decodeFromString<DesktopOcrResult>(parseTranslationObject(response).toString())
        .bubbles.filter(DesktopOcrBubble::isValid).distinct().take(100)

private fun Graphics2D.drawBubble(pageWidth: Int, pageHeight: Int, bubble: DesktopOcrBubble, text: String) {
    val x = (pageWidth * bubble.x / 1000f).roundToInt()
    val y = (pageHeight * bubble.y / 1000f).roundToInt()
    val width = (pageWidth * bubble.width / 1000f).roundToInt().coerceAtMost(pageWidth - x)
    val height = (pageHeight * bubble.height / 1000f).roundToInt().coerceAtMost(pageHeight - y)
    val padding = minOf(width, height) / 12
    val innerWidth = width - padding * 2
    val innerHeight = height - padding * 2
    if (innerWidth < 4 || innerHeight < 4) return
    val lines = (minOf(innerHeight, innerWidth, 72) downTo 4).firstNotNullOfOrNull { size ->
        val attributed = AttributedString(text.replace('\n', ' ')).apply {
            addAttribute(TextAttribute.FONT, Font(Font.SANS_SERIF, Font.PLAIN, size))
        }
        val iterator = attributed.iterator
        val measurer = LineBreakMeasurer(iterator, fontRenderContext)
        val layout = ArrayList<TextLayout>()
        var usedHeight = 0f
        while (measurer.position < iterator.endIndex && usedHeight <= innerHeight) {
            val line = measurer.nextLayout(innerWidth.toFloat())
            layout += line
            usedHeight += line.ascent + line.descent + line.leading
        }
        layout.takeIf { usedHeight <= innerHeight && measurer.position == iterator.endIndex }
    } ?: return
    val previousClip = clip
    try {
        clipRect(x, y, width, height)
        color = Color(255, 255, 255, 248)
        fillRoundRect(x, y, width, height, padding, padding)
        color = Color.BLACK
        var baseline = y + padding.toFloat()
        for (line in lines) {
            baseline += line.ascent
            line.draw(this, x + (width - line.advance) / 2, baseline)
            baseline += line.descent + line.leading
        }
    } finally {
        clip = previousClip
    }
}
