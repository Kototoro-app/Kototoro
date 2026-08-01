package org.skepsun.kototoro.reader.translate.domain

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.core.util.ext.awaitCancellable
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.reader.translate.data.ReaderTranslationTextCache

internal class ReaderTranslationCoordinator(
	private val settings: AppSettings,
	private val textCache: ReaderTranslationTextCache,
	private val onnxTranslationEngine: OnnxReaderTranslationEngine,
	private val okHttpClient: OkHttpClient,
	private val jsonMediaType: MediaType,
	private val defaultOpenAiModel: String,
	private val openAiTranslationSystemPrompt: String,
	private val maxOpenAiBatchSize: Int,
	private val thinkTagRegex: Regex,
	private val buildTextCacheKey: (String, String, String) -> String,
	private val sanitizeTranslation: (String) -> String,
	private val isAcceptableTranslation: (String, String, String, String) -> Boolean,
	private val log: (() -> String) -> Unit,
	private val oneLine: (String, Int) -> String,
) {

	suspend fun translateBlocksCached(
		texts: List<String>,
		sourceLang: String,
		targetLang: String,
	): Map<String, String> {
		if (texts.isEmpty()) return emptyMap()
		val uniqueTexts = texts.distinct()
		val translated = LinkedHashMap<String, String>(uniqueTexts.size)
		val misses = ArrayList<String>(uniqueTexts.size)

		for (text in uniqueTexts) {
			val cacheKey = buildTextCacheKey(text, sourceLang, targetLang)
			val cached = textCache[cacheKey]
			if (!cached.isNullOrBlank()) {
				val sanitized = sanitizeTranslation(cached)
				if (sanitized.isNotBlank()) {
					translated[text] = sanitized
					if (sanitized != cached) {
						textCache[cacheKey] = sanitized
					}
					log { "translate cache hit src=${oneLine(text, 140)} out=${oneLine(sanitized, 140)}" }
				} else {
					textCache[cacheKey] = ""
					misses.add(text)
					log { "translate cache rejected src=${oneLine(text, 140)} out=${oneLine(sanitized, 140)}" }
				}
			} else {
				misses.add(text)
			}
		}
		if (misses.isEmpty()) return translated

		val resolvedSourceLang = if (sourceLang.trim().lowercase() == "auto") {
			val sampleText = misses.filter { it.isNotBlank() }.joinToString("\n").take(500)
			if (sampleText.isNotBlank()) {
				detectLanguage(sampleText) ?: "en"
			} else {
				"en"
			}
		} else {
			sourceLang
		}
		android.util.Log.d("ReaderTranslationCoordinator", "translateBlocksCached: resolved source language to $resolvedSourceLang")

		val mode = settings.readerTranslationMode
		val onnxModelId = settings.readerTranslationOnnxModelId.trim()
		android.util.Log.d("ReaderTranslationCoordinator", "translateBlocksCached: mode=$mode, onnxModelId='$onnxModelId', misses.size=${misses.size}")
		if (mode != ReaderTranslationMode.API_ONLY && onnxModelId.isNotBlank()) {
			val needOnnx = misses.filter { translated[it].isNullOrBlank() }
			android.util.Log.d("ReaderTranslationCoordinator", "translateBlocksCached: calling ONNX for ${needOnnx.size} texts")
			if (needOnnx.isNotEmpty()) {
				val onnxMap = runCatching {
					onnxTranslationEngine.translateBatch(needOnnx, resolvedSourceLang, targetLang, onnxModelId)
				}.onFailure {
					if (it is kotlinx.coroutines.CancellationException) throw it
					it.printStackTraceDebug()
					log { "translate onnx failed: ${it.message.orEmpty()}" }
					android.util.Log.e("ReaderTranslationCoordinator", "translateBlocksCached: ONNX failed: ${it.message}", it)
				}.getOrDefault(emptyMap())
				android.util.Log.d("ReaderTranslationCoordinator", "translateBlocksCached: ONNX returned ${onnxMap.size} results")
				for (text in needOnnx) {
					val onnxText = onnxMap[text]?.trim().orEmpty()
					android.util.Log.d("ReaderTranslationCoordinator", "translateBlocksCached: ONNX result for '${text.take(50)}...': '${onnxText.take(50)}...' (length=${onnxText.length})")
					if (onnxText.isNotBlank()) {
						val sanitized = sanitizeTranslation(onnxText)
						if (isAcceptableTranslation(text, sanitized, sourceLang, targetLang)) {
							translated[text] = sanitized
							textCache[buildTextCacheKey(text, sourceLang, targetLang)] = sanitized
							log { "translate onnx hit src=${oneLine(text, 140)} out=${oneLine(sanitized, 140)}" }
							android.util.Log.d("ReaderTranslationCoordinator", "translateBlocksCached: ONNX accepted")
						} else {
							log { "translate onnx rejected src=${oneLine(text, 140)} out=${oneLine(sanitized, 140)}" }
							android.util.Log.w("ReaderTranslationCoordinator", "translateBlocksCached: ONNX rejected by isAcceptableTranslation")
						}
					} else {
						android.util.Log.w("ReaderTranslationCoordinator", "translateBlocksCached: ONNX returned blank")
					}
				}
			}
		}

		if (mode != ReaderTranslationMode.API_ONLY) {
			val needLocal = misses.filter { translated[it].isNullOrBlank() }
			if (needLocal.isNotEmpty()) {  // 只有还有未翻译的文本时才用 ML Kit
				log { "translate local requested size=${needLocal.size}" }
				var localResults = runCatching {
					log { "translate local batch calling translateLocalBatch..." }
					translateLocalBatch(needLocal, resolvedSourceLang, targetLang)
				}.onFailure {
					if (it is kotlinx.coroutines.CancellationException) throw it
					it.printStackTraceDebug()
					log { "translate local batch failed: ${it.message.orEmpty()}" }
				}.getOrDefault(emptyMap())
				log { "translate local batch returned ${localResults.size} results" }
				if (needLocal.isNotEmpty() && localResults.values.none { it.isNotBlank() }) {
					log { "translate local batch empty, fallback to per-item translation" }
					localResults = coroutineScope {
						needLocal.map { text ->
							async {
								val local = runCatching {
									translateLocal(text, resolvedSourceLang, targetLang)
								}.onFailure {
									if (it is kotlinx.coroutines.CancellationException) throw it
									log { "translate local fallback failed src=${oneLine(text, 140)} err=${it.message.orEmpty()}" }
								}.getOrDefault("").trim()
								text to local
							}
						}.awaitAll().toMap()
					}
				}
				for ((text, local) in localResults) {
					val raw = local.trim()
					if (raw.isNotBlank()) {
						val sanitized = sanitizeTranslation(raw)
						if (isAcceptableTranslation(text, sanitized, sourceLang, targetLang)) {
							translated[text] = sanitized
							textCache[buildTextCacheKey(text, sourceLang, targetLang)] = sanitized
							log { "translate local hit src=${oneLine(text, 140)} out=${oneLine(sanitized, 140)}" }
						} else {
							log { "translate local rejected src=${oneLine(text, 140)} out=${oneLine(sanitized, 140)}" }
						}
					}
				}
			} else {
				log { "translate local skipped, all texts already translated by ONNX" }
			}
		}

		if (mode == ReaderTranslationMode.LOCAL_ONLY) {
			log { "translate mode=LOCAL_ONLY, skip api fallback" }
			for (text in uniqueTexts) {
				translated.putIfAbsent(text, "")
			}
			return translated
		}

		if (mode != ReaderTranslationMode.LOCAL_ONLY) {
			val needApi = misses.filter { translated[it].isNullOrBlank() }
			if (needApi.isNotEmpty()) {
				val apiMap = translateBatchByApi(needApi, resolvedSourceLang, targetLang)
				for (text in needApi) {
					val apiText = apiMap[text]?.trim().orEmpty()
					if (apiText.isNotBlank()) {
						val sanitized = sanitizeTranslation(apiText)
						if (sanitized.isNotBlank()) {
							translated[text] = sanitized
							textCache[buildTextCacheKey(text, sourceLang, targetLang)] = sanitized
							log { "translate api hit src=${oneLine(text, 140)} out=${oneLine(sanitized, 140)}" }
						} else {
							log { "translate api rejected src=${oneLine(text, 140)} out=${oneLine(sanitized, 140)}" }
						}
					}
				}
			}
		}

		for (text in uniqueTexts) {
			translated.putIfAbsent(text, "")
		}
		return translated
	}

	private suspend fun translateBatchByApi(
		texts: List<String>,
		sourceLang: String,
		targetLang: String,
	): Map<String, String> {
		val endpoint = resolveTranslationApiEndpoint()
		if (endpoint.isBlank() || texts.isEmpty()) {
			return texts.associateWith { "" }
		}

		return if (isOpenAiCompatibleChatCompletionsEndpoint(endpoint)) {
			translateBatchByOpenAi(texts, sourceLang, targetLang)
		} else {
			val map = LinkedHashMap<String, String>(texts.size)
			for (text in texts) {
				map[text] = translateByApi(text, sourceLang, targetLang)
			}
			map
		}
	}

	private suspend fun translateBatchByOpenAi(
		texts: List<String>,
		sourceLang: String,
		targetLang: String,
	): Map<String, String> {
		if (texts.isEmpty()) return emptyMap()
		val mapped = LinkedHashMap<String, String>(texts.size)
		val batches = buildOpenAiMicroBatches(texts)
		log { "openai batch requests count=${batches.size} texts=${texts.size}" }
		for (batch in batches) {
			if (batch.size == 1) {
				val text = batch.first()
				mapped[text] = requestOpenAiSingle(text, sourceLang, targetLang)
				continue
			}
			val batchMap = requestOpenAiBatch(batch, sourceLang, targetLang)
			if (batchMap.isEmpty()) {
				batch.forEach { text ->
					mapped[text] = requestOpenAiSingle(text, sourceLang, targetLang)
				}
				continue
			}
			for (text in batch) {
				mapped[text] = batchMap[text].orEmpty()
			}
		}
		return mapped
	}

	private suspend fun requestOpenAiBatch(
		texts: List<String>,
		sourceLang: String,
		targetLang: String,
	): Map<String, String> {
		if (texts.isEmpty()) return emptyMap()
		val endpoint = resolveTranslationApiEndpoint()
		val apiKey = settings.readerTranslationApiKey.trim()
		val model = settings.readerTranslationApiModel.trim().ifBlank { defaultOpenAiModel }
		val userPrompt = buildString {
			appendLine("Translate manga OCR text from $sourceLang to $targetLang.")
			appendLine("Return strict JSON only.")
			appendLine("Use this array format:")
			appendLine("""[{"id":1,"translation":"..."},{"id":2,"translation":"..."}]""")
			appendLine("Keep ids unchanged. If unreadable or uncertain, use empty translation.")
			appendLine()
			appendLine("Texts:")
			texts.forEachIndexed { index, text ->
				appendLine("${index + 1}. $text")
			}
		}
		val payload = JSONObject().apply {
			put("model", model)
			put("temperature", 0)
			if (isDeepSeekEndpoint(endpoint)) {
				put("thinking", JSONObject().put("type", "disabled"))
			}
			put(
				"messages",
				JSONArray()
					.put(JSONObject().put("role", "system").put("content", openAiTranslationSystemPrompt))
					.put(JSONObject().put("role", "user").put("content", userPrompt))
			)
		}
		return runCatching {
			withContext(Dispatchers.IO) {
				val requestBuilder = Request.Builder()
					.url(endpoint)
					.post(payload.toString().toRequestBody(jsonMediaType))
					.header("Content-Type", "application/json")
				TranslationApiProviderCatalog.applyAuthentication(
					requestBuilder,
					settings.readerTranslationApiProviderPreset,
					apiKey,
				)
				applyCustomHeaders(requestBuilder)
				val response = okHttpClient.newCall(requestBuilder.build()).await()
				response.use { resp ->
					val rawBody = resp.body.readJsonTextUtf8()
					if (!resp.isSuccessful) {
						log { "openai batch request failed code=${resp.code} msg=${resp.message} body=${oneLine(rawBody, 300)}" }
						return@use emptyMap()
					}
					if (rawBody.isBlank()) return@use emptyMap()
					val json = runCatching { JSONObject(rawBody) }.getOrNull() ?: return@use emptyMap()
					val content = extractOpenAiMessageContent(json).orEmpty()
					if (content.isBlank()) return@use emptyMap()
					log { "openai batch raw reply=${oneLine(content, 400)}" }
					val parsed = parseBatchTranslationJson(content, texts.size)
					if (parsed.isEmpty()) return@use emptyMap()
					LinkedHashMap<String, String>(texts.size).apply {
						texts.forEachIndexed { index, text ->
							put(text, sanitizeTranslation(parsed[index + 1].orEmpty()))
						}
					}
				}
			}
		}.onFailure {
			if (it is kotlinx.coroutines.CancellationException) throw it
			log { "openai batch request failed size=${texts.size} err=${it.message.orEmpty()}" }
		}.getOrDefault(emptyMap())
	}

	private suspend fun requestOpenAiSingle(
		text: String,
		sourceLang: String,
		targetLang: String,
	): String {
		if (text.isBlank()) return ""
		val endpoint = resolveTranslationApiEndpoint()
		val apiKey = settings.readerTranslationApiKey.trim()
		val model = settings.readerTranslationApiModel.trim().ifBlank { defaultOpenAiModel }
		val userPrompt = buildString {
			appendLine("Translate manga OCR text from $sourceLang to $targetLang.")
			appendLine("Only output the translation itself.")
			appendLine("If unreadable or uncertain, output nothing.")
			appendLine("Keep short screams natural.")
			append(text)
		}
		val payload = JSONObject().apply {
			put("model", model)
			put("temperature", 0)
			if (isDeepSeekEndpoint(endpoint)) {
				put("thinking", JSONObject().put("type", "disabled"))
			}
			put(
				"messages",
				JSONArray()
					.put(JSONObject().put("role", "system").put("content", openAiTranslationSystemPrompt))
					.put(JSONObject().put("role", "user").put("content", userPrompt))
			)
		}

		return runCatching {
			withContext(Dispatchers.IO) {
				val requestBuilder = Request.Builder()
					.url(endpoint)
					.post(payload.toString().toRequestBody(jsonMediaType))
					.header("Content-Type", "application/json")
				TranslationApiProviderCatalog.applyAuthentication(
					requestBuilder,
					settings.readerTranslationApiProviderPreset,
					apiKey,
				)
				applyCustomHeaders(requestBuilder)
				val response = okHttpClient.newCall(requestBuilder.build()).await()
				response.use { resp ->
					val rawBody = resp.body.readJsonTextUtf8()
					if (!resp.isSuccessful) {
						log { "openai request failed code=${resp.code} msg=${resp.message} body=${oneLine(rawBody, 300)}" }
						return@use ""
					}
					if (rawBody.isBlank()) return@use ""
					val json = runCatching { JSONObject(rawBody) }.getOrNull() ?: return@use ""
					val content = extractOpenAiMessageContent(json).orEmpty()
					if (content.isBlank()) return@use ""
					log { "openai raw reply=${oneLine(content, 400)}" }
					sanitizeTranslation(content)
				}
			}
		}.onFailure {
			if (it is kotlinx.coroutines.CancellationException) throw it
			log { "openai single request failed src=${oneLine(text, 140)} err=${it.message.orEmpty()}" }
		}.getOrDefault("")
	}

	private suspend fun translateLocal(text: String, sourceLang: String, targetLang: String): String {
		// 如果源语言是 auto，先检测
		val resolvedSourceLang = if (sourceLang.trim().lowercase() == "auto") {
			detectLanguage(text) ?: "en"
		} else {
			sourceLang
		}

		val source = resolveMlKitLanguage(resolvedSourceLang)
		val target = resolveMlKitLanguage(targetLang)
		if (source == null || target == null) {
			log { "translate local skip unsupported source=$resolvedSourceLang target=$targetLang" }
			return ""  // 返回空字符串，不返回原文
		}
		val options = TranslatorOptions.Builder()
			.setSourceLanguage(source)
			.setTargetLanguage(target)
			.build()
		val translator = Translation.getClient(options)
		return try {
			withTimeout(60_000) {
				translator.downloadModelIfNeeded().awaitCancellable()
			}
			withTimeout(15_000) {
				translator.translate(text).awaitCancellable()
			}
		} finally {
			translator.close()
		}
	}

	private suspend fun translateLocalBatch(
		texts: List<String>,
		sourceLang: String,
		targetLang: String,
	): Map<String, String> {
		android.util.Log.d("ReaderTranslationCoordinator", "translateLocalBatch entered: texts.size=${texts.size}, source=$sourceLang, target=$targetLang")
		if (texts.isEmpty()) return emptyMap()

		// 如果源语言是 auto，先检测第一段文本的语言
		val resolvedSourceLang = if (sourceLang.trim().lowercase() == "auto") {
			android.util.Log.d("ReaderTranslationCoordinator", "translateLocalBatch detecting language...")
			val sampleText = texts.filter { it.isNotBlank() }.joinToString("\n").take(500)
			if (sampleText.isNotBlank()) {
				detectLanguage(sampleText) ?: "en"
			} else {
				"en"
			}
		} else {
			sourceLang
		}
		android.util.Log.d("ReaderTranslationCoordinator", "translateLocalBatch resolved source language: $resolvedSourceLang")

		val source = resolveMlKitLanguage(resolvedSourceLang)
		val target = resolveMlKitLanguage(targetLang)
		android.util.Log.d("ReaderTranslationCoordinator", "translateLocalBatch ML Kit languages: source=$source, target=$target")
		if (source == null || target == null) {
			log { "translate local batch skip unsupported source=$resolvedSourceLang target=$targetLang size=${texts.size}" }
			android.util.Log.w("ReaderTranslationCoordinator", "translateLocalBatch unsupported languages, returning empty")
			return texts.associateWith { "" }
		}
		android.util.Log.d("ReaderTranslationCoordinator", "translateLocalBatch creating translator...")
		val options = TranslatorOptions.Builder()
			.setSourceLanguage(source)
			.setTargetLanguage(target)
			.build()
		val translator = Translation.getClient(options)
		return try {
			log { "translate local batch start size=${texts.size} source=$resolvedSourceLang target=$targetLang" }

			android.util.Log.d("ReaderTranslationCoordinator", "translateLocalBatch downloading model if needed...")
			val downloadTask = translator.downloadModelIfNeeded()
			withTimeout(60_000) {
				downloadTask.awaitCancellable()
			}
			android.util.Log.d("ReaderTranslationCoordinator", "translateLocalBatch model ready, starting translation...")

			val results = LinkedHashMap<String, String>(texts.size)
			for ((index, text) in texts.withIndex()) {
				val out = runCatching {
					withTimeout(15_000) {
						translator.translate(text).awaitCancellable()
					}
				}.onFailure {
					if (it is kotlinx.coroutines.CancellationException) throw it
					log { "translate local item failed src=${oneLine(text, 140)} err=${it.message.orEmpty()}" }
					android.util.Log.e("ReaderTranslationCoordinator", "translateLocalBatch item $index failed: ${it.message}")
				}.getOrDefault("").trim()
				results[text] = out
				if (index == 0) {
					android.util.Log.d("ReaderTranslationCoordinator", "translateLocalBatch first item done, result length=${out.length}")
				}
			}
			android.util.Log.d("ReaderTranslationCoordinator", "translateLocalBatch done translated=${results.count { it.value.isNotBlank() }}/${texts.size}")
			log { "translate local batch done translated=${results.count { it.value.isNotBlank() }}/${texts.size}" }
			results
		} catch (e: Exception) {
			android.util.Log.e("ReaderTranslationCoordinator", "translateLocalBatch failed: ${e.javaClass.simpleName}: ${e.message}", e)
			throw e
		} finally {
			translator.close()
		}
	}

	/**
	 * 使用 ML Kit Language Identification 检测文本语言
	 * 返回 BCP-47 语言标签（如 "en", "zh", "ja"），失败返回 null
	 */
	private suspend fun detectLanguage(text: String): String? {
		if (text.isBlank()) return null
		val languageIdentifier = LanguageIdentification.getClient()
		return try {
			val result = withTimeout(15_000) {
				languageIdentifier.identifyLanguage(text).awaitCancellable()
			}
			if (result == "und") {
				log { "language detection undetermined for text=${oneLine(text, 100)}" }
				null
			} else {
				log { "language detected: $result for text=${oneLine(text, 100)}" }
				result
			}
		} catch (e: Exception) {
			log { "language detection failed: ${e.message.orEmpty()}" }
			null
		} finally {
			languageIdentifier.close()
		}
	}

	private fun resolveMlKitLanguage(languageTag: String): String? {
		val normalized = languageTag
			.trim()
			.lowercase()
			.replace('_', '-')
			.substringBefore('-')
		return TranslateLanguage.fromLanguageTag(normalized) ?: when (normalized) {
			"ar" -> TranslateLanguage.ARABIC
			"bg" -> TranslateLanguage.BULGARIAN
			"bn" -> TranslateLanguage.BENGALI
			"ca" -> TranslateLanguage.CATALAN
			"cs" -> TranslateLanguage.CZECH
			"da" -> TranslateLanguage.DANISH
			"de" -> TranslateLanguage.GERMAN
			"el" -> TranslateLanguage.GREEK
			"en" -> TranslateLanguage.ENGLISH
			"es" -> TranslateLanguage.SPANISH
			"fi" -> TranslateLanguage.FINNISH
			"fr" -> TranslateLanguage.FRENCH
			"hi" -> TranslateLanguage.HINDI
			"hr" -> TranslateLanguage.CROATIAN
			"it" -> TranslateLanguage.ITALIAN
			"ja" -> TranslateLanguage.JAPANESE
			"ko" -> TranslateLanguage.KOREAN
			"th" -> TranslateLanguage.THAI
			"nl" -> TranslateLanguage.DUTCH
			"pl" -> TranslateLanguage.POLISH
			"pt" -> TranslateLanguage.PORTUGUESE
			"ro" -> TranslateLanguage.ROMANIAN
			"ru" -> TranslateLanguage.RUSSIAN
			"sk" -> TranslateLanguage.SLOVAK
			"sv" -> TranslateLanguage.SWEDISH
			"tl" -> TranslateLanguage.TAGALOG
			"tr" -> TranslateLanguage.TURKISH
			"uk" -> TranslateLanguage.UKRAINIAN
			"vi" -> TranslateLanguage.VIETNAMESE
			"zh" -> TranslateLanguage.CHINESE
			else -> null
		}
	}

	private suspend fun translateByApi(text: String, sourceLang: String, targetLang: String): String {
		val endpoint = resolveTranslationApiEndpoint()
		if (endpoint.isBlank()) {
			return ""
		}
		val payload = JSONObject().apply {
			put("q", text)
			put("source", sourceLang)
			put("target", targetLang)
			put("format", "text")
		}
		val requestBuilder = Request.Builder()
			.url(endpoint)
			.post(payload.toString().toRequestBody(jsonMediaType))
		val key = settings.readerTranslationApiKey.trim()
		TranslationApiProviderCatalog.applyAuthentication(
			requestBuilder,
			settings.readerTranslationApiProviderPreset,
			key,
		)
		applyCustomHeaders(requestBuilder)
		val request = requestBuilder.build()
		val response = okHttpClient.newCall(request).await()
		response.use { resp ->
			if (!resp.isSuccessful) {
				log { "api translate failed code=${resp.code} msg=${resp.message}" }
				return ""
			}
			val body = resp.body.readJsonTextUtf8()
			val sanitized = sanitizeTranslation(body)
			log { "api raw reply=${oneLine(body, 300)} sanitized=${oneLine(sanitized, 140)} src=${oneLine(text, 140)}" }
			if (sanitized.isNotBlank()) {
				val cacheKey = buildTextCacheKey(text, sourceLang, targetLang)
				textCache[cacheKey] = sanitized
			}
			return sanitized
		}
	}

	private fun extractOpenAiMessageContent(responseJson: JSONObject): String? {
		val choices = responseJson.optJSONArray("choices") ?: return null
		if (choices.length() == 0) return null
		val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
		val content = message.opt("content")
		return when (content) {
			is String -> content
			is JSONArray -> {
				buildString {
					for (i in 0 until content.length()) {
						val chunk = content.optJSONObject(i) ?: continue
						append(chunk.optString("text"))
					}
				}
			}
			else -> null
		}
	}

	private fun parseBatchTranslationJson(content: String, expectedSize: Int): Map<Int, String> {
		val clean = normalizeJsonLikeContent(stripThinkContent(content).trim())
		if (clean.isBlank()) return emptyMap()

		fun validate(map: Map<Int, String>): Map<Int, String> {
			if (map.isEmpty() || map.size > expectedSize) {
				log { "openai parsed invalid mapSize=${map.size} expected<=$expectedSize content=${oneLine(clean, 400)}" }
				return emptyMap()
			}
			log { "openai parsed items=${map.size}" }
			return map
		}

		fun parseStandardJson(raw: String): Map<Int, String> {
			val map = LinkedHashMap<Int, String>(expectedSize)
			if (raw.startsWith("[")) {
				val arr = JSONArray(raw)
				for (i in 0 until arr.length()) {
					val obj = arr.optJSONObject(i) ?: continue
					val id = obj.optInt("id", i + 1)
					val translation = pickTranslationField(obj)
					if (id > 0 && translation.isNotBlank()) {
						map[id] = translation
					}
				}
			} else {
				val json = JSONObject(raw)
				val items = json.optJSONArray("items")
					?: json.optJSONArray("translations")
					?: json.optJSONArray("data")
				if (items != null) {
					for (i in 0 until items.length()) {
						val obj = items.optJSONObject(i) ?: continue
						val id = obj.optInt("id", i + 1)
						val translation = pickTranslationField(obj)
						if (id > 0 && translation.isNotBlank()) {
							map[id] = translation
						}
					}
				}
			}
			return map
		}

		return runCatching {
			validate(parseStandardJson(clean))
		}.getOrElse {
			val salvaged = parseMalformedBatchTranslationJson(clean, expectedSize)
			if (salvaged.isNotEmpty()) {
				log { "openai parse salvaged items=${salvaged.size}" }
				validate(salvaged)
			} else {
				log { "openai parse exception content=${oneLine(clean, 400)}" }
				emptyMap()
			}
		}
	}

	private fun isOpenAiCompatibleChatCompletionsEndpoint(endpoint: String): Boolean {
		val normalized = endpoint.lowercase()
		return normalized.contains("/v1/chat/completions") || normalized.contains("/chat/completions")
	}

	private fun resolveTranslationApiEndpoint(): String {
		return TranslationApiProviderCatalog.resolveChatEndpoint(
			settings.readerTranslationApiProviderPreset,
			settings.readerTranslationApiEndpoint,
		)
	}

	private fun isDeepSeekEndpoint(endpoint: String): Boolean {
		val normalized = endpoint.lowercase()
		return normalized.contains("api.deepseek.com")
	}

	private fun buildOpenAiMicroBatches(texts: List<String>): List<List<String>> {
		if (texts.isEmpty()) return emptyList()
		if (texts.size <= maxOpenAiBatchSize) return listOf(texts)
		val result = mutableListOf<List<String>>()
		val current = mutableListOf<String>()

		fun flush() {
			if (current.isNotEmpty()) {
				result += current.toList()
				current.clear()
			}
		}

		for (text in texts) {
			val noisy = isLikelyNoisyOcrSource(text)
			val longText = text.length >= 28
			val shortSfxLike = text.length <= 10 && text.count { it.isJapaneseKana() } >= 2
			val preferSingle = noisy || longText
			if (preferSingle) {
				flush()
				result += listOf(text)
				continue
			}
			if (current.isNotEmpty()) {
				val hasShortSfxLike = current.any { it.length <= 10 && it.count { ch -> ch.isJapaneseKana() } >= 2 }
				if ((shortSfxLike && !hasShortSfxLike && current.size >= 2) || current.size >= maxOpenAiBatchSize) {
					flush()
				}
			}
			current += text
			if (current.size >= maxOpenAiBatchSize) {
				flush()
			}
		}
		flush()
		return result
	}

	private fun pickTranslationField(obj: JSONObject): String {
		val direct = listOf("translation", "translatedText", "text", "output")
			.firstNotNullOfOrNull { key ->
				obj.optString(key).trim().takeIf { it.isNotBlank() }
			}
		if (!direct.isNullOrBlank()) return direct

		return runCatching {
			obj.optJSONObject("data")?.optJSONArray("translations")?.optJSONObject(0)?.optString("translatedText")?.trim()
		}.getOrNull().orEmpty()
	}

	private fun extractTranslationFromMalformedJson(raw: String): String? {
		val regexes = listOf(
			Regex("""(?is)"translation"\s*:\s*"((?:\\.|[^"\\])*)(?:"|$)"""),
			Regex("""(?is)"translatedText"\s*:\s*"((?:\\.|[^"\\])*)(?:"|$)"""),
		)
		for (regex in regexes) {
			val value = regex.find(raw)?.groupValues?.getOrNull(1).orEmpty()
			val decoded = decodeJsonStringFragment(value)
			if (decoded.isNotBlank()) {
				return decoded
			}
		}
		return null
	}

	private fun decodeJsonStringFragment(value: String): String {
		if (value.isBlank()) return ""
		return value
			.replace("\\n", "\n")
			.replace("\\r", "\r")
			.replace("\\t", "\t")
			.replace("\\\"", "\"")
			.replace("\\\\", "\\")
			.trim()
			.removeSurrounding("\"")
	}

	private fun normalizeJsonLikeContent(raw: String): String {
		val text = raw.trim()
		if (!text.startsWith("```")) return text
		val lines = text.lines()
		if (lines.isEmpty()) return text
		val body = lines.drop(1).dropLastWhile { it.trim().startsWith("```") }.joinToString("\n").trim()
		return body.ifBlank { text }
	}

	private fun parseMalformedBatchTranslationJson(raw: String, expectedSize: Int): Map<Int, String> {
		val result = LinkedHashMap<Int, String>(expectedSize)
		val objectRegex = Regex("""(?s)\{[^{}]*}""")
		val idRegex = Regex("""(?is)"\s*id\s*"\s*:\s*"?(\d+)""")
		val pairRegex = Regex(
			"""(?is)"\s*id\s*"\s*:\s*"?(\d+)"?[^{}\[\]]*?"\s*(?:translation|translatedText|output)\s*"\s*:\s*"((?:\\.|[^"\\])*)"""
		)
		val translationRegexes = listOf(
			Regex("""(?is)"\s*translation\s*"\s*:\s*"((?:\\.|[^"\\])*)"""),
			Regex("""(?is)"\s*translatedText\s*"\s*:\s*"((?:\\.|[^"\\])*)"""),
			Regex("""(?is)"\s*output\s*"\s*:\s*"((?:\\.|[^"\\])*)"""),
		)

		for (match in objectRegex.findAll(raw)) {
			val item = match.value
			val id = idRegex.find(item)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
			if (id <= 0 || id > expectedSize || result.containsKey(id)) continue
			val translation = translationRegexes.firstNotNullOfOrNull { regex ->
				regex.find(item)?.groupValues?.getOrNull(1)?.let(::decodeJsonStringFragment)?.trim()?.takeIf { it.isNotBlank() }
			}.orEmpty()
			if (translation.isNotBlank()) {
				result[id] = translation
			}
		}
		if (result.size < expectedSize) {
			for (match in pairRegex.findAll(raw)) {
				val id = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
				if (id <= 0 || id > expectedSize || result.containsKey(id)) continue
				val translation = decodeJsonStringFragment(match.groupValues.getOrNull(2).orEmpty()).trim()
				if (translation.isNotBlank()) {
					result[id] = translation
				}
			}
		}
		return result
	}

	private fun stripThinkContent(text: String): String {
		if (text.isBlank()) return text
		return thinkTagRegex.replace(text, "")
			.replace(Regex("(?is)<think>.*$"), "")
			.replace("<analysis>", "", ignoreCase = true)
			.replace("</analysis>", "", ignoreCase = true)
			.trim()
	}

	private fun isLikelyNoisyOcrSource(text: String): Boolean {
		if (text.isBlank()) return false
		val len = text.length
		if (len < 8) return false
		val digits = text.count { it.isDigit() }
		val symbols = text.count {
			!it.isWhitespace() &&
				!it.isLetterOrDigit() &&
				!it.isJapaneseKana() &&
				!it.isCjkUnifiedIdeograph()
		}
		val separators = text.count { it in setOf(':', '：', '/', '／', '.', '．', '…', '-', 'ー') }
		val ratio = (digits + symbols + separators).toFloat() / len.toFloat()
		return ratio >= 0.28f || (digits >= 4 && separators >= 4) || Regex("""(?:\d[：:/／．.]){3,}""").containsMatchIn(text)
	}

	private fun Char.isJapaneseKana(): Boolean {
		return this in '\u3040'..'\u30ff' || this == 'ー'
	}

	private fun Char.isCjkUnifiedIdeograph(): Boolean {
		val block = Character.UnicodeBlock.of(this) ?: return false
		val blockName = block.toString()
		return blockName.startsWith("CJK_UNIFIED_IDEOGRAPHS") ||
			block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
	}

	private fun ResponseBody?.readJsonTextUtf8(): String {
		if (this == null) return ""
		return runCatching {
			bytes().toString(Charsets.UTF_8)
		}.getOrDefault("")
	}

	fun isAcceptableTranslation(
		sourceText: String,
		translatedText: String,
		sourceLang: String,
		targetLang: String,
	): Boolean {
		if (translatedText.isBlank()) return false
		if (translatedText == "..." || translatedText == "…") return false
		if (!settings.isReaderTranslationQualityFilterEnabled) return true
		if (shouldSuppressRenderedBubble(sourceText, translatedText, targetLang)) return false
		return true
	}

	fun sanitizeTranslation(text: String): String {
		if (text.isBlank()) return ""
		val clean = stripThinkContent(text)
		if (clean.isBlank()) return ""
		val normalized = normalizeJsonLikeContent(clean)
		if (normalized.isBlank()) return ""
		if (
			normalized.contains("Thinking Process", ignoreCase = true) ||
			normalized.contains("Analyze the Request", ignoreCase = true)
		) {
			return ""
		}

		val jsonStart = normalized.indexOf('{')
		val jsonEnd = normalized.lastIndexOf('}')
		if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
			val jsonText = normalized.substring(jsonStart, jsonEnd + 1)
			runCatching {
				val json = JSONObject(jsonText)
				val result = pickTranslationField(json)
				if (result.isNotBlank()) return result
			}
		}

		extractTranslationFromMalformedJson(normalized)?.let { extracted ->
			if (extracted.isNotBlank()) return extracted
		}

		return normalized
			.replace(Regex("^\\{.*\"translation\":\\s*\"", RegexOption.IGNORE_CASE), "")
			.replace(Regex("\"\\s*\\}$"), "")
			.removeSurrounding("**")
			.removeSurrounding("\"")
			.trim()
			.takeUnless {
				it.isBlank() || it == "..." || it == "…"
			}
			.orEmpty()
	}

	private fun applyCustomHeaders(requestBuilder: Request.Builder) {
		val customHeaders = settings.readerTranslationApiCustomHeaders.trim()
		if (customHeaders.isBlank() || !customHeaders.startsWith("{")) return
		kotlin.runCatching {
			val json = JSONObject(customHeaders)
			for (key in json.keys()) {
				val value = json.optString(key)
				if (value.isNotBlank()) {
					requestBuilder.header(key, value)
				}
			}
		}
	}

	private fun shouldSuppressRenderedBubble(
		sourceText: String,
		translatedText: String,
		targetLang: String,
	): Boolean {
		if (!settings.isReaderTranslationQualityFilterEnabled) return false
		val sourceNoisy = isLikelyNoisyOcrSource(sourceText)
		if (!sourceNoisy) return false
		if (isWeakTranslatedNoise(translatedText, targetLang)) return true
		val sourceNormalized = normalizeForTranslationCompare(sourceText)
		val translatedNormalized = normalizeForTranslationCompare(translatedText)
		if (sourceNormalized.isNotBlank() && translatedNormalized.isNotBlank() && sourceNormalized == translatedNormalized) {
			return true
		}
		return false
	}

	private fun isWeakTranslatedNoise(text: String, targetLang: String): Boolean {
		if (text.isBlank()) return true
		val compact = text.filterNot(Char::isWhitespace)
		if (compact.isBlank()) return true
		val normalized = normalizeForTranslationCompare(compact)
		if (normalized.isBlank()) return true
		val digits = compact.count { it.isDigit() }
		val latin = compact.count { it.isLatinLetterLike() }
		val cjk = compact.count { it.isCjkUnifiedIdeograph() }
		val kana = compact.count { it.isJapaneseKana() }
		val strongText = cjk + kana
		if (normalized.length <= 3 && digits + latin >= normalized.length) return true
		if (normalized.length <= 5 && digits >= 2 && strongText <= 1) return true
		if (targetLang.startsWith("zh") && normalized.length <= 4 && cjk == 0 && digits + latin >= 2) return true
		return false
	}

	private fun normalizeForTranslationCompare(text: String): String {
		return buildString(text.length) {
			for (ch in text) {
				if (ch.isLetterOrDigit() || ch.isCjkUnifiedIdeograph() || ch.isJapaneseKana()) {
					append(ch)
				}
			}
		}.trim()
	}

	private fun Char.isAsciiLetter(): Boolean {
		return this in 'a'..'z' || this in 'A'..'Z'
	}

	private fun Char.isLatinLetterLike(): Boolean {
		if (isAsciiLetter()) return true
		return Character.UnicodeScript.of(code) == Character.UnicodeScript.LATIN
	}
}
