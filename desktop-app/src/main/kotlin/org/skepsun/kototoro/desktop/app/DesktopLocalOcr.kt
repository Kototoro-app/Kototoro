package org.skepsun.kototoro.desktop.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.reader.core.MangaOcrModels
import org.skepsun.kototoro.reader.core.decodeManga48pxCtc
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

internal data class DesktopLocalOcrSettings(val modelDirectory: String = "")

internal fun interface DesktopPageOcr {
    suspend fun recognize(image: ByteArray, settings: DesktopLocalOcrSettings): List<DesktopOcrBubble>
}

internal object OnnxDesktopOcr : DesktopPageOcr {
    private const val DETECTION_SIZE = 1024
    private const val RECOGNITION_HEIGHT = 48
    private const val RECOGNITION_PADDING = 128
    private const val RECOGNITION_MAX_WIDTH = 320
    private const val RECOGNITION_THRESHOLD = 0.1f

    override suspend fun recognize(image: ByteArray, settings: DesktopLocalOcrSettings): List<DesktopOcrBubble> =
        withContext(Dispatchers.IO) {
            require(settings.modelDirectory.isNotBlank()) { AndroidStrings["desktop_ocr_model_folder_required"] }
            val env = try {
                OrtEnvironment.getEnvironment()
            } catch (error: LinkageError) {
                throw IllegalStateException(AndroidStrings["desktop_ocr_runtime_unavailable"], error)
            }
            val directory = Path.of(settings.modelDirectory)
            val detectorPath = verify(directory, MangaOcrModels.detector)
            val recognizerPath = verify(directory, MangaOcrModels.recognizer)
            val dictionary = Files.readAllLines(verify(directory, MangaOcrModels.alphabet))
            val source = checkNotNull(ImageIO.read(ByteArrayInputStream(image)))
            try {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                    val regions = env.createSession(detectorPath.toString(), options).use { detector ->
                        detect(source, detector)
                    }
                    if (regions.isEmpty()) return@withContext emptyList()
                    env.createSession(recognizerPath.toString(), options).use { recognizer ->
                        regions.mapNotNull { region ->
                            ensureActive()
                            val result = recognizeRegion(source, region, recognizer, dictionary)
                            if (result.isBlank()) null else region.toBubble(source.width, source.height, result)
                        }
                    }
                }
            } finally {
                source.flush()
            }
        }

    private fun verify(directory: Path, file: MangaOcrModels.File): Path {
        val path = directory.resolve(file.name)
        require(Files.isRegularFile(path)) { AndroidStrings.format("desktop_ocr_model_missing", file.name) }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count == -1) break
                digest.update(bytes, 0, count)
            }
        }
        require(HexFormat.of().formatHex(digest.digest()) == file.sha256) {
            AndroidStrings.format("desktop_ocr_model_invalid", file.name)
        }
        return path
    }

    private fun detect(source: BufferedImage, detector: OrtSession): List<Rectangle> {
        val scale = DETECTION_SIZE.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val inputWidth = aligned(width, 256)
        val inputHeight = aligned(height, 256)
        val resized = resize(source, width, height)
        try {
            tensor(resized, inputWidth, inputHeight, padding = -1f).use { input ->
                detector.run(mapOf(detector.inputNames.single() to input)).use { result ->
                    val output = result.get("db_map").orElseThrow() as OnnxTensor
                    val shape = (output.info as TensorInfo).shape
                    check(shape.size == 4 && shape[0] == 1L && shape[1] >= 1L)
                    val mapWidth = shape[3].toInt()
                    val mapHeight = shape[2].toInt()
                    val probabilities = FloatArray(mapWidth * mapHeight)
                    output.floatBuffer.get(probabilities)
                    for (index in probabilities.indices) probabilities[index] = 1f / (1f + exp(-probabilities[index]))
                    return desktopDbNetRegions(probabilities, mapWidth, mapHeight).mapNotNull { region ->
                        val left = (region.x.toDouble() * inputWidth / mapWidth / scale).toInt().coerceIn(0, source.width)
                        val top = (region.y.toDouble() * inputHeight / mapHeight / scale).toInt().coerceIn(0, source.height)
                        val right = ceil(region.maxX * inputWidth / mapWidth / scale).toInt().coerceIn(0, source.width)
                        val bottom = ceil(region.maxY * inputHeight / mapHeight / scale).toInt().coerceIn(0, source.height)
                        if (right - left < 4 || bottom - top < 4) null else Rectangle(left, top, right - left, bottom - top)
                    }
                }
            }
        } finally {
            resized.flush()
        }
    }

    private fun recognizeRegion(
        source: BufferedImage,
        region: Rectangle,
        recognizer: OrtSession,
        dictionary: List<String>,
    ): String {
        val crop = source.getSubimage(region.x, region.y, region.width, region.height)
        val vertical = crop.height > crop.width
        val actualWidth = ceil(RECOGNITION_HEIGHT.toFloat() * maxOf(crop.width, crop.height) /
            minOf(crop.width, crop.height)).toInt().coerceIn(1, RECOGNITION_MAX_WIDTH)
        val scaled = BufferedImage(actualWidth, RECOGNITION_HEIGHT, BufferedImage.TYPE_INT_RGB)
        try {
            val canvas = scaled.createGraphics()
            try {
                canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                if (vertical) {
                    canvas.translate(0, RECOGNITION_HEIGHT)
                    canvas.rotate(-Math.PI / 2)
                    canvas.drawImage(crop, 0, 0, RECOGNITION_HEIGHT, actualWidth, null)
                } else {
                    canvas.drawImage(crop, 0, 0, actualWidth, RECOGNITION_HEIGHT, null)
                }
            } finally {
                canvas.dispose()
            }
            tensor(scaled, aligned(actualWidth + RECOGNITION_PADDING, 4), RECOGNITION_HEIGHT, padding = 0f)
                .use { input ->
                    recognizer.run(mapOf(recognizer.inputNames.single() to input)).use { result ->
                        val output = result[0] as OnnxTensor
                        val shape = (output.info as TensorInfo).shape
                        check(shape.size == 3 && shape[0] == 1L)
                        val steps = shape[1].toInt()
                        val classes = shape[2].toInt()
                        val data = FloatArray(steps * classes)
                        output.floatBuffer.get(data)
                        return decodeManga48pxCtc(data, 0, steps, classes, dictionary)
                            .takeIf { it.confidence >= RECOGNITION_THRESHOLD }?.text?.trim().orEmpty()
                    }
                }
        } finally {
            scaled.flush()
        }
    }

    private fun tensor(image: BufferedImage, width: Int, height: Int, padding: Float): OnnxTensor {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val plane = width * height
        val values = FloatArray(plane * 3) { padding }
        for (row in 0 until image.height) {
            for (col in 0 until image.width) {
                val pixel = pixels[row * image.width + col]
                val i = row * width + col
                values[i] = (pixel ushr 16 and 255) / 127.5f - 1f
                values[plane + i] = (pixel ushr 8 and 255) / 127.5f - 1f
                values[plane * 2 + i] = (pixel and 255) / 127.5f - 1f
            }
        }
        return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(values),
            longArrayOf(1, 3, height.toLong(), width.toLong()))
    }
}

private fun aligned(value: Int, multiple: Int): Int = (value + multiple - 1) / multiple * multiple

private fun resize(image: BufferedImage, width: Int, height: Int): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { resized ->
        val canvas = resized.createGraphics()
        try {
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            canvas.drawImage(image, 0, 0, width, height, null)
        } finally {
            canvas.dispose()
        }
    }

/** Android's DBNet takes contours from the thresholded map. The desktop adapter bounds connected components. */
internal fun desktopDbNetRegions(probabilities: FloatArray, width: Int, height: Int): List<Rectangle> {
    val remaining = BooleanArray(probabilities.size) { probabilities[it] > 0.5f }
    val queue = IntArray(probabilities.size)
    val found = ArrayList<Rectangle>()
    for (seed in remaining.indices) {
        if (!remaining[seed]) continue
        var size = 0
        fun visit(index: Int) {
            if (!remaining[index]) return
            remaining[index] = false
            queue[size++] = index
        }
        visit(seed)
        var cursor = 0
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        var score = 0f
        while (cursor < size) {
            val index = queue[cursor++]
            val x = index % width
            val y = index / width
            score += probabilities[index]
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
            if (x > 0) visit(index - 1)
            if (x + 1 < width) visit(index + 1)
            if (y > 0) visit(index - width)
            if (y + 1 < height) visit(index + width)
        }
        val w = right - left + 1
        val h = bottom - top + 1
        if (size < 4 || w < 2 || h < 2 || score / size < 0.7f) continue
        val padding = (w.toDouble() * h * 2.3 / (2.0 * (w + h))).roundToInt().coerceAtLeast(1)
        val x = (left - padding).coerceAtLeast(0)
        val y = (top - padding).coerceAtLeast(0)
        found += Rectangle(x, y, (right + padding + 1).coerceAtMost(width) - x,
            (bottom + padding + 1).coerceAtMost(height) - y)
    }
    return found.sortedWith(compareBy<Rectangle> { it.y }.thenBy { it.x }).take(100)
}

private fun Rectangle.toBubble(pageWidth: Int, pageHeight: Int, text: String): DesktopOcrBubble {
    val left = x * 1000 / pageWidth
    val top = y * 1000 / pageHeight
    val right = ((x + width) * 1000 / pageWidth).coerceIn(left + 1, 1000)
    val bottom = ((y + height) * 1000 / pageHeight).coerceIn(top + 1, 1000)
    return DesktopOcrBubble(left, top, right - left, bottom - top, text)
}
