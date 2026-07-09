package org.skepsun.kototoro.reader.translate.domain

import android.util.Log
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.sentencepiece.SpTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnnxReaderTranslationEngine @Inject constructor(
	private val onnxModelManager: OnnxModelManager,
) {

	private sealed interface RuntimeHolder {
		val modelId: String
		fun close()
	}

	private data class GenericRuntime(
		override val modelId: String,
		val backend: String,
		val tokenizer: HuggingFaceTokenizer,
		val session: OrtSession,
		val inputNames: Set<String>,
		val logitsOutputName: String,
		val promptTemplate: PromptTemplateInfo,
	) : RuntimeHolder {
		override fun close() {
			runCatching { session.close() }
			runCatching { tokenizer.close() }
		}
	}

	private data class NllbRuntime(
		override val modelId: String,
		val backend: String,
		val modelFamily: ModelFamily,
		val tokenizer: SpTokenizer,
		val encoderSession: OrtSession,
		val decoderSession: OrtSession,
		val cacheInitSession: OrtSession,
		val embedLmSession: OrtSession,
		val decoderInputNames: Set<String>,
		val decoderOutputNames: Set<String>,
		val layers: Int,
		val hiddenSize: Int,
	) : RuntimeHolder {
		override fun close() {
			runCatching { encoderSession.close() }
			runCatching { decoderSession.close() }
			runCatching { cacheInitSession.close() }
			runCatching { embedLmSession.close() }
			runCatching { tokenizer.close() }
		}
	}

	private data class Qwen35Runtime(
		override val modelId: String,
		val backend: String,
		val tokenizer: HuggingFaceTokenizer,
		val embedSession: OrtSession,
		val decoderSession: OrtSession,
		val decoderInputNames: Set<String>,
		val decoderInputInfo: Map<String, TensorInfo>,
		val disableThinkingInputName: String?,
		val stopTokenIds: Set<Long>,
	) : RuntimeHolder {
		override fun close() {
			runCatching { embedSession.close() }
			runCatching { decoderSession.close() }
			runCatching { tokenizer.close() }
		}
	}

	private data class TranslateGemmaRuntime(
		override val modelId: String,
		val backend: String,
		val tokenizer: HuggingFaceTokenizer,
		val embedSession: OrtSession?,
		val decoderSession: OrtSession,
		val disableThinkingInputName: String?,
		val decoderInputNames: Set<String>,
		val decoderInputInfo: Map<String, TensorInfo>,
		val stopTokenIds: Set<Long>,
		val promptTemplate: PromptTemplateInfo,
	) : RuntimeHolder {
		override fun close() {
			runCatching { embedSession?.close() }
			runCatching { decoderSession.close() }
			runCatching { tokenizer.close() }
		}
	}

	private data class QwenStepResult(
		val nextToken: Long,
		val decoderRun: OrtSession.Result,
		val totalSequenceLength: Int,
	)

	private val lock = Mutex()
	private val tokenizerLock = Mutex()
	@Volatile
	private var runtime: RuntimeHolder? = null

	private enum class ModelFamily {
		NLLB,
		MADLAD_LIKE,
	}

	private enum class PromptTemplateFamily {
		GENERIC_TRANSLATE_ONLY,
		HY_MT_CHAT,
		TRANSLATE_GEMMA_TEXT,
	}

	private data class PromptTemplateInfo(
		val family: PromptTemplateFamily,
		val source: String,
	)

	suspend fun translateBatch(
		texts: List<String>,
		sourceLang: String,
		targetLang: String,
		modelId: String,
	): Map<String, String> {
		if (texts.isEmpty()) return emptyMap()
		android.util.Log.d(LOG_TAG, "translateBatch: modelId=$modelId, texts.size=${texts.size}")
		val rt = ensureRuntime(modelId)
		if (rt == null) {
			android.util.Log.e(LOG_TAG, "translateBatch: ensureRuntime returned null for modelId=$modelId")
			return texts.associateWith { "" }
		}
		android.util.Log.d(LOG_TAG, "translateBatch: runtime loaded successfully, type=${rt.javaClass.simpleName}")
		val parallelism = minOf(MAX_PARALLEL_TRANSLATIONS, texts.size)
		Log.d(
			LOG_TAG,
			"ONNX translate batch start size=${texts.size} parallel=$parallelism model=${rt.modelId} backend=${runtimeBackend(rt)}"
		)
		if (rt is Qwen35Runtime && texts.size > 1) {
			val batchMap = runCatching {
				translateBatchQwen35(rt, texts, sourceLang, targetLang)
			}.onFailure {
				Log.w(LOG_TAG, "Qwen batch translate failed: ${it.message}", it)
			}.getOrDefault(emptyMap())
			val merged = LinkedHashMap<String, String>(texts.size)
			for (text in texts) {
				val out = batchMap[text].orEmpty().trim()
				if (out.isNotBlank()) {
					merged[text] = out
				}
			}
			val missing = texts.filter { merged[it].isNullOrBlank() }
			if (missing.isNotEmpty()) {
				Log.d(LOG_TAG, "Qwen batch partial hit=${merged.size}/${texts.size}, fallback singles missing=${missing.size}")
				merged.putAll(
					translateIndividually(
						runtime = rt,
						texts = missing,
						sourceLang = sourceLang,
						targetLang = targetLang,
						parallelism = parallelism,
						allowForceReload = false,
						modelId = modelId,
					)
				)
			}
			return texts.associateWith { merged[it].orEmpty() }
		}
		return translateIndividually(
			runtime = rt,
			texts = texts,
			sourceLang = sourceLang,
			targetLang = targetLang,
			parallelism = parallelism,
			allowForceReload = texts.size == 1,
			modelId = modelId,
		)
	}

	private suspend fun translateIndividually(
		runtime: RuntimeHolder,
		texts: List<String>,
		sourceLang: String,
		targetLang: String,
		parallelism: Int,
		allowForceReload: Boolean,
		modelId: String,
	): Map<String, String> {
		val semaphore = Semaphore(parallelism.coerceAtLeast(1))
		return coroutineScope {
			texts.map { text ->
				async(Dispatchers.Default) {
					semaphore.withPermit {
						var translated = runCatching {
							translateWithRuntime(runtime, text, sourceLang, targetLang)
						}.onFailure {
							it.printStackTrace()
						}.getOrDefault("")
						if (translated.isBlank() && text.isNotBlank() && allowForceReload) {
							val refreshed = ensureRuntime(modelId, forceReload = true)
							if (refreshed != null) {
								translated = runCatching {
									translateWithRuntime(refreshed, text, sourceLang, targetLang)
								}.onFailure {
									it.printStackTrace()
								}.getOrDefault("")
							}
						}
						text to translated
					}
				}
			}.awaitAll().toMap(LinkedHashMap(texts.size))
		}
	}

	private suspend fun translateWithRuntime(
		runtime: RuntimeHolder,
		text: String,
		sourceLang: String,
		targetLang: String,
	): String {
		return when (runtime) {
			is NllbRuntime -> translateOneNllb(runtime, text, sourceLang, targetLang)
			is GenericRuntime -> translateOneGeneric(runtime, text, sourceLang, targetLang)
			is Qwen35Runtime -> translateOneQwen35(runtime, text, sourceLang, targetLang)
			is TranslateGemmaRuntime -> translateOneTranslateGemma(runtime, text, sourceLang, targetLang)
		}
	}

	private suspend fun ensureRuntime(modelId: String, forceReload: Boolean = false): RuntimeHolder? {
		android.util.Log.d(LOG_TAG, "ensureRuntime: modelId=$modelId, forceReload=$forceReload")
		if (modelId.isBlank()) {
			android.util.Log.e(LOG_TAG, "ensureRuntime: modelId is blank")
			return null
		}
		val isDownloaded = onnxModelManager.isModelDownloaded(modelId)
		android.util.Log.d(LOG_TAG, "ensureRuntime: isModelDownloaded=$isDownloaded")
		if (!isDownloaded) {
			android.util.Log.e(LOG_TAG, "ensureRuntime: model not downloaded: $modelId")
			return null
		}
		val current = runtime
		if (!forceReload && current != null && current.modelId == modelId) {
			return current
		}
		return lock.withLock {
			val again = runtime
			if (!forceReload && again != null && again.modelId == modelId) return@withLock again
			runtime?.close()
			runtime = null
			val modelDir = onnxModelManager.getModelDir(modelId)
			runtime = createRuntimeWithFallback(modelId, modelDir)
			runtime
			}
		}

	private fun createRuntimeWithFallback(modelId: String, modelDir: File): RuntimeHolder? {
		val modelType = modelId.lowercase()
		Log.i(LOG_TAG, "Attempting to create runtime for model: $modelId")

		// Sequential fallback: GPU -> CPU
		for (useGpu in listOf(true, false)) {
			val typeLabel = if (useGpu) "GPU(NNAPI)" else "CPU"
			Log.d(LOG_TAG, "Trying $typeLabel initialization for $modelId")
			
			val rt = when {
				// 1. Check for NLLB specifically
				modelDir.walkTopDown().any { it.name.equals("NLLB_encoder.onnx", true) } -> {
					tryCreateNllbRuntime(modelId, modelDir, typeLabel, useGpu)
				}
				// 2. Check for Qwen 3.5
				modelDir.walkTopDown().any { it.name.contains("decoder_model_merged", true) } || modelId.contains("qwen", true) -> {
					tryCreateQwen35Runtime(modelId, modelDir, typeLabel, useGpu)
				}
				// 3. Check for TranslateGemma
				modelDir.walkTopDown().any { it.name.contains("translategemma", true) } ||
					modelId.contains("translategemma", true) ||
					modelDir.walkTopDown().any { it.name.equals("genai_config.json", true) && modelId.contains("hy_mt", true) } -> {
					tryCreateTranslateGemmaRuntime(modelId, modelDir, typeLabel, useGpu)
				}
				// 4. Fallback to Madlad or Generic
				else -> {
					tryCreateMadladLikeRuntime(modelId, modelDir, typeLabel, useGpu) 
						?: tryCreateGenericRuntime(modelId, modelDir, typeLabel, useGpu)
				}
			}
			
			if (rt != null) {
				Log.i(LOG_TAG, "Successfully initialized $modelId with $typeLabel")
				return rt
			}
			Log.w(LOG_TAG, "$typeLabel initialization failed for $modelId, trying next fallback...")
		}
		return null
	}

	private fun createSessionOptions(useGpu: Boolean): OrtSession.SessionOptions {
		val options = OrtSession.SessionOptions()
		options.setMemoryPatternOptimization(false)
		options.setCPUArenaAllocator(false)
		options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
		if (useGpu) {
			val result = runCatching { options.addNnapi() }
			if (result.isSuccess) {
				Log.i(LOG_TAG, "ONNX NNAPI accelerator added successfully")
			} else {
				Log.w(LOG_TAG, "ONNX NNAPI accelerator failed to add: ${result.exceptionOrNull()?.message}")
			}
		}
		return options
	}

	private fun tryCreateNllbRuntime(modelId: String, modelDir: File, backend: String, useGpu: Boolean): NllbRuntime? {
		val encoder = modelDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("NLLB_encoder.onnx", ignoreCase = true) }
		val decoder = modelDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("NLLB_decoder.onnx", ignoreCase = true) }
		val cacheInit = modelDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("NLLB_cache_initializer.onnx", ignoreCase = true) }
		val embedLm = modelDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("NLLB_embed_and_lm_head.onnx", ignoreCase = true) }
		val spModel = modelDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("sentencepiece_bpe.model", ignoreCase = true) }
		if (encoder == null || decoder == null || cacheInit == null || embedLm == null || spModel == null) {
			return null
		}
		val options = createSessionOptions(useGpu)
		return try {
			val env = OrtEnvironment.getEnvironment()
			val decoderSession = env.createSession(decoder.absolutePath, options)
			NllbRuntime(
				modelId = modelId,
				backend = backend,
				modelFamily = ModelFamily.NLLB,
				tokenizer = SpTokenizer(spModel.toPath()),
				encoderSession = env.createSession(encoder.absolutePath, options),
				decoderSession = decoderSession,
				cacheInitSession = env.createSession(cacheInit.absolutePath, options),
				embedLmSession = env.createSession(embedLm.absolutePath, options),
				decoderInputNames = decoderSession.inputNames,
				decoderOutputNames = decoderSession.outputNames,
				layers = 12,
				hiddenSize = 64,
			)
		} catch (e: Throwable) {
			e.printStackTrace()
			null
		} finally {
			runCatching { options.close() }
		}
	}

	private fun tryCreateMadladLikeRuntime(modelId: String, modelDir: File, backend: String, useGpu: Boolean): NllbRuntime? {
		if (!modelDir.exists()) return null
		val encoder = modelDir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("onnx", true) && it.name.contains("encoder", true) }
		val decoder = modelDir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("onnx", true) && it.name.contains("decoder", true) }
		val cacheInit = modelDir.walkTopDown().firstOrNull {
			it.isFile && it.extension.equals("onnx", true) &&
				it.name.contains("cache", true) && it.name.contains("init", true)
		}
		val embedLm = modelDir.walkTopDown().firstOrNull {
			it.isFile && it.extension.equals("onnx", true) &&
				(it.name.contains("embed", true) || it.name.contains("lm_head", true) || it.name.contains("lm", true))
		}
		val spModel = modelDir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("model", true) }
		if (encoder == null || decoder == null || cacheInit == null || embedLm == null || spModel == null) {
			return null
		}
		val options = createSessionOptions(useGpu)
		return try {
			val env = OrtEnvironment.getEnvironment()
			val decoderSession = env.createSession(decoder.absolutePath, options)
			NllbRuntime(
				modelId = modelId,
				backend = backend,
				modelFamily = ModelFamily.MADLAD_LIKE,
				tokenizer = SpTokenizer(spModel.toPath()),
				encoderSession = env.createSession(encoder.absolutePath, options),
				decoderSession = decoderSession,
				cacheInitSession = env.createSession(cacheInit.absolutePath, options),
				embedLmSession = env.createSession(embedLm.absolutePath, options),
				decoderInputNames = decoderSession.inputNames,
				decoderOutputNames = decoderSession.outputNames,
				layers = 32,
				hiddenSize = 128,
			)
		} catch (_: Throwable) {
			null
		} finally {
			runCatching { options.close() }
		}
	}

	private fun tryCreateGenericRuntime(modelId: String, modelDir: File, backend: String, useGpu: Boolean): GenericRuntime? {
		val tokenizerPath = findTokenizerPath(modelDir) ?: return null
		val decoderPath = findDecoderOnnxPath(modelDir) ?: return null
		val tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath.toPath())
		val env = OrtEnvironment.getEnvironment()
		val options = createSessionOptions(useGpu)
		val session = try {
			env.createSession(decoderPath.absolutePath, options)
		} finally {
			options.close()
		}
		val inputNames = session.inputNames
		if ("input_ids" !in inputNames) {
			runCatching { session.close() }
			runCatching { tokenizer.close() }
			return null
		}
		val logitsOutputName = session.outputNames.firstOrNull { it.contains("logits") }
			?: session.outputNames.firstOrNull()
			?: run {
				runCatching { session.close() }
				runCatching { tokenizer.close() }
				return null
			}
		val promptTemplate = detectPromptTemplate(
			modelId = modelId,
			modelDir = modelDir,
			fallbackFamily = PromptTemplateFamily.GENERIC_TRANSLATE_ONLY,
		)
		Log.i(
			LOG_TAG,
			"Generic ONNX prompt template=${promptTemplate.family.name.lowercase()} source=${promptTemplate.source} model=$modelId"
		)
		return GenericRuntime(modelId, backend, tokenizer, session, inputNames, logitsOutputName, promptTemplate)
	}

	private fun tryCreateQwen35Runtime(modelId: String, modelDir: File, backend: String, useGpu: Boolean): Qwen35Runtime? {
		val tokenizerPath = findTokenizerPath(modelDir) ?: return null
		val decoderPath = findQwenDecoderOnnxPath(modelDir) ?: return null
		val embedPath = findQwenEmbedOnnxPath(modelDir) ?: return null
		val tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath.toPath())
		val env = OrtEnvironment.getEnvironment()
		val options = createSessionOptions(useGpu)
		return try {
			val embedSession = env.createSession(embedPath.absolutePath, options)
			val decoderSession = env.createSession(decoderPath.absolutePath, options)
			val inputNames = decoderSession.inputNames
			if ("inputs_embeds" !in inputNames) {
				runCatching { embedSession.close() }
				runCatching { decoderSession.close() }
				runCatching { tokenizer.close() }
				return null
			}
			val inputInfo = decoderSession.inputInfo.mapNotNull { (name, nodeInfo) ->
				(name to (nodeInfo.info as? TensorInfo))
			}.filter { it.second != null }.associate { it.first to it.second!! }
			val disableThinkingInputName = inputNames.firstOrNull {
				it.equals("enable_thinking", ignoreCase = true) ||
					it.equals("disable_thinking", ignoreCase = true)
			}
			Qwen35Runtime(
				modelId = modelId,
				backend = backend,
				tokenizer = tokenizer,
				embedSession = embedSession,
				decoderSession = decoderSession,
				decoderInputNames = inputNames,
				decoderInputInfo = inputInfo,
				disableThinkingInputName = disableThinkingInputName,
				stopTokenIds = loadStopTokens(modelDir),
			).also {
				Log.i(
					LOG_TAG,
					if (disableThinkingInputName != null) {
						"Qwen ONNX thinking control input detected: $disableThinkingInputName"
					} else {
						"Qwen ONNX thinking control input not found in graph; prompt-level suppression only"
					}
				)
			}
		} catch (_: Throwable) {
			runCatching { tokenizer.close() }
			null
		} finally {
			runCatching { options.close() }
		}
	}

	private fun tryCreateTranslateGemmaRuntime(modelId: String, modelDir: File, backend: String, useGpu: Boolean): TranslateGemmaRuntime? {
		val tokenizerPath = findTokenizerPath(modelDir) ?: return null
		val decoderPath = modelDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("model.onnx", true) }
			?: findQwenDecoderOnnxPath(modelDir) ?: return null
		val embedPath = findQwenEmbedOnnxPath(modelDir)
		val tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath.toPath())
		val env = OrtEnvironment.getEnvironment()
		val options = createSessionOptions(useGpu)
		return try {
			val decoderSession = env.createSession(decoderPath.absolutePath, options)
			val embedSession = if (embedPath != null) env.createSession(embedPath.absolutePath, options) else null
			val inputNames = decoderSession.inputNames
			if ("inputs_embeds" !in inputNames && "input_ids" !in inputNames) {
				runCatching { embedSession?.close() }
				runCatching { decoderSession.close() }
				runCatching { tokenizer.close() }
				return null
			}
			val inputInfo = decoderSession.inputInfo.mapNotNull { (name, nodeInfo) ->
				(name to (nodeInfo.info as? TensorInfo))
			}.filter { it.second != null }.associate { it.first to it.second!! }
			val disableThinkingInputName = inputNames.firstOrNull {
				it.equals("enable_thinking", ignoreCase = true) ||
					it.equals("disable_thinking", ignoreCase = true)
			}
			val promptTemplate = detectPromptTemplate(
				modelId = modelId,
				modelDir = modelDir,
				fallbackFamily = PromptTemplateFamily.TRANSLATE_GEMMA_TEXT,
			)
			Log.i(
				LOG_TAG,
				"TranslateGemma prompt template=${promptTemplate.family.name.lowercase()} source=${promptTemplate.source} model=$modelId"
			)
			TranslateGemmaRuntime(
				modelId = modelId,
				backend = backend,
				tokenizer = tokenizer,
				embedSession = embedSession,
				decoderSession = decoderSession,
				decoderInputNames = inputNames,
				decoderInputInfo = inputInfo,
				disableThinkingInputName = disableThinkingInputName,
				stopTokenIds = loadStopTokens(modelDir),
				promptTemplate = promptTemplate,
			)
		} catch (_: Throwable) {
			runCatching { tokenizer.close() }
			null
		} finally {
			runCatching { options.close() }
		}
	}

	private suspend fun translateOneGeneric(runtime: GenericRuntime, text: String, sourceLang: String, targetLang: String): String {
		val input = text.trim()
		if (input.isEmpty()) return ""
		val prompt = buildPromptForTemplateFamily(
			family = runtime.promptTemplate.family,
			sourceLang = sourceLang,
			targetLang = targetLang,
			input = input,
		)
		val encoded = tokenizerLock.withLock { runtime.tokenizer.encode(prompt).ids }
		if (encoded.isEmpty()) return ""
		val ids = encoded.toMutableList()
		val generated = ArrayList<Long>(MAX_NEW_TOKENS)
		repeat(MAX_NEW_TOKENS) {
			val next = runGenericDecoderStep(runtime, ids.toLongArray()) ?: return@repeat
			if (next in STOP_TOKEN_IDS) return@repeat
			generated += next
			ids += next
		}
		if (generated.isEmpty()) return ""
		return tokenizerLock.withLock { runtime.tokenizer.decode(generated.toLongArray()).trim() }
	}

	private suspend fun translateOneQwen35(
		runtime: Qwen35Runtime,
		text: String,
		sourceLang: String,
		targetLang: String,
	): String {
		val input = text.trim()
		if (input.isEmpty()) return ""
		val targetLangName = translateGemmaLanguageName(normalizeTranslateGemmaLanguageCode(targetLang))
		val primaryPrompt = "<|im_start|>system\nYou are a professional manga translator.\n" +
			"Translate from $sourceLang to $targetLang.\n" +
			"Output compact JSON only. No thinking. No explanation. No markdown.\n" +
			"Do not amplify repeated onomatopoeia or screams.\n" +
			"Keep the repetition count close to the source and concise in $targetLangName.\n" +
			"For long repeated screams like キャアアアアアアア, prefer a short natural $targetLangName sound effect rather than dozens of repeated characters.\n<|im_end|>\n" +
			"<|im_start|>user\nTranslate from $sourceLang to $targetLang:\n$input\n/no_think\n<|im_end|>\n" +
			"<|im_start|>assistant\n<think> </think>\n{\"translation\":\""
		val firstAttempt = runQwen35Generation(
			runtime = runtime,
			prompt = primaryPrompt,
			input = input,
			maxNewTokens = MAX_QWEN_NEW_TOKENS,
			jsonPrefill = true,
		)
		val firstSanitized = extractQwenTranslation(firstAttempt.rawOutput, firstAttempt.jsonPrefill)
		if (firstSanitized.isNotBlank()) {
			return firstSanitized
		}
		Log.w(
			LOG_TAG,
			"Qwen primary decode produced no usable translation, retrying src=${oneLine(input, 80)} raw=${oneLine(firstAttempt.rawOutput, 160)}"
		)
		val retryPrompt = "<|im_start|>system\nTranslate from $sourceLang to $targetLang. " +
			"Return translated text only. No thinking. No explanation. " +
			"Do not amplify repeated onomatopoeia or screams; keep them concise.\n<|im_end|>\n" +
			"<|im_start|>user\n$input\n/no_think\n<|im_end|>\n" +
			"<|im_start|>assistant\n<think> </think>\n"
		val retryAttempt = runQwen35Generation(
			runtime = runtime,
			prompt = retryPrompt,
			input = input,
			maxNewTokens = MAX_QWEN_RETRY_NEW_TOKENS,
			jsonPrefill = false,
		)
		return extractQwenTranslation(retryAttempt.rawOutput, retryAttempt.jsonPrefill)
	}

	private suspend fun translateBatchQwen35(
		runtime: Qwen35Runtime,
		texts: List<String>,
		sourceLang: String,
		targetLang: String,
	): Map<String, String> {
		if (texts.isEmpty()) return emptyMap()
		val targetLangName = translateGemmaLanguageName(normalizeTranslateGemmaLanguageCode(targetLang))
		val indexedInput = JSONArray().apply {
			texts.forEachIndexed { index, text ->
				put(JSONObject().put("id", index + 1).put("text", text))
			}
		}
		val prompt = "<|im_start|>system\n" +
			"You are a professional manga translator.\n" +
			"These lines belong to the same manga page and the same chapter flow.\n" +
			"OCR may contain mistakes, broken characters, repeated sounds, or truncated words.\n" +
			"Use nearby lines as context to infer the most natural translation, but do not invent new plot details.\n" +
			"Never return empty translations.\n" +
			"If a line is mostly an onomatopoeia or scream, translate it into a natural $targetLangName scream or sound effect.\n" +
			"Do not amplify repeated onomatopoeia or screams beyond the source.\n" +
			"Keep repeated screams concise in $targetLangName, usually one short exclamation instead of many repeated characters.\n" +
			"If a line contains a person name plus an honorific, keep the name and translate the honorific naturally.\n" +
			"Return compact JSON only in this format: {\"items\":[{\"id\":1,\"translation\":\"...\"}]}\n" +
			"No thinking. No explanation. No markdown. No extra fields.\n" +
			"<|im_end|>\n" +
			"<|im_start|>user\n" +
			"Translate from $sourceLang to $targetLang. Same page OCR lines:\n" +
			indexedInput.toString() + "\n" +
			"/no_think\n" +
			"<|im_end|>\n" +
			"<|im_start|>assistant\n" +
			"<think> </think>\n" +
			"{\"items\":["
		val attempt = runQwen35Generation(
			runtime = runtime,
			prompt = prompt,
			input = texts.joinToString(" | "),
			maxNewTokens = batchMaxNewTokens(texts),
			jsonPrefill = false,
			completionPredicate = ::looksLikeCompletedBatchJsonSuffix,
		)
		val parsed = parseQwenBatchTranslations(attempt.rawOutput, texts)
		if (parsed.isNotEmpty()) {
			return parsed
		}
		Log.w(LOG_TAG, "Qwen batch decode produced no usable translations raw=${oneLine(attempt.rawOutput, 220)}")
		return emptyMap()
	}

	private data class QwenGenerationAttempt(
		val rawOutput: String,
		val jsonPrefill: Boolean,
	)

	private suspend fun runQwen35Generation(
		runtime: Qwen35Runtime,
		prompt: String,
		input: String,
		maxNewTokens: Int,
		jsonPrefill: Boolean,
		completionPredicate: ((String) -> Boolean)? = null,
	): QwenGenerationAttempt {
		val promptIds = tokenizerLock.withLock { runtime.tokenizer.encode(prompt).ids }
		if (promptIds.isEmpty()) return QwenGenerationAttempt("", jsonPrefill)
		val generated = ArrayList<Long>(maxNewTokens)
		var prevRun: OrtSession.Result? = null
		var nextInputIds = promptIds.map { it.toLong() }.toLongArray()
		var pastSequenceLength = 0
		var step = 0
		try {
			while (step < maxNewTokens) {
				val result = runQwen35Step(runtime, nextInputIds, pastSequenceLength, prevRun) ?: break
				runCatching { prevRun?.close() }
				prevRun = result.decoderRun
				pastSequenceLength = result.totalSequenceLength
				val nextToken = result.nextToken
				if (nextToken in runtime.stopTokenIds) {
					break
				}
				generated += nextToken
				nextInputIds = longArrayOf(nextToken)
				if (generated.size >= 6) {
					val partial = tokenizerLock.withLock { runtime.tokenizer.decode(generated.toLongArray()).trim() }
					if (completionPredicate != null && completionPredicate(partial)) {
						break
					}
					if (jsonPrefill && looksLikeCompletedTranslationSuffix(partial)) {
						break
					}
					if (!jsonPrefill && looksLikeCompletedTranslationJson(partial)) {
						break
					}
				}
				step++
			}
		} finally {
			runCatching { prevRun?.close() }
		}
		val rawOutput = tokenizerLock.withLock { runtime.tokenizer.decode(generated.toLongArray()).trim() }
		Log.d(
			LOG_TAG,
			"Qwen translate done backend=${runtime.backend} generatedTokens=${generated.size} src=${oneLine(input, 80)} raw=${oneLine(rawOutput, 160)}"
		)
		return QwenGenerationAttempt(rawOutput = rawOutput, jsonPrefill = jsonPrefill)
	}

	private suspend fun translateOneTranslateGemma(
		runtime: TranslateGemmaRuntime,
		text: String,
		sourceLang: String,
		targetLang: String,
	): String {
		val input = text.trim()
		if (input.isEmpty()) return ""
		
		val prompt = buildPromptForTemplateFamily(
			family = runtime.promptTemplate.family,
			sourceLang = sourceLang,
			targetLang = targetLang,
			input = input,
		)

		val promptIds = tokenizerLock.withLock { runtime.tokenizer.encode(prompt).ids }
		if (promptIds.isEmpty()) return ""
		
		val generated = ArrayList<Long>(MAX_NEW_TOKENS)
		var prevRun: OrtSession.Result? = null
		var nextInputIds = promptIds.map { it.toLong() }.toLongArray()
		var pastSequenceLength = 0
		var step = 0
		
		try {
			while (step < MAX_NEW_TOKENS) {
				val result = runTranslateGemmaStep(runtime, nextInputIds, pastSequenceLength, prevRun) ?: break
				runCatching { prevRun?.close() }
				prevRun = result.decoderRun
				pastSequenceLength = result.totalSequenceLength
				val nextToken = result.nextToken
				if (nextToken in runtime.stopTokenIds || nextToken == 1L || nextToken == 107L) {
					break
				}
				generated += nextToken
				nextInputIds = longArrayOf(nextToken)
				
				if (generated.size >= 6) {
					val partial = tokenizerLock.withLock { runtime.tokenizer.decode(generated.toLongArray()).trim() }
					if (partial.contains("<end_of_turn>")) break
				}
				step++
			}
		} finally {
			runCatching { prevRun?.close() }
		}
		val rawOutput = tokenizerLock.withLock { runtime.tokenizer.decode(generated.toLongArray()).trim() }
		val stripped = rawOutput
			.substringBefore("<end_of_turn>")
			.replace(Regex("(?is)<think>.*?</think>"), "")
			.trim()
		return stripped
	}

	private fun buildPromptForTemplateFamily(
		family: PromptTemplateFamily,
		sourceLang: String,
		targetLang: String,
		input: String,
	): String {
		return when (family) {
			PromptTemplateFamily.HY_MT_CHAT -> buildHyMtChatPrompt(
				sourceLang = sourceLang,
				targetLang = targetLang,
				input = input,
			)
			PromptTemplateFamily.TRANSLATE_GEMMA_TEXT -> buildTranslateGemmaTextPrompt(
				sourceLang = sourceLang,
				targetLang = targetLang,
				input = input,
			)
			PromptTemplateFamily.GENERIC_TRANSLATE_ONLY -> buildGenericTranslatePrompt(
				sourceLang = sourceLang,
				targetLang = targetLang,
				input = input,
			)
		}
	}

	private fun buildGenericTranslatePrompt(
		sourceLang: String,
		targetLang: String,
		input: String,
	): String {
		return "Translate from $sourceLang to $targetLang. Return only translation.\n\n$input"
	}

	private fun buildHyMtChatPrompt(
		sourceLang: String,
		targetLang: String,
		input: String,
	): String {
		return buildString(input.length + 240) {
			append("<｜hy_begin▁of▁sentence｜>")
			append("You are a professional manga translator. Return only the translated text.")
			append("<｜hy_place▁holder▁no▁3｜>")
			append("<｜hy_User｜>")
			append("Translate from ")
			append(sourceLang)
			append(" to ")
			append(targetLang)
			append(".\n")
			append(input)
			append("<｜hy_Assistant｜>")
		}
	}

	private fun buildTranslateGemmaTextPrompt(
		sourceLang: String,
		targetLang: String,
		input: String,
	): String {
		val sourceLangCode = normalizeTranslateGemmaLanguageCode(sourceLang)
		val targetLangCode = normalizeTranslateGemmaLanguageCode(targetLang)
		val sourceLangName = translateGemmaLanguageName(sourceLangCode)
		val targetLangName = translateGemmaLanguageName(targetLangCode)
		return buildString(input.length + 320) {
			append("<start_of_turn>user\n")
			append("You are a professional ")
			append(sourceLangName)
			append(" (")
			append(sourceLangCode)
			append(") to ")
			append(targetLangName)
			append(" (")
			append(targetLangCode)
			append(") translator. Your goal is to accurately convey the meaning and nuances of the original ")
			append(sourceLangName)
			append(" text while adhering to ")
			append(targetLangName)
			append(" grammar, vocabulary, and cultural sensitivities.\n")
			append("Produce only the ")
			append(targetLangName)
			append(" translation, without any additional explanations or commentary. Please translate the following ")
			append(sourceLangName)
			append(" text into ")
			append(targetLangName)
			append(":\n\n\n")
			append(input)
			append("<end_of_turn>\n<start_of_turn>model\n")
		}
	}

	private fun detectPromptTemplate(
		modelId: String,
		modelDir: File,
		fallbackFamily: PromptTemplateFamily,
	): PromptTemplateInfo {
		val chatTemplateFile = File(modelDir, "chat_template.jinja")
		if (chatTemplateFile.isFile) {
			val family = detectPromptTemplateFromText(chatTemplateFile.readText(), fallbackFamily)
			return PromptTemplateInfo(family = family, source = "chat_template.jinja")
		}
		val tokenizerConfigFile = File(modelDir, "tokenizer_config.json")
		if (tokenizerConfigFile.isFile) {
			val family = runCatching {
				val root = JSONObject(tokenizerConfigFile.readText())
				root.optString("chat_template")
			}.getOrNull()?.takeIf { it.isNotBlank() }?.let { template ->
				detectPromptTemplateFromText(template, fallbackFamily)
			}
			if (family != null) {
				return PromptTemplateInfo(family = family, source = "tokenizer_config.json")
			}
		}
		if (modelId.contains("translategemma", ignoreCase = true)) {
			return PromptTemplateInfo(
				family = PromptTemplateFamily.TRANSLATE_GEMMA_TEXT,
				source = "model_id_hint",
			)
		}
		if (modelId.contains("hy_mt", ignoreCase = true)) {
			return PromptTemplateInfo(
				family = PromptTemplateFamily.HY_MT_CHAT,
				source = "model_id_hint",
			)
		}
		return PromptTemplateInfo(family = fallbackFamily, source = "fallback")
	}

	private fun detectPromptTemplateFromText(
		templateText: String,
		fallbackFamily: PromptTemplateFamily,
	): PromptTemplateFamily {
		val normalized = templateText.lowercase()
		if (
			normalized.contains("hy_user") &&
			normalized.contains("hy_assistant")
		) {
			return PromptTemplateFamily.HY_MT_CHAT
		}
		if (
			normalized.contains("source_lang_code") &&
			normalized.contains("target_lang_code") &&
			normalized.contains("professional") &&
			normalized.contains("translation")
		) {
			return PromptTemplateFamily.TRANSLATE_GEMMA_TEXT
		}
		return fallbackFamily
	}

	private fun normalizeTranslateGemmaLanguageCode(lang: String): String {
		return lang.trim().replace('_', '-').ifBlank { "en" }
	}

	private fun translateGemmaLanguageName(langCode: String): String {
		val tag = langCode.replace('_', '-')
		val locale = Locale.forLanguageTag(tag)
		val displayName = locale.getDisplayLanguage(Locale.ENGLISH).trim()
		if (displayName.isNotBlank()) return displayName
		val baseCode = tag.substringBefore('-')
		val baseLocale = Locale.forLanguageTag(baseCode)
		return baseLocale.getDisplayLanguage(Locale.ENGLISH).trim().ifBlank { tag }
	}

	private fun extractQwenTranslation(rawOutput: String, jsonPrefill: Boolean): String {
		if (rawOutput.isBlank()) return ""
		val normalized = if (jsonPrefill) {
			val suffix = rawOutput.substringBefore("<|im_end|>").trim()
			"{\"translation\":\"$suffix"
		} else {
			rawOutput
		}
		val stripped = normalized
			.replace(Regex("(?is)<think>.*?</think>"), "")
			.replace(Regex("(?is)<think>.*$"), "")
			.trim()
		return try {
			val jsonStart = stripped.indexOf('{')
			val jsonEnd = stripped.lastIndexOf('}')
			if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
				val json = JSONObject(stripped.substring(jsonStart, jsonEnd + 1))
				json.optString("translation").ifBlank {
					json.optString("translatedText").ifBlank {
						json.optString("output")
					}
				}.trim()
			} else {
				stripped
			}
		} catch (_: Exception) {
			stripped
				.substringAfter("\"translation\":\"", "")
				.substringBeforeLast("\"}")
				.substringBefore("<|im_end|>")
				.replace("\\n", "\n")
				.replace("\\\"", "\"")
				.trim()
		}.takeUnless { candidate ->
			candidate.isBlank() ||
				candidate.contains("<think>", ignoreCase = true) ||
				candidate.contains("Thinking Process", ignoreCase = true)
		}.orEmpty()
	}

	private fun looksLikeCompletedTranslationJson(text: String): Boolean {
		if (text.isBlank()) return false
		return COMPLETED_TRANSLATION_JSON_REGEX.containsMatchIn(text.trim())
	}

	private fun looksLikeCompletedTranslationSuffix(text: String): Boolean {
		if (text.isBlank()) return false
		val trimmed = text.trim()
		return trimmed.contains("\"}") || trimmed.contains("\"\n}")
	}

	private fun looksLikeCompletedBatchJsonSuffix(text: String): Boolean {
		if (text.isBlank()) return false
		val trimmed = text.trim()
		return trimmed.contains("]}")
	}

	private fun batchMaxNewTokens(texts: List<String>): Int {
		val totalChars = texts.sumOf { it.length }
		return (80 + totalChars * 2).coerceIn(96, MAX_QWEN_BATCH_NEW_TOKENS)
	}

	private fun parseQwenBatchTranslations(
		rawOutput: String,
		texts: List<String>,
	): Map<String, String> {
		if (rawOutput.isBlank() || texts.isEmpty()) return emptyMap()
		val normalized = rawOutput
			.replace(Regex("(?is)<think>.*?</think>"), "")
			.replace(Regex("(?is)<think>.*$"), "")
			.replace("```json", "", ignoreCase = true)
			.replace("```", "")
			.trim()
		val wrapped = when {
			normalized.startsWith("{\"items\"") -> completeBatchJson(normalized)
			normalized.startsWith("[") -> completeBatchJson("{\"items\":$normalized")
			normalized.startsWith("{\"id\"") -> completeBatchJson("{\"items\":[$normalized")
			else -> {
				val firstItem = normalized.indexOf("{\"id\"")
				if (firstItem != -1) {
					completeBatchJson("{\"items\":[" + normalized.substring(firstItem))
				} else {
					return emptyMap()
				}
			}
		}
		val jsonStart = wrapped.indexOf('{')
		val jsonEnd = wrapped.lastIndexOf('}')
		if (jsonStart == -1 || jsonEnd <= jsonStart) return emptyMap()
		return runCatching {
			val root = JSONObject(wrapped.substring(jsonStart, jsonEnd + 1))
			val items = root.optJSONArray("items") ?: return@runCatching emptyMap<String, String>()
			buildMap {
				for (i in 0 until items.length()) {
					val obj = items.optJSONObject(i) ?: continue
					val id = obj.optInt("id", -1)
					if (id !in 1..texts.size) continue
					val translation = obj.optString("translation").trim().ifBlank {
						obj.optString("translatedText").trim()
					}
					if (translation.isNotBlank()) {
						put(texts[id - 1], translation)
					}
				}
			}
		}.getOrElse {
			emptyMap()
		}
	}

	private fun completeBatchJson(text: String): String {
		var result = text.trim()
		if (!result.startsWith("{\"items\"")) return result
		if (!result.contains("\"items\"")) return result
		if (!result.contains("]")) {
			result += "]"
		}
		if (!result.endsWith("}")) {
			result += "}"
		}
		if (!result.endsWith("]}")) {
			if (result.endsWith("]")) {
				result += "}"
			} else if (!result.contains("]}")) {
				result += "]}"
			}
		}
		return result
	}

	private fun runtimeBackend(runtime: RuntimeHolder): String {
		return when (runtime) {
			is GenericRuntime -> runtime.backend
			is NllbRuntime -> runtime.backend
			is Qwen35Runtime -> runtime.backend
			is TranslateGemmaRuntime -> runtime.backend
		}
	}

	private fun runQwen35Step(
		runtime: Qwen35Runtime,
		inputTokenIds: LongArray,
		pastSequenceLength: Int,
		prevRun: OrtSession.Result?,
	): QwenStepResult? {
		if (inputTokenIds.isEmpty()) return null
		val inputIdsTensor = createInt64Tensor(inputTokenIds)
		var embedRun: OrtSession.Result? = null
		val created = mutableListOf<OnnxTensor>()
		try {
			embedRun = runtime.embedSession.run(mapOf("input_ids" to inputIdsTensor))
			val embedTensor = (embedRun.get("inputs_embeds").orElse(null) as? OnnxTensor)
				?: (runtime.embedSession.outputNames.firstOrNull()?.let { name ->
					embedRun.get(name).orElse(null) as? OnnxTensor
				})
				?: return null
			val seqLen = inputTokenIds.size
			val totalLen = pastSequenceLength + seqLen
			val attentionMask = createInt64Tensor(LongArray(totalLen) { 1L }).also { created += it }
			val positionIds = createQwenPositionIds(pastSequenceLength, seqLen).also { created += it }
			val decoderInputs = mutableMapOf<String, OnnxTensor>()
			decoderInputs["inputs_embeds"] = embedTensor
			decoderInputs["attention_mask"] = attentionMask
			decoderInputs["position_ids"] = positionIds
			runtime.disableThinkingInputName?.let { inputName ->
				val value = when {
					inputName.equals("enable_thinking", ignoreCase = true) -> createBooleanTensor(false)
					inputName.equals("disable_thinking", ignoreCase = true) -> createBooleanTensor(true)
					else -> createBooleanTensor(false)
				}
				decoderInputs[inputName] = value
				created += value
			}
			for (name in runtime.decoderInputNames) {
				if (name == "inputs_embeds" || name == "attention_mask" || name == "position_ids") continue
				if (name == runtime.disableThinkingInputName) continue
				if (name.startsWith("past_")) {
					if (prevRun == null) {
						val info = runtime.decoderInputInfo[name] ?: return null
						val zero = createZeroTensorForInput(name, info, pastSequenceLength = 0)
						decoderInputs[name] = zero
						created += zero
					} else {
						val presentName = mapPastInputToPresentOutput(name)
						decoderInputs[name] = prevRun.get(presentName).orElse(null) as? OnnxTensor ?: return null
					}
				} else {
					return null
				}
			}
			val decoderRun = runtime.decoderSession.run(decoderInputs)
			val logits = (decoderRun.get("logits").orElse(null) as? OnnxTensor)
				?: (runtime.decoderSession.outputNames.firstOrNull()?.let { name ->
					decoderRun.get(name).orElse(null) as? OnnxTensor
				})
				?: run {
					decoderRun.close()
					return null
				}
			val nextToken = argmaxLastToken(logits.value) ?: run {
				decoderRun.close()
				return null
			}
			return QwenStepResult(nextToken, decoderRun, totalLen)
		} finally {
			runCatching { inputIdsTensor.close() }
			created.forEach { runCatching { it.close() } }
			runCatching { embedRun?.close() }
		}
	}

	private fun runTranslateGemmaStep(
		runtime: TranslateGemmaRuntime,
		inputTokenIds: LongArray,
		pastSequenceLength: Int,
		prevRun: OrtSession.Result?,
	): QwenStepResult? {
		if (inputTokenIds.isEmpty()) return null
		val inputIdsTensor = createInt64Tensor(inputTokenIds)
		var embedRun: OrtSession.Result? = null
		val created = mutableListOf<OnnxTensor>()
		created += inputIdsTensor
		try {
			val seqLen = inputTokenIds.size
			val totalLen = pastSequenceLength + seqLen
			val attentionMask = createInt64Tensor(LongArray(totalLen) { 1L }).also { created += it }
			val positionIds = createInt64Tensor(LongArray(seqLen) { (pastSequenceLength + it).toLong() }).also { created += it }
			val decoderInputs = mutableMapOf<String, OnnxTensor>()

			if (runtime.embedSession != null) {
				embedRun = runtime.embedSession.run(mapOf("input_ids" to inputIdsTensor))
				val embedTensor = (embedRun.get("inputs_embeds").orElse(null) as? OnnxTensor)
					?: (runtime.embedSession.outputNames.firstOrNull()?.let { name ->
						embedRun.get(name).orElse(null) as? OnnxTensor
					}) ?: return null
				decoderInputs["inputs_embeds"] = embedTensor
			} else {
				if ("input_ids" in runtime.decoderInputNames) {
					decoderInputs["input_ids"] = inputIdsTensor
				}
			}

			if ("attention_mask" in runtime.decoderInputNames) {
				decoderInputs["attention_mask"] = attentionMask
			}
			if ("position_ids" in runtime.decoderInputNames) {
				decoderInputs["position_ids"] = positionIds
			}

			for (name in runtime.decoderInputNames) {
				if (name == "inputs_embeds" || name == "input_ids" || name == "attention_mask" || name == "position_ids") continue
				if (name.startsWith("past_key_values.")) {
					if (prevRun == null) {
						val info = runtime.decoderInputInfo[name] ?: return null
						val zero = createZeroTensorForInput(name, info, pastSequenceLength = 0)
						decoderInputs[name] = zero
						created += zero
					} else {
						val presentName = mapPastInputToPresentOutput(name)
						decoderInputs[name] = prevRun.get(presentName).orElse(null) as? OnnxTensor ?: return null
					}
				}
			}
			
			val decoderRun = runtime.decoderSession.run(decoderInputs)
			val logits = (decoderRun.get("logits").orElse(null) as? OnnxTensor)
				?: (runtime.decoderSession.outputNames.firstOrNull()?.let { name ->
					decoderRun.get(name).orElse(null) as? OnnxTensor
				})
				?: run {
					decoderRun.close()
					return null
				}
			val nextToken = argmaxLastToken(logits.value) ?: run {
				decoderRun.close()
				return null
			}
			return QwenStepResult(nextToken, decoderRun, totalLen)
		} finally {
			created.forEach { runCatching { it.close() } }
			runCatching { embedRun?.close() }
		}
	}

	private fun runGenericDecoderStep(runtime: GenericRuntime, inputIds: LongArray): Long? {
		val inputs = linkedMapOf<String, OnnxTensor>()
		try {
			for (name in runtime.inputNames) {
				when (name) {
					"input_ids" -> inputs[name] = createInt64Tensor(inputIds)
					"attention_mask" -> inputs[name] = createInt64Tensor(LongArray(inputIds.size) { 1L })
					"position_ids" -> inputs[name] = createInt64Tensor(LongArray(inputIds.size) { it.toLong() })
					else -> return null
				}
			}
			runtime.session.run(inputs).use { result ->
				val logits = result.get(runtime.logitsOutputName).orElse(null) as? OnnxTensor ?: return null
				return argmaxLastToken(logits.value)
			}
		} finally {
			inputs.values.forEach { runCatching { it.close() } }
		}
	}

	private suspend fun translateOneNllb(runtime: NllbRuntime, text: String, sourceLang: String, targetLang: String): String {
		val input = text.trim()
		if (input.isEmpty()) return ""
		val sp = runtime.tokenizer.getProcessor()
		val eos = sp.getId("</s>")
		val srcNllb = toNllbCode(sourceLang)
		val tgtNllb = toNllbCode(targetLang)
		val tgtLangId = tgtNllb?.let(::languageId)
		val encoded = tokenizerLock.withLock {
			when (runtime.modelFamily) {
				ModelFamily.NLLB -> sp.encode(input)
				ModelFamily.MADLAD_LIKE -> sp.encode("<2${targetLang.trim().lowercase().substringBefore('-')}> $input")
			}
		}
		val inputIds = when (runtime.modelFamily) {
			ModelFamily.NLLB -> {
				val srcLangId = srcNllb?.let(::languageId) ?: return ""
				val ids = IntArray(encoded.size + 2)
				ids[0] = srcLangId
				for (i in encoded.indices) {
					ids[i + 1] = spToNllbToken(encoded[i])
				}
				ids[ids.lastIndex] = eos
				ids
			}
			ModelFamily.MADLAD_LIKE -> {
				val ids = IntArray(encoded.size + 1)
				for (i in encoded.indices) {
					ids[i] = encoded[i]
				}
				ids[ids.lastIndex] = eos
				ids
			}
		}
		val attentionMask = IntArray(inputIds.size) { 1 }

		var encoderRun: OrtSession.Result? = null
		var cacheInitRun: OrtSession.Result? = null
			var prevDecoderRun: OrtSession.Result? = null
			var emptyPast: OnnxTensor? = null
			try {
				val encoderInput = mutableMapOf<String, OnnxTensor>()
				val encoderCreated = mutableListOf<OnnxTensor>()
				var encoderEmbedResult: OrtSession.Result? = null
				val inputIdsTensor = createInt64Tensor(inputIds.map { it.toLong() }.toLongArray()).also { encoderCreated += it }
				val attTensor = createInt64Tensor(attentionMask.map { it.toLong() }.toLongArray()).also { encoderCreated += it }
				if ("input_ids" in runtime.encoderSession.inputNames) encoderInput["input_ids"] = inputIdsTensor
				if ("attention_mask" in runtime.encoderSession.inputNames) encoderInput["attention_mask"] = attTensor
				if ("embed_matrix" in runtime.encoderSession.inputNames) {
					val embed = runNllbEmbed(runtime, inputIdsTensor, false, null) ?: return ""
					encoderInput["embed_matrix"] = embed.first
					encoderEmbedResult = embed.second
				}
				encoderRun = runtime.encoderSession.run(encoderInput)
				runCatching { encoderEmbedResult?.close() }
				encoderCreated.forEach { runCatching { it.close() } }
				val encoderHidden = encoderRun.get("last_hidden_state").orElse(null) as? OnnxTensor ?: return ""

			val cacheInput = mutableMapOf<String, OnnxTensor>()
			cacheInput["encoder_hidden_states"] = encoderHidden
			cacheInitRun = runtime.cacheInitSession.run(cacheInput)
			emptyPast = createEmptyPastTensor(runtime.hiddenSize)

			val outputTokens = mutableListOf<Int>()
			var step = 0
			var nextInputId = if (runtime.modelFamily == ModelFamily.NLLB) NLLB_DECODER_START_TOKEN else MADLAD_DECODER_START_TOKEN
			while (step < MAX_NEW_TOKENS) {
				val decInputTensors = mutableMapOf<String, OnnxTensor>()
				val created = mutableListOf<OnnxTensor>()
				val inIds = createInt64Tensor(longArrayOf(nextInputId.toLong())).also { created += it }
				val encMask = createInt64Tensor(attentionMask.map { it.toLong() }.toLongArray()).also { created += it }
				if ("input_ids" in runtime.decoderInputNames) decInputTensors["input_ids"] = inIds
				if ("encoder_attention_mask" in runtime.decoderInputNames) decInputTensors["encoder_attention_mask"] = encMask
				if ("encoder_hidden_states" in runtime.decoderInputNames) decInputTensors["encoder_hidden_states"] = encoderHidden
				if ("embed_matrix" in runtime.decoderInputNames) {
					val embed = runNllbEmbed(runtime, inIds, false, null) ?: return ""
					decInputTensors["embed_matrix"] = embed.first
					runCatching { embed.second.close() }
				}
				for (i in 0 until runtime.layers) {
					val dKeyName = "past_key_values.$i.decoder.key"
					val dValName = "past_key_values.$i.decoder.value"
					val eKeyName = "past_key_values.$i.encoder.key"
					val eValName = "past_key_values.$i.encoder.value"
					if (dKeyName in runtime.decoderInputNames) {
						decInputTensors[dKeyName] = if (step == 0) emptyPast!! else (prevDecoderRun?.get("present.$i.decoder.key")?.orElse(null) as? OnnxTensor ?: return "")
					}
					if (dValName in runtime.decoderInputNames) {
						decInputTensors[dValName] = if (step == 0) emptyPast!! else (prevDecoderRun?.get("present.$i.decoder.value")?.orElse(null) as? OnnxTensor ?: return "")
					}
					if (eKeyName in runtime.decoderInputNames) {
						decInputTensors[eKeyName] = cacheInitRun?.get("present.$i.encoder.key")?.orElse(null) as? OnnxTensor ?: return ""
					}
					if (eValName in runtime.decoderInputNames) {
						decInputTensors[eValName] = cacheInitRun?.get("present.$i.encoder.value")?.orElse(null) as? OnnxTensor ?: return ""
					}
				}
				val decoderRun = runtime.decoderSession.run(decInputTensors)
				created.forEach { runCatching { it.close() } }
				val token = selectNllbNextToken(runtime, decoderRun) ?: run {
					decoderRun.close()
					return ""
				}
				prevDecoderRun?.close()
				prevDecoderRun = decoderRun
				if (token == eos) {
					break
				}
				outputTokens += token
				nextInputId = if (runtime.modelFamily == ModelFamily.NLLB && step == 0) {
					tgtLangId ?: return ""
				} else {
					token
				}
				step++
			}
			if (outputTokens.isEmpty()) return ""
			val spIds = when (runtime.modelFamily) {
				ModelFamily.NLLB -> outputTokens.mapNotNull { nllbToSpToken(it) }.toIntArray()
				ModelFamily.MADLAD_LIKE -> outputTokens.filter { it in 4 until NLLB_DICTIONARY_LENGTH }.toIntArray()
			}
			if (spIds.isEmpty()) return ""
			return tokenizerLock.withLock { sp.decode(spIds).replace('▁', ' ').trim() }
		} finally {
			runCatching { prevDecoderRun?.close() }
			runCatching { cacheInitRun?.close() }
			runCatching { encoderRun?.close() }
			runCatching { emptyPast?.close() }
		}
	}

	private fun selectNllbNextToken(runtime: NllbRuntime, decoderRun: OrtSession.Result): Int? {
		val logitsTensor = if ("logits" in runtime.decoderOutputNames) {
			decoderRun.get("logits").orElse(null) as? OnnxTensor
		} else {
			val preLogits = decoderRun.get("pre_logits").orElse(null) as? OnnxTensor ?: return null
			val lm = runNllbEmbed(runtime, null, true, preLogits) ?: return null
			val result = lm.second
			val logits = result.get("logits").orElse(null) as? OnnxTensor ?: run {
				result.close()
				return null
			}
			val token = argmaxLastToken(logits.value)?.toInt()
			result.close()
			return token
		}
		val raw = logitsTensor?.value ?: return null
		return argmaxLastToken(raw)?.toInt()
	}

	private fun runNllbEmbed(
		runtime: NllbRuntime,
		inputIds: OnnxTensor?,
		useLmHead: Boolean,
		preLogits: OnnxTensor?,
	): Pair<OnnxTensor, OrtSession.Result>? {
		val inputs = mutableMapOf<String, OnnxTensor>()
		val created = mutableListOf<OnnxTensor>()
		try {
			if (inputIds != null && "input_ids" in runtime.embedLmSession.inputNames) {
				inputs["input_ids"] = inputIds
			} else if ("input_ids" in runtime.embedLmSession.inputNames) {
				createInt64Tensor(longArrayOf(0L, 2L)).also {
					created += it
					inputs["input_ids"] = it
				}
			}
			if ("pre_logits" in runtime.embedLmSession.inputNames) {
				if (preLogits != null) {
					inputs["pre_logits"] = preLogits
				} else {
					createFloatTensor(longArrayOf(0L, 1L, 1024L)).also {
						created += it
						inputs["pre_logits"] = it
					}
				}
			}
			if ("use_lm_head" in runtime.embedLmSession.inputNames) {
				createBooleanTensor(useLmHead).also {
					created += it
					inputs["use_lm_head"] = it
				}
			}
			val result = runtime.embedLmSession.run(inputs)
			val outputName = if (useLmHead) "logits" else "embed_matrix"
			val tensor = result.get(outputName).orElse(null) as? OnnxTensor ?: run {
				result.close()
				return null
			}
			return tensor to result
		} finally {
			created.forEach { runCatching { it.close() } }
		}
	}

	private fun createInt64Tensor(values: LongArray): OnnxTensor {
		return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), LongBuffer.wrap(values), longArrayOf(1L, values.size.toLong()))
	}

	private fun createQwenPositionIds(pastSequenceLength: Int, sequenceLength: Int): OnnxTensor {
		val values = LongArray(3 * sequenceLength)
		for (i in 0 until sequenceLength) {
			val pos = (pastSequenceLength + i).toLong()
			values[i] = pos
			values[sequenceLength + i] = pos
			values[sequenceLength * 2 + i] = pos
		}
		return OnnxTensor.createTensor(
			OrtEnvironment.getEnvironment(),
			LongBuffer.wrap(values),
			longArrayOf(3L, 1L, sequenceLength.toLong()),
		)
	}

	private fun createZeroTensorForInput(name: String, info: TensorInfo, pastSequenceLength: Int): OnnxTensor {
		val shape = info.shape.mapIndexed { index, dim ->
			when {
				dim > 0L -> dim
				index == 0 -> 1L
				name.startsWith("past_key_values.") && index == 2 -> pastSequenceLength.toLong()
				else -> 1L
			}
		}.toLongArray()
		val size = shape.fold(1L) { acc, dim -> acc * dim }.toInt().coerceAtLeast(0)
		return OnnxTensor.createTensor(
			OrtEnvironment.getEnvironment(),
			FloatBuffer.wrap(FloatArray(size)),
			shape,
		)
	}

	private fun mapPastInputToPresentOutput(name: String): String {
		return when {
			name.startsWith("past_key_values.") -> name.replaceFirst("past_key_values.", "present.")
			name.startsWith("past_conv.") -> name.replaceFirst("past_conv.", "present_conv.")
			name.startsWith("past_recurrent.") -> name.replaceFirst("past_recurrent.", "present_recurrent.")
			else -> name
		}
	}

	private fun createBooleanTensor(value: Boolean): OnnxTensor {
		return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), booleanArrayOf(value))
	}

	private fun createFloatTensor(shape: LongArray): OnnxTensor {
		val size = shape.fold(1L) { acc, dim -> acc * dim }.toInt().coerceAtLeast(0)
		return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(FloatArray(size) { 0f }), shape)
	}

	private fun createEmptyPastTensor(hiddenSize: Int): OnnxTensor {
		return OnnxTensor.createTensor(
			OrtEnvironment.getEnvironment(),
			FloatBuffer.wrap(FloatArray(0)),
			longArrayOf(1L, 16L, 0L, hiddenSize.toLong()),
		)
	}

	private fun argmaxLastToken(value: Any): Long? {
		return when (value) {
			is Array<*> -> argmaxFromArray(value)
			else -> null
		}
	}

	private fun argmaxFromArray(value: Array<*>): Long? {
		if (value.isEmpty()) return null
		val first = value.firstOrNull() ?: return null
		if (first is FloatArray) return argmax(first)
		if (first is Array<*>) {
			val lastSeq = first.lastOrNull()
			if (lastSeq is FloatArray) return argmax(lastSeq)
			if (lastSeq is Array<*>) {
				val last = lastSeq.lastOrNull()
				if (last is FloatArray) return argmax(last)
			}
		}
		return null
	}

	private fun argmax(values: FloatArray): Long {
		var maxIndex = 0
		var maxValue = Float.NEGATIVE_INFINITY
		for (i in values.indices) {
			if (values[i] > maxValue) {
				maxValue = values[i]
				maxIndex = i
			}
		}
		return maxIndex.toLong()
	}

	private fun findTokenizerPath(modelDir: File): File? {
		if (!modelDir.exists()) return null
		val tokenizerJson = modelDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("tokenizer.json", ignoreCase = true) }
		if (tokenizerJson != null) return tokenizerJson.parentFile
		val vocab = modelDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("vocab.json", ignoreCase = true) }
		val merges = modelDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("merges.txt", ignoreCase = true) }
		return if (vocab != null && merges != null && vocab.parentFile == merges.parentFile) vocab.parentFile else null
	}

	private fun findDecoderOnnxPath(modelDir: File): File? {
		if (!modelDir.exists()) return null
		return modelDir.walkTopDown()
			.filter { it.isFile && it.extension.equals("onnx", ignoreCase = true) }
			.sortedWith(compareByDescending<File> { it.name.contains("decoder", true) }.thenByDescending { it.length() })
			.firstOrNull()
	}

	private fun findQwenDecoderOnnxPath(modelDir: File): File? {
		if (!modelDir.exists()) return null
		return modelDir.walkTopDown()
			.filter { it.isFile && it.extension.equals("onnx", true) && it.name.contains("decoder_model_merged", true) }
			.sortedWith(compareByDescending<File> { it.name.contains("_q4", true) }.thenByDescending { it.length() })
			.firstOrNull()
	}

	private fun findQwenEmbedOnnxPath(modelDir: File): File? {
		if (!modelDir.exists()) return null
		return modelDir.walkTopDown()
			.filter { it.isFile && it.extension.equals("onnx", true) && it.name.contains("embed_tokens", true) }
			.sortedWith(compareByDescending<File> { it.name.contains("_q4", true) }.thenByDescending { it.length() })
			.firstOrNull()
	}

	private fun loadStopTokens(modelDir: File): Set<Long> {
		val defaults = mutableSetOf(
			0L, 1L, 2L,
			HY_EOT_TOKEN_ID, HY_EOS_TOKEN_ID,
			QWEN_EOS_TOKEN_ID, QWEN_ALT_EOS_TOKEN_ID,
			QWEN2_EOS_TOKEN_ID, QWEN2_ALT_EOS_TOKEN_ID,
		)
		val generationConfig = listOf(
			File(modelDir, "generation_config.json"),
			File(modelDir, "genai_config.json"),
		).firstOrNull { it.isFile } ?: return defaults
		return runCatching {
			val root = JSONObject(generationConfig.readText())
			val model = root.optJSONObject("model")
			val eos = if (root.has("eos_token_id")) {
				root.opt("eos_token_id")
			} else {
				model?.opt("eos_token_id")
			}
			when (eos) {
				is Number -> defaults += eos.toLong()
				is JSONArray -> {
					for (i in 0 until eos.length()) {
						val value = eos.opt(i)
						if (value is Number) defaults += value.toLong()
					}
				}
			}
			defaults
		}.getOrDefault(defaults)
	}

	private fun toNllbCode(lang: String): String? {
		val raw = lang.trim().lowercase()
		val key = raw.substringBefore('-')
		return BCP47_TO_NLLB[key]
	}

	private fun languageId(nllbCode: String): Int? {
		val index = NLLB_LANGUAGE_LIST.indexOf(nllbCode)
		if (index < 0) return null
		return NLLB_DICTIONARY_LENGTH + index + 1
	}

	private fun spToNllbToken(spId: Int): Int {
		val shifted = spId + 1
		return when (shifted) {
			1 -> 3
			2 -> 0
			3 -> 2
			else -> shifted
		}
	}

	private fun nllbToSpToken(nllbId: Int): Int? {
		if (nllbId >= NLLB_DICTIONARY_LENGTH || nllbId <= 3) return null
		return nllbId - 1
	}

	companion object {
		private const val LOG_TAG = "ReaderTranslate"
		private const val MAX_NEW_TOKENS = 200
		private const val MAX_QWEN_NEW_TOKENS = 48
		private const val MAX_QWEN_RETRY_NEW_TOKENS = 32
		private const val MAX_QWEN_BATCH_NEW_TOKENS = 224
		private const val MAX_PARALLEL_TRANSLATIONS = 2
		private const val NLLB_DICTIONARY_LENGTH = 256000
			private const val NLLB_DECODER_START_TOKEN = 2
			private const val MADLAD_DECODER_START_TOKEN = 0
			private const val HY_EOT_TOKEN_ID = 120008L
			private const val HY_EOS_TOKEN_ID = 120020L
			private const val QWEN_EOS_TOKEN_ID = 248044L
			private const val QWEN_ALT_EOS_TOKEN_ID = 248046L
			private const val QWEN2_EOS_TOKEN_ID = 151643L
			private const val QWEN2_ALT_EOS_TOKEN_ID = 151645L
		private val STOP_TOKEN_IDS = setOf(0L, 1L, 2L)
		private val COMPLETED_TRANSLATION_JSON_REGEX =
			Regex("""\{\s*"translation"\s*:\s*"((?:\\.|[^"\\])*)"\s*\}""", RegexOption.DOT_MATCHES_ALL)

		private fun oneLine(text: String, limit: Int = 140): String {
			if (text.isBlank()) return ""
			return text.replace('\n', ' ').replace('\r', ' ').trim().let {
				if (it.length <= limit) it else it.take(limit) + "..."
			}
		}

		private val BCP47_TO_NLLB = mapOf(
			"ar" to "arb_Arab", "bg" to "bul_Cyrl", "ca" to "cat_Latn", "zh" to "zho_Hans",
			"cs" to "ces_Latn", "da" to "dan_Latn", "de" to "deu_Latn", "el" to "ell_Grek",
			"en" to "eng_Latn", "es" to "spa_Latn", "fi" to "fin_Latn", "fr" to "fra_Latn",
			"gl" to "glg_Latn", "hr" to "hrv_Latn", "it" to "ita_Latn", "ja" to "jpn_Jpan",
			"ko" to "kor_Hang", "mk" to "mkd_Cyrl", "nl" to "nld_Latn", "pl" to "pol_Latn",
			"pt" to "por_Latn", "ro" to "ron_Latn", "ru" to "rus_Cyrl", "sk" to "slk_Latn",
			"sv" to "swe_Latn", "tl" to "tgl_Latn", "tr" to "tur_Latn", "uk" to "ukr_Cyrl",
			"vi" to "vie_Latn",
		)

		private val NLLB_LANGUAGE_LIST = listOf(
			"ace_Arab", "ace_Latn", "acm_Arab", "acq_Arab", "aeb_Arab", "afr_Latn", "ajp_Arab", "aka_Latn", "amh_Ethi", "apc_Arab", "arb_Arab", "ars_Arab", "ary_Arab", "arz_Arab", "asm_Beng", "ast_Latn", "awa_Deva", "ayr_Latn", "azb_Arab", "azj_Latn", "bak_Cyrl", "bam_Latn", "ban_Latn", "bel_Cyrl", "bem_Latn", "ben_Beng", "bho_Deva", "bjn_Arab", "bjn_Latn", "bod_Tibt", "bos_Latn", "bug_Latn", "bul_Cyrl", "cat_Latn", "ceb_Latn", "ces_Latn", "cjk_Latn", "ckb_Arab", "crh_Latn", "cym_Latn", "dan_Latn", "deu_Latn", "dik_Latn", "dyu_Latn", "dzo_Tibt", "ell_Grek", "eng_Latn", "epo_Latn", "est_Latn", "eus_Latn", "ewe_Latn", "fao_Latn", "pes_Arab", "fij_Latn", "fin_Latn", "fon_Latn", "fra_Latn", "fur_Latn", "fuv_Latn", "gla_Latn", "gle_Latn", "glg_Latn", "grn_Latn", "guj_Gujr", "hat_Latn", "hau_Latn", "heb_Hebr", "hin_Deva", "hne_Deva", "hrv_Latn", "hun_Latn", "hye_Armn", "ibo_Latn", "ilo_Latn", "ind_Latn", "isl_Latn", "ita_Latn", "jav_Latn", "jpn_Jpan", "kab_Latn", "kac_Latn", "kam_Latn", "kan_Knda", "kas_Arab", "kas_Deva", "kat_Geor", "knc_Arab", "knc_Latn", "kaz_Cyrl", "kbp_Latn", "kea_Latn", "khm_Khmr", "kik_Latn", "kin_Latn", "kir_Cyrl", "kmb_Latn", "kon_Latn", "kor_Hang", "kmr_Latn", "lao_Laoo", "lvs_Latn", "lij_Latn", "lim_Latn", "lin_Latn", "lit_Latn", "lmo_Latn", "ltg_Latn", "ltz_Latn", "lua_Latn", "lug_Latn", "luo_Latn", "lus_Latn", "mag_Deva", "mai_Deva", "mal_Mlym", "mar_Deva", "min_Latn", "mkd_Cyrl", "plt_Latn", "mlt_Latn", "mni_Beng", "khk_Cyrl", "mos_Latn", "mri_Latn", "zsm_Latn", "mya_Mymr", "nld_Latn", "nno_Latn", "nob_Latn", "npi_Deva", "nso_Latn", "nus_Latn", "nya_Latn", "oci_Latn", "gaz_Latn", "ory_Orya", "pag_Latn", "pan_Guru", "pap_Latn", "pol_Latn", "por_Latn", "prs_Arab", "pbt_Arab", "quy_Latn", "ron_Latn", "run_Latn", "rus_Cyrl", "sag_Latn", "san_Deva", "sat_Beng", "scn_Latn", "shn_Mymr", "sin_Sinh", "slk_Latn", "slv_Latn", "smo_Latn", "sna_Latn", "snd_Arab", "som_Latn", "sot_Latn", "spa_Latn", "als_Latn", "srd_Latn", "srp_Cyrl", "ssw_Latn", "sun_Latn", "swe_Latn", "swh_Latn", "szl_Latn", "tam_Taml", "tat_Cyrl", "tel_Telu", "tgk_Cyrl", "tgl_Latn", "tha_Thai", "tir_Ethi", "taq_Latn", "taq_Tfng", "tpi_Latn", "tsn_Latn", "tso_Latn", "tuk_Latn", "tum_Latn", "tur_Latn", "twi_Latn", "tzm_Tfng", "uig_Arab", "ukr_Cyrl", "umb_Latn", "urd_Arab", "uzn_Latn", "vec_Latn", "vie_Latn", "war_Latn", "wol_Latn", "xho_Latn", "ydd_Hebr", "yor_Latn", "yue_Hant", "zho_Hans", "zho_Hant", "zul_Latn"
		)
	}
}
