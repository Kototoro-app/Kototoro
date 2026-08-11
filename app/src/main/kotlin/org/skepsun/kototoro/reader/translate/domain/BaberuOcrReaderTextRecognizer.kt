package org.skepsun.kototoro.reader.translate.domain

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BaberuOcrReaderTextRecognizer @Inject constructor(
	private val modelManager: OnnxModelManager,
) : ReaderTextRecognizer {

	private data class Runtime(
		val vision: OrtSession,
		val prefill: OrtSession,
		val step: OrtSession,
		val vocab: Vocab,
	) {
		fun close() {
			runCatching { vision.close() }
			runCatching { prefill.close() }
			runCatching { step.close() }
		}
	}

	private data class CacheState(
		val result: OrtSession.Result,
		val tensors: List<OnnxTensor>,
	)

	private class Vocab(chars: List<String>) {
		private val idToChar = Array(chars.size + 4) { "" }.also { array ->
			chars.forEachIndexed { index, value -> array[index + 4] = value }
		}
		private val contentIds = chars.mapIndexedNotNullTo(HashSet()) { index, value ->
			val isSingleCodePoint = value.codePointCount(0, value.length) == 1
			val type = value.takeIf { isSingleCodePoint }?.codePointAt(0)?.let(Character::getType)
			val isContent = type in LETTER_TYPES || type in NUMBER_TYPES
			if (isSingleCodePoint && value !in NON_CONTENT_CHARS && isContent) index + 4 else null
		}

		fun decode(ids: List<Int>): String = buildString {
			ids.forEach { id -> if (id in idToChar.indices) append(idToChar[id]) }
		}

		fun isContent(id: Int): Boolean = id in contentIds

		private companion object {
			val NON_CONTENT_CHARS = setOf("ー", "ｰ", "〜", "~")
			val LETTER_TYPES = setOf(
				Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
				Character.TITLECASE_LETTER.toInt(), Character.MODIFIER_LETTER.toInt(),
				Character.OTHER_LETTER.toInt(),
			)
			val NUMBER_TYPES = setOf(
				Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(),
				Character.OTHER_NUMBER.toInt(),
			)
		}
	}

	private val runtimeLock = Mutex()
	private val inferenceLock = Mutex()
	@Volatile private var runtime: Runtime? = null

	override suspend fun recognize(sourceUri: Uri, regions: List<TextRegion>): List<OcrTextBlock> {
		if (regions.isEmpty()) return emptyList()
		val bitmap = withContext(Dispatchers.IO) { BitmapDecoderCompat.decode(sourceUri.toFile()) }
		return try {
			recognize(bitmap, regions)
		} finally {
			bitmap.recycle()
		}
	}

	override suspend fun recognize(bitmap: Bitmap, regions: List<TextRegion>): List<OcrTextBlock> {
		if (regions.isEmpty()) return emptyList()
		return inferenceLock.withLock {
			val current = ensureRuntime() ?: return@withLock emptyList()
			withContext(Dispatchers.Default) {
				regions.mapNotNull { region ->
					if (region.rect.width() < MIN_CROP_SIZE || region.rect.height() < MIN_CROP_SIZE) return@mapNotNull null
					val crop = cropBitmap(bitmap, region.rect)
					try {
						val text = decode(crop, current).trim()
						if (text.isBlank()) null else OcrTextBlock(
							text = text,
							boundingBox = region.rect,
							confidence = region.confidence,
							directionHint = region.directionHint,
							angleHintDegrees = region.angleHintDegrees,
							isAxisAligned = region.isAxisAligned,
							quadPoints = region.quadPoints,
							detectorId = region.detectorId,
						)
					} finally {
						crop.recycle()
					}
				}
			}
		}
	}

	private suspend fun ensureRuntime(): Runtime? {
		runtime?.let { return it }
		return runtimeLock.withLock {
			runtime?.let { return@withLock it }
			val model = OnnxOfficialModelCatalog.findById(MODEL_ID) ?: return@withLock null
			val dir = File(modelManager.ensureModelReady(model))
			val visionFile = File(dir, "onnx/vision_int4.onnx")
			val prefillFile = File(dir, "onnx/decoder_prefill_int8.onnx")
			val stepFile = File(dir, "onnx/decoder_step_int8.onnx")
			val vocabFile = File(dir, "tokenizer/vocab.json")
			check(visionFile.isFile && prefillFile.isFile && stepFile.isFile && vocabFile.isFile) {
				"Incomplete Baberu model in ${dir.absolutePath}"
			}
			val options = OrtSession.SessionOptions().apply {
				setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
				setIntraOpNumThreads(2)
			}
			Runtime(
				vision = OrtEnvironment.getEnvironment().createSession(visionFile.absolutePath, options),
				prefill = OrtEnvironment.getEnvironment().createSession(prefillFile.absolutePath, options),
				step = OrtEnvironment.getEnvironment().createSession(stepFile.absolutePath, options),
				vocab = Vocab(loadVocab(vocabFile)),
			).also { runtime = it }
		}
	}

	private fun decode(bitmap: Bitmap, runtime: Runtime): String {
		val pixels = preprocess(bitmap)
		val visionInput = OnnxTensor.createTensor(
			OrtEnvironment.getEnvironment(),
			FloatBuffer.wrap(pixels),
			longArrayOf(1, 3, 224, 224),
		)
		val visionResult = try {
			runtime.vision.run(mapOf(runtime.vision.inputInfo.keys.first() to visionInput))
		} finally {
			visionInput.close()
		}
		val visionEmbeds = visionResult[0] as OnnxTensor
		try {
			val bos = longArrayOf(BOS_ID.toLong())
			val prefillIds = tensor(bos, longArrayOf(1, 1))
			val prefillInputs = mapOf(
				runtime.prefill.inputInfo.keys.first { it.contains("vision_embeds") } to visionEmbeds,
				runtime.prefill.inputInfo.keys.first { it.contains("input_ids") } to prefillIds,
			)
			var cache = try {
				readCache(runtime.prefill.run(prefillInputs))
			} finally {
				prefillIds.close()
			}
			try {
				var logits = extractLogits(cache.result[0].value)
				val sequence = arrayListOf(BOS_ID)
				val generated = ArrayList<Int>()
				var position = VISION_TOKENS + 1
				while (generated.size < MAX_NEW_TOKENS) {
					applyRepetitionPenalty(logits, sequence)
					if (generated.lastOrNull()?.let(runtime.vocab::isContent) == true &&
						trailingRun(generated) >= MAX_CONTENT_RUN
					) {
						logits[generated.last()] = Float.NEGATIVE_INFINITY
					}
					val next = argmax(logits)
					if (next == EOS_ID) break
					generated += next
					sequence += next
					if (generated.size == MAX_NEW_TOKENS) break
					val input = tensor(longArrayOf(next.toLong()), longArrayOf(1, 1))
					val pos = tensor(longArrayOf(position++.toLong()), longArrayOf(1, 1))
					val inputs = linkedMapOf<String, OnnxTensor>(
						runtime.step.inputInfo.keys.first { it.contains("input_ids") } to input,
						runtime.step.inputInfo.keys.first { it.contains("position_ids") } to pos,
					)
					cache.tensors.forEachIndexed { index, value -> inputs[PAST_NAMES[index]] = value }
					val nextCache = try {
						readCache(runtime.step.run(inputs))
					} finally {
						input.close()
						pos.close()
					}
					cache.result.close()
					cache = nextCache
					logits = extractLogits(cache.result[0].value)
				}
				return runtime.vocab.decode(generated)
			} finally {
				cache.result.close()
			}
		} finally {
			visionResult.close()
		}
	}

	private fun readCache(result: OrtSession.Result): CacheState = CacheState(
		result,
		(1 until result.size()).map { result[it] as OnnxTensor },
	)

	private fun extractLogits(value: Any): FloatArray {
		@Suppress("UNCHECKED_CAST")
		val batch = value as Array<Array<FloatArray>>
		return batch[0].last()
	}

	private fun applyRepetitionPenalty(logits: FloatArray, sequence: List<Int>) {
		sequence.toSet().forEach { id ->
			if (id !in logits.indices) return@forEach
			logits[id] = if (logits[id] < 0f) logits[id] * REPETITION_PENALTY else logits[id] / REPETITION_PENALTY
		}
	}

	private fun trailingRun(sequence: List<Int>): Int {
		val last = sequence.lastOrNull() ?: return 0
		return sequence.asReversed().takeWhile { it == last }.size
	}

	private fun argmax(values: FloatArray): Int = values.indices.maxByOrNull { values[it] } ?: EOS_ID

	private fun preprocess(bitmap: Bitmap): FloatArray {
		val scaled = bitmap.scale(224, 224)
		val plane = 224 * 224
		val output = FloatArray(plane * 3)
		var offset = 0
		for (y in 0 until 224) for (x in 0 until 224) {
			val pixel = scaled[x, y]
			val channels = intArrayOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
			for (channel in 0..2) output[channel * plane + offset] = ((channels[channel] / 255f) - MEAN[channel]) / STD[channel]
			offset++
		}
		scaled.recycle()
		return output
	}

	private fun cropBitmap(source: Bitmap, rect: android.graphics.Rect): Bitmap {
		val left = rect.left.coerceIn(0, source.width - 1)
		val top = rect.top.coerceIn(0, source.height - 1)
		val right = rect.right.coerceIn(left + 1, source.width)
		val bottom = rect.bottom.coerceIn(top + 1, source.height)
		return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
	}

	private fun tensor(values: LongArray, shape: LongArray): OnnxTensor = OnnxTensor.createTensor(
		OrtEnvironment.getEnvironment(), LongBuffer.wrap(values), shape,
	)

	private fun loadVocab(file: File): List<String> {
		val json = JSONArray(file.readText())
		return List(json.length()) { index -> json.getString(index) }
	}

	companion object {
		const val MODEL_ID = "baberu_ocr_int4"
		const val BOS_ID = 1
		const val EOS_ID = 2
		const val VISION_TOKENS = 256
		const val MAX_NEW_TOKENS = 128
		const val MAX_CONTENT_RUN = 12
		const val REPETITION_PENALTY = 1.2f
		const val MIN_CROP_SIZE = 16
		val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
		val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
		val PAST_NAMES = (0 until 6).map { "past_k$it" } + (0 until 6).map { "past_v$it" }
	}
}
