package org.skepsun.kototoro.reader.translate.domain

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class PaddleCtcResult(
	val text: String,
	val confidence: Float,
)

internal fun computePaddleRecognitionWidth(sourceWidth: Int, sourceHeight: Int, maxWidth: Int): Int {
	return ceil(48f * sourceWidth / sourceHeight.coerceAtLeast(1)).toInt().coerceIn(1, maxWidth)
}

internal fun shouldRotatePaddleRecognitionCrop(width: Int, height: Int): Boolean {
	return height.toFloat() / width.coerceAtLeast(1) >= 1.5f
}

internal fun shouldRotateManga48pxRecognitionCrop(width: Int, height: Int): Boolean {
	return height > width
}

internal fun decodePaddleCtc(
	values: FloatArray,
	offset: Int,
	timeSteps: Int,
	classes: Int,
	dictionary: List<String>,
): PaddleCtcResult {
	val text = StringBuilder()
	var previousIndex = -1
	var confidenceSum = 0f
	var reachedEndToken = false
	for (timeStep in 0 until timeSteps) {
		val timeStepOffset = offset + timeStep * classes
		var bestIndex = 0
		var bestValue = Float.NEGATIVE_INFINITY
		for (classIndex in 0 until classes) {
			val value = values[timeStepOffset + classIndex]
			if (value > bestValue) {
				bestIndex = classIndex
				bestValue = value
			}
		}
		confidenceSum += bestValue
		if (!reachedEndToken && bestIndex != 0 && bestIndex != previousIndex) {
			val character = dictionary.getOrNull(bestIndex - 1)
			when (character) {
				"<S>" -> continue
				"</S>" -> reachedEndToken = true
				"<SP>" -> text.append(' ')
				null -> Unit
				else -> text.append(character)
			}
		}
		previousIndex = bestIndex
	}
	return PaddleCtcResult(
		text = text.toString(),
		confidence = if (timeSteps == 0) 0f else confidenceSum / timeSteps,
	)
}

internal fun decodeManga48pxCtc(
	values: FloatArray,
	offset: Int,
	timeSteps: Int,
	classes: Int,
	dictionary: List<String>,
): PaddleCtcResult {
	val text = StringBuilder()
	var previousIndex = 0
	var emittedLogProbabilitySum = 0f
	var emittedCount = 0
	for (timeStep in 0 until timeSteps) {
		val timeStepOffset = offset + timeStep * classes
		var bestIndex = 0
		var bestLogit = Float.NEGATIVE_INFINITY
		for (classIndex in 0 until classes) {
			val logit = values[timeStepOffset + classIndex]
			if (logit > bestLogit) {
				bestIndex = classIndex
				bestLogit = logit
			}
		}
		if (bestIndex != 0 && bestIndex != previousIndex) {
			var expSum = 0.0
			for (classIndex in 0 until classes) {
				expSum += exp((values[timeStepOffset + classIndex] - bestLogit).toDouble())
			}
			emittedLogProbabilitySum += -ln(expSum).toFloat()
			emittedCount++
			when (val character = dictionary.getOrNull(bestIndex)) {
				"<SP>" -> text.append(' ')
				null -> Unit
				else -> text.append(character)
			}
		}
		previousIndex = bestIndex
	}
	return PaddleCtcResult(
		text = text.toString(),
		confidence = if (emittedCount == 0) 0f else exp(emittedLogProbabilitySum / emittedCount),
	)
}

@Singleton
class PaddleReaderOcrEngine @Inject constructor(
	private val settings: AppSettings,
	private val onnxModelManager: OnnxModelManager,
) : ReaderOcrService, ReaderTextDetector, ReaderTextRecognizer {
	private enum class RecognizerKind {
		PADDLE,
		MANGA_48PX_CTC,
	}

	private data class Runtime(
		val detectorModelId: String,
		val recognizerModelId: String,
		val detectorNormalizeMode: DetectorNormalizeMode,
		val detSession: OrtSession,
		val recSession: OrtSession,
		val recDict: List<String>,
		val recognizerKind: RecognizerKind,
	) {
		fun close() {
			runCatching { detSession.close() }
			runCatching { recSession.close() }
		}
	}

	private data class DetectionResize(
		val width: Int,
		val height: Int,
		val scaleX: Float,
		val scaleY: Float,
	)

	private data class OcrTuning(
		val detectionMaxSide: Int,
		val detectionThreshold: Float,
		val minBoxSize: Int,
		val recognitionThreshold: Float,
		val recognitionMaxWidth: Int,
		val recognitionBatchSize: Int,
	)

	private enum class DetectorNormalizeMode {
		UNIT,
		IMAGENET,
	}

	private val runtimeLock = Mutex()
	@Volatile
	private var runtime: Runtime? = null
	private val textDetector = PaddleTextDetector()
	private val textRecognizer = PaddleTextRecognizer()

	private fun currentOcrTuning(): OcrTuning {
		return OcrTuning(
			detectionMaxSide = settings.readerTranslationOcrDetectionMaxSide.coerceIn(512, 2048),
			detectionThreshold = settings.readerTranslationOcrDetectionThreshold.coerceIn(0.01f, 0.99f),
			minBoxSize = settings.readerTranslationOcrMinBoxSize.coerceIn(1, 48),
			recognitionThreshold = settings.readerTranslationOcrRecognitionThreshold.coerceIn(0f, 0.99f),
			recognitionMaxWidth = settings.readerTranslationOcrRecognitionMaxWidth.coerceIn(128, 1024),
			recognitionBatchSize = settings.readerTranslationOcrRecognitionBatchSize.coerceIn(1, 16),
		)
	}

	override suspend fun recognize(request: OcrRequest): List<OcrTextBlock> {
		val modelPair = resolveActiveModelPair(request.sourceLang) ?: run {
			log { "paddle onnx model unavailable" }
			return emptyList()
		}
		val runtime = ensureRuntime(
			detectorModelId = modelPair.detector.id,
			recognizerModelId = modelPair.recognizer.id,
		) ?: return emptyList()
		val decodedBitmap = runInterruptible(Dispatchers.IO) {
			BitmapDecoderCompat.decode(request.sourceUri.toFile())
		}
		return try {
			val roi = request.roi
			if (roi != null) {
				recognizeSingleRegion(decodedBitmap, roi, runtime)?.let(::listOf).orEmpty()
			} else {
				recognizeRegions(
					bitmap = decodedBitmap,
					regions = detectTextRegions(decodedBitmap, runtime),
					runtime = runtime,
				)
			}
		} finally {
			decodedBitmap.recycle()
		}
	}

	override suspend fun detect(sourceUri: Uri): List<TextRegion> {
		val decodedBitmap = runInterruptible(Dispatchers.IO) {
			BitmapDecoderCompat.decode(sourceUri.toFile())
		}
		return try {
			detect(decodedBitmap)
		} finally {
			decodedBitmap.recycle()
		}
	}

	override suspend fun detect(bitmap: Bitmap): List<TextRegion> {
		val modelPair = resolveActiveModelPair(null) ?: run {
			log { "paddle onnx detector unavailable" }
			return emptyList()
		}
		val runtime = ensureRuntime(
			detectorModelId = modelPair.detector.id,
			recognizerModelId = modelPair.recognizer.id,
		) ?: return emptyList()
		return detectTextRegions(bitmap, runtime)
	}

	override suspend fun recognize(sourceUri: Uri, regions: List<TextRegion>): List<OcrTextBlock> {
		return recognize(sourceUri, regions, null)
	}

	suspend fun recognize(
		sourceUri: Uri,
		regions: List<TextRegion>,
		automaticLanguage: String?,
	): List<OcrTextBlock> {
		val decodedBitmap = runInterruptible(Dispatchers.IO) {
			BitmapDecoderCompat.decode(sourceUri.toFile())
		}
		return try {
			recognize(decodedBitmap, regions, automaticLanguage)
		} finally {
			decodedBitmap.recycle()
		}
	}

	override suspend fun recognize(bitmap: Bitmap, regions: List<TextRegion>): List<OcrTextBlock> {
		return recognize(bitmap, regions, null)
	}

	suspend fun recognize(
		bitmap: Bitmap,
		regions: List<TextRegion>,
		automaticLanguage: String?,
	): List<OcrTextBlock> {
		if (regions.isEmpty()) return emptyList()
		val modelPair = resolveActiveModelPair(automaticLanguage) ?: run {
			log { "paddle onnx recognizer unavailable" }
			return emptyList()
		}
		log { "metric.ocr.paddle.recognizer_model=${modelPair.recognizer.id}" }
		val runtime = ensureRuntime(
			detectorModelId = modelPair.detector.id,
			recognizerModelId = modelPair.recognizer.id,
		) ?: return emptyList()
		return recognizeRegions(bitmap, regions, runtime)
	}

	private data class ModelPair(
		val detector: org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel,
		val recognizer: org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel,
	)

	private fun resolveActiveModelPair(automaticLanguage: String?): ModelPair? {
		val detector = resolveDetectorModel() ?: return null
		val recognizer = resolveRecognizerModel(automaticLanguage) ?: return null
		return ModelPair(detector = detector, recognizer = recognizer)
	}

	private fun resolveDetectorModel(): org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel? {
		val preferredId = settings.readerTranslationPaddleDetModelId
		val detectors = OnnxOfficialModelCatalog.models.filter {
			it.category == OnnxModelCategory.OCR_DETECTOR
		}
		if (detectors.isEmpty()) return null
		// When DET is set to MLKIT, Paddle engine won't be used for detection —
		// but if called, fall back to the first ONNX detector anyway.
		if (preferredId == "MLKIT") {
			return detectors.first()
		}
		return detectors.firstOrNull { it.id == preferredId } ?: detectors.first()
	}

	/**
	 * Resolves the recognizer model. When set to "AUTO", selects the best model
	 * based on the current manga language context, aligning with manga-translator-ui:
	 * - Korean (ko) → PP-OCRv5 Korean
	 * - Thai (th) → PP-OCRv5 Thai
	 * - Latin-script languages → PP-OCRv5 Latin
	 * - Missing languages and other scripts → multilingual PP-OCRv6 Medium
	 */
	private fun resolveRecognizerModel(
		automaticLanguage: String?,
	): org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel? {
		val preferredId = normalizeRecognizerModelId(settings.readerTranslationPaddleOfficialModelId)
		// Only include Paddle-compatible recognizers (those with dict files).
		// MangaOCR is an encoder-decoder model with its own pipeline — not compatible here.
		val allRecognizers = OnnxOfficialModelCatalog.models.filter {
			it.category == OnnxModelCategory.OCR_RECOGNIZER && !it.id.startsWith("mangaocr")
		}
		if (allRecognizers.isEmpty()) return null

		if (preferredId.isNotBlank() && preferredId != "AUTO") {
			return allRecognizers.firstOrNull { it.id == preferredId }
				?: allRecognizers.first()
		}

		val sourceLang = automaticLanguage.normalizeReaderTranslationLanguageTag()
		val automaticModelId = resolveAutomaticPaddleRecognizerModelId(sourceLang)
		val autoResolved = allRecognizers.firstOrNull { it.id == automaticModelId }
		log { "metric.ocr.paddle.auto_language=${sourceLang ?: "none"} auto_model=$automaticModelId" }
		if (autoResolved != null) return autoResolved

		// Fallback: best available downloaded model
		val downloaded = allRecognizers.filter { onnxModelManager.isModelDownloaded(it.id) }
		return resolvePreferredRecognizer(downloaded, PPOCRV6_RECOGNIZER_PRIORITY)
			?: downloaded.firstOrNull { it.id == "ppocrv5_server_rec_onnx" }
			?: downloaded.firstOrNull { it.id == "ppocrv5_mobile_rec_onnx" }
			?: downloaded.firstOrNull()
			?: resolvePreferredRecognizer(allRecognizers, PPOCRV6_RECOGNIZER_PRIORITY)
			?: allRecognizers.first()
	}

	private fun resolvePreferredDownloadedRecognizer(
		models: List<org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel>,
		priority: List<String>,
	): org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel? {
		return resolvePreferredRecognizer(
			models = models.filter { onnxModelManager.isModelDownloaded(it.id) },
			priority = priority,
		)
	}

	private fun resolvePreferredRecognizer(
		models: List<org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel>,
		priority: List<String>,
	): org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel? {
		for (modelId in priority) {
			models.firstOrNull { it.id == modelId }?.let { return it }
		}
		return null
	}

	private suspend fun ensureRuntime(
		detectorModelId: String,
		recognizerModelId: String,
	): Runtime? {
		val current = runtime
		if (current != null &&
			current.detectorModelId == detectorModelId &&
			current.recognizerModelId == recognizerModelId
		) {
			return current
		}
		return runtimeLock.withLock {
			val again = runtime
			if (again != null &&
				again.detectorModelId == detectorModelId &&
				again.recognizerModelId == recognizerModelId
			) {
				return@withLock again
			}
			runtime?.close()
			runtime = null
			val detectorModel = OnnxOfficialModelCatalog.findById(detectorModelId)
				?.takeIf { it.category == OnnxModelCategory.OCR_DETECTOR }
				?: return@withLock null
			val recognizerModel = OnnxOfficialModelCatalog.findById(recognizerModelId)
				?.takeIf { it.category == OnnxModelCategory.OCR_RECOGNIZER }
				?: return@withLock null
			val detectorModelDir = File(onnxModelManager.ensureModelReady(detectorModel))
			val recognizerModelDir = File(onnxModelManager.ensureModelReady(recognizerModel))
			val detFile = findOnnxFile(detectorModelDir, detectorModel)
				?: error("Missing OCR det model in: ${detectorModelDir.absolutePath}")
			val recFile = findOnnxFile(recognizerModelDir, recognizerModel)
				?: error("Missing OCR rec model in: ${recognizerModelDir.absolutePath}")
			val recognizerKind = if (recognizerModelId == MANGA_48PX_CTC_RECOGNIZER_MODEL_ID) {
				RecognizerKind.MANGA_48PX_CTC
			} else {
				RecognizerKind.PADDLE
			}
			val recDict = buildRecDictionary(recognizerModelDir, recognizerKind)
			val env = OrtEnvironment.getEnvironment()
			val detectorOptions = OrtSession.SessionOptions().apply {
				setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
				setIntraOpNumThreads(2)
			}
			val recognizerOptions = OrtSession.SessionOptions().apply {
				setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
				setIntraOpNumThreads(2)
			}
			val created = Runtime(
				detectorModelId = detectorModelId,
				recognizerModelId = recognizerModelId,
				detectorNormalizeMode = resolveDetectorNormalizeMode(detectorModel),
				detSession = env.createSession(detFile.absolutePath, detectorOptions),
				recSession = env.createSession(recFile.absolutePath, recognizerOptions),
				recDict = recDict,
				recognizerKind = recognizerKind,
			)
			runtime = created
			created
		}
	}

	/**
	 * Find the .onnx file inside the model directory.
	 * Tries the explicit file names from the catalog first, then falls back to the first .onnx file found.
	 */
	private fun findOnnxFile(dir: File, model: org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel): File? {
		for (mf in model.files) {
			if (mf.fileName.endsWith(".onnx", ignoreCase = true)) {
				val f = File(dir, mf.fileName)
				if (f.isFile) return f
			}
		}
		return dir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("onnx", ignoreCase = true) }
	}

	private fun resolveDetectorNormalizeMode(
		model: org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel,
	): DetectorNormalizeMode {
		return if (model.id.startsWith("ppocrv6_")) {
			DetectorNormalizeMode.IMAGENET
		} else {
			DetectorNormalizeMode.UNIT
		}
	}

	private fun findDictTextFile(dir: File): File? {
		val candidates = listOf(
			"alphabet-all-v5.txt",
			"ppocrv5_dict.txt",
			"ppocrv5_latin_dict.txt",
			"ppocrv5_korean_dict.txt",
			"ppocrv5_thai_dict.txt",
			"en_dict.txt",
			"korean_dict.txt",
		)
		for (name in candidates) {
			val f = File(dir, name)
			if (f.isFile) return f
		}
		return dir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("txt", ignoreCase = true) }
	}

	private fun buildRecDictionary(dir: File, recognizerKind: RecognizerKind): List<String> {
		val dictFile = findDictTextFile(dir)
		if (dictFile != null) {
			return buildRecDictionaryFromTextFile(dictFile, recognizerKind)
		}
		val yamlFile = File(dir, "inference.yml").takeIf { it.isFile }
			?: File(dir, "inference.yaml").takeIf { it.isFile }
			?: error("Missing OCR dict or inference.yml in: ${dir.absolutePath}")
		return buildRecDictionaryFromYamlFile(yamlFile)
	}

	private fun buildRecDictionaryFromTextFile(
		dictFile: File,
		recognizerKind: RecognizerKind,
	): List<String> {
		val entries = dictFile.readLines()
			.map { it.trimEnd('\r') }
			.filter { it.isNotEmpty() }
		return if (recognizerKind == RecognizerKind.MANGA_48PX_CTC) entries else entries + " "
	}

	private fun buildRecDictionaryFromYamlFile(yamlFile: File): List<String> {
		val entries = mutableListOf<String>()
		var collecting = false
		yamlFile.forEachLine { line ->
			val trimmed = line.trim()
			if (!collecting) {
				if (trimmed == "character_dict:") {
					collecting = true
				}
				return@forEachLine
			}
			val item = line.trimStart()
			if (!item.startsWith("- ")) {
				if (trimmed.isNotEmpty()) {
					collecting = false
				}
				return@forEachLine
			}
			entries += parseYamlScalar(item.removePrefix("- ").trim())
		}
		check(entries.isNotEmpty()) {
			"Missing character_dict in: ${yamlFile.absolutePath}"
		}
		return entries + " "
	}

	private fun parseYamlScalar(raw: String): String {
		if (raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'') {
			return raw.substring(1, raw.lastIndex).replace("''", "'")
		}
		if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
			return raw.substring(1, raw.lastIndex)
				.replace("\\\"", "\"")
				.replace("\\\\", "\\")
		}
		return raw
	}

	private fun normalizeRecognizerModelId(modelId: String): String {
		return when (modelId) {
			"ppocrv5_mobile_onnx" -> "ppocrv5_mobile_rec_onnx"
			"ppocrv5_server_onnx" -> "ppocrv5_server_rec_onnx"
			"en_ppocrv5_mobile_rec_onnx" -> LATIN_RECOGNIZER_MODEL_ID
			"korean_ppocrv3_mobile_rec_onnx" -> KOREAN_RECOGNIZER_MODEL_ID
			else -> modelId
		}
	}

	private fun detectTextRegions(bitmap: Bitmap, runtime: Runtime): List<TextRegion> {
		return textDetector.detectTextRegions(bitmap, runtime, currentOcrTuning()).map { rect ->
			TextRegion(
				rect = rect,
				confidence = 1f,
				detectorId = PADDLE_DETECTOR_ID,
				directionHint = inferTextDirectionHint(rect),
				angleHintDegrees = inferTextAngleHintDegrees(rect),
				isAxisAligned = true,
				quadPoints = rectToTextQuad(rect),
			)
		}
	}

	private fun recognizeRegions(
		bitmap: Bitmap,
		regions: List<TextRegion>,
		runtime: Runtime,
	): List<OcrTextBlock> {
		if (regions.isEmpty()) return emptyList()
		return textRecognizer.recognizeRegions(bitmap, regions, runtime, currentOcrTuning())
	}

	private fun recognizeSingleRegion(bitmap: Bitmap, region: Rect, runtime: Runtime): OcrTextBlock? {
		return recognizeSingleRegion(
			bitmap = bitmap,
			region = TextRegion(
				rect = region,
				directionHint = inferTextDirectionHint(region),
				angleHintDegrees = inferTextAngleHintDegrees(region),
				isAxisAligned = true,
				quadPoints = rectToTextQuad(region),
			),
			runtime = runtime,
		)
	}

	private fun recognizeSingleRegion(bitmap: Bitmap, region: TextRegion, runtime: Runtime): OcrTextBlock? {
		val crop = cropRegionBitmap(bitmap, region)
		val normalized = textRecognizer.normalizeRecognitionOrientation(crop, runtime.recognizerKind)
		return try {
			val tuning = currentOcrTuning()
			val (text, confidence) = textRecognizer.recognizeCrop(normalized, runtime, tuning)
			if (text.isBlank()) {
				null
			} else {
				OcrTextBlock(
					text = text,
					boundingBox = region.rect,
					confidence = confidence,
					directionHint = region.directionHint,
					angleHintDegrees = region.angleHintDegrees,
					isAxisAligned = region.isAxisAligned,
					quadPoints = region.quadPoints,
				)
			}
		} finally {
			normalized.recycle()
			if (normalized !== crop) {
				crop.recycle()
			}
		}
	}

	private fun createImageTensor(
		bitmap: Bitmap,
		height: Int,
		width: Int,
		normalizeToSigned: Boolean,
		normalizeMode: DetectorNormalizeMode? = null,
	): OnnxTensor {
		val readableBitmap = ensureReadableBitmap(bitmap)
		val pixels = IntArray(width * height)
		try {
			readableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
			val data = FloatArray(3 * width * height)
			val channelStride = width * height
			for (y in 0 until height) {
				for (x in 0 until width) {
					val pixel = pixels[y * width + x]
					val r = ((pixel shr 16) and 0xFF) / 255f
					val g = ((pixel shr 8) and 0xFF) / 255f
					val b = (pixel and 0xFF) / 255f
					val base = y * width + x
					if (normalizeMode == DetectorNormalizeMode.IMAGENET) {
						data[base] = (r - IMAGENET_MEAN_R) / IMAGENET_STD_R
						data[channelStride + base] = (g - IMAGENET_MEAN_G) / IMAGENET_STD_G
						data[channelStride * 2 + base] = (b - IMAGENET_MEAN_B) / IMAGENET_STD_B
					} else if (normalizeToSigned) {
						data[base] = (r - 0.5f) / 0.5f
						data[channelStride + base] = (g - 0.5f) / 0.5f
						data[channelStride * 2 + base] = (b - 0.5f) / 0.5f
					} else {
						data[base] = r
						data[channelStride + base] = g
						data[channelStride * 2 + base] = b
					}
				}
			}
			return OnnxTensor.createTensor(
				OrtEnvironment.getEnvironment(),
				FloatBuffer.wrap(data),
				longArrayOf(1, 3, height.toLong(), width.toLong()),
			)
		} finally {
			if (readableBitmap !== bitmap) {
				readableBitmap.recycle()
			}
		}
	}

	private fun createRecognitionBatchTensor(
		bitmaps: List<Bitmap>,
		widths: List<Int>,
		targetHeight: Int,
		canvasWidth: Int,
		recognizerKind: RecognizerKind,
	): OnnxTensor {
		val data = FloatArray(bitmaps.size * 3 * targetHeight * canvasWidth)
		val imageStride = 3 * targetHeight * canvasWidth
		val channelStride = targetHeight * canvasWidth
		bitmaps.forEachIndexed { imageIndex, bitmap ->
			val readableBitmap = ensureReadableBitmap(bitmap)
			val width = widths[imageIndex]
			val pixels = IntArray(width * targetHeight)
			try {
				readableBitmap.getPixels(pixels, 0, width, 0, 0, width, targetHeight)
				val imageOffset = imageIndex * imageStride
				for (y in 0 until targetHeight) {
					for (x in 0 until width) {
						val pixel = pixels[y * width + x]
						val r = ((pixel shr 16) and 0xFF) / 255f
						val g = ((pixel shr 8) and 0xFF) / 255f
						val b = (pixel and 0xFF) / 255f
						val base = imageOffset + y * canvasWidth + x
						data[base] = ((if (recognizerKind == RecognizerKind.PADDLE) b else r) - 0.5f) / 0.5f
						data[imageOffset + channelStride + y * canvasWidth + x] = (g - 0.5f) / 0.5f
						data[imageOffset + channelStride * 2 + y * canvasWidth + x] =
							((if (recognizerKind == RecognizerKind.PADDLE) r else b) - 0.5f) / 0.5f
					}
				}
			} finally {
				if (readableBitmap !== bitmap) {
					readableBitmap.recycle()
				}
			}
		}
		return OnnxTensor.createTensor(
			OrtEnvironment.getEnvironment(),
			FloatBuffer.wrap(data),
			longArrayOf(bitmaps.size.toLong(), 3, targetHeight.toLong(), canvasWidth.toLong()),
		)
	}

	private fun decodeRecognitionTensor(
		tensor: OnnxTensor,
		dictionary: List<String>,
		recognizerKind: RecognizerKind,
	): Pair<String, Float> {
		val shape = tensor.info.shape
		if (shape.size != 3) return "" to 0f
		val timeSteps = shape[1].toInt()
		val classes = shape[2].toInt()
		val values = FloatArray(timeSteps * classes)
		tensor.floatBuffer.get(values)
		return decodeRecognitionValues(
			values = values,
			offset = 0,
			timeSteps = timeSteps,
			classes = classes,
			dictionary = dictionary,
			recognizerKind = recognizerKind,
		)
	}

	private fun decodeRecognitionValues(
		values: FloatArray,
		offset: Int,
		timeSteps: Int,
		classes: Int,
		dictionary: List<String>,
		recognizerKind: RecognizerKind,
	): Pair<String, Float> {
		val result = when (recognizerKind) {
			RecognizerKind.PADDLE -> decodePaddleCtc(values, offset, timeSteps, classes, dictionary)
			RecognizerKind.MANGA_48PX_CTC -> decodeManga48pxCtc(values, offset, timeSteps, classes, dictionary)
		}
		return result.text to result.confidence
	}

	private fun cropBitmap(source: Bitmap, box: Rect): Bitmap {
		val left = box.left.coerceIn(0, source.width - 1)
		val top = box.top.coerceIn(0, source.height - 1)
		val right = box.right.coerceIn(left + 1, source.width)
		val bottom = box.bottom.coerceIn(top + 1, source.height)
		return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
	}

	private fun ensureReadableBitmap(bitmap: Bitmap): Bitmap {
		if (bitmap.config != Bitmap.Config.HARDWARE) {
			return bitmap
		}
		return bitmap.copy(Bitmap.Config.ARGB_8888, false)
	}

	private fun cropRegionBitmap(source: Bitmap, region: TextRegion): Bitmap {
		val quad = region.quadPoints
		val quadRect = textQuadToBoundingRect(quad)
		if (!region.isAxisAligned || !isAxisAlignedQuad(quad)) {
			warpRegionBitmap(source, quad)?.let { return it }
		}
		val cropRect = if (region.isAxisAligned && isAxisAlignedQuad(quad)) {
			Rect(
				max(region.rect.left, quadRect.left),
				max(region.rect.top, quadRect.top),
				min(region.rect.right, quadRect.right),
				min(region.rect.bottom, quadRect.bottom),
			)
		} else {
			quadRect
		}
		return cropBitmap(source, cropRect)
	}

	private fun warpRegionBitmap(source: Bitmap, quad: TextQuad): Bitmap? {
		val src = quadToFloatArray(quad)
		val targetWidth = estimateQuadWidth(quad).roundToInt().coerceAtLeast(1)
		val targetHeight = estimateQuadHeight(quad).roundToInt().coerceAtLeast(1)
		if (targetWidth <= 1 || targetHeight <= 1) return null
		val dst = floatArrayOf(
			0f, 0f,
			targetWidth.toFloat(), 0f,
			targetWidth.toFloat(), targetHeight.toFloat(),
			0f, targetHeight.toFloat(),
		)
		val matrix = Matrix()
		if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
			return null
		}
		return runCatching {
			Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { output ->
				val canvas = Canvas(output)
				canvas.drawBitmap(source, matrix, TRANSFORM_PAINT)
			}
		}.getOrNull()
	}

	private fun quadToFloatArray(quad: TextQuad): FloatArray {
		return floatArrayOf(
			quad.points[0].first, quad.points[0].second,
			quad.points[1].first, quad.points[1].second,
			quad.points[2].first, quad.points[2].second,
			quad.points[3].first, quad.points[3].second,
		)
	}

	private fun estimateQuadWidth(quad: TextQuad): Float {
		val top = distance(quad.points[0], quad.points[1])
		val bottom = distance(quad.points[3], quad.points[2])
		return ((top + bottom) * 0.5f).coerceAtLeast(1f)
	}

	private fun estimateQuadHeight(quad: TextQuad): Float {
		val left = distance(quad.points[0], quad.points[3])
		val right = distance(quad.points[1], quad.points[2])
		return ((left + right) * 0.5f).coerceAtLeast(1f)
	}

	private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
		val dx = a.first - b.first
		val dy = a.second - b.second
		return kotlin.math.sqrt(dx * dx + dy * dy)
	}

	private inline fun log(message: () -> String) {
		if (settings.isReaderTranslationDebugLogsEnabled) {
			Log.d(LOG_TAG, message())
		}
	}

	private companion object {
		const val LOG_TAG = "ReaderOcrPaddleOrt"
		const val PADDLE_DETECTOR_ID = "paddle_onnx_ppocrv5_det"
		const val DET_MIN_COMPONENT_PIXELS = 8
		const val REC_INPUT_HEIGHT = 48
		const val MANGA_48PX_CTC_EXTRA_PADDING = 128
		const val MULTILINGUAL_RECOGNIZER_MODEL_ID = "ppocrv6_medium_rec_onnx"
		const val LATIN_RECOGNIZER_MODEL_ID = "latin_ppocrv5_mobile_rec_onnx"
		const val KOREAN_RECOGNIZER_MODEL_ID = "korean_ppocrv5_mobile_rec_onnx"
		const val THAI_RECOGNIZER_MODEL_ID = "thai_ppocrv5_mobile_rec_onnx"
		const val IMAGENET_MEAN_R = 0.485f
		const val IMAGENET_MEAN_G = 0.456f
		const val IMAGENET_MEAN_B = 0.406f
		const val IMAGENET_STD_R = 0.229f
		const val IMAGENET_STD_G = 0.224f
		const val IMAGENET_STD_B = 0.225f
		val PPOCRV6_RECOGNIZER_PRIORITY = listOf(
			"ppocrv6_medium_rec_onnx",
			"ppocrv6_small_rec_onnx",
			"ppocrv6_tiny_rec_onnx",
		)
		val TRANSFORM_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
		val NEIGHBOR_OFFSETS = arrayOf(
			1 to 0,
			-1 to 0,
			0 to 1,
			0 to -1,
		)
	}

	private inner class PaddleTextDetector {

		fun detectTextRegions(bitmap: Bitmap, runtime: Runtime, tuning: OcrTuning): List<Rect> {
			val resize = computeDetectionResize(bitmap.width, bitmap.height, tuning)
			val scaled = Bitmap.createScaledBitmap(bitmap, resize.width, resize.height, true)
			var inputTensor: OnnxTensor? = null
			var result: OrtSession.Result? = null
			return try {
				inputTensor = createImageTensor(
					bitmap = scaled,
					height = resize.height,
					width = resize.width,
					normalizeToSigned = false,
					normalizeMode = runtime.detectorNormalizeMode,
				)
				val inputName = runtime.detSession.inputNames.first()
				result = runtime.detSession.run(mapOf(inputName to inputTensor))
				val outputName = runtime.detSession.outputNames.first()
				val tensor = result.get(outputName).orElse(null) as? OnnxTensor ?: return emptyList()
				decodeDetectionMap(
					tensor = tensor,
					sourceWidth = bitmap.width,
					sourceHeight = bitmap.height,
					scaleX = resize.scaleX,
					scaleY = resize.scaleY,
					tuning = tuning,
				)
			} finally {
				runCatching { result?.close() }
				runCatching { inputTensor?.close() }
				scaled.recycle()
			}
		}

		private fun computeDetectionResize(width: Int, height: Int, tuning: OcrTuning): DetectionResize {
			val maxSide = max(width, height).coerceAtLeast(1)
			val scale = min(1f, tuning.detectionMaxSide.toFloat() / maxSide.toFloat())
			val scaledW = max(32, ((width * scale).roundToInt() / 32).coerceAtLeast(1) * 32)
			val scaledH = max(32, ((height * scale).roundToInt() / 32).coerceAtLeast(1) * 32)
			return DetectionResize(
				width = scaledW,
				height = scaledH,
				scaleX = scaledW.toFloat() / width.toFloat().coerceAtLeast(1f),
				scaleY = scaledH.toFloat() / height.toFloat().coerceAtLeast(1f),
			)
		}

		private fun decodeDetectionMap(
			tensor: OnnxTensor,
			sourceWidth: Int,
			sourceHeight: Int,
			scaleX: Float,
			scaleY: Float,
			tuning: OcrTuning,
		): List<Rect> {
			val shape = tensor.info.shape
			if (shape.size != 4) return emptyList()
			val height = shape[2].toInt()
			val width = shape[3].toInt()
			val values = FloatArray(height * width)
			tensor.floatBuffer.get(values)
			val visited = BooleanArray(values.size)
			val regions = mutableListOf<Rect>()
			val queue = IntArray(values.size)
			for (y in 0 until height) {
				for (x in 0 until width) {
					val idx = y * width + x
					if (visited[idx] || values[idx] < tuning.detectionThreshold) continue
					var head = 0
					var tail = 0
					queue[tail++] = idx
					visited[idx] = true
					var minX = x
					var minY = y
					var maxX = x
					var maxY = y
					var count = 0
					while (head < tail) {
						val current = queue[head++]
						val cy = current / width
						val cx = current % width
						count++
						if (cx < minX) minX = cx
						if (cy < minY) minY = cy
						if (cx > maxX) maxX = cx
						if (cy > maxY) maxY = cy
						for (offset in NEIGHBOR_OFFSETS) {
							val nx = cx + offset.first
							val ny = cy + offset.second
							if (nx !in 0 until width || ny !in 0 until height) continue
							val nIdx = ny * width + nx
							if (visited[nIdx] || values[nIdx] < tuning.detectionThreshold) continue
							visited[nIdx] = true
							queue[tail++] = nIdx
						}
					}
					if (count < DET_MIN_COMPONENT_PIXELS) continue
					val rect = Rect(
						(minX / scaleX).roundToInt().coerceIn(0, sourceWidth - 1),
						(minY / scaleY).roundToInt().coerceIn(0, sourceHeight - 1),
						((maxX + 1) / scaleX).roundToInt().coerceIn(1, sourceWidth),
						((maxY + 1) / scaleY).roundToInt().coerceIn(1, sourceHeight),
					)
					if (rect.width() < tuning.minBoxSize || rect.height() < tuning.minBoxSize) continue
					regions += rect
				}
			}
			return regions.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
		}
	}

	private inner class PaddleTextRecognizer {

		fun normalizeRecognitionOrientation(bitmap: Bitmap, recognizerKind: RecognizerKind): Bitmap {
			val shouldRotate = when (recognizerKind) {
				RecognizerKind.PADDLE -> shouldRotatePaddleRecognitionCrop(bitmap.width, bitmap.height)
				RecognizerKind.MANGA_48PX_CTC -> shouldRotateManga48pxRecognitionCrop(bitmap.width, bitmap.height)
			}
			if (!shouldRotate) {
				return bitmap
			}
			val matrix = Matrix().apply { postRotate(-90f) }
			return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
		}

		fun recognizeRegions(
			bitmap: Bitmap,
			regions: List<TextRegion>,
			runtime: Runtime,
			tuning: OcrTuning,
		): List<OcrTextBlock> {
			val results = mutableListOf<OcrTextBlock>()
			regions.chunked(tuning.recognitionBatchSize).forEach { chunk ->
				results += runCatching {
					recognizeRegionBatch(bitmap, chunk, runtime, tuning)
				}.getOrElse { error ->
					if (chunk.size <= 1) {
						log { "paddle recognizer failed: ${error.message}" }
						emptyList()
					} else {
						log { "paddle recognizer batch failed, fallback to single region: ${error.message}" }
						chunk.mapNotNull { region ->
							recognizeSingleRegion(bitmap, region, runtime)
						}
					}
				}
			}
			return results
		}

		private fun recognizeRegionBatch(
			bitmap: Bitmap,
			regions: List<TextRegion>,
			runtime: Runtime,
			tuning: OcrTuning,
		): List<OcrTextBlock> {
			val crops = mutableListOf<Bitmap>()
			val scaled = mutableListOf<Bitmap>()
			val normalized = mutableListOf<Bitmap>()
			var inputTensor: OnnxTensor? = null
			var result: OrtSession.Result? = null
			return try {
				val targetHeight = REC_INPUT_HEIGHT
				val widths = mutableListOf<Int>()
				for (region in regions) {
					val crop = cropRegionBitmap(bitmap, region)
					crops += crop
					val normalizedCrop = normalizeRecognitionOrientation(crop, runtime.recognizerKind)
					normalized += normalizedCrop
					val targetWidth = computeRecognitionWidth(normalizedCrop, tuning)
					widths += targetWidth
					scaled += Bitmap.createScaledBitmap(normalizedCrop, targetWidth, targetHeight, true)
				}
				inputTensor = createRecognitionBatchTensor(
					bitmaps = scaled,
					widths = widths,
					targetHeight = targetHeight,
					canvasWidth = computeRecognitionCanvasWidth(widths, tuning, runtime.recognizerKind),
					recognizerKind = runtime.recognizerKind,
				)
				val inputName = runtime.recSession.inputNames.first()
				result = runtime.recSession.run(mapOf(inputName to inputTensor))
				val outputName = runtime.recSession.outputNames.first()
				val tensor = result.get(outputName).orElse(null) as? OnnxTensor ?: return emptyList()
				decodeRecognitionBatchTensor(tensor, runtime.recDict, regions, tuning, runtime.recognizerKind)
			} finally {
				runCatching { result?.close() }
				runCatching { inputTensor?.close() }
				scaled.forEach { it.recycle() }
				normalized.forEachIndexed { index, normalizedBitmap ->
					if (normalizedBitmap !== crops[index]) {
						normalizedBitmap.recycle()
					}
				}
				crops.forEach { it.recycle() }
			}
		}

		fun recognizeCrop(bitmap: Bitmap, runtime: Runtime, tuning: OcrTuning): Pair<String, Float> {
			val targetHeight = REC_INPUT_HEIGHT
			val targetWidth = computeRecognitionWidth(bitmap, tuning)
			val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
			var inputTensor: OnnxTensor? = null
			var result: OrtSession.Result? = null
			return try {
				inputTensor = createRecognitionBatchTensor(
					bitmaps = listOf(scaled),
					widths = listOf(targetWidth),
					targetHeight = targetHeight,
					canvasWidth = computeRecognitionCanvasWidth(listOf(targetWidth), tuning, runtime.recognizerKind),
					recognizerKind = runtime.recognizerKind,
				)
				val inputName = runtime.recSession.inputNames.first()
				result = runtime.recSession.run(mapOf(inputName to inputTensor))
				val outputName = runtime.recSession.outputNames.first()
				val tensor = result.get(outputName).orElse(null) as? OnnxTensor ?: return "" to 0f
				val (text, confidence) = decodeRecognitionTensor(tensor, runtime.recDict, runtime.recognizerKind)
				if (confidence < tuning.recognitionThreshold) "" to confidence else text to confidence
			} finally {
				runCatching { result?.close() }
				runCatching { inputTensor?.close() }
				scaled.recycle()
			}
		}

		private fun computeRecognitionWidth(bitmap: Bitmap, tuning: OcrTuning): Int {
			return computePaddleRecognitionWidth(bitmap.width, bitmap.height, tuning.recognitionMaxWidth)
		}

		private fun computeRecognitionCanvasWidth(
			widths: List<Int>,
			tuning: OcrTuning,
			recognizerKind: RecognizerKind,
		): Int {
			if (recognizerKind == RecognizerKind.PADDLE) return tuning.recognitionMaxWidth
			val paddedWidth = (widths.maxOrNull() ?: 1) + MANGA_48PX_CTC_EXTRA_PADDING
			return (paddedWidth + 3) / 4 * 4
		}

		private fun decodeRecognitionBatchTensor(
			tensor: OnnxTensor,
			dictionary: List<String>,
			regions: List<TextRegion>,
			tuning: OcrTuning,
			recognizerKind: RecognizerKind,
		): List<OcrTextBlock> {
			val shape = tensor.info.shape
			if (shape.size != 3) return emptyList()
			val batchSize = shape[0].toInt()
			val timeSteps = shape[1].toInt()
			val classes = shape[2].toInt()
			val values = FloatArray(batchSize * timeSteps * classes)
			tensor.floatBuffer.get(values)
			val blocks = mutableListOf<OcrTextBlock>()
			for (batchIndex in 0 until min(batchSize, regions.size)) {
				val (text, confidence) = decodeRecognitionValues(
					values = values,
					offset = batchIndex * timeSteps * classes,
					timeSteps = timeSteps,
					classes = classes,
					dictionary = dictionary,
					recognizerKind = recognizerKind,
				)
				if (text.isBlank() || confidence < tuning.recognitionThreshold) continue
				val region = regions[batchIndex]
				blocks += OcrTextBlock(
					text = text,
					boundingBox = region.rect,
					confidence = confidence,
					directionHint = region.directionHint,
					angleHintDegrees = region.angleHintDegrees,
					isAxisAligned = region.isAxisAligned,
					quadPoints = region.quadPoints,
				)
			}
			return blocks
		}
	}
}

internal fun resolveAutomaticPaddleRecognizerModelId(language: String?): String {
	val normalized = language.normalizeReaderTranslationLanguageTag()
	return when {
		normalized == "ko" -> "korean_ppocrv5_mobile_rec_onnx"
		normalized == "th" -> "thai_ppocrv5_mobile_rec_onnx"
		normalized in setOf(
			"af", "az", "bs", "ca", "cs", "cy", "da", "de", "en", "es", "et", "eu", "fi", "fr", "gl",
			"hr", "hu", "id", "is", "it", "la", "lt", "lv", "ms", "nl", "no", "pl", "pt", "ro", "sk",
			"sl", "sq", "sv", "sw", "tl", "tr", "vi",
		) -> "latin_ppocrv5_mobile_rec_onnx"
		else -> "ppocrv6_medium_rec_onnx"
	}
}
