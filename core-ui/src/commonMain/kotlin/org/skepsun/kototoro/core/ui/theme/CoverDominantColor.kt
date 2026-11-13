package org.skepsun.kototoro.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal expect fun ImageBitmap.toReadableSoftwareBitmap(): ImageBitmap?

/**
 * Extracts and caches a cover's dominant hue so details pages can tint their background without hurting contrast.
 * Mirrors the iOS `CoverDominantColor` 24x24 weighted hue-bin algorithm in pure Compose Multiplatform.
 */
object CoverDominantColor {

    private const val SAMPLE_SIDE = 24
    private const val HUE_BINS = 24
    private const val CACHE_LIMIT = 200

    private val cache = LinkedHashMap<String, Color>(CACHE_LIMIT, 0.75f)

    fun cached(key: String): Color? = synchronized(cache) { cache[key] }

    fun putCached(key: String, color: Color) {
        if (key.isBlank()) return
        synchronized(cache) {
            cache.remove(key)
            cache[key] = color
            while (cache.size > CACHE_LIMIT) {
                val eldest = cache.keys.firstOrNull() ?: break
                cache.remove(eldest)
            }
        }
    }

    fun clearCache() {
        synchronized(cache) {
            cache.clear()
        }
    }

    /**
     * Extracts the dominant hue color from [bitmap], sampling up to 24 scanlines to avoid full-image pixel copies.
     * Safe to invoke off the main thread once a software-readable bitmap is obtained.
     */
    fun extract(bitmap: ImageBitmap): Color? {
        val readable = bitmap.toReadableSoftwareBitmap() ?: return null
        val w = readable.width
        val h = readable.height
        if (w <= 0 || h <= 0) return null

        val rows = min(SAMPLE_SIDE, h)
        val cols = min(SAMPLE_SIDE, w)
        val rowBuffer = IntArray(w)
        val sampled = IntArray(cols * rows)

        for (sy in 0 until rows) {
            val srcY = ((sy + 0.5f) * h / rows).toInt().coerceIn(0, h - 1)
            readable.readPixels(
                buffer = rowBuffer,
                startX = 0,
                startY = srcY,
                width = w,
                height = 1,
                bufferOffset = 0,
                stride = w,
            )
            for (sx in 0 until cols) {
                val srcX = ((sx + 0.5f) * w / cols).toInt().coerceIn(0, w - 1)
                sampled[sy * cols + sx] = rowBuffer[srcX]
            }
        }
        return extractFromPixels(sampled, cols, rows)
    }

    /**
     * Extracts the dominant color from an ARGB [pixels] buffer of dimensions [width] × [height].
     */
    fun extractFromPixels(pixels: IntArray, width: Int, height: Int): Color? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null

        val cols = min(SAMPLE_SIDE, width)
        val rows = min(SAMPLE_SIDE, height)

        val binWeight = FloatArray(HUE_BINS)
        val binHueX = FloatArray(HUE_BINS)
        val binHueY = FloatArray(HUE_BINS)
        val binSat = FloatArray(HUE_BINS)
        val binBri = FloatArray(HUE_BINS)

        for (y in 0 until rows) {
            val srcY = if (rows == height) y else ((y + 0.5f) * height / rows).toInt().coerceIn(0, height - 1)
            // Top-left origin: favor the lower half of the cover that meets the page fade.
            val regionWeight = if (y >= rows / 2) 1.8f else 1.0f
            for (x in 0 until cols) {
                val srcX = if (cols == width) x else ((x + 0.5f) * width / cols).toInt().coerceIn(0, width - 1)
                val argb = pixels[srcY * width + srcX]
                val a = (argb ushr 24) and 0xFF
                if (a < 128) continue

                val r = ((argb ushr 16) and 0xFF) / 255f
                val g = ((argb ushr 8) and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f

                val (hNorm, s, v) = rgbToHsvNormalized(r, g, b)
                if (s <= 0.14f || v <= 0.08f || (v > 0.96f && s < 0.18f)) continue

                val weight = regionWeight * (0.35f + s * 1.4f)
                val bin = (hNorm * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                val angle = hNorm * (2.0 * PI)
                binWeight[bin] += weight
                binHueX[bin] += (cos(angle) * weight).toFloat()
                binHueY[bin] += (sin(angle) * weight).toFloat()
                binSat[bin] += s * weight
                binBri[bin] += v * weight
            }
        }

        var bestBin = -1
        var bestWeight = 0f
        for (i in 0 until HUE_BINS) {
            if (binWeight[i] > bestWeight) {
                bestWeight = binWeight[i]
                bestBin = i
            }
        }
        if (bestBin < 0 || bestWeight <= 0f) return null

        var avgAngle = atan2(binHueY[bestBin].toDouble(), binHueX[bestBin].toDouble())
        if (avgAngle < 0.0) avgAngle += 2.0 * PI
        val hueDegrees = ((avgAngle / (2.0 * PI)) * 360.0).toFloat().coerceIn(0f, 360f)
        val avgSat = (binSat[bestBin] / bestWeight).coerceIn(0f, 1f)
        val avgVal = (binBri[bestBin] / bestWeight).coerceIn(0f, 1f)
        return Color.hsv(hue = hueDegrees, saturation = avgSat, value = avgVal)
    }

    /**
     * Blends [dominantColor] subtly into [baseColor] while preserving text contrast across light/dark themes.
     */
    fun resolvePageBackground(
        baseColor: Color,
        dominantColor: Color?,
        isDarkTheme: Boolean,
        increasedContrast: Boolean = false,
        strength: Float = 1f,
    ): Color {
        val factor = strength.coerceIn(0f, 1f)
        if (dominantColor == null || increasedContrast || factor <= 0f) return baseColor

        val (hNorm, saturation, _) = rgbToHsvNormalized(dominantColor.red, dominantColor.green, dominantColor.blue)
        if (saturation <= 0.04f) return baseColor

        val hueDegrees = (hNorm * 360f).coerceIn(0f, 360f)
        return if (isDarkTheme) {
            val accent = Color.hsv(
                hue = hueDegrees,
                saturation = saturation.coerceIn(0.30f, 0.65f),
                value = 0.40f,
            )
            lerp(baseColor, accent, 0.28f * factor)
        } else {
            val accent = Color.hsv(
                hue = hueDegrees,
                saturation = saturation.coerceIn(0.32f, 0.72f),
                value = 0.90f,
            )
            lerp(baseColor, accent, 0.16f * factor)
        }
    }
}

/** Converts normalized RGB `[0, 1]` to HSV with hue, saturation and value all in `[0, 1]`. */
internal fun rgbToHsvNormalized(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC

    val v = maxC
    val s = if (maxC > 0f) delta / maxC else 0f
    val h = if (delta <= 1e-6f) {
        0f
    } else {
        var raw = when (maxC) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } / 6f
        if (raw < 0f) raw += 1f
        raw.coerceIn(0f, 1f)
    }
    return Triple(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
}
