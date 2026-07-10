package org.skepsun.kototoro.reader.translate.domain

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
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
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class DefaultDbNetTextDetector @Inject constructor(
	private val settings: AppSettings,
	private val onnxModelManager: OnnxModelManager,
) : ReaderTextDetector {

	private data class Runtime(
		val session: OrtSession,
		val inputName: String,
		val backend: ExecutionBackend,
	) {
		fun close() = runCatching { session.close() }
	}

	private enum class ExecutionBackend {
		NNAPI,
		XNNPACK,
		CPU,
	}

	private data class PreparedInput(
		val tensor: OnnxTensor,
		val inputWidth: Int,
		val inputHeight: Int,
		val scale: Float,
	)

	private data class Candidate(
		val quad: TextQuad,
		val rect: Rect,
		val score: Float,
	)

	private data class ProbabilityMap(
		val values: FloatArray,
		val width: Int,
		val height: Int,
	)

	private val runtimeLock = Mutex()
	private val inferenceLock = Mutex()
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
		return inferenceLock.withLock {
			val startedAt = SystemClock.elapsedRealtime()
			val detectSize = resolveAdaptiveDetectionSize(
				width = bitmap.width,
				height = bitmap.height,
				requestedSize = settings.readerTranslationOcrDetectionMaxSide,
			)
			val isLongImage = requiresLongImageRearrange(bitmap, detectSize)
			val regions = if (isLongImage) {
				detectLongImage(bitmap, currentRuntime, detectSize)
			} else {
				detectSingle(bitmap, currentRuntime, detectSize)
			}
			log {
				"metric.ocr.dbnet.total_ms=${SystemClock.elapsedRealtime() - startedAt} " +
					"backend=${currentRuntime.backend.name.lowercase()} input_size=$detectSize " +
					"long_image=${if (isLongImage) 1 else 0} regions=${regions.size}"
			}
			regions
		}
	}

	private suspend fun ensureRuntime(modelId: String): Runtime? {
		runtime?.let { return it }
		return runtimeLock.withLock {
			runtime?.let { return@withLock it }
			val model = OnnxOfficialModelCatalog.findById(modelId) ?: return@withLock null
			val modelDir = File(onnxModelManager.ensureModelReady(model))
			val modelFile = modelDir.walkTopDown().firstOrNull {
				it.isFile && it.extension.equals("onnx", ignoreCase = true)
			} ?: return@withLock null
			val backends = buildList {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(ExecutionBackend.NNAPI)
				add(ExecutionBackend.XNNPACK)
				add(ExecutionBackend.CPU)
			}
			backends.firstNotNullOfOrNull { backend ->
				createRuntime(modelFile, backend)
			}.also { resolved ->
				if (resolved != null) {
					runtime = resolved
					log { "metric.ocr.dbnet.backend=${resolved.backend.name.lowercase()}" }
				}
			}
		}
	}

	private fun createRuntime(modelFile: File, backend: ExecutionBackend): Runtime? {
		return runCatching {
			OrtSession.SessionOptions().use { options ->
				options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
				options.setInterOpNumThreads(1)
				when (backend) {
					ExecutionBackend.NNAPI -> options.addNnapi()
					ExecutionBackend.XNNPACK -> {
						options.setIntraOpNumThreads(1)
						options.addXnnpack(mapOf("intra_op_num_threads" to inferenceThreadCount().toString()))
					}
					ExecutionBackend.CPU -> options.setIntraOpNumThreads(inferenceThreadCount())
				}
				val session = OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, options)
				val inputName = session.inputNames.firstOrNull() ?: run {
					session.close()
					return@use null
				}
				Runtime(session = session, inputName = inputName, backend = backend)
			}
		}.onFailure { error ->
			log {
				"metric.ocr.dbnet.backend_failed=${backend.name.lowercase()} " +
					"error=${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}"
			}
		}.getOrNull()
	}

	private fun detectSingle(bitmap: Bitmap, runtime: Runtime, detectSize: Int): List<TextRegion> {
		val prepareStartedAt = SystemClock.elapsedRealtime()
		val prepared = prepareInput(bitmap, detectSize)
		val prepareDuration = SystemClock.elapsedRealtime() - prepareStartedAt
		var result: OrtSession.Result? = null
		return try {
			val inferenceStartedAt = SystemClock.elapsedRealtime()
			result = runtime.session.run(mapOf(runtime.inputName to prepared.tensor))
			val inferenceDuration = SystemClock.elapsedRealtime() - inferenceStartedAt
			val dbTensor = result.get(DB_OUTPUT_NAME).orElse(null) as? OnnxTensor
				?: result.firstOrNull { it.key == DB_OUTPUT_NAME }?.value as? OnnxTensor
				?: return emptyList()
			val postprocessStartedAt = SystemClock.elapsedRealtime()
			val regions = decodeDbMap(
				tensor = dbTensor,
				prepared = prepared,
				sourceWidth = bitmap.width,
				sourceHeight = bitmap.height,
			)
			log {
				"metric.ocr.dbnet.prepare_ms=$prepareDuration inference_ms=$inferenceDuration " +
					"postprocess_ms=${SystemClock.elapsedRealtime() - postprocessStartedAt} " +
					"tensor=${prepared.inputWidth}x${prepared.inputHeight}"
			}
			regions
		} finally {
			runCatching { result?.close() }
			prepared.tensor.close()
		}
	}

	private fun prepareInput(bitmap: Bitmap, detectSize: Int): PreparedInput {
		val readable = if (bitmap.config == Bitmap.Config.HARDWARE) {
			bitmap.copy(Bitmap.Config.ARGB_8888, false)
		} else {
			bitmap
		}
		val rgba = Mat()
		val rgb = Mat()
		val filtered = Mat()
		val resized = Mat()
		val padded = Mat()
		try {
			Utils.bitmapToMat(readable, rgba)
			Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
			val scale = detectSize.toFloat() / max(bitmap.width, bitmap.height).coerceAtLeast(1).toFloat()
			val resizedWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
			val resizedHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
			Imgproc.resize(rgb, resized, Size(resizedWidth.toDouble(), resizedHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
			Imgproc.bilateralFilter(resized, filtered, 17, 80.0, 80.0)
			val padRight = paddingFor(resizedWidth, INPUT_MULTIPLE)
			val padBottom = paddingFor(resizedHeight, INPUT_MULTIPLE)
			Core.copyMakeBorder(
				filtered,
				padded,
				0,
				padBottom,
				0,
				padRight,
				Core.BORDER_CONSTANT,
				Scalar.all(0.0),
			)
			val width = padded.cols()
			val height = padded.rows()
			return PreparedInput(
				tensor = createInputTensor(padded),
				inputWidth = width,
				inputHeight = height,
				scale = scale,
			)
		} finally {
			rgba.release()
			rgb.release()
			filtered.release()
			resized.release()
			padded.release()
			if (readable !== bitmap) readable.recycle()
		}
	}

	private fun createInputTensor(rgb: Mat): OnnxTensor {
		val width = rgb.cols()
		val height = rgb.rows()
		val bytes = ByteArray(width * height * 3)
		rgb.get(0, 0, bytes)
		val plane = width * height
		val values = FloatArray(plane * 3)
		for (index in 0 until plane) {
			val byteOffset = index * 3
			values[index] = (bytes[byteOffset].toInt() and 0xFF) / 127.5f - 1f
			values[plane + index] = (bytes[byteOffset + 1].toInt() and 0xFF) / 127.5f - 1f
			values[plane * 2 + index] = (bytes[byteOffset + 2].toInt() and 0xFF) / 127.5f - 1f
		}
		return OnnxTensor.createTensor(
			OrtEnvironment.getEnvironment(),
			FloatBuffer.wrap(values),
			longArrayOf(1, 3, height.toLong(), width.toLong()),
		)
	}

	private fun decodeDbMap(
		tensor: OnnxTensor,
		prepared: PreparedInput,
		sourceWidth: Int,
		sourceHeight: Int,
	): List<TextRegion> {
		val shape = (tensor.info as? TensorInfo)?.shape ?: return emptyList()
		if (shape.size != 4 || shape[0] != 1L || shape[1] < 1L) return emptyList()
		val channels = shape[1].toInt()
		val mapHeight = shape[2].toInt()
		val mapWidth = shape[3].toInt()
		if (mapWidth <= 0 || mapHeight <= 0) return emptyList()
		val raw = FloatArray(channels * mapWidth * mapHeight)
		tensor.floatBuffer.get(raw)
		val probabilities = FloatArray(mapWidth * mapHeight) { index -> sigmoid(raw[index]) }
		return decodeProbabilityMap(
			probabilities = probabilities,
			mapWidth = mapWidth,
			mapHeight = mapHeight,
			inputWidth = prepared.inputWidth,
			inputHeight = prepared.inputHeight,
			scale = prepared.scale,
			sourceWidth = sourceWidth,
			sourceHeight = sourceHeight,
		)
	}

	private fun decodeProbabilityMap(
		probabilities: FloatArray,
		mapWidth: Int,
		mapHeight: Int,
		inputWidth: Int,
		inputHeight: Int,
		scale: Float,
		sourceWidth: Int,
		sourceHeight: Int,
	): List<TextRegion> {
		val probabilityMap = Mat(mapHeight, mapWidth, CvType.CV_32FC1)
		val binaryMap = Mat(mapHeight, mapWidth, CvType.CV_8UC1)
		val hierarchy = Mat()
		val contours = ArrayList<MatOfPoint>()
		return try {
			probabilityMap.put(0, 0, probabilities)
			val binary = ByteArray(probabilities.size) { index ->
				if (probabilities[index] > TEXT_THRESHOLD) 0xFF.toByte() else 0
			}
			binaryMap.put(0, 0, binary)
			Imgproc.findContours(binaryMap, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
			val candidates = contours.asSequence()
				.take(MAX_CANDIDATES)
				.mapNotNull { contour ->
					decodeContour(
						contour = contour,
						probabilityMap = probabilityMap,
						mapWidth = mapWidth,
						mapHeight = mapHeight,
						inputWidth = inputWidth,
						inputHeight = inputHeight,
						scale = scale,
						sourceWidth = sourceWidth,
						sourceHeight = sourceHeight,
					)
				}
				.toList()
			nonMaxSuppress(candidates, NMS_IOU_THRESHOLD)
				.sortedWith(compareBy<Candidate> { it.rect.top }.thenBy { it.rect.left })
				.map { candidate ->
					TextRegion(
						rect = candidate.rect,
						confidence = candidate.score,
						detectorId = MODEL_ID,
						isAxisAligned = isAxisAlignedQuad(candidate.quad),
						quadPoints = candidate.quad,
					)
				}
		} finally {
			contours.forEach(Mat::release)
			hierarchy.release()
			binaryMap.release()
			probabilityMap.release()
		}
	}

	private fun decodeContour(
		contour: MatOfPoint,
		probabilityMap: Mat,
		mapWidth: Int,
		mapHeight: Int,
		inputWidth: Int,
		inputHeight: Int,
		scale: Float,
		sourceWidth: Int,
		sourceHeight: Int,
	): Candidate? {
		if (contour.rows() < 4) return null
		val points2f = MatOfPoint2f(*contour.toArray())
		return try {
			val baseRect = Imgproc.minAreaRect(points2f)
			if (min(baseRect.size.width, baseRect.size.height) < MIN_BOX_SIDE) return null
			val score = scoreContour(probabilityMap, contour)
			if (score < BOX_THRESHOLD) return null
			val area = baseRect.size.width * baseRect.size.height
			val perimeter = 2.0 * (baseRect.size.width + baseRect.size.height)
			if (area <= 0.0 || perimeter <= 0.0) return null
			val distance = area * UNCLIP_RATIO / perimeter
			val expanded = RotatedRect(
				baseRect.center,
				Size(baseRect.size.width + distance * 2.0, baseRect.size.height + distance * 2.0),
				baseRect.angle,
			)
			if (min(expanded.size.width, expanded.size.height) < MIN_UNCLIPPED_BOX_SIDE) return null
			val outputPoints = arrayOf(Point(), Point(), Point(), Point())
			expanded.points(outputPoints)
			val mappedPoints = outputPoints.map { point ->
				val inputX = point.x / mapWidth.toDouble() * inputWidth.toDouble()
				val inputY = point.y / mapHeight.toDouble() * inputHeight.toDouble()
				(inputX / scale).toFloat().coerceIn(0f, sourceWidth.toFloat()) to
					(inputY / scale).toFloat().coerceIn(0f, sourceHeight.toFloat())
			}
			val quad = TextQuad(orderQuad(mappedPoints))
			val rect = textQuadToBoundingRect(quad)
			if (rect.width() < 2 || rect.height() < 2) return null
			Candidate(quad = quad, rect = rect, score = score)
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

	private fun detectLongImage(bitmap: Bitmap, runtime: Runtime, detectSize: Int): List<TextRegion> {
		val transpose = bitmap.height < bitmap.width
		val readable = if (bitmap.config == Bitmap.Config.HARDWARE) {
			bitmap.copy(Bitmap.Config.ARGB_8888, false)
		} else {
			bitmap
		}
		val rgba = Mat()
		val rgb = Mat()
		val splitView = Mat()
		try {
			Utils.bitmapToMat(readable, rgba)
			Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
			if (transpose) Core.transpose(rgb, splitView) else rgb.copyTo(splitView)
			val longSide = splitView.rows()
			val shortSide = splitView.cols()
			val stripsPerBatch = max(floor(2.0 * detectSize.toDouble() / shortSide.toDouble()).toInt(), 2)
			val patchSize = stripsPerBatch * shortSide
			val stripCount = ceil(longSide.toDouble() / patchSize.toDouble()).toInt()
			val stripStep = if (stripCount > 1) (longSide - patchSize) / (stripCount - 1) else 0
			val batchCount = ceil(stripCount.toDouble() / stripsPerBatch.toDouble()).toInt()
			var combined: FloatArray? = null
			var combinedWidth = 0
			var combinedHeight = 0
			for (batchIndex in 0 until batchCount) {
				val square = Mat.zeros(patchSize, patchSize, CvType.CV_8UC3)
				val resized = Mat()
				try {
					for (slot in 0 until stripsPerBatch) {
						val stripIndex = batchIndex * stripsPerBatch + slot
						if (stripIndex >= stripCount) break
						val top = stripIndex * stripStep
						val source = splitView.submat(top, top + patchSize, 0, shortSide)
						val target = square.submat(0, patchSize, slot * shortSide, (slot + 1) * shortSide)
						try {
							source.copyTo(target)
						} finally {
							target.release()
							source.release()
						}
					}
					Imgproc.resize(square, resized, Size(detectSize.toDouble(), detectSize.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
					val map = inferProbabilityMap(resized, runtime) ?: continue
					val restoredStripWidth = map.width / stripsPerBatch
					if (restoredStripWidth <= 0) continue
					if (combined == null) {
						combinedWidth = restoredStripWidth
						combinedHeight = (restoredStripWidth.toDouble() / shortSide.toDouble() * longSide.toDouble()).toInt()
						combined = FloatArray(combinedWidth * combinedHeight)
					}
					val output = combined ?: continue
					val scaledStep = (stripStep.toDouble() * map.height.toDouble() / patchSize.toDouble()).toInt()
					for (slot in 0 until stripsPerBatch) {
						val stripIndex = batchIndex * stripsPerBatch + slot
						if (stripIndex >= stripCount) break
						val relativeTop = stripIndex * stripStep.toDouble() / longSide.toDouble()
						val targetTop = (relativeTop * combinedHeight).roundToInt()
						val copyHeight = min(map.height, combinedHeight - targetTop)
						val sourceLeft = slot * restoredStripWidth
						for (y in 0 until copyHeight) {
							val sourceOffset = y * map.width + sourceLeft
							val targetOffset = (targetTop + y) * combinedWidth
							for (x in 0 until combinedWidth) {
								output[targetOffset + x] += map.values[sourceOffset + x]
							}
						}
						if (stripIndex > 0 && scaledStep < map.height) {
							val overlapHeight = min(map.height - scaledStep, copyHeight)
							for (y in 0 until overlapHeight) {
								val targetOffset = (targetTop + y) * combinedWidth
								for (x in 0 until combinedWidth) {
									output[targetOffset + x] /= 2f
								}
							}
						}
					}
				} finally {
					resized.release()
					square.release()
				}
			}
			val restored = combined ?: return emptyList()
			val finalValues: FloatArray
			val finalWidth: Int
			val finalHeight: Int
			if (transpose) {
				finalWidth = combinedHeight
				finalHeight = combinedWidth
				finalValues = FloatArray(finalWidth * finalHeight)
				for (y in 0 until finalHeight) {
					for (x in 0 until finalWidth) {
						finalValues[y * finalWidth + x] = restored[x * combinedWidth + y]
					}
				}
			} else {
				finalValues = restored
				finalWidth = combinedWidth
				finalHeight = combinedHeight
			}
			return decodeProbabilityMap(
				probabilities = finalValues,
				mapWidth = finalWidth,
				mapHeight = finalHeight,
				inputWidth = bitmap.width,
				inputHeight = bitmap.height,
				scale = 1f,
				sourceWidth = bitmap.width,
				sourceHeight = bitmap.height,
			)
		} finally {
			splitView.release()
			rgb.release()
			rgba.release()
			if (readable !== bitmap) readable.recycle()
		}
	}

	private fun inferProbabilityMap(rgb: Mat, runtime: Runtime): ProbabilityMap? {
		val tensor = createInputTensor(rgb)
		var result: OrtSession.Result? = null
		return try {
			result = runtime.session.run(mapOf(runtime.inputName to tensor))
			val output = result.get(DB_OUTPUT_NAME).orElse(null) as? OnnxTensor ?: return null
			val shape = (output.info as? TensorInfo)?.shape ?: return null
			if (shape.size != 4 || shape[0] != 1L || shape[1] < 1L) return null
			val channels = shape[1].toInt()
			val height = shape[2].toInt()
			val width = shape[3].toInt()
			val raw = FloatArray(channels * width * height)
			output.floatBuffer.get(raw)
			ProbabilityMap(
				values = FloatArray(width * height) { sigmoid(raw[it]) },
				width = width,
				height = height,
			)
		} finally {
			runCatching { result?.close() }
			tensor.close()
		}
	}

	private fun nonMaxSuppress(candidates: List<Candidate>, threshold: Float): List<Candidate> {
		val kept = ArrayList<Candidate>()
		for (candidate in candidates.sortedByDescending(Candidate::score)) {
			if (kept.none { intersectionOverUnion(it.rect, candidate.rect) >= threshold }) {
				kept += candidate
			}
		}
		return kept
	}

	private fun intersectionOverUnion(a: Rect, b: Rect): Float {
		val width = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0)
		val height = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0)
		val intersection = width.toLong() * height.toLong()
		if (intersection <= 0L) return 0f
		val areaA = a.width().coerceAtLeast(0).toLong() * a.height().coerceAtLeast(0).toLong()
		val areaB = b.width().coerceAtLeast(0).toLong() * b.height().coerceAtLeast(0).toLong()
		val union = areaA + areaB - intersection
		return if (union <= 0L) 0f else intersection.toFloat() / union.toFloat()
	}

	private fun orderQuad(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
		if (points.size != 4) return points
		val centerX = points.sumOf { it.first.toDouble() }.toFloat() / points.size
		val centerY = points.sumOf { it.second.toDouble() }.toFloat() / points.size
		val ordered = points.sortedBy { kotlin.math.atan2((it.second - centerY).toDouble(), (it.first - centerX).toDouble()) }
		val start = ordered.indices.minByOrNull { ordered[it].first + ordered[it].second } ?: 0
		return List(ordered.size) { ordered[(start + it) % ordered.size] }
	}

	private fun requiresLongImageRearrange(bitmap: Bitmap, detectSize: Int): Boolean {
		return requiresLongImageRearrange(bitmap.width, bitmap.height, detectSize)
	}

	private fun inferenceThreadCount(): Int {
		return java.lang.Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_INFERENCE_THREADS)
	}

	private inline fun log(message: () -> String) {
		if (settings.isReaderTranslationDebugLogsEnabled) {
			Log.d(LOG_TAG, message())
		}
	}

	private fun ensureOpenCvLoaded(): Boolean {
		if (openCvLoaded) return true
		return synchronized(OPEN_CV_LOCK) {
			if (!openCvLoaded) {
				openCvLoaded = OpenCVLoader.initLocal()
			}
			openCvLoaded
		}
	}

	companion object {
		const val MODEL_ID = "manga_default_det_20241225_onnx"
		const val MODEL_FILE_NAME = "detect-20241225.onnx"
		const val MODEL_SHA256 = "0875cca4fda3d3c29dbc9b8d22b1230a9240f2ee187c83cc5068ee2006332f97"
		private const val LOG_TAG = "ReaderOcrDbNet"
		private const val NORMAL_PAGE_DETECT_SIZE = 1024
		private const val MAX_DBNET_DETECT_SIZE = 2048
		private const val MAX_INFERENCE_THREADS = 4
		private const val DB_OUTPUT_NAME = "db_map"
		private const val INPUT_MULTIPLE = 256
		private const val TEXT_THRESHOLD = 0.5f
		private const val BOX_THRESHOLD = 0.7f
		private const val UNCLIP_RATIO = 2.3
		private const val MIN_BOX_SIDE = 3.0
		private const val MIN_UNCLIPPED_BOX_SIDE = 5.0
		private const val NMS_IOU_THRESHOLD = 0.9f
		private const val MAX_CANDIDATES = 1000
		private const val LONG_IMAGE_SCALE_THRESHOLD = 2.5f
		private const val LONG_IMAGE_ASPECT_THRESHOLD = 3f
		private val OPEN_CV_LOCK = Any()
		@Volatile
		private var openCvLoaded = false

		internal fun paddingFor(value: Int, multiple: Int): Int {
			return (multiple - value % multiple) % multiple
		}

		internal fun resolveDetectionSize(requestedSize: Int): Int {
			return requestedSize.coerceIn(512, MAX_DBNET_DETECT_SIZE)
		}

		internal fun resolveAdaptiveDetectionSize(width: Int, height: Int, requestedSize: Int): Int {
			val resolvedSize = resolveDetectionSize(requestedSize)
			val longSide = max(width, height).toFloat()
			val shortSide = min(width, height).coerceAtLeast(1).toFloat()
			return if (longSide / shortSide > LONG_IMAGE_ASPECT_THRESHOLD) {
				resolvedSize
			} else {
				min(resolvedSize, NORMAL_PAGE_DETECT_SIZE)
			}
		}

		internal fun sigmoid(value: Float): Float {
			return (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
		}

		internal fun requiresLongImageRearrange(width: Int, height: Int, detectSize: Int): Boolean {
			val longSide = max(width, height).toFloat()
			val shortSide = min(width, height).coerceAtLeast(1).toFloat()
			return longSide / detectSize.toFloat() > LONG_IMAGE_SCALE_THRESHOLD &&
				longSide / shortSide > LONG_IMAGE_ASPECT_THRESHOLD
		}
	}
}
