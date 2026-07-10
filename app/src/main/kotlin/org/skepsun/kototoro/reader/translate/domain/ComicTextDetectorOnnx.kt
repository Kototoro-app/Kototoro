package org.skepsun.kototoro.reader.translate.domain

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.reader.translate.data.OnnxModelCategory
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class ComicTextDetectorOnnx @Inject constructor(
	private val settings: AppSettings,
	private val onnxModelManager: OnnxModelManager,
) : ReaderTextDetector {

	private data class Runtime(
		val modelId: String,
		val inputName: String,
		val outputNames: Set<String>,
		val session: OrtSession,
	) {
		fun close() {
			runCatching { session.close() }
		}
	}

	private data class LetterboxedBitmap(
		val bitmap: Bitmap,
		val resizedWidth: Int,
		val resizedHeight: Int,
	)

	private data class ScoredRegion(
		val rect: Rect,
		val quad: TextQuad,
		val score: Float,
	)

	private data class DecodedRegions(
		val regions: List<ScoredRegion>,
		val contourCount: Int,
		val filteredCount: Int,
	)

	private val runtimeLock = Mutex()
	@Volatile
	private var runtime: Runtime? = null

	override suspend fun detect(sourceUri: Uri): List<TextRegion> {
		val bitmap = runInterruptible(Dispatchers.IO) {
			BitmapDecoderCompat.decode(sourceUri.toFile())
		}
		return try {
			detect(bitmap)
		} finally {
			bitmap.recycle()
		}
	}

	override suspend fun detect(bitmap: Bitmap): List<TextRegion> {
		if (!ensureOpenCvLoaded()) return emptyList()
		val model = OnnxOfficialModelCatalog.findById(MODEL_ID)
			?.takeIf { it.category == OnnxModelCategory.OCR_DETECTOR }
			?: return emptyList()
		val currentRuntime = ensureRuntime(model.id) ?: return emptyList()
		val startedAt = SystemClock.elapsedRealtime()
		val prepared = letterboxBitmap(bitmap, INPUT_SIZE)
		var inputTensor: OnnxTensor? = null
		var result: OrtSession.Result? = null
		return try {
			inputTensor = createImageTensor(prepared.bitmap)
			val preparedAt = SystemClock.elapsedRealtime()
			result = currentRuntime.session.run(mapOf(currentRuntime.inputName to inputTensor))
			val inferredAt = SystemClock.elapsedRealtime()
			val lineMapTensor = findLineMapTensor(result, currentRuntime.outputNames)
			val decoded = lineMapTensor?.let {
				decodeLineMapRegions(
					tensor = it,
					contentWidth = prepared.resizedWidth,
					contentHeight = prepared.resizedHeight,
					sourceWidth = bitmap.width,
					sourceHeight = bitmap.height,
				)
			} ?: DecodedRegions(emptyList(), 0, 0)
			val postprocessedAt = SystemClock.elapsedRealtime()
			log {
				"metric.ocr.ctd.prepare_ms=${preparedAt - startedAt} " +
					"inference_ms=${inferredAt - preparedAt} postprocess_ms=${postprocessedAt - inferredAt} " +
					"contours=${decoded.contourCount} filtered=${decoded.filteredCount} " +
					"regions=${decoded.regions.size} total_ms=${postprocessedAt - startedAt}"
			}
			decoded.regions
				.sortedWith(compareBy<ScoredRegion> { it.rect.top }.thenBy { it.rect.left })
				.map { region ->
				TextRegion(
					rect = region.rect,
					confidence = region.score,
					detectorId = MODEL_ID,
					isAxisAligned = isAxisAlignedQuad(region.quad),
					quadPoints = region.quad,
				)
				}
		} finally {
			runCatching { result?.close() }
			runCatching { inputTensor?.close() }
			prepared.bitmap.recycle()
		}
	}

	private fun decodeLineMapRegions(
		tensor: OnnxTensor,
		contentWidth: Int,
		contentHeight: Int,
		sourceWidth: Int,
		sourceHeight: Int,
	): DecodedRegions {
		val shape = (tensor.info as? TensorInfo)?.shape ?: return DecodedRegions(emptyList(), 0, 0)
		if (shape.size != 4 || shape[0] != 1L || shape[1] < 1L) {
			return DecodedRegions(emptyList(), 0, 0)
		}
		val channels = shape[1].toInt()
		val mapHeight = shape[2].toInt()
		val mapWidth = shape[3].toInt()
		if (mapWidth <= 0 || mapHeight <= 0) return DecodedRegions(emptyList(), 0, 0)
		val values = FloatArray(channels * mapWidth * mapHeight)
		tensor.floatBuffer.get(values)
		val validMapWidth = (contentWidth.toFloat() / INPUT_SIZE * mapWidth).roundToInt().coerceIn(1, mapWidth)
		val validMapHeight = (contentHeight.toFloat() / INPUT_SIZE * mapHeight).roundToInt().coerceIn(1, mapHeight)
		val probabilityMap = Mat(mapHeight, mapWidth, CvType.CV_32FC1)
		val binaryMap = Mat.zeros(mapHeight, mapWidth, CvType.CV_8UC1)
		val hierarchy = Mat()
		val contours = ArrayList<MatOfPoint>()
		return try {
			probabilityMap.put(0, 0, values.copyOfRange(0, mapWidth * mapHeight))
			val binary = ByteArray(mapWidth * mapHeight) { index ->
				val x = index % mapWidth
				val y = index / mapWidth
				if (x < validMapWidth && y < validMapHeight && values[index] > LINE_THRESHOLD) {
					0xFF.toByte()
				} else {
					0
				}
			}
			binaryMap.put(0, 0, binary)
			Imgproc.findContours(binaryMap, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
			var filteredCount = 0
			val regions = contours.asSequence()
				.take(MAX_CANDIDATES)
				.mapNotNull { contour ->
					val decoded = decodeLineContour(
						contour = contour,
						probabilityMap = probabilityMap,
						validMapWidth = validMapWidth,
						validMapHeight = validMapHeight,
						sourceWidth = sourceWidth,
						sourceHeight = sourceHeight,
					)
					if (decoded == null) filteredCount += 1
					decoded
				}
				.toList()
			DecodedRegions(
				regions = regions,
				contourCount = min(contours.size, MAX_CANDIDATES),
				filteredCount = filteredCount,
			)
		} finally {
			contours.forEach(Mat::release)
			hierarchy.release()
			binaryMap.release()
			probabilityMap.release()
		}
	}

	private fun decodeLineContour(
		contour: MatOfPoint,
		probabilityMap: Mat,
		validMapWidth: Int,
		validMapHeight: Int,
		sourceWidth: Int,
		sourceHeight: Int,
	): ScoredRegion? {
		val points2f = MatOfPoint2f(*contour.toArray())
		return try {
			val baseRect = Imgproc.minAreaRect(points2f)
			if (min(baseRect.size.width, baseRect.size.height) < MIN_CONTOUR_SIDE) return null
			val score = scoreContour(probabilityMap, contour)
			if (score <= BOX_SCORE_THRESHOLD) return null
			val area = baseRect.size.width * baseRect.size.height
			val perimeter = 2.0 * (baseRect.size.width + baseRect.size.height)
			if (area <= 0.0 || perimeter <= 0.0) return null
			val distance = area * UNCLIP_RATIO / perimeter
			val expanded = RotatedRect(
				baseRect.center,
				Size(baseRect.size.width + distance * 2.0, baseRect.size.height + distance * 2.0),
				baseRect.angle,
			)
			val points = arrayOf(Point(), Point(), Point(), Point())
			expanded.points(points)
			val mappedPoints = points.map { point ->
				(point.x / validMapWidth * sourceWidth).toFloat().coerceIn(0f, sourceWidth.toFloat()) to
					(point.y / validMapHeight * sourceHeight).toFloat().coerceIn(0f, sourceHeight.toFloat())
			}
			val quad = TextQuad(orderQuad(mappedPoints))
			val rect = textQuadToBoundingRect(quad)
			if (rect.width() <= 0 || rect.height() <= 0) return null
			ScoredRegion(rect = rect, quad = quad, score = score.coerceIn(0f, 1f))
		} finally {
			points2f.release()
		}
	}

	private fun scoreContour(probabilityMap: Mat, contour: MatOfPoint): Float {
		val bounds = Imgproc.boundingRect(contour)
		if (bounds.width <= 0 || bounds.height <= 0) return 0f
		val shifted = MatOfPoint(*contour.toArray().map { Point(it.x - bounds.x, it.y - bounds.y) }.toTypedArray())
		val mask = Mat.zeros(bounds.height, bounds.width, CvType.CV_8UC1)
		val roi = probabilityMap.submat(bounds)
		return try {
			Imgproc.fillPoly(mask, listOf(shifted), Scalar.all(1.0))
			Core.mean(roi, mask).`val`[0].toFloat()
		} finally {
			roi.release()
			mask.release()
			shifted.release()
		}
	}

	private fun orderQuad(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
		val sorted = points.sortedWith(compareBy<Pair<Float, Float>> { it.first }.thenBy { it.second })
		val left = sorted.take(2).sortedBy { it.second }
		val right = sorted.takeLast(2).sortedBy { it.second }
		return listOf(left[0], right[0], right[1], left[1])
	}

	private suspend fun ensureRuntime(modelId: String): Runtime? {
		val current = runtime
		if (current != null && current.modelId == modelId) {
			return current
		}
		return runtimeLock.withLock {
			val again = runtime
			if (again != null && again.modelId == modelId) {
				return@withLock again
			}
			runtime?.close()
			runtime = null
			val model = OnnxOfficialModelCatalog.findById(modelId) ?: return@withLock null
			val modelDir = File(onnxModelManager.ensureModelReady(model))
			val modelFile = modelDir.walkTopDown().firstOrNull { file ->
				file.isFile && file.extension.equals("onnx", ignoreCase = true)
			} ?: return@withLock null
			val env = OrtEnvironment.getEnvironment()
			val options = OrtSession.SessionOptions().apply {
				setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
				setInterOpNumThreads(1)
				setIntraOpNumThreads(2)
			}
			val session = env.createSession(modelFile.absolutePath, options)
			val inputName = session.inputNames.firstOrNull() ?: return@withLock null
			Runtime(
				modelId = modelId,
				inputName = inputName,
				outputNames = session.outputNames,
				session = session,
			).also {
				runtime = it
			}
		}
	}

	private fun letterboxBitmap(source: Bitmap, targetSize: Int): LetterboxedBitmap {
		val readable = ensureReadableBitmap(source)
		val scale = min(
			targetSize.toFloat() / readable.width.coerceAtLeast(1).toFloat(),
			targetSize.toFloat() / readable.height.coerceAtLeast(1).toFloat(),
		)
		val resizedWidth = (readable.width * scale).roundToInt().coerceIn(1, targetSize)
		val resizedHeight = (readable.height * scale).roundToInt().coerceIn(1, targetSize)
		val resized = Bitmap.createScaledBitmap(readable, resizedWidth, resizedHeight, true)
		val canvasBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(canvasBitmap)
		canvas.drawColor(Color.BLACK)
		canvas.drawBitmap(resized, 0f, 0f, null)
		if (resized !== readable) {
			resized.recycle()
		}
		if (readable !== source) {
			readable.recycle()
		}
		return LetterboxedBitmap(
			bitmap = canvasBitmap,
			resizedWidth = resizedWidth,
			resizedHeight = resizedHeight,
		)
	}

	private fun createImageTensor(bitmap: Bitmap): OnnxTensor {
		val width = bitmap.width
		val height = bitmap.height
		val pixels = IntArray(width * height)
		bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
		val data = FloatArray(3 * width * height)
		val channelStride = width * height
		for (y in 0 until height) {
			for (x in 0 until width) {
				val pixel = pixels[y * width + x]
				val base = y * width + x
				data[base] = ((pixel shr 16) and 0xFF) / 255f
				data[channelStride + base] = ((pixel shr 8) and 0xFF) / 255f
				data[channelStride * 2 + base] = (pixel and 0xFF) / 255f
			}
		}
		return OnnxTensor.createTensor(
			OrtEnvironment.getEnvironment(),
			FloatBuffer.wrap(data),
			longArrayOf(1, 3, height.toLong(), width.toLong()),
		)
	}

	private fun findLineMapTensor(
		result: OrtSession.Result,
		outputNames: Set<String>,
	): OnnxTensor? {
		for (name in outputNames) {
			val tensor = result.get(name).orElse(null) as? OnnxTensor ?: continue
			val info = tensor.info as? TensorInfo ?: continue
			val shape = info.shape
			if (shape.size == 4 && shape[1] == 2L) {
				return tensor
			}
		}
		return null
	}

	private fun ensureReadableBitmap(bitmap: Bitmap): Bitmap {
		if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
		return bitmap.copy(Bitmap.Config.ARGB_8888, false)
	}

	private fun ensureOpenCvLoaded(): Boolean {
		if (openCvLoaded) return true
		synchronized(openCvLoadLock) {
			if (!openCvLoadAttempted) {
				openCvLoaded = OpenCVLoader.initLocal()
				openCvLoadAttempted = true
			}
			return openCvLoaded
		}
	}

	private inline fun log(message: () -> String) {
		if (settings.isReaderTranslationDebugLogsEnabled) {
			Log.d(LOG_TAG, message())
		}
	}

	companion object {
		private val openCvLoadLock = Any()
		@Volatile
		private var openCvLoadAttempted = false
		@Volatile
		private var openCvLoaded = false

		const val LOG_TAG = "ReaderOcrCtdOrt"
		const val MODEL_ID = "comic_text_detector_onnx"
		const val INPUT_SIZE = 1024
		const val LINE_THRESHOLD = 0.30f
		const val BOX_SCORE_THRESHOLD = 0.60f
		const val UNCLIP_RATIO = 1.50
		const val MAX_CANDIDATES = 1000
		const val MIN_CONTOUR_SIDE = 2.0
	}
}
