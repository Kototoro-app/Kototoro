package org.skepsun.kototoro.reader.translate.domain

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.reader.translate.data.OnnxModelCategory
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class MangaOcrReaderTextRecognizer @Inject constructor(
	private val onnxModelManager: OnnxModelManager,
) : ReaderTextRecognizer {

	data class Diagnostics(
		val attemptedCount: Int,
		val recognizedCount: Int,
		val emptyCount: Int,
		val emptyRatio: Float,
		val cropSummary: String,
		val emptySamples: List<String>,
		val traceSamples: List<String>,
	)

	private data class Runtime(
		val modelId: String,
		val encoderSession: OrtSession,
		val decoderSession: OrtSession,
		val generationConfig: GenerationConfig,
		val imageConfig: ImageConfig,
		val tokenizer: SimpleTokenizer,
	) {
		fun close() {
			runCatching { encoderSession.close() }
			runCatching { decoderSession.close() }
		}
	}

	private data class GenerationConfig(
		val decoderStartTokenId: Int,
		val eosTokenId: Int,
		val maxLength: Int,
	)

	private data class ImageConfig(
		val width: Int,
		val height: Int,
		val rescaleFactor: Float,
		val mean: FloatArray,
		val std: FloatArray,
	)

	private class SimpleTokenizer(
		private val idToToken: Array<String>,
		private val specialTokens: Set<String>,
	) {
		fun decode(ids: List<Int>): String {
			val builder = StringBuilder()
			for (id in ids) {
				if (id !in idToToken.indices) continue
				val token = idToToken[id]
				if (token.isEmpty() || token in specialTokens) continue
				if (token.startsWith("##")) {
					builder.append(token.substring(2))
				} else {
					builder.append(token)
				}
			}
			return builder.toString()
		}
	}

	private val runtimeLock = Mutex()
	@Volatile
	private var runtime: Runtime? = null
	@Volatile
	private var lastDiagnostics: Diagnostics? = null
	@Volatile
	private var diagnosticsEmitter: (((() -> String)) -> Unit)? = null
	private val activeTraceCollector = ThreadLocal<MutableList<String>?>()

	private data class DecodeTrace(
		val ids: List<Int>,
		val decodedText: String,
	)

	override suspend fun recognize(sourceUri: Uri, regions: List<TextRegion>): List<OcrTextBlock> {
		if (regions.isEmpty()) return emptyList()
		val bitmap = runInterruptible(Dispatchers.IO) {
			BitmapDecoderCompat.decode(sourceUri.toFile())
		}
		return try {
			recognize(bitmap, regions)
		} finally {
			bitmap.recycle()
		}
	}

	override suspend fun recognize(bitmap: Bitmap, regions: List<TextRegion>): List<OcrTextBlock> {
		if (regions.isEmpty()) return emptyList()
		val currentRuntime = ensureRuntime() ?: return emptyList()
		var attemptedCount = 0
		var emptyCount = 0
		var minWidth = Int.MAX_VALUE
		var maxWidth = 0
		var minHeight = Int.MAX_VALUE
		var maxHeight = 0
		var totalWidth = 0L
		var totalHeight = 0L
		val emptySamples = ArrayList<String>(MAX_EMPTY_LOG_SAMPLES)
		val traceSamples = ArrayList<String>(MAX_TRACE_SAMPLES)
		activeTraceCollector.set(traceSamples)
		val cappedRegions = if (regions.size > MAX_REGIONS_PER_PAGE) {
			regions.sortedByDescending { it.rect.width() * it.rect.height() }.take(MAX_REGIONS_PER_PAGE)
		} else {
			regions
		}
		val result = cappedRegions.mapNotNull { region ->
			val rect = region.rect
			attemptedCount += 1
			minWidth = minOf(minWidth, rect.width())
			maxWidth = maxOf(maxWidth, rect.width())
			minHeight = minOf(minHeight, rect.height())
			maxHeight = maxOf(maxHeight, rect.height())
			totalWidth += rect.width().toLong()
			totalHeight += rect.height().toLong()
			recognizeSingle(bitmap, region, currentRuntime).also { block ->
				if (block == null) {
					emptyCount += 1
					if (emptySamples.size < MAX_EMPTY_LOG_SAMPLES) {
						emptySamples += "${rect.width()}x${rect.height()}@${rect.left},${rect.top}"
					}
				}
			}
		}
		activeTraceCollector.remove()
		if (attemptedCount > 0) {
			val emptyRatio = emptyCount.toFloat() / attemptedCount
			val cropSummary = buildString {
				append("width[min=")
				append(minWidth.coerceAtMost(maxWidth))
				append(",max=")
				append(maxWidth)
				append(",avg=")
				append((totalWidth / attemptedCount).toInt())
				append("] height[min=")
				append(minHeight.coerceAtMost(maxHeight))
				append(",max=")
				append(maxHeight)
				append(",avg=")
				append((totalHeight / attemptedCount).toInt())
				append("]")
			}
			lastDiagnostics = Diagnostics(
				attemptedCount = attemptedCount,
				recognizedCount = result.size,
				emptyCount = emptyCount,
				emptyRatio = emptyRatio,
				cropSummary = cropSummary,
				emptySamples = emptySamples.toList(),
				traceSamples = traceSamples.toList(),
			)
			emitDiagnostic {
				"metric.ocr.mangaocr.attempted=$attemptedCount recognized=${result.size} empty=$emptyCount empty_ratio=$emptyRatio"
			}
			emitDiagnostic {
				"metric.ocr.mangaocr.crops $cropSummary"
			}
			if (emptySamples.isNotEmpty()) {
				emitDiagnostic { "metric.ocr.mangaocr.empty_crop_samples=${emptySamples.joinToString(";")}" }
			}
			traceSamples.forEachIndexed { index, sample ->
				emitDiagnostic { "metric.ocr.mangaocr.trace[$index]=$sample" }
			}
		}
		return result
	}

	fun setDiagnosticsEmitter(emitter: (((() -> String)) -> Unit)?) {
		diagnosticsEmitter = emitter
	}

	fun consumeLastDiagnostics(): Diagnostics? {
		val snapshot = lastDiagnostics
		lastDiagnostics = null
		return snapshot
	}

	private suspend fun ensureRuntime(): Runtime? {
		val current = runtime
		if (current != null) return current
		return runtimeLock.withLock {
			runtime?.let { return@withLock it }
			val model = OnnxOfficialModelCatalog.findById(MANGA_OCR_MODEL_ID)
				?.takeIf { it.category == OnnxModelCategory.OCR_RECOGNIZER }
				?: return@withLock null
			val modelDir = File(onnxModelManager.ensureModelReady(model))
			val encoderFile = File(modelDir, "encoder_model.onnx")
			val decoderFile = File(modelDir, "decoder_model.onnx")
			val generationConfigFile = File(modelDir, "generation_config.json")
			val preprocessorConfigFile = File(modelDir, "preprocessor_config.json")
			val tokenizerFile = File(modelDir, "tokenizer.json")
			val specialTokensMapFile = File(modelDir, "special_tokens_map.json")
			check(encoderFile.isFile) { "Missing MangaOCR encoder: ${encoderFile.absolutePath}" }
			check(decoderFile.isFile) { "Missing MangaOCR decoder: ${decoderFile.absolutePath}" }
			check(generationConfigFile.isFile) { "Missing MangaOCR generation config: ${generationConfigFile.absolutePath}" }
			check(preprocessorConfigFile.isFile) { "Missing MangaOCR preprocessor config: ${preprocessorConfigFile.absolutePath}" }
			check(tokenizerFile.isFile) { "Missing MangaOCR tokenizer: ${tokenizerFile.absolutePath}" }
			check(specialTokensMapFile.isFile) { "Missing MangaOCR special tokens map: ${specialTokensMapFile.absolutePath}" }
			val env = OrtEnvironment.getEnvironment()
			val options = OrtSession.SessionOptions().apply {
				setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
				setIntraOpNumThreads(2)
			}
			val created = Runtime(
				modelId = model.id,
				encoderSession = env.createSession(encoderFile.absolutePath, options),
				decoderSession = env.createSession(decoderFile.absolutePath, options),
				generationConfig = loadGenerationConfig(generationConfigFile),
				imageConfig = loadImageConfig(preprocessorConfigFile),
				tokenizer = loadTokenizer(tokenizerFile, specialTokensMapFile),
			)
			runtime = created
			created
		}
	}

	private fun recognizeSingle(bitmap: Bitmap, region: TextRegion, runtime: Runtime): OcrTextBlock? {
		val rect = region.rect
		if (rect.width() < MIN_CROP_SIZE || rect.height() < MIN_CROP_SIZE) return null
		val crop = cropBitmap(bitmap, rect)
		return try {
			val trace = recognizeCrop(crop, runtime)
			val text = trace.decodedText.trim()
			if (text.isBlank() || isHallucinatedNoiseText(text)) {
				logDecodeTrace(rect, crop, trace, success = false)
				null
			} else {
				logDecodeTrace(rect, crop, trace, success = true)
				OcrTextBlock(
					text = text,
					boundingBox = rect,
					confidence = 1f,
					directionHint = region.directionHint,
					angleHintDegrees = region.angleHintDegrees,
					isAxisAligned = region.isAxisAligned,
					quadPoints = region.quadPoints,
					detectorId = region.detectorId,
				)
			}
		} finally {
			crop.recycle()
		}
	}

	/**
	 * Detects MangaOCR hallucinations on noise crops: pure dots/ellipsis,
	 * a single repeated character, or very short interjections from tiny regions.
	 */
	private fun isHallucinatedNoiseText(text: String): Boolean {
		// Pure dots/ellipsis: "......", "...", "・・・・・・"
		val stripped = text.replace(".", "").replace("…", "").replace("・", "")
			.replace("。", "").replace("、", "").replace("!", "").replace("！", "")
			.replace("?", "").replace("？", "").replace("ー", "").replace("～", "")
			.replace("ッ", "").replace("っ", "")
			.trim()
		if (stripped.isEmpty()) return true
		// Single unique character repeated: "ああああああ", "ハハハ"
		val uniqueChars = stripped.toSet()
		if (uniqueChars.size == 1 && stripped.length <= 8) return true
		// Very short text (1-2 meaningful chars) is likely noise from tiny crops
		if (stripped.length <= 1) return true
		return false
	}

	private fun recognizeCrop(bitmap: Bitmap, runtime: Runtime): DecodeTrace {
		val imageTensor = preprocess(bitmap, runtime.imageConfig)
		val encoderOutputs = runtime.encoderSession.run(mapOf(encoderInputName(runtime) to imageTensor))
		val encoderHidden = encoderOutputs[0] as? OnnxTensor ?: run {
			imageTensor.close()
			encoderOutputs.close()
			return DecodeTrace(emptyList(), "")
		}
		val encoderShape = (encoderHidden.info as? TensorInfo)?.shape ?: longArrayOf()
		val encoderSeqLen = encoderShape.getOrNull(1)?.toInt() ?: 0
		val encoderAttention = createEncoderAttentionMask(encoderSeqLen)
		val ids = ArrayList<Int>(runtime.generationConfig.maxLength)
		ids += runtime.generationConfig.decoderStartTokenId
		var step = 0
		while (step < runtime.generationConfig.maxLength) {
			val inputIds = ids.map(Int::toLong).toLongArray()
			val inputTensor = OnnxTensor.createTensor(
				OrtEnvironment.getEnvironment(),
				LongBuffer.wrap(inputIds),
				longArrayOf(1, inputIds.size.toLong()),
			)
			val inputs = linkedMapOf<String, OnnxTensor>()
			inputs[decoderInputName(runtime)] = inputTensor
			inputs[encoderHiddenName(runtime)] = encoderHidden
			val attentionName = encoderAttentionName(runtime)
			if (attentionName != null && encoderAttention != null) {
				inputs[attentionName] = encoderAttention
			}
			val outputs = runtime.decoderSession.run(inputs)
			val logits = extractLastLogits(outputs[0].value, inputIds.size)
			val nextId = logits?.let(::argmax) ?: runtime.generationConfig.eosTokenId
			outputs.close()
			inputTensor.close()
			ids += nextId
			if (nextId == runtime.generationConfig.eosTokenId) {
				break
			}
			step++
		}
		encoderAttention?.close()
		encoderHidden.close()
		encoderOutputs.close()
		imageTensor.close()
		return DecodeTrace(
			ids = ids,
			decodedText = runtime.tokenizer.decode(ids).trim(),
		)
	}

	private fun logDecodeTrace(rect: Rect, crop: Bitmap, trace: DecodeTrace, success: Boolean) {
		val tokenPreview = trace.ids.take(MAX_TOKEN_PREVIEW).joinToString(",")
		val textPreview = trace.decodedText.replace('\n', ' ').take(MAX_TEXT_PREVIEW)
		val message = "crop=${crop.width}x${crop.height} rect=$rect success=$success tokens=${trace.ids.size} ids=[$tokenPreview] text=$textPreview"
		emitDiagnostic { "metric.ocr.mangaocr.$message" }
		activeTraceCollector.get()?.let { traces ->
			if (traces.size < MAX_TRACE_SAMPLES) {
				traces += message
			}
		}
	}

	private fun emitDiagnostic(message: () -> String) {
		val emitter = diagnosticsEmitter
		if (emitter != null) {
			emitter.invoke(message)
		} else {
			Log.d(TAG, message())
		}
	}

	private fun preprocess(bitmap: Bitmap, imageConfig: ImageConfig): OnnxTensor {
		val source = bitmap.toSoftwareBitmapIfNeeded()
		val resized = source.scale(imageConfig.width, imageConfig.height)
		try {
			val input = FloatArray(3 * imageConfig.width * imageConfig.height)
			var index = 0
			for (y in 0 until imageConfig.height) {
				for (x in 0 until imageConfig.width) {
					val pixel = resized[x, y]
					val r = ((pixel shr 16) and 0xFF) * imageConfig.rescaleFactor
					val g = ((pixel shr 8) and 0xFF) * imageConfig.rescaleFactor
					val b = (pixel and 0xFF) * imageConfig.rescaleFactor
					input[index] = ((r - imageConfig.mean[0]) / imageConfig.std[0]).toFloat()
					input[index + imageConfig.width * imageConfig.height] = ((g - imageConfig.mean[1]) / imageConfig.std[1]).toFloat()
					input[index + 2 * imageConfig.width * imageConfig.height] = ((b - imageConfig.mean[2]) / imageConfig.std[2]).toFloat()
					index++
				}
			}
			return OnnxTensor.createTensor(
				OrtEnvironment.getEnvironment(),
				FloatBuffer.wrap(input),
				longArrayOf(1, 3, imageConfig.height.toLong(), imageConfig.width.toLong()),
			)
		} finally {
			resized.recycle()
			if (source !== bitmap) {
				source.recycle()
			}
		}
	}

	private fun cropBitmap(source: Bitmap, box: Rect): Bitmap {
		val left = box.left.coerceIn(0, source.width - 1)
		val top = box.top.coerceIn(0, source.height - 1)
		val right = box.right.coerceIn(left + 1, source.width)
		val bottom = box.bottom.coerceIn(top + 1, source.height)
		return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
	}

	private fun loadGenerationConfig(file: File): GenerationConfig {
		val json = JSONObject(file.readText())
		return GenerationConfig(
			decoderStartTokenId = json.getInt("decoder_start_token_id"),
			eosTokenId = json.getInt("eos_token_id"),
			maxLength = json.getInt("max_length"),
		)
	}

	private fun loadImageConfig(file: File): ImageConfig {
		val json = JSONObject(file.readText())
		val size = json.getJSONObject("size")
		val mean = json.getJSONArray("image_mean")
		val std = json.getJSONArray("image_std")
		return ImageConfig(
			width = size.getInt("width"),
			height = size.getInt("height"),
			rescaleFactor = json.getDouble("rescale_factor").toFloat(),
			mean = floatArrayOf(
				mean.getDouble(0).toFloat(),
				mean.getDouble(1).toFloat(),
				mean.getDouble(2).toFloat(),
			),
			std = floatArrayOf(
				std.getDouble(0).toFloat(),
				std.getDouble(1).toFloat(),
				std.getDouble(2).toFloat(),
			),
		)
	}

	private fun loadTokenizer(tokenizerFile: File, specialTokensMapFile: File): SimpleTokenizer {
		val tokenizerJson = JSONObject(tokenizerFile.readText())
		val modelJson = tokenizerJson.getJSONObject("model")
		val vocabJson = modelJson.getJSONObject("vocab")
		val entries = ArrayList<Pair<String, Int>>(vocabJson.length())
		val keys = vocabJson.keys()
		var maxId = 0
		while (keys.hasNext()) {
			val token = keys.next()
			val id = vocabJson.getInt(token)
			maxId = max(maxId, id)
			entries += token to id
		}
		val idToToken = Array(maxId + 1) { "" }
		for ((token, id) in entries) {
			if (id in idToToken.indices) {
				idToToken[id] = token
			}
		}
		val specialJson = JSONObject(specialTokensMapFile.readText())
		val specialTokens = HashSet<String>()
		val specialKeys = specialJson.keys()
		while (specialKeys.hasNext()) {
			val key = specialKeys.next()
			val tokenObj = specialJson.optJSONObject(key) ?: continue
			specialTokens += tokenObj.optString("content")
		}
		return SimpleTokenizer(idToToken, specialTokens)
	}

	private fun encoderInputName(runtime: Runtime): String {
		return runtime.encoderSession.inputInfo.keys.first()
	}

	private fun decoderInputName(runtime: Runtime): String {
		return runtime.decoderSession.inputInfo.keys.first { it.contains("input_ids") }
	}

	private fun encoderHiddenName(runtime: Runtime): String {
		return runtime.decoderSession.inputInfo.keys.first { it.contains("encoder_hidden") }
	}

	private fun encoderAttentionName(runtime: Runtime): String? {
		return runtime.decoderSession.inputInfo.keys.firstOrNull { it.contains("encoder_attention") }
	}

	private fun createEncoderAttentionMask(seqLen: Int): OnnxTensor? {
		if (seqLen <= 0) return null
		val mask = LongArray(seqLen) { 1L }
		return OnnxTensor.createTensor(
			OrtEnvironment.getEnvironment(),
			LongBuffer.wrap(mask),
			longArrayOf(1, seqLen.toLong()),
		)
	}

	private fun extractLastLogits(raw: Any, seqLen: Int): FloatArray? {
		val batch = raw as? Array<*> ?: return null
		val tokens = batch.firstOrNull() as? Array<*> ?: return null
		val last = tokens.getOrNull(seqLen - 1) as? FloatArray ?: return null
		return last
	}

	private fun argmax(values: FloatArray): Int {
		var bestIndex = 0
		var bestValue = Float.NEGATIVE_INFINITY
		for (i in values.indices) {
			if (values[i] > bestValue) {
				bestValue = values[i]
				bestIndex = i
			}
		}
		return bestIndex
	}

	private fun Bitmap.toSoftwareBitmapIfNeeded(): Bitmap {
		return if (config == Bitmap.Config.HARDWARE) {
			copy(Bitmap.Config.ARGB_8888, false)
		} else {
			this
		}
	}

	private companion object {
		const val MANGA_OCR_MODEL_ID = MANGA_OCR_RECOGNIZER_MODEL_ID
		const val TAG = "ReaderTranslate"
		const val MAX_EMPTY_LOG_SAMPLES = 6
		const val MAX_TRACE_SAMPLES = 6
		const val MAX_TOKEN_PREVIEW = 16
		const val MAX_TEXT_PREVIEW = 80
		const val MIN_CROP_SIZE = 16
		const val MAX_REGIONS_PER_PAGE = 150
	}
}
