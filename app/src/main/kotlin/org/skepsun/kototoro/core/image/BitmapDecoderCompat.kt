package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import okio.IOException
import okio.buffer
import okio.source
import org.aomedia.avif.android.AvifDecoder
import org.aomedia.avif.android.AvifDecoder.Info
import org.jetbrains.annotations.Blocking
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.MimeType
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.readByteBuffer
import org.skepsun.kototoro.core.util.ext.toByteBuffer
import org.skepsun.kototoro.core.util.ext.toMimeTypeOrNull
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

object BitmapDecoderCompat {

	private const val FORMAT_AVIF = "avif"

	@Blocking
	fun decode(file: File): Bitmap = when (val format = probeMimeType(file)?.subtype) {
		FORMAT_AVIF -> file.source().buffer().use { decodeAvif(it.readByteBuffer()) }
		else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			ImageDecoder.decodeBitmap(ImageDecoder.createSource(file))
		} else {
			checkBitmapNotNull(BitmapFactory.decodeFile(file.absolutePath), format)
		}
	}

	@Blocking
	fun decode(stream: InputStream, type: MimeType?, isMutable: Boolean = false): Bitmap {
		val format = type?.subtype
		if (format == FORMAT_AVIF) {
			return decodeAvif(stream.toByteBuffer())
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
			val opts = BitmapFactory.Options()
			opts.inMutable = isMutable
			return checkBitmapNotNull(BitmapFactory.decodeStream(stream, null, opts), format)
		}
		val byteBuffer = stream.toByteBuffer()
		return if (AvifDecoder.isAvifImage(byteBuffer)) {
			decodeAvif(byteBuffer)
		} else {
			ImageDecoder.decodeBitmap(ImageDecoder.createSource(byteBuffer), DecoderConfigListener(isMutable))
		}
	}

	@Blocking
	fun createRegionDecoder(inoutStream: InputStream): BitmapRegionDecoder? = try {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			BitmapRegionDecoder.newInstance(inoutStream)
		} else {
			@Suppress("DEPRECATION")
			BitmapRegionDecoder.newInstance(inoutStream, false)
		}
	} catch (e: IOException) {
		e.printStackTraceDebug()
		null
	}

	@Blocking
	fun isAnimated(file: File): Boolean {
		if (!file.exists()) return false
		return try {
			file.inputStream().use { stream ->
				val header = ByteArray(1024)
				val n = stream.read(header)
				if (n < 6) return false
				isAnimatedHeader(header, n)
			}
		} catch (_: Exception) {
			false
		}
	}

	private fun isAnimatedHeader(h: ByteArray, n: Int): Boolean {
		// GIF87a / GIF89a
		if (h[0] == 'G'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte()) {
			return true
		}
		// Animated WebP: RIFF....WEBPVP8X with animation flag bit
		if (n >= 21 &&
			h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() &&
			h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte() &&
			h[8] == 'W'.code.toByte() && h[9] == 'E'.code.toByte() &&
			h[10] == 'B'.code.toByte() && h[11] == 'P'.code.toByte() &&
			h[12] == 'V'.code.toByte() && h[13] == 'P'.code.toByte() &&
			h[14] == '8'.code.toByte() && h[15] == 'X'.code.toByte() &&
			(h[20].toInt() and 0x02) != 0
		) return true
		// Animated AVIF: ftyp box with major brand "avis" or compatible brand "avis"
		if (n >= 12 &&
			h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte() &&
			h[6] == 'y'.code.toByte() && h[7] == 'p'.code.toByte()
		) {
			if (n >= 12 && String(h, 8, 4) == "avis") return true
			val numCompatible = (n - 16) / 4
			for (i in 0 until numCompatible) {
				val off = 16 + i * 4
				if (off + 4 <= n && String(h, off, 4) == "avis") return true
			}
		}
		// APNG: PNG magic bytes + acTL chunk somewhere in first 1024 bytes
		if (n >= 8 &&
			h[0] == 0x89.toByte() && h[1] == 0x50.toByte() &&
			h[2] == 0x4E.toByte() && h[3] == 0x47.toByte() &&
			h[4] == 0x0D.toByte() && h[5] == 0x0A.toByte() &&
			h[6] == 0x1A.toByte() && h[7] == 0x0A.toByte()
		) {
			for (i in 8 until n - 4) {
				if (h[i] == 'a'.code.toByte() && h[i + 1] == 'c'.code.toByte() &&
					h[i + 2] == 'T'.code.toByte() && h[i + 3] == 'L'.code.toByte()
				) return true
			}
		}
		return false
	}

	@Blocking
	fun probeMimeType(file: File): MimeType? {
		return MimeTypes.probeMimeType(file) ?: detectBitmapType(file)
	}

	@Blocking
	private fun detectBitmapType(file: File): MimeType? = runCatchingCancellable {
		val options = BitmapFactory.Options().apply {
			inJustDecodeBounds = true
		}
		BitmapFactory.decodeFile(file.path, options)?.recycle()
		options.outMimeType?.toMimeTypeOrNull()
	}.getOrNull()

	private fun checkBitmapNotNull(bitmap: Bitmap?, format: String?): Bitmap =
		bitmap ?: throw ImageDecodeException(null, format)

	private fun decodeAvif(bytes: ByteBuffer): Bitmap {
		val info = Info()
		if (!AvifDecoder.getInfo(bytes, bytes.remaining(), info)) {
			throw ImageDecodeException(
				null,
				FORMAT_AVIF,
				"Requested to decode byte buffer which cannot be handled by AvifDecoder",
			)
		}
		val config = if (info.depth == 8 || info.alphaPresent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
		val bitmap = createBitmap(info.width, info.height, config)
		if (!AvifDecoder.decode(bytes, bytes.remaining(), bitmap)) {
			bitmap.recycle()
			throw ImageDecodeException(null, FORMAT_AVIF)
		}
		return bitmap
	}

	@RequiresApi(Build.VERSION_CODES.P)
	private class DecoderConfigListener(
		private val isMutable: Boolean,
	) : ImageDecoder.OnHeaderDecodedListener {

		override fun onHeaderDecoded(
			decoder: ImageDecoder,
			info: ImageDecoder.ImageInfo,
			source: ImageDecoder.Source
		) {
			decoder.isMutableRequired = isMutable
		}
	}
}
