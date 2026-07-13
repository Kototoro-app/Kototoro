package org.skepsun.kototoro.reader.ui.pager

import android.graphics.Bitmap

/** Resolves a neutral reader background from pixels along the page boundary. */
internal object ReaderAutoBackground {

	fun resolve(bitmap: Bitmap): Int {
		return resolve(bitmap.width, bitmap.height, bitmap::getPixel)
	}

	internal fun merge(first: Int, second: Int?): Int {
		if (second == null || first == second) return first
		if (first.isCloseTo(second, MAX_MERGE_COLOR_DISTANCE_SQUARED)) {
			return averageColor(first, second).toSafeBackgroundColor()
		}
		if (first == LIGHT_COLOR && second != DARK_COLOR) {
			return second.toSafeBackgroundColor(MAX_MERGED_SATURATION)
		}
		if (second == LIGHT_COLOR && first != DARK_COLOR) {
			return first.toSafeBackgroundColor(MAX_MERGED_SATURATION)
		}
		return if ((first.luminance() + second.luminance()) / 2 < DARK_LUMINANCE_THRESHOLD) {
			DARK_COLOR
		} else {
			LIGHT_COLOR
		}
	}

	internal fun resolve(width: Int, height: Int, pixelAt: (Int, Int) -> Int): Int {
		if (width < MIN_SIZE || height < MIN_SIZE) return LIGHT_COLOR
		val samples = buildList(SAMPLE_COUNT * 4) {
			for (index in 0 until SAMPLE_COUNT) {
				val x = index * (width - 1) / (SAMPLE_COUNT - 1)
				val y = index * (height - 1) / (SAMPLE_COUNT - 1)
				add(sample(pixelAt(x, EDGE_OFFSET)))
				add(sample(pixelAt(x, height - 1 - EDGE_OFFSET)))
				add(sample(pixelAt(EDGE_OFFSET, y)))
				add(sample(pixelAt(width - 1 - EDGE_OFFSET, y)))
			}
		}
		if (samples.count { it.luminance >= WHITE_LUMINANCE_THRESHOLD } >= samples.size * DOMINANT_RATIO) {
			return LIGHT_COLOR
		}
		if (samples.count { it.luminance <= BLACK_LUMINANCE_THRESHOLD } >= samples.size * DOMINANT_RATIO) {
			return DARK_COLOR
		}

		val representative = Sample(
			red = samples.map(Sample::red).median(),
			green = samples.map(Sample::green).median(),
			blue = samples.map(Sample::blue).median(),
			luminance = 0.0,
		)
		val matchingSamples = samples.count { it.isCloseTo(representative) }
		if (matchingSamples >= samples.size * UNIFORM_RATIO) {
			return representative.toColor().toSafeBackgroundColor()
		}

		val medianLuminance = samples.map(Sample::luminance).sorted()[samples.size / 2]
		return if (medianLuminance < DARK_LUMINANCE_THRESHOLD) DARK_COLOR else LIGHT_COLOR
	}

	private fun sample(color: Int): Sample {
		if (color ushr 24 < MIN_ALPHA) return Sample(255, 255, 255, 1.0)
		val red = color shr 16 and 0xFF
		val green = color shr 8 and 0xFF
		val blue = color and 0xFF
		return Sample(
			red = red,
			green = green,
			blue = blue,
			luminance = 0.2126 * linearize(red) + 0.7152 * linearize(green) + 0.0722 * linearize(blue),
		)
	}

	private fun linearize(channel: Int): Double = (channel / 255.0).let {
		if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4)
	}

	private fun List<Int>.median(): Int = sorted()[size / 2]

	private fun averageColor(first: Int, second: Int): Int = colorOf(
		red = (first.red + second.red) / 2,
		green = (first.green + second.green) / 2,
		blue = (first.blue + second.blue) / 2,
	)

	private fun Int.isCloseTo(other: Int, maxDistanceSquared: Int): Boolean {
		val redDelta = red - other.red
		val greenDelta = green - other.green
		val blueDelta = blue - other.blue
		return redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta <= maxDistanceSquared
	}

	private fun Int.luminance(): Double {
		return 0.2126 * linearize(red) + 0.7152 * linearize(green) + 0.0722 * linearize(blue)
	}

	private fun Int.toSafeBackgroundColor(maxSaturation: Float = MAX_SATURATION): Int {
		val max = maxOf(red, green, blue) / 255f
		val min = minOf(red, green, blue) / 255f
		val originalLightness = (max + min) / 2f
		val lightness = originalLightness.coerceIn(MIN_BACKGROUND_LIGHTNESS, MAX_BACKGROUND_LIGHTNESS)
		val delta = max - min
		val saturation = if (delta == 0f) {
			0f
		} else {
			(delta / (1f - kotlin.math.abs(2f * originalLightness - 1f))).coerceAtMost(maxSaturation)
		}
		val hue = when {
			delta == 0f -> 0f
			max == red / 255f -> 60f * (((green - blue) / 255f / delta) % 6f)
			max == green / 255f -> 60f * (((blue - red) / 255f / delta) + 2f)
			else -> 60f * (((red - green) / 255f / delta) + 4f)
		}.let { if (it < 0f) it + 360f else it }
		return hslToColor(hue, saturation, lightness)
	}

	private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Int {
		val chroma = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
		val component = chroma * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
		val (redPart, greenPart, bluePart) = when (hue) {
			in 0f..<60f -> Triple(chroma, component, 0f)
			in 60f..<120f -> Triple(component, chroma, 0f)
			in 120f..<180f -> Triple(0f, chroma, component)
			in 180f..<240f -> Triple(0f, component, chroma)
			in 240f..<300f -> Triple(component, 0f, chroma)
			else -> Triple(chroma, 0f, component)
		}
		val offset = lightness - chroma / 2f
		return colorOf(
			red = ((redPart + offset) * 255f).toInt(),
			green = ((greenPart + offset) * 255f).toInt(),
			blue = ((bluePart + offset) * 255f).toInt(),
		)
	}

	private fun colorOf(red: Int, green: Int, blue: Int): Int {
		return -0x1000000 or
			(red.coerceIn(0, 255) shl 16) or
			(green.coerceIn(0, 255) shl 8) or
			blue.coerceIn(0, 255)
	}

	private val Int.red: Int get() = this shr 16 and 0xFF
	private val Int.green: Int get() = this shr 8 and 0xFF
	private val Int.blue: Int get() = this and 0xFF

	private data class Sample(
		val red: Int,
		val green: Int,
		val blue: Int,
		val luminance: Double,
	) {

		fun isCloseTo(other: Sample): Boolean {
			val redDelta = red - other.red
			val greenDelta = green - other.green
			val blueDelta = blue - other.blue
			return redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta <= MAX_COLOR_DISTANCE_SQUARED
		}

		fun toColor(): Int = -0x1000000 or (red shl 16) or (green shl 8) or blue
	}

	private const val MIN_SIZE = 8
	private const val SAMPLE_COUNT = 12
	private const val EDGE_OFFSET = 1
	private const val MIN_ALPHA = 128
	private const val DARK_LUMINANCE_THRESHOLD = 0.35
	private const val WHITE_LUMINANCE_THRESHOLD = 0.90
	private const val BLACK_LUMINANCE_THRESHOLD = 0.02
	private const val DOMINANT_RATIO = 0.60
	private const val UNIFORM_RATIO = 0.60
	private const val MAX_COLOR_DISTANCE = 48
	private const val MAX_COLOR_DISTANCE_SQUARED = 3 * MAX_COLOR_DISTANCE * MAX_COLOR_DISTANCE
	private const val MAX_MERGE_COLOR_DISTANCE = 64
	private const val MAX_MERGE_COLOR_DISTANCE_SQUARED = 3 * MAX_MERGE_COLOR_DISTANCE * MAX_MERGE_COLOR_DISTANCE
	private const val MAX_SATURATION = 0.35f
	private const val MAX_MERGED_SATURATION = 0.24f
	private const val MIN_BACKGROUND_LIGHTNESS = 0.08f
	private const val MAX_BACKGROUND_LIGHTNESS = 0.92f
	private const val DARK_COLOR = -0x1000000
	private const val LIGHT_COLOR = -0x1
}
