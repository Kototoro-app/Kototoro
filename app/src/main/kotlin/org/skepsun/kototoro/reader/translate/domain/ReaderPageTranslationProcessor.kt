package org.skepsun.kototoro.reader.translate.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.os.SystemClock
import androidx.annotation.WorkerThread
import androidx.collection.LruCache
import androidx.collection.LongSparseArray
import androidx.core.net.toFile
import androidx.core.net.toUri
import eu.kanade.tachiyomi.network.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderOcrEngine
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.core.prefs.ReaderTranslationPipelineMode
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toMimeTypeOrNull
import org.skepsun.kototoro.local.data.LocalStorageCache
import org.skepsun.kototoro.local.data.PageCache
import org.skepsun.kototoro.reader.translate.data.OnnxModelCategory
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.reader.translate.data.ReaderTranslationTextCache
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.skepsun.kototoro.core.util.ext.awaitCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okio.source
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class ReaderPageTranslationProcessor @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val settings: AppSettings,
	@PageCache private val cache: LocalStorageCache,
	private val textCache: ReaderTranslationTextCache,
	@ContentHttpClient
	private val okHttpClient: OkHttpClient,
	private val mlKitOcrEngine: MlKitReaderOcrEngine,
	private val paddleOcrEngine: PaddleReaderOcrEngine,
	private val comicTextDetectorOnnx: ComicTextDetectorOnnx,
	private val defaultDbNetTextDetector: DefaultDbNetTextDetector,
	private val bubbleReaderTextDetector: BubbleReaderTextDetector,
	private val mangaOcrReaderTextRecognizer: MangaOcrReaderTextRecognizer,
	private val onnxBubbleDetectorEngine: OnnxBubbleDetectorEngine,
	private val onnxTranslationEngine: OnnxReaderTranslationEngine,
	private val debugLogStore: ReaderTranslationDebugLogStore,
) {

	private val processingSemaphore = Semaphore(MAX_PARALLEL_TRANSLATION_PAGES)
	
	@Volatile
	private var currentE2eConcurrency = 3
	@Volatile
	private var e2eSemaphore = Semaphore(3)

	private fun getE2eSemaphore(): Semaphore {
		val target = settings.readerE2eApiConcurrency.coerceAtLeast(1)
		if (currentE2eConcurrency != target) {
			currentE2eConcurrency = target
			e2eSemaphore = Semaphore(target)
		}
		return e2eSemaphore
	}
	private val pageStateLock = Any()
	private val renderedSourceMap = LruCache<String, String>(512)
	private val pageRenderEpochs = LongSparseArray<Int>()
	private val loggingPageId = ThreadLocal<Long?>()
	private val automaticRecognizerLanguage = ThreadLocal<String?>()
	@Volatile
	private var renderCacheEpoch: Int = 0
	@Volatile
	private var lastResolvedOcrPipelineStrategy: String = OCR_STRATEGY_PAGE_DET_REC
	private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		style = Paint.Style.FILL
		alpha = 242
	}
	private val compactOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.BLACK
		style = Paint.Style.FILL
		alpha = 190
	}
	private val debugSourceRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(220, 255, 160, 0)
		style = Paint.Style.STROKE
		strokeWidth = dp(1.5f).toFloat()
	}
	private val debugPreparedRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(220, 0, 220, 255)
		style = Paint.Style.STROKE
		strokeWidth = dp(1.5f).toFloat()
	}
	private val debugContentRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(220, 80, 255, 120)
		style = Paint.Style.STROKE
		strokeWidth = dp(1f).toFloat()
	}
	private val debugLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(240, 255, 80, 80)
		textSize = dp(10f).toFloat()
	}
	private val textPaintTemplate = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		textAlign = Paint.Align.LEFT
	}
	private val bubbleRenderCoordinator by lazy(LazyThreadSafetyMode.NONE) {
		ReaderBubbleRenderCoordinator(
			isLikelyGarbledText = ::isLikelyGarbledText,
			shouldSuppressRenderedBubble = ::shouldSuppressRenderedBubble,
			isLikelySpeechBubbleRegion = ::isLikelySpeechBubbleRegion,
			prepareTranslatedBubble = ::prepareTranslatedBubble,
			qualityFilterEnabled = { settings.isReaderTranslationQualityFilterEnabled },
			log = ::log,
			oneLine = ::oneLine,
		)
	}
	private val textMergeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
		ReaderTextMergeCoordinator(
			shouldMergeFragments = ::shouldMergeFragments,
			mergeRects = ::mergeRects,
			composeMergedText = ::composeGroupedText,
		)
	}
	private val paddleTextDetector: ReaderTextDetector
		get() = paddleOcrEngine
	private val ctdTextDetector: ReaderTextDetector
		get() = comicTextDetectorOnnx
	private val dbNetTextDetector: ReaderTextDetector
		get() = defaultDbNetTextDetector
	private val mangaTextRecognizer: ReaderTextRecognizer
		get() = mangaOcrReaderTextRecognizer
	private val translationCoordinator by lazy(LazyThreadSafetyMode.NONE) {
		ReaderTranslationCoordinator(
			settings = settings,
			textCache = textCache,
			onnxTranslationEngine = onnxTranslationEngine,
			okHttpClient = okHttpClient,
			jsonMediaType = JSON_MEDIA_TYPE,
			defaultOpenAiModel = DEFAULT_OPENAI_MODEL,
			openAiTranslationSystemPrompt = OPENAI_TRANSLATION_SYSTEM_PROMPT,
			maxOpenAiBatchSize = MAX_OPENAI_BATCH_SIZE,
			thinkTagRegex = THINK_TAG_REGEX,
			buildTextCacheKey = ::buildTextCacheKey,
			sanitizeTranslation = ::sanitizeTranslation,
			isAcceptableTranslation = ::isAcceptableTranslation,
			log = ::log,
			oneLine = ::oneLine,
		)
	}
	private val endToEndTranslator by lazy(LazyThreadSafetyMode.NONE) {
		GeminiEndToEndTranslator(
			settings = settings,
			okHttpClient = okHttpClient,
			jsonMediaType = JSON_MEDIA_TYPE,
			log = ::log,
		)
	}
	private val ocrPipelineCoordinator by lazy(LazyThreadSafetyMode.NONE) {
		ReaderOcrPipelineCoordinator(
			loadPageText = ::loadPageTextWithCache,
			mergePageTextBlocks = ::mergePageTextBlocks,
		)
	}

	fun clearAllCaches() {
		textCache.clear()
		debugLogStore.clearAll()
		synchronized(pageStateLock) {
			pageRenderEpochs.clear()
		}
		renderCacheEpoch += 1
		log { "translation caches cleared epoch=$renderCacheEpoch" }
	}

	fun clearPageCaches(pageId: Long) {
		debugLogStore.clearPage(pageId)
		synchronized(pageStateLock) {
			val current = pageRenderEpochs[pageId] ?: 0
			pageRenderEpochs.put(pageId, current + 1)
		}
		log { "translation page cache cleared page=$pageId" }
	}

	suspend fun peekRendered(
		page: ContentPage,
		sourceUri: Uri,
		autoRecognizerLanguage: String? = null,
	): Uri? {
		if (!settings.isReaderTranslationEnabled) {
			return null
		}
		val normalizedAutoRecognizerLanguage = autoRecognizerLanguage.normalizeReaderTranslationLanguageTag()
		val sourceLang = resolveSourceLanguage(page, normalizedAutoRecognizerLanguage)
		val targetLang = settings.readerTranslationTargetLanguage.normalizeReaderTranslationLanguageTag() ?: "zh"
		if (sourceLang == targetLang) {
			return null
		}
		val renderCacheKey = buildRenderedCacheKey(
			pageUrl = page.url,
			sourceUri = sourceUri.toString(),
			sourceLang = sourceLang,
			targetLang = targetLang,
			pageEpoch = getPageRenderEpoch(page.id),
			autoRecognizerLanguage = normalizedAutoRecognizerLanguage,
		)
		return cache[renderCacheKey]?.toUri()?.also {
			appendPageLog(page.id, "metric.render_cache.hit=1")
			rememberRenderedSource(it, sourceUri)
		}
	}

	fun peekSourceOfRendered(renderedUri: Uri): Uri? {
		return renderedSourceMap[renderedUri.toString()]?.toUri()
	}

	suspend fun process(
		page: ContentPage,
		sourceUri: Uri,
		forceEnabled: Boolean = false,
		autoRecognizerLanguage: String? = null,
	): Uri {
		val enabled = forceEnabled || settings.isReaderTranslationEnabled
		val showTranslated = settings.isReaderTranslationShowTranslated
		Log.d(LOG_TAG, "process debug: page=${page.id} enabled=$enabled showTranslated=$showTranslated")
		if (!enabled) {
			return sourceUri
		}
		val normalizedAutoRecognizerLanguage = autoRecognizerLanguage.normalizeReaderTranslationLanguageTag()
		val sourceLang = resolveSourceLanguage(page, normalizedAutoRecognizerLanguage)
		val targetLang = settings.readerTranslationTargetLanguage.normalizeReaderTranslationLanguageTag() ?: "zh"
		Log.d(LOG_TAG, "process debug: page=${page.id} sourceLang=$sourceLang targetLang=$targetLang")
		if (sourceLang == targetLang) {
			return sourceUri
		}
		val localUri = ensureLocalFileUri(sourceUri) ?: run {
			Log.d(LOG_TAG, "process debug: localize failed page=${page.id} uri=$sourceUri")
			log { "process skip: cannot localize uri=$sourceUri" }
			return sourceUri
		}
		Log.d(LOG_TAG, "process debug: local uri ready page=${page.id} uri=$localUri")
		val renderCacheKey = buildRenderedCacheKey(
			pageUrl = page.url,
			sourceUri = sourceUri.toString(),
			sourceLang = sourceLang,
			targetLang = targetLang,
			pageEpoch = getPageRenderEpoch(page.id),
			autoRecognizerLanguage = normalizedAutoRecognizerLanguage,
		)
		cache[renderCacheKey]?.let {
			Log.d(LOG_TAG, "process debug: render cache hit before start page=${page.id}")
			return it.toUri().also { rendered ->
				rememberRenderedSource(rendered, localUri)
			}
		}
		val processStartLog =
			"process start page=${page.id} sourceLang=$sourceLang targetLang=$targetLang " +
				"ocr=${settings.readerTranslationOcrEngine.name} " +
				"det=${settings.readerTranslationPaddleDetModelId} " +
				"rec=${settings.readerTranslationPaddleOfficialModelId} " +
				"autoRecLang=${normalizedAutoRecognizerLanguage ?: "none"}"
		log { processStartLog }
		
		val permitSemaphore = if (settings.readerTranslationPipelineMode == ReaderTranslationPipelineMode.END_TO_END_API) {
			getE2eSemaphore()
		} else {
			processingSemaphore
		}
		Log.d(LOG_TAG, "process debug: waiting permit page=${page.id}")
		
		return withContext(
			loggingPageId.asContextElement(page.id) +
				automaticRecognizerLanguage.asContextElement(normalizedAutoRecognizerLanguage),
		) {
			permitSemaphore.withPermit {
				Log.d(LOG_TAG, "process debug: permit acquired page=${page.id}")
				appendPageLog(page.id, processStartLog)
				cache[renderCacheKey]?.let {
					appendPageLog(page.id, "metric.render_cache.hit=1")
					Log.d(LOG_TAG, "process debug: render cache hit after permit page=${page.id}")
					return@withPermit it.toUri().also { rendered ->
						rememberRenderedSource(rendered, localUri)
					}
				}
				runCatching {
					processImpl(page.id, localUri, renderCacheKey, sourceLang, targetLang)
				}.onFailure {
					Log.d(LOG_TAG, "process debug: pipeline failed page=${page.id} err=${it.javaClass.simpleName}: ${it.message.orEmpty()}")
					it.printStackTraceDebug()
					appendPageLog(page.id, "process failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}")
					appendPageLog(page.id, "fail_code=$FAIL_CODE_PROCESS_EXCEPTION")
				}.getOrDefault(sourceUri).also { result ->
					Log.d(LOG_TAG, "process debug: pipeline returned page=${page.id} translated=${result != sourceUri}")
				}
			}
		}
	}

	fun getPageDebugLog(pageId: Long): String {
		return debugLogStore.get(pageId)
	}

	fun observeDebugLogUpdates(): Flow<Long> {
		return debugLogStore.observeUpdates()
	}

	@WorkerThread
	private suspend fun processImpl(
		pageId: Long,
		sourceUri: Uri,
		renderCacheKey: String,
		sourceLang: String,
		targetLang: String,
	): Uri {
		val totalStartMs = SystemClock.elapsedRealtime()
		var ocrCacheHit = false
		var ocrDurationMs = -1L
		var translateDurationMs = -1L
		var renderDurationMs = -1L
		var ocrBlocks = 0
		var bubbleCount = 0
		var renderedBubbleCount = 0
		var processResult = "source"
		appendPageLog(pageId, "metric.render_cache.hit=0")

		// OCR result caching: skip re-OCR if we already processed this image
		try {
			val sourceBitmap = runInterruptible(Dispatchers.IO) {
				BitmapDecoderCompat.decode(sourceUri.toFile())
			}
			val bitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
			if (bitmap !== sourceBitmap) {
				sourceBitmap.recycle()
			}
			val canvas = Canvas(bitmap)
			if (settings.readerTranslationPipelineMode == ReaderTranslationPipelineMode.END_TO_END_API) {
				val bubblePairs = endToEndTranslator.processImage(bitmap, sourceLang, targetLang)
				val inputs = bubblePairs.map { it.first }
				val map = bubblePairs.associate { it.first.sourceText to it.second }
				
				bubbleCount = inputs.size
				val renderStartMs = SystemClock.elapsedRealtime()
				val renderPreparation = bubbleRenderCoordinator.prepareBubbles(
					bubbleInputs = inputs,
					translatedMap = map,
					targetLang = targetLang,
					bitmap = bitmap,
				)
				val preparedBubbles = renderPreparation.preparedBubbles
				val nonEmptyTranslatedCount = renderPreparation.nonEmptyTranslatedCount
				for (bubble in preparedBubbles) {
					drawBubbleBackground(canvas, bubble)
				}
				for (bubble in preparedBubbles) {
					drawBubbleText(canvas, bubble)
				}
				if (settings.isReaderTranslationDebugLogsEnabled) {
					for (bubble in preparedBubbles) {
						drawBubbleDebugOverlay(canvas, bubble)
					}
				}
				log { "render done e2eBubbles=${preparedBubbles.size}" }
				renderedBubbleCount = preparedBubbles.size
				if (preparedBubbles.isEmpty()) {
					val failCode = if (nonEmptyTranslatedCount == 0) {
						FAIL_CODE_TRANSLATE_EMPTY
					} else {
						FAIL_CODE_RENDER_FILTERED
					}
					renderDurationMs = SystemClock.elapsedRealtime() - renderStartMs
					log { "fail_code=$failCode" }
					bitmap.recycle()
					return sourceUri
				}
				val output = cache.set(renderCacheKey, bitmap).toUri()
				rememberRenderedSource(output, sourceUri)
				renderDurationMs = SystemClock.elapsedRealtime() - renderStartMs
				processResult = "rendered_e2e"
				bitmap.recycle()
				return output
			}

			val ocrPipeline = ocrPipelineCoordinator.execute(
				sourceUri = sourceUri,
				sourceLang = sourceLang,
				pageId = pageId,
				bitmap = bitmap,
			)
			ocrCacheHit = ocrPipeline.pageOcr?.cacheHit ?: false
			ocrDurationMs = ocrPipeline.pageOcr?.durationMs ?: 0L
			ocrBlocks = ocrPipeline.pageTextBlocks.size
			log { "metric.ocr.fragments=${ocrPipeline.textFragments.size}" }
			if (ocrPipeline.textFragments.isEmpty()) {
				log { "fail_code=$FAIL_CODE_OCR_EMPTY" }
				bitmap.recycle()
				return sourceUri
			}
			val bubbleInputs = buildBubbleInputsFromFragments(
				fragments = ocrPipeline.textFragments,
				sourceLang = sourceLang,
				targetLang = targetLang,
			)
			bubbleCount = bubbleInputs.size
			val translateStartMs = SystemClock.elapsedRealtime()
			val translatedMap = translateBlocksCached(
				texts = bubbleInputs.map { it.sourceText },
				sourceLang = sourceLang,
				targetLang = targetLang,
			)
			translateDurationMs = SystemClock.elapsedRealtime() - translateStartMs
			val renderStartMs = SystemClock.elapsedRealtime()
			val renderPreparation = bubbleRenderCoordinator.prepareBubbles(
				bubbleInputs = bubbleInputs,
				translatedMap = translatedMap,
				targetLang = targetLang,
				bitmap = bitmap,
			)
			val preparedBubbles = renderPreparation.preparedBubbles
			val nonEmptyTranslatedCount = renderPreparation.nonEmptyTranslatedCount
			// Two-pass render: draw all bubble backgrounds first, then all texts to avoid later bubbles covering earlier texts.
			for (bubble in preparedBubbles) {
				drawBubbleBackground(canvas, bubble)
			}
			for (bubble in preparedBubbles) {
				drawBubbleText(canvas, bubble)
			}
			if (settings.isReaderTranslationDebugLogsEnabled) {
				for (bubble in preparedBubbles) {
					drawBubbleDebugOverlay(canvas, bubble)
				}
			}
			log { "render done translatedBubbles=${preparedBubbles.size}" }
			renderedBubbleCount = preparedBubbles.size
			if (preparedBubbles.isEmpty()) {
				val failCode = if (nonEmptyTranslatedCount == 0) {
					FAIL_CODE_TRANSLATE_EMPTY
				} else {
					FAIL_CODE_RENDER_FILTERED
				}
				renderDurationMs = SystemClock.elapsedRealtime() - renderStartMs
				log { "fail_code=$failCode" }
				bitmap.recycle()
				return sourceUri
			}
			val output = cache.set(renderCacheKey, bitmap).toUri()
			rememberRenderedSource(output, sourceUri)
			renderDurationMs = SystemClock.elapsedRealtime() - renderStartMs
			processResult = "rendered"
			bitmap.recycle()
			return output
		} finally {
			log { "metric.ocr.cache_hit=${if (ocrCacheHit) 1 else 0}" }
			if (ocrDurationMs >= 0L) log { "metric.ocr.total_ms=$ocrDurationMs" }
			log { "metric.ocr.pipeline.strategy=$lastResolvedOcrPipelineStrategy" }
			log { "metric.ocr.blocks=$ocrBlocks" }
			log { "metric.translation.bubbles=$bubbleCount" }
			if (translateDurationMs >= 0L) log { "metric.translation.total_ms=$translateDurationMs" }
			if (renderDurationMs >= 0L) log { "metric.render.total_ms=$renderDurationMs" }
			log { "metric.render.translated_bubbles=$renderedBubbleCount" }
			log { "metric.process.result=$processResult" }
			log { "metric.process.total_ms=${SystemClock.elapsedRealtime() - totalStartMs}" }
		}
	}

	private fun rememberRenderedSource(renderedUri: Uri, sourceUri: Uri) {
		renderedSourceMap.put(renderedUri.toString(), sourceUri.toString())
	}

	private suspend fun loadPageTextWithCache(sourceUri: Uri, sourceLang: String, pageId: Long): PageOcrLoadResult {
		val startMs = SystemClock.elapsedRealtime()
		val ocrCacheKey = buildOcrCacheKey(sourceUri.toString(), sourceLang)
		val cached = textCache[ocrCacheKey]
		if (cached != null) {
			log { "ocr cache hit" }
			val textBlocks = deserializeOcrBlocks(cached)
			log { "ocr done blocks=${textBlocks.size}" }
			return PageOcrLoadResult(
				textBlocks = textBlocks,
				cacheHit = true,
				durationMs = SystemClock.elapsedRealtime() - startMs,
			)
		}
		val textBlocks = recognizeTextWithFallback(sourceUri, sourceLang, pageId)
		if (textBlocks.isNotEmpty()) {
			textCache[ocrCacheKey] = serializeOcrBlocks(textBlocks)
		}
		log { "ocr done blocks=${textBlocks.size}" }
		return PageOcrLoadResult(
			textBlocks = textBlocks,
			cacheHit = false,
			durationMs = SystemClock.elapsedRealtime() - startMs,
		)
	}

	private suspend fun recognizeTextWithFallback(sourceUri: Uri, sourceLang: String, pageId: Long): List<OcrTextBlock> {
		val minAcceptableBlocks = when {
			isJapaneseSourceLanguage(sourceLang) -> 3
			sourceLang.startsWith("zh") || sourceLang.startsWith("ko") -> 2
			else -> 1
		}
		val order = resolvePageOcrRouteOrder()
		lastResolvedOcrPipelineStrategy = OCR_STRATEGY_PAGE_DET_REC
		var bestResult: List<OcrTextBlock> = emptyList()
		var bestRoute: PageOcrRoute? = null
		for (route in order) {
			val attemptStartMs = SystemClock.elapsedRealtime()
			val result = runCatching {
				recognizeTextByRoute(route, sourceUri, sourceLang, pageId)
			}.onFailure {
				it.printStackTraceDebug()
				log {
					"metric.ocr.attempt.${route.metricKey}.error=" +
						"${it.javaClass.simpleName}:${it.message.orEmpty().take(160)}"
				}
			}.getOrDefault(emptyList())
			val attemptDurationMs = SystemClock.elapsedRealtime() - attemptStartMs
			log { "metric.ocr.attempt.${route.metricKey}.ms=$attemptDurationMs" }
			log { "metric.ocr.attempt.${route.metricKey}.blocks=${result.size}" }
			if (result.isNotEmpty()) {
				val qualityIssue = findOcrQualityIssue(result)
				val qualityStats = buildOcrQualityStats(result)
				log { "metric.ocr.attempt.${route.metricKey}.chars=${qualityStats.nonWhitespaceChars}" }
				log { "metric.ocr.attempt.${route.metricKey}.avg_confidence=${qualityStats.averageConfidence}" }
				log { "metric.ocr.attempt.${route.metricKey}.low_confidence_ratio=${qualityStats.lowConfidenceRatio}" }
				if (isBetterOcrResult(result, bestResult)) {
					bestResult = result
					bestRoute = route
				}
				if (route.detector == OcrDetectorBackend.MLKIT || (result.size >= minAcceptableBlocks && qualityIssue == null)) {
					log { "metric.ocr.selected_engine=${route.metricKey}" }
					log { "metric.ocr.selected_blocks=${result.size}" }
					log { "ocr route=${route.metricKey} blocks=${result.size}" }
					return result
				}
				if (qualityIssue != null) {
					log { "ocr route=${route.metricKey} low_quality ${qualityIssue.toLogString()}, trying fallback" }
					continue
				}
				log {
					"ocr route=${route.metricKey} blocks=${result.size}, below threshold=$minAcceptableBlocks, trying fallback"
				}
				continue
			}
			log { "ocr route=${route.metricKey} blocks=0, trying fallback" }
		}
		if (bestResult.isNotEmpty()) {
			bestRoute?.let {
				log { "metric.ocr.selected_engine=${it.metricKey}" }
			}
			log { "metric.ocr.selected_blocks=${bestResult.size}" }
			log { "ocr fallback use best route=${bestRoute?.metricKey} blocks=${bestResult.size}" }
		}
		return bestResult
	}

	private fun isBetterOcrResult(candidate: List<OcrTextBlock>, current: List<OcrTextBlock>): Boolean {
		if (candidate.size != current.size) return candidate.size > current.size
		return countNonWhitespaceChars(candidate) > countNonWhitespaceChars(current)
	}

	private fun buildOcrQualityStats(blocks: List<OcrTextBlock>): OcrQualityStats {
		if (blocks.isEmpty()) return OcrQualityStats(
			blockCount = 0,
			nonWhitespaceChars = 0,
			averageConfidence = 0f,
			lowConfidenceBlockCount = 0,
			lowConfidenceRatio = 0f,
		)
		val confidences = blocks.map { it.confidence.coerceIn(0f, 1f) }
		val lowConfidenceBlockCount = confidences.count { it < LOW_OCR_CONFIDENCE_THRESHOLD }
		return OcrQualityStats(
			blockCount = blocks.size,
			nonWhitespaceChars = countNonWhitespaceChars(blocks),
			averageConfidence = confidences.average().toFloat().toFixedMetricFloat(),
			lowConfidenceBlockCount = lowConfidenceBlockCount,
			lowConfidenceRatio = (lowConfidenceBlockCount.toFloat() / blocks.size.toFloat()).toFixedMetricFloat(),
		)
	}

	private fun findOcrQualityIssue(blocks: List<OcrTextBlock>): OcrQualityStats? {
		val stats = buildOcrQualityStats(blocks)
		if (stats.blockCount == 0) return null
		val veryLowAverage = stats.averageConfidence < MIN_AVERAGE_OCR_CONFIDENCE &&
			stats.lowConfidenceRatio >= MIN_LOW_OCR_CONFIDENCE_RATIO
		val mostlyLowShortResult = stats.averageConfidence < SOFT_AVERAGE_OCR_CONFIDENCE &&
			stats.lowConfidenceRatio >= MOSTLY_LOW_OCR_CONFIDENCE_RATIO &&
			stats.nonWhitespaceChars <= SHORT_OCR_RESULT_CHAR_LIMIT
		return if (veryLowAverage || mostlyLowShortResult) stats else null
	}

	private fun countNonWhitespaceChars(blocks: List<OcrTextBlock>): Int {
		return blocks.sumOf { block -> block.text.count { !it.isWhitespace() } }
	}

	private fun Float.toFixedMetricFloat(): Float {
		return (this * 1000f).roundToInt() / 1000f
	}

	private fun resolvePageOcrRouteOrder(): List<PageOcrRoute> {
		val detModelId = settings.readerTranslationPaddleDetModelId
		val configuredRecModelId = settings.readerTranslationPaddleOfficialModelId
		val recModelId = if (configuredRecModelId == "AUTO") {
			resolveAutomaticReaderRecognizerModelId(automaticRecognizerLanguage.get())
		} else {
			configuredRecModelId
		}
		val detBackend = resolveDetectorBackend(detModelId)
		val recBackend = when (recModelId) {
			"MLKIT" -> OcrRecognizerBackend.MLKIT
			MANGA_OCR_RECOGNIZER_MODEL_ID -> OcrRecognizerBackend.MANGA_OCR
			else -> OcrRecognizerBackend.PADDLE
		}
		if (configuredRecModelId == "AUTO") {
			log {
				"metric.ocr.auto_recognizer.language=${automaticRecognizerLanguage.get() ?: "none"} " +
					"model=$recModelId backend=${recBackend.name.lowercase()}"
			}
		}
		val effectiveRoute = PageOcrRoute(
			detector = detBackend,
			recognizer = recBackend,
		)
		val routes = linkedSetOf<PageOcrRoute>()
		// Always push the user's selected effective route as fallback.
		routes += effectiveRoute

		// Absolute fallback to ensure pipeline never returns empty route list
		routes += PageOcrRoute(
			detector = OcrDetectorBackend.MLKIT,
			recognizer = OcrRecognizerBackend.MLKIT,
		)
		
		return routes.toList()
	}

	private fun resolveDetectorBackend(modelId: String): OcrDetectorBackend {
		if (modelId == "MLKIT") return OcrDetectorBackend.MLKIT
		val model = OnnxOfficialModelCatalog.findById(modelId)
		return when (model?.category) {
			OnnxModelCategory.BUBBLE_DETECTION -> OcrDetectorBackend.BUBBLE
			OnnxModelCategory.OCR_DETECTOR -> {
				when (model.id) {
					ComicTextDetectorOnnx.MODEL_ID -> OcrDetectorBackend.CTD
					DefaultDbNetTextDetector.MODEL_ID -> OcrDetectorBackend.DBNET
					else -> OcrDetectorBackend.PADDLE
				}
			}
			else -> OcrDetectorBackend.PADDLE
		}
	}

	private suspend fun recognizeTextByEngine(
		engine: ReaderOcrEngine,
		sourceUri: Uri,
		sourceLang: String,
		pageId: Long,
	): List<OcrTextBlock> {
		return recognizeTextByEngine(
			engine = engine,
			request = OcrRequest(
				sourceUri = sourceUri,
				sourceLang = sourceLang,
				pageId = pageId,
				requestType = OcrRequestType.PAGE,
				debugTag = "page:$pageId:${engine.name.lowercase()}",
			),
		)
	}

	private suspend fun recognizeTextByEngine(
		engine: ReaderOcrEngine,
		request: OcrRequest,
	): List<OcrTextBlock> {
		return when (engine) {
			ReaderOcrEngine.MLKIT -> mlKitOcrEngine.recognize(request)
			ReaderOcrEngine.PADDLE -> {
				if (request.requestType == OcrRequestType.PAGE && request.roi == null) {
					recognizePageTextByPipeline(request.sourceUri)
				} else {
					paddleOcrEngine.recognize(request)
				}
			}
		}
	}

	private suspend fun recognizePageTextByPipeline(sourceUri: Uri): List<OcrTextBlock> {
		val regions = paddleTextDetector.detect(sourceUri)
		log { "metric.ocr.paddle.detected_regions=${regions.size}" }
		if (regions.isEmpty()) return emptyList()
		val blocks = paddleOcrEngine.recognize(sourceUri, regions, automaticRecognizerLanguage.get())
		log { "metric.ocr.paddle.recognized_blocks=${blocks.size}" }
		return blocks
	}

	private suspend fun recognizeTextByRoute(
		route: PageOcrRoute,
		sourceUri: Uri,
		sourceLang: String,
		pageId: Long,
	): List<OcrTextBlock> {
		mangaOcrReaderTextRecognizer.setDiagnosticsEmitter(::log)
		return when {
			route.detector == OcrDetectorBackend.MLKIT &&
				route.recognizer == OcrRecognizerBackend.MLKIT -> recognizeTextByEngine(
				engine = ReaderOcrEngine.MLKIT,
				sourceUri = sourceUri,
				sourceLang = sourceLang,
				pageId = pageId,
			)
			route.detector == OcrDetectorBackend.PADDLE &&
				route.recognizer == OcrRecognizerBackend.PADDLE -> recognizeTextByEngine(
				engine = ReaderOcrEngine.PADDLE,
				sourceUri = sourceUri,
				sourceLang = sourceLang,
				pageId = pageId,
			)
			route.detector == OcrDetectorBackend.MLKIT &&
				route.recognizer == OcrRecognizerBackend.MANGA_OCR -> {
				val detectedBlocks = recognizeTextByEngine(
					engine = ReaderOcrEngine.MLKIT,
					sourceUri = sourceUri,
					sourceLang = sourceLang,
					pageId = pageId,
				)
				val regions = detectedBlocksToRegions(detectedBlocks)
				log { "metric.ocr.mlkit.detected_regions=${regions.size}" }
				if (regions.isEmpty()) return emptyList()
				val recognized = mangaTextRecognizer.recognize(sourceUri, regions)
				mangaOcrReaderTextRecognizer.consumeLastDiagnostics()
				log { "metric.ocr.mangaocr.recognized_blocks=${recognized.size}" }
				recognized
			}
			route.detector == OcrDetectorBackend.PADDLE &&
				route.recognizer == OcrRecognizerBackend.MANGA_OCR -> {
				val regions = paddleTextDetector.detect(sourceUri)
				log { "metric.ocr.paddle.detected_regions=${regions.size}" }
				if (regions.isEmpty()) return emptyList()
				val recognized = mangaTextRecognizer.recognize(sourceUri, regions)
				mangaOcrReaderTextRecognizer.consumeLastDiagnostics()
				log { "metric.ocr.mangaocr.recognized_blocks=${recognized.size}" }
				recognized
			}
			route.detector == OcrDetectorBackend.BUBBLE &&
				route.recognizer == OcrRecognizerBackend.MANGA_OCR -> {
				val regions = bubbleReaderTextDetector.detect(sourceUri)
				log { "metric.ocr.bubble.detected_regions=${regions.size}" }
				if (regions.isEmpty()) return emptyList()
				val recognized = mangaTextRecognizer.recognize(sourceUri, regions)
				mangaOcrReaderTextRecognizer.consumeLastDiagnostics()
				log { "metric.ocr.bubble_mangaocr.recognized_blocks=${recognized.size}" }
				recognized
			}
			route.detector == OcrDetectorBackend.CTD &&
				route.recognizer == OcrRecognizerBackend.MANGA_OCR -> {
				val regions = ctdTextDetector.detect(sourceUri)
				log { "metric.ocr.ctd.detected_regions=${regions.size}" }
				if (regions.isEmpty()) return emptyList()
				val recognized = mangaTextRecognizer.recognize(sourceUri, regions)
				mangaOcrReaderTextRecognizer.consumeLastDiagnostics()
				log { "metric.ocr.ctd_mangaocr.recognized_blocks=${recognized.size}" }
				recognized
			}
			route.detector == OcrDetectorBackend.DBNET &&
				route.recognizer == OcrRecognizerBackend.MANGA_OCR -> {
				val regions = dbNetTextDetector.detect(sourceUri)
				log { "metric.ocr.dbnet.detected_regions=${regions.size}" }
				if (regions.isEmpty()) return emptyList()
				val recognized = mangaTextRecognizer.recognize(sourceUri, regions)
				mangaOcrReaderTextRecognizer.consumeLastDiagnostics()
				log { "metric.ocr.dbnet_mangaocr.recognized_blocks=${recognized.size}" }
				recognized
			}
			route.detector == OcrDetectorBackend.DBNET &&
				route.recognizer == OcrRecognizerBackend.MLKIT -> {
				val regions = dbNetTextDetector.detect(sourceUri)
				log { "metric.ocr.dbnet.detected_regions=${regions.size}" }
				if (regions.isEmpty()) return emptyList()
				val recognized = mlKitOcrEngine.recognize(sourceUri, regions)
				log { "metric.ocr.dbnet_mlkit.recognized_blocks=${recognized.size}" }
				recognized
			}
			route.detector == OcrDetectorBackend.MLKIT &&
				route.recognizer == OcrRecognizerBackend.PADDLE -> {
				// ML Kit detection → Paddle recognition
				val detectedBlocks = recognizeTextByEngine(
					engine = ReaderOcrEngine.MLKIT,
					sourceUri = sourceUri,
					sourceLang = sourceLang,
					pageId = pageId,
				)
				val regions = detectedBlocksToRegions(detectedBlocks)
				log { "metric.ocr.mlkit_det_paddle_rec.detected_regions=${regions.size}" }
				if (regions.isEmpty()) return emptyList()
				val recognized = paddleOcrEngine.recognize(sourceUri, regions, automaticRecognizerLanguage.get())
				log { "metric.ocr.mlkit_det_paddle_rec.recognized_blocks=${recognized.size}" }
				recognized
			}
			route.detector == OcrDetectorBackend.CTD &&
				route.recognizer == OcrRecognizerBackend.PADDLE -> {
				val regions = ctdTextDetector.detect(sourceUri)
				log { "metric.ocr.ctd.detected_regions=${regions.size}" }
				if (regions.isEmpty()) return emptyList()
				val recognized = paddleOcrEngine.recognize(sourceUri, regions, automaticRecognizerLanguage.get())
				log { "metric.ocr.ctd_paddle.recognized_blocks=${recognized.size}" }
				recognized
			}
				route.detector == OcrDetectorBackend.BUBBLE &&
					route.recognizer == OcrRecognizerBackend.PADDLE -> {
					val regions = bubbleReaderTextDetector.detect(sourceUri)
					log { "metric.ocr.bubble.detected_regions=${regions.size}" }
					if (regions.isEmpty()) return emptyList()
					val recognized = paddleOcrEngine.recognize(sourceUri, regions, automaticRecognizerLanguage.get())
					log { "metric.ocr.bubble_paddle.recognized_blocks=${recognized.size}" }
					recognized
				}
				route.detector == OcrDetectorBackend.DBNET &&
					route.recognizer == OcrRecognizerBackend.PADDLE -> {
					val regions = dbNetTextDetector.detect(sourceUri)
					log { "metric.ocr.dbnet.detected_regions=${regions.size}" }
					if (regions.isEmpty()) return emptyList()
					val recognized = paddleOcrEngine.recognize(sourceUri, regions, automaticRecognizerLanguage.get())
					log { "metric.ocr.dbnet_paddle.recognized_blocks=${recognized.size}" }
					recognized
				}
				else -> emptyList()
		}
	}

	private fun logMangaOcrDiagnostics() {
		val diagnostics = mangaOcrReaderTextRecognizer.consumeLastDiagnostics() ?: return
		log {
			"metric.ocr.mangaocr.attempted=${diagnostics.attemptedCount} recognized=${diagnostics.recognizedCount} " +
				"empty=${diagnostics.emptyCount} empty_ratio=${diagnostics.emptyRatio}"
		}
		log { "metric.ocr.mangaocr.crops ${diagnostics.cropSummary}" }
		if (diagnostics.emptySamples.isNotEmpty()) {
			log { "metric.ocr.mangaocr.empty_crop_samples=${diagnostics.emptySamples.joinToString(";")}" }
		}
		diagnostics.traceSamples.forEachIndexed { index, sample ->
			log { "metric.ocr.mangaocr.trace[$index]=$sample" }
		}
	}

	private fun detectedBlocksToRegions(blocks: List<OcrTextBlock>): List<TextRegion> {
		return blocks.mapNotNull { block ->
			val rect = block.boundingBox ?: return@mapNotNull null
			TextRegion(
				rect = rect,
				confidence = block.confidence,
				detectorId = "mlkit_block",
				directionHint = block.directionHint,
				angleHintDegrees = block.angleHintDegrees,
				isAxisAligned = block.isAxisAligned,
				quadPoints = block.quadPoints ?: rectToTextQuad(rect),
			)
		}
	}

	private suspend fun translateBlocksCached(
		texts: List<String>,
		sourceLang: String,
		targetLang: String,
	): Map<String, String> {
		return translationCoordinator.translateBlocksCached(
			texts = texts,
			sourceLang = sourceLang,
			targetLang = targetLang,
		)
	}

	private suspend fun ensureLocalFileUri(sourceUri: Uri): Uri? {
		if (sourceUri.isFileUri()) return sourceUri
		return runCatching {
			val key = "reader_translate_src_${sourceUri}".sha256()
			cache[key]?.toUri()?.takeIf { it.isFileUri() }?.let { return it }
			when (sourceUri.scheme?.lowercase()) {
				"content", "android.resource" -> {
					val type = context.contentResolver.getType(sourceUri)?.toMimeTypeOrNull()
					context.contentResolver.openInputStream(sourceUri)?.use { input ->
						cache.set(key, input.source(), type).toUri()
					}
				}

				"http", "https" -> {
					val request = Request.Builder().url(sourceUri.toString()).get().build()
					okHttpClient.newCall(request).await().use { resp ->
						if (!resp.isSuccessful) return null
						val body = resp.body ?: return null
						val mimeType = body.contentType()?.toString()?.toMimeTypeOrNull()
						cache.set(key, body.source(), mimeType).toUri()
					}
				}

				else -> null
			}
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	private fun mergePageTextBlocks(
		textBlocks: List<OcrTextBlock>,
		sourceLang: String,
	): List<TextFragment> {
		val fragments = textBlocks.asSequence()
			.mapNotNull { block ->
				val rect = block.boundingBox ?: return@mapNotNull null
				val text = block.text.trim()
				if (text.isEmpty()) {
					null
				} else {
					TextFragment(
						rect = rect,
						text = text,
						directionHint = if (block.directionHint != TextDirectionHint.UNKNOWN) {
							block.directionHint
						} else {
							inferTextDirectionHint(rect, text)
						},
						angleHintDegrees = block.angleHintDegrees,
						isAxisAligned = block.isAxisAligned,
						quadPoints = block.quadPoints ?: rectToTextQuad(rect),
						detectorId = block.detectorId,
					)
				}
			}
			.toList()
		if (fragments.isNotEmpty() && fragments.all { it.detectorId.startsWith(BUBBLE_DETECTOR_ID_PREFIX) }) {
			return fragments
		}
		return textMergeCoordinator.merge(
			fragments = fragments,
			sourceLang = sourceLang,
		)
	}

	private fun buildBubbleInputsFromFragments(
		fragments: List<TextFragment>,
		sourceLang: String,
		targetLang: String,
	): List<BubbleInput> {
		return fragments.mapNotNull { fragment ->
			val sourceText = normalizeTextForTranslation(fragment.text, sourceLang)
			if (sourceText.isBlank()) {
				return@mapNotNull null
			}
			BubbleInput(
				rect = Rect(fragment.rect),
				sourceText = sourceText,
				verticalPreferred = false,
				sourceContentRect = Rect(fragment.rect),
				sourceContentRects = listOf(Rect(fragment.rect)),
			)
		}
	}

	private fun buildBubbleInputs(
		groups: List<GroupedBubbleSource>,
		sourceLang: String,
		targetLang: String,
	): List<BubbleInput> {
		return groups.mapIndexedNotNull { index, group ->
			val orderedFragments = sortFragmentsForReadingOrder(group.fragments, sourceLang)
			val mergedRect = group.bubbleRect ?: mergeRects(group.fragments.map { it.rect }) ?: return@mapIndexedNotNull null
			val sourceText = normalizeTextForTranslation(
				composeGroupedText(orderedFragments, sourceLang),
				sourceLang,
			)
			if (sourceText.isBlank()) {
				return@mapIndexedNotNull null
			}
			BubbleInput(
				rect = mergedRect,
				sourceText = sourceText,
				verticalPreferred = false,
				classId = group.classId,
				detectorAnchored = group.detectorAnchored,
				sourceContentRect = mergeRects(orderedFragments.map { it.rect }),
				sourceContentRects = orderedFragments.map { Rect(it.rect) },
			)
		}
	}

	private fun shouldMergeFragments(a: TextFragment, b: TextFragment): Boolean {
		val aDirection = effectiveDirectionHint(a)
		val bDirection = effectiveDirectionHint(b)
		if (a.isAxisAligned && b.isAxisAligned) {
			val angleDiff = kotlin.math.abs(effectiveAngleHintDegrees(a) - effectiveAngleHintDegrees(b))
			if (angleDiff in 45.1f..134.9f) {
				return false
			}
		}
		if (
			aDirection != TextDirectionHint.UNKNOWN &&
			bDirection != TextDirectionHint.UNKNOWN &&
			aDirection != bDirection
		) {
			return false
		}
		val acx = a.rect.centerX().toFloat()
		val acy = a.rect.centerY().toFloat()
		val bcx = b.rect.centerX().toFloat()
		val bcy = b.rect.centerY().toFloat()
		val dx = kotlin.math.abs(acx - bcx)
		val dy = kotlin.math.abs(acy - bcy)
		val minW = min(a.rect.width(), b.rect.width()).toFloat().coerceAtLeast(1f)
		val minH = min(a.rect.height(), b.rect.height()).toFloat().coerceAtLeast(1f)
		val xOverlap = overlapLen(a.rect.left, a.rect.right, b.rect.left, b.rect.right).toFloat()
		val yOverlap = overlapLen(a.rect.top, a.rect.bottom, b.rect.top, b.rect.bottom).toFloat()
		val xOverlapRatio = xOverlap / minW
		val yOverlapRatio = yOverlap / minH
		val gapX = axisGap(a.rect.left, a.rect.right, b.rect.left, b.rect.right).toFloat()
		val gapY = axisGap(a.rect.top, a.rect.bottom, b.rect.top, b.rect.bottom).toFloat()
		val maxGapScale = 0.85f
		val minOverlapRatio = 0.18f
		val inflationLimit = 2.2f
		val maxGapX = minW * maxGapScale + dp(3f)
		val maxGapY = minH * maxGapScale + dp(3f)
		val preferColumnMerge = shouldPreferColumnMerge(a, b, aDirection, bDirection)
		val sameColumnCandidate =
			yOverlapRatio >= (minOverlapRatio * 1.35f) &&
				gapX <= maxGapX * 0.95f &&
				dx <= minW * 1.85f
		val sameRowCandidate =
			xOverlapRatio >= (minOverlapRatio * 1.35f) &&
				gapY <= maxGapY * 0.95f &&
				dy <= minH * 1.85f
		val primaryAxisAligned = if (preferColumnMerge) sameColumnCandidate else sameRowCandidate
		if (!primaryAxisAligned) return false
		// Reject pair if merged area grows too much compared with two source boxes.
		val merged = Rect(
			min(a.rect.left, b.rect.left),
			min(a.rect.top, b.rect.top),
			max(a.rect.right, b.rect.right),
			max(a.rect.bottom, b.rect.bottom),
		)
		val sumArea = rectArea(a.rect) + rectArea(b.rect)
		val mergedArea = rectArea(merged)
		if (sumArea > 0f) {
			val inflation = mergedArea / sumArea
			if (inflation > inflationLimit && xOverlapRatio < 0.45f && yOverlapRatio < 0.45f) return false
		}

		// Final center-distance guard.
		val distanceScale = 2.2f
		return dx <= minW * distanceScale && dy <= minH * distanceScale
	}

	private fun shouldPreferColumnMerge(
		a: TextFragment,
		b: TextFragment,
		aDirection: TextDirectionHint,
		bDirection: TextDirectionHint,
	): Boolean {
		return when {
			aDirection == TextDirectionHint.VERTICAL || bDirection == TextDirectionHint.VERTICAL -> true
			aDirection == TextDirectionHint.HORIZONTAL || bDirection == TextDirectionHint.HORIZONTAL -> false
			isLikelyVerticalFragment(a) && isLikelyVerticalFragment(b) -> true
			isLikelyHorizontalFragment(a) && isLikelyHorizontalFragment(b) -> false
			else -> {
				val avgWidth = (a.rect.width() + b.rect.width()) / 2f
				val avgHeight = (a.rect.height() + b.rect.height()) / 2f
				avgHeight > avgWidth * 1.2f
			}
		}
	}

	private fun isLikelyVerticalFragment(fragment: TextFragment): Boolean {
		return when (effectiveDirectionHint(fragment)) {
			TextDirectionHint.VERTICAL -> true
			TextDirectionHint.HORIZONTAL -> false
			else -> fragment.rect.height() > fragment.rect.width() * 1.2f
		}
	}

	private fun isLikelyHorizontalFragment(fragment: TextFragment): Boolean {
		return when (effectiveDirectionHint(fragment)) {
			TextDirectionHint.HORIZONTAL -> true
			TextDirectionHint.VERTICAL -> false
			else -> fragment.rect.width() >= fragment.rect.height() * 0.85f
		}
	}

	private fun effectiveDirectionHint(fragment: TextFragment): TextDirectionHint {
		if (fragment.directionHint != TextDirectionHint.UNKNOWN) {
			return fragment.directionHint
		}
		return inferTextDirectionHint(fragment.rect, fragment.text)
	}

	private fun effectiveAngleHintDegrees(fragment: TextFragment): Float {
		if (fragment.angleHintDegrees != 0f || fragment.directionHint == TextDirectionHint.HORIZONTAL) {
			return fragment.angleHintDegrees
		}
		return inferTextAngleHintDegrees(fragment.rect, fragment.text)
	}

	private fun overlapLen(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int {
		return (min(aEnd, bEnd) - max(aStart, bStart)).coerceAtLeast(0)
	}

	private fun axisGap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int {
		return when {
			aEnd < bStart -> bStart - aEnd
			bEnd < aStart -> aStart - bEnd
			else -> 0
		}
	}

	private fun rectArea(rect: Rect): Float {
		return (rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)).toFloat()
	}

	private fun composeGroupedText(group: List<TextFragment>, sourceLang: String): String {
		if (group.isEmpty()) return ""
		val isJapanese = isJapaneseSourceLanguage(sourceLang)
		val sorted = sortFragmentsForReadingOrder(group, sourceLang)
		val separator = if (isJapanese && isLikelyColumnLayout(group)) "\n" else ""
		return sorted.joinToString(separator) { it.text.trim() }.trim()
	}

	private fun normalizeTextForTranslation(text: String, sourceLang: String): String {
		val trimmed = text.trim()
		if (trimmed.isEmpty() || isJapaneseSourceLanguage(sourceLang)) return trimmed
		return trimmed
			.replace(HYPHENATED_LINE_BREAK_REGEX, "")
			.replace(INLINE_HYPHENATED_WORD_REGEX, "")
			.replace(Regex("""[ \t]{2,}"""), " ")
			.trim()
	}

	private fun sortFragmentsForReadingOrder(
		group: List<TextFragment>,
		sourceLang: String,
	): List<TextFragment> {
		if (group.isEmpty()) return emptyList()
		return if (isJapaneseSourceLanguage(sourceLang)) {
			group.sortedWith(
				compareByDescending<TextFragment> { it.rect.centerX() }
					.thenBy { it.rect.centerY() }
			)
		} else {
			group.sortedWith(compareBy<TextFragment> { it.rect.centerY() }.thenBy { it.rect.centerX() })
		}
	}

	private fun isJapaneseSourceLanguage(sourceLang: String): Boolean {
		return sourceLang.trim().lowercase().startsWith("ja")
	}

	private fun isLikelyColumnLayout(group: List<TextFragment>): Boolean {
		if (group.size < 2) return false
		val avgWidth = group.map { it.rect.width() }.average()
		val avgHeight = group.map { it.rect.height() }.average()
		val xBuckets = group.map { it.rect.centerX() / max(1, dp(24f)) }.toSet().size
		return avgHeight > avgWidth * 1.2 && xBuckets >= 2
	}

	private fun isVerticalTargetLanguage(targetLang: String): Boolean {
		val normalized = targetLang.trim().lowercase()
		return normalized.startsWith("zh") || normalized.startsWith("ja")
	}

	private fun mergeRects(rects: List<Rect>): Rect? {
		if (rects.isEmpty()) return null
		var left = rects[0].left
		var top = rects[0].top
		var right = rects[0].right
		var bottom = rects[0].bottom
		for (i in 1 until rects.size) {
			left = min(left, rects[i].left)
			top = min(top, rects[i].top)
			right = max(right, rects[i].right)
			bottom = max(bottom, rects[i].bottom)
		}
		return Rect(left, top, right, bottom)
	}

	private fun expandRect(rect: Rect, pad: Int): Rect {
		return Rect(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
	}

	private fun clampRect(rect: Rect, bitmapWidth: Int, bitmapHeight: Int): Rect {
		return Rect(
			rect.left.coerceIn(0, bitmapWidth - 1),
			rect.top.coerceIn(0, bitmapHeight - 1),
			rect.right.coerceIn(1, bitmapWidth),
			rect.bottom.coerceIn(1, bitmapHeight),
		)
	}

	private fun expandRectToMinimumHeight(rect: Rect, minimumHeight: Int, bitmapHeight: Int): Rect {
		val targetHeight = minimumHeight.coerceAtMost(bitmapHeight)
		if (rect.height() >= targetHeight) return rect
		val top = (rect.centerY() - targetHeight / 2).coerceIn(0, bitmapHeight - targetHeight)
		return Rect(rect.left, top, rect.right, top + targetHeight)
	}

	private fun expandRectToMinimumWidth(rect: Rect, minimumWidth: Int, bitmapWidth: Int): Rect {
		val targetWidth = minimumWidth.coerceAtMost(bitmapWidth)
		if (rect.width() >= targetWidth) return rect
		val left = (rect.centerX() - targetWidth / 2).coerceIn(0, bitmapWidth - targetWidth)
		return Rect(left, rect.top, left + targetWidth, rect.bottom)
	}

	private fun compactOverlayMinimumHeight(padding: Int): Int {
		val measurePaint = TextPaint(textPaintTemplate).apply {
			textSize = dp(MIN_RENDER_TEXT_SIZE_DP).toFloat()
		}
		val metrics = measurePaint.fontMetricsInt
		return (metrics.bottom - metrics.top).coerceAtLeast(1) + padding * 2
	}

	private fun compactOverlayMinimumWidth(text: String, padding: Int): Int {
		val glyphs = textToGlyphs(text).ifEmpty { listOf("…") }
		val cellSize = resolveVerticalCellSize(glyphs, dp(MIN_RENDER_TEXT_SIZE_DP).toFloat())
		return cellSize + padding * 2
	}

	private fun prepareTranslatedBubble(
		input: BubbleInput,
		text: String,
		bitmapWidth: Int,
		bitmapHeight: Int,
		bubbleLikeRegion: Boolean,
	): PreparedBubble? {
		if (bitmapWidth <= 1 || bitmapHeight <= 1) {
			return null
		}
		val rect = input.rect
		val verticalPreferred = input.verticalPreferred
		val detectorAnchored = input.detectorAnchored
		val sourceContentRect = input.sourceContentRect
		val compactOverlay = readerTranslationRenderStyle() == ReaderTranslationRenderStyle.COMPACT_OVERLAY
		val padding = if (compactOverlay) dp(2f) else dp(4f)
		val normalizedRect = Rect(
			rect.left.coerceIn(0, bitmapWidth - 1),
			rect.top.coerceIn(0, bitmapHeight - 1),
			rect.right.coerceIn(1, bitmapWidth),
			rect.bottom.coerceIn(1, bitmapHeight),
		)
		val normalizedContentRect = sourceContentRect?.let {
			Rect(
				it.left.coerceIn(0, bitmapWidth - 1),
				it.top.coerceIn(0, bitmapHeight - 1),
				it.right.coerceIn(1, bitmapWidth),
				it.bottom.coerceIn(1, bitmapHeight),
			)
		}
		val normalizedContentRects = input.sourceContentRects.mapNotNull { contentRect ->
			val normalized = Rect(
				contentRect.left.coerceIn(0, bitmapWidth - 1),
				contentRect.top.coerceIn(0, bitmapHeight - 1),
				contentRect.right.coerceIn(1, bitmapWidth),
				contentRect.bottom.coerceIn(1, bitmapHeight),
			)
			normalized.takeIf { it.width() > 1 && it.height() > 1 }
		}
		if (compactOverlay) {
			val anchorRect = normalizedContentRect ?: normalizedRect
			val compactAnchorRect = clampRect(
				rect = expandRect(anchorRect, dp(1f)),
				bitmapWidth = bitmapWidth,
				bitmapHeight = bitmapHeight,
			)
			val compactHeightRect = expandRectToMinimumHeight(
				rect = compactAnchorRect,
				minimumHeight = compactOverlayMinimumHeight(padding),
				bitmapHeight = bitmapHeight,
			)
			val compactRect = if (verticalPreferred) {
				expandRectToMinimumWidth(
					rect = compactHeightRect,
					minimumWidth = compactOverlayMinimumWidth(text, padding),
					bitmapWidth = bitmapWidth,
				)
			} else {
				compactHeightRect
			}
			return solveCompactOverlayBubble(
				input = input.copy(
					sourceContentRect = anchorRect,
					sourceContentRects = emptyList(),
				),
				text = text,
				anchorRect = compactRect,
				padding = padding,
				verticalPreferred = verticalPreferred,
				bitmapWidth = bitmapWidth,
				bitmapHeight = bitmapHeight,
			)
		}
		val rawRect = if (detectorAnchored && normalizedContentRect != null) {
			mergeRects(
				listOf(
					normalizedRect,
					expandRect(normalizedContentRect, dp(DETECTOR_CONTENT_MERGE_PADDING_DP)),
				),
			) ?: normalizedRect
		} else {
			normalizedRect
		}
		val baseRect = stabilizeRenderRect(
			rect = rawRect,
			bitmapWidth = bitmapWidth,
			bitmapHeight = bitmapHeight,
			verticalPreferred = verticalPreferred,
			bubbleLikeRegion = bubbleLikeRegion,
			detectorAnchored = detectorAnchored,
			sourceContentRect = sourceContentRect,
		)
		val expansionScales = if (detectorAnchored) {
			DETECTOR_ANCHORED_EXPAND_SCALES
		} else {
			BUBBLE_EXPAND_SCALES
		}
		val outerRects = resolveRenderOuterRects(
			baseRect = baseRect,
			expansionScales = expansionScales,
			bitmapWidth = bitmapWidth,
			bitmapHeight = bitmapHeight,
		)
		if (normalizedContentRect != null && normalizedContentRects.size > 1) {
			val segmented = prepareSegmentedBubble(
				text = text,
				sourceContentRect = normalizedContentRect,
				sourceContentRects = normalizedContentRects,
				outerRects = outerRects,
				verticalPreferred = verticalPreferred,
				input = input.copy(
					sourceContentRect = normalizedContentRect,
					sourceContentRects = normalizedContentRects.map { Rect(it) },
				),
			)
			if (segmented?.segments?.isNotEmpty() == true) {
				return segmented
			}
		}
		return solveSingleBoxBubble(
			input = input,
			text = text,
			outerRects = outerRects,
			padding = padding,
			sourceContentRect = normalizedContentRect,
			bubbleLikeRegion = bubbleLikeRegion,
			verticalPreferred = verticalPreferred,
			bitmapWidth = bitmapWidth,
			bitmapHeight = bitmapHeight,
			allowLocalExpansion = true,
			allowEllipsize = false,
		)
	}

	private fun computeLayoutBounds(layout: StaticLayout): LayoutBounds {
		var minLeft = 0f
		var maxRight = 1f
		var initialized = false
		for (i in 0 until layout.lineCount) {
			val lineLeft = layout.getLineLeft(i)
			val lineRight = layout.getLineRight(i)
			if (!initialized) {
				minLeft = lineLeft
				maxRight = lineRight
				initialized = true
			} else {
				minLeft = min(minLeft, lineLeft)
				maxRight = max(maxRight, lineRight)
			}
		}
		if (!initialized) {
			return LayoutBounds(
				left = 0f,
				right = 1f,
				width = 1,
			)
		}
		return LayoutBounds(
			left = minLeft,
			right = maxRight,
			width = ceil((maxRight - minLeft).toDouble()).toInt().coerceAtLeast(1),
		)
	}

	private fun computeVerticalUsedWidth(plan: VerticalLayoutPlan): Int {
		val colsUsed = ceil(plan.glyphs.size / plan.rowCapacity.toDouble()).toInt().coerceAtLeast(1)
		return colsUsed * plan.cellSize
	}

	private fun computeVerticalUsedHeight(plan: VerticalLayoutPlan): Int {
		val rowsUsed = min(plan.rowCapacity, plan.glyphs.size).coerceAtLeast(1)
		return rowsUsed * plan.cellSize
	}

	private fun resolveVerticalCellSize(glyphs: List<String>, textSize: Float): Int {
		val measurePaint = TextPaint(textPaintTemplate).apply {
			this.textSize = textSize
		}
		var maxGlyphWidth = textSize
		for (glyph in glyphs) {
			if (glyph.isBlank()) continue
			maxGlyphWidth = max(maxGlyphWidth, measurePaint.measureText(glyph))
		}
		val sideBearingPadding = max(2f, textSize * 0.16f)
		return ceil(max(textSize * 1.18f, maxGlyphWidth + sideBearingPadding).toDouble())
			.toInt()
			.coerceAtLeast(1)
	}

	private fun stabilizeRenderRect(
		rect: Rect,
		bitmapWidth: Int,
		bitmapHeight: Int,
		verticalPreferred: Boolean,
		bubbleLikeRegion: Boolean,
		detectorAnchored: Boolean,
		sourceContentRect: Rect?,
	): Rect {
		val width = rect.width()
		val height = rect.height()
		if (width <= 0 || height <= 0) return rect
		if (detectorAnchored) {
			val normalizedContentRect = sourceContentRect?.let {
				Rect(
					it.left.coerceIn(0, bitmapWidth - 1),
					it.top.coerceIn(0, bitmapHeight - 1),
					it.right.coerceIn(1, bitmapWidth),
					it.bottom.coerceIn(1, bitmapHeight),
				)
			}
			val contentWidth = normalizedContentRect?.width()?.coerceAtLeast(0) ?: 0
			val contentHeight = normalizedContentRect?.height()?.coerceAtLeast(0) ?: 0
			val isTallStrip = verticalPreferred || height > width * 2
			var expanded = Rect(rect)
			if (isTallStrip) {
				val minDetectorWidth = dp(34f)
				val maxDetectorWidth = dp(140f)
				val targetWidth = max(
					expanded.width(),
					max(
						minDetectorWidth,
						max(
							contentWidth + dp(18f),
							(expanded.height() * DETECTOR_ANCHORED_MIN_WIDTH_RATIO).toInt(),
						),
					),
				).coerceAtMost(min(maxDetectorWidth, bitmapWidth))
				if (targetWidth > expanded.width()) {
					val cx = expanded.centerX()
					var left = cx - targetWidth / 2
					var right = left + targetWidth
					if (left < 0) {
						left = 0
						right = targetWidth
					}
					if (right > bitmapWidth) {
						right = bitmapWidth
						left = right - targetWidth
					}
					expanded = Rect(
						left.coerceIn(0, bitmapWidth - 1),
						expanded.top.coerceIn(0, bitmapHeight - 1),
						right.coerceIn(1, bitmapWidth),
						expanded.bottom.coerceIn(1, bitmapHeight),
					)
				}
			}
			val minDetectorHeight = max(
				expanded.height(),
				max(
					contentHeight + dp(18f),
					if (verticalPreferred) contentHeight + dp(28f) else contentHeight + dp(14f),
				),
			).coerceAtMost(bitmapHeight)
			if (minDetectorHeight > expanded.height()) {
				val cy = expanded.centerY()
				var top = cy - minDetectorHeight / 2
				var bottom = top + minDetectorHeight
				if (top < 0) {
					top = 0
					bottom = minDetectorHeight
				}
				if (bottom > bitmapHeight) {
					bottom = bitmapHeight
					top = bottom - minDetectorHeight
				}
				expanded = Rect(
					expanded.left.coerceIn(0, bitmapWidth - 1),
					top.coerceIn(0, bitmapHeight - 1),
					expanded.right.coerceIn(1, bitmapWidth),
					bottom.coerceIn(1, bitmapHeight),
				)
			}
			return expanded
		}
		if (bubbleLikeRegion) return rect
		val minRenderColumnWidth = dp(56f)
		val maxRenderColumnWidth = dp(120f)
		if (width >= minRenderColumnWidth || height <= width * 2) return rect
		val targetWidth = max(
			minRenderColumnWidth,
			min(maxRenderColumnWidth, (height * MIN_RENDER_COLUMN_WIDTH_RATIO).toInt()),
		).coerceAtMost(bitmapWidth)
		if (targetWidth <= width) return rect
		val cx = rect.centerX()
		var left = cx - targetWidth / 2
		var right = left + targetWidth
		if (left < 0) {
			left = 0
			right = targetWidth
		}
		if (right > bitmapWidth) {
			right = bitmapWidth
			left = right - targetWidth
		}
		return Rect(
			left.coerceIn(0, bitmapWidth - 1),
			rect.top.coerceIn(0, bitmapHeight - 1),
			right.coerceIn(1, bitmapWidth),
			rect.bottom.coerceIn(1, bitmapHeight),
		)
	}

	private fun initialHorizontalTextSize(text: String, width: Int, height: Int): Float {
		val len = text.filterNot { it.isWhitespace() }.length.coerceAtLeast(1)
		val areaBasedSize = Math.sqrt(width.toDouble() * height * 0.45 / len).toFloat()
		val dimBound = min(
			height * 0.42f,
			width * HORIZONTAL_TEXT_SIZE_WIDTH_RATIO,
		)
		return min(
			dp(MAX_RENDER_TEXT_SIZE_DP).toFloat(),
			min(areaBasedSize, dimBound)
		).coerceAtLeast(dp(MIN_INITIAL_TEXT_SIZE_DP).toFloat())
	}

	private fun resolveHorizontalMaxTextSize(width: Int, height: Int): Float {
		return min(
			dp(MAX_RENDER_TEXT_SIZE_DP).toFloat(),
			min(
				height * 0.82f,
				width * 0.82f,
			),
		).coerceAtLeast(dp(MIN_INITIAL_TEXT_SIZE_DP).toFloat())
	}

	private fun resolveVerticalMaxTextSize(width: Int, height: Int): Float {
		return min(
			dp(MAX_VERTICAL_RENDER_TEXT_SIZE_DP).toFloat(),
			min(
				height * 0.48f,
				width * 0.82f,
			),
		).coerceAtLeast(dp(MIN_INITIAL_TEXT_SIZE_DP).toFloat())
	}

	private fun centerRectByContent(
		outer: Rect,
		contentWidth: Int,
		contentHeight: Int,
		padding: Int,
		sourceContentRect: Rect? = null,
	): Rect {
		val extraScale = 1.10f
		val minSide = dp(12f)
		val minTargetW = min(minSide, outer.width()).coerceAtLeast(1)
		val minTargetH = min(minSide, outer.height()).coerceAtLeast(1)
		// Ensure the rendered rect at least covers the source text area so original text is masked
		val minSourceW = sourceContentRect?.let { it.width() + padding * 2 } ?: 0
		val minSourceH = sourceContentRect?.let { it.height() + padding * 2 } ?: 0
		val targetW = max(
			((contentWidth + padding * 2) * extraScale).toInt(),
			minSourceW,
		).coerceIn(minTargetW, outer.width())
		val targetH = max(
			((contentHeight + padding * 2) * extraScale).toInt(),
			minSourceH,
		).coerceIn(minTargetH, outer.height())
		val cx = sourceContentRect?.centerX() ?: outer.centerX()
		val cy = sourceContentRect?.centerY() ?: outer.centerY()
		var left = cx - targetW / 2
		var top = cy - targetH / 2
		var right = left + targetW
		var bottom = top + targetH
		if (left < outer.left) {
			left = outer.left
			right = left + targetW
		}
		if (top < outer.top) {
			top = outer.top
			bottom = top + targetH
		}
		if (right > outer.right) {
			right = outer.right
			left = right - targetW
		}
		if (bottom > outer.bottom) {
			bottom = outer.bottom
			top = bottom - targetH
		}
		return Rect(left, top, right, bottom)
	}

	private fun isLikelySpeechBubbleRegion(bitmap: Bitmap, rect: Rect): Boolean {
		val left = rect.left.coerceIn(0, bitmap.width - 1)
		val top = rect.top.coerceIn(0, bitmap.height - 1)
		val right = rect.right.coerceIn(left + 1, bitmap.width)
		val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
		val w = right - left
		val h = bottom - top
		if (w <= 0 || h <= 0) return false
		val stepX = max(1, w / 10)
		val stepY = max(1, h / 10)
		var total = 0
		var bright = 0
		for (y in top until bottom step stepY) {
			for (x in left until right step stepX) {
				val pixel = bitmap.getPixel(x, y)
				val lum = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
				total++
				if (lum >= 220) bright++
			}
		}
		if (total == 0) return false
		val brightRatio = bright.toFloat() / total.toFloat()
		return brightRatio >= 0.62f
	}

	private fun readerTranslationRenderStyle(): ReaderTranslationRenderStyle {
		return ReaderTranslationRenderStyle.fromPreference(settings.readerTranslationRenderStyle)
	}

	private fun renderTextColor(): Int {
		return when (readerTranslationRenderStyle()) {
			ReaderTranslationRenderStyle.REPLACE -> Color.BLACK
			ReaderTranslationRenderStyle.COMPACT_OVERLAY -> Color.WHITE
		}
	}

	private fun drawBubbleBackground(canvas: Canvas, bubble: PreparedBubble) {
		val roundRadius = dp(6f).toFloat()
		val paint = when (readerTranslationRenderStyle()) {
			ReaderTranslationRenderStyle.REPLACE -> bubblePaint
			ReaderTranslationRenderStyle.COMPACT_OVERLAY -> compactOverlayPaint
		}
		if (bubble.segments.isNotEmpty()) {
			for (segment in bubble.segments) {
				canvas.drawRoundRect(RectF(segment.backgroundRect), roundRadius, roundRadius, paint)
			}
			return
		}
		canvas.drawRoundRect(RectF(bubble.rect), roundRadius, roundRadius, paint)
	}

	private fun drawBubbleText(canvas: Canvas, bubble: PreparedBubble) {
		if (bubble.segments.isNotEmpty()) {
			for (segment in bubble.segments) {
				val vertical = segment.verticalPlan
				if (vertical != null) {
					drawVerticalText(
						canvas = canvas,
						plan = vertical,
						contentLeft = segment.contentRect.left.toFloat(),
						contentTop = segment.contentRect.top.toFloat(),
						contentWidth = segment.contentRect.width(),
						contentHeight = segment.contentRect.height(),
					)
				} else {
					segment.layout?.let { layout ->
						drawHorizontalLayout(
							canvas = canvas,
							layout = layout,
							contentLeft = segment.contentRect.left.toFloat(),
							contentTop = segment.contentRect.top.toFloat(),
							contentWidth = segment.contentRect.width(),
							contentHeight = segment.contentRect.height(),
						)
					}
				}
			}
			return
		}
		val contentLeft = (bubble.rect.left + bubble.padding).toFloat()
		val contentTop = (bubble.rect.top + bubble.padding).toFloat()
		val vertical = bubble.verticalPlan
		if (vertical != null) {
			drawVerticalText(
				canvas = canvas,
				plan = vertical,
				contentLeft = contentLeft,
				contentTop = contentTop,
				contentWidth = bubble.contentWidth,
				contentHeight = bubble.contentHeight,
			)
		} else {
			bubble.layout?.let { layout ->
				drawHorizontalLayout(
					canvas = canvas,
					layout = layout,
					contentLeft = contentLeft,
					contentTop = contentTop,
					contentWidth = bubble.contentWidth,
					contentHeight = bubble.contentHeight,
				)
			}
		}
	}

	private fun drawBubbleDebugOverlay(canvas: Canvas, bubble: PreparedBubble) {
		val overlay = bubble.debugOverlay ?: return
		canvas.drawRect(overlay.sourceRect, debugSourceRectPaint)
		overlay.contentRect?.let { canvas.drawRect(it, debugSourceRectPaint) }
		canvas.drawRect(overlay.preparedRect, debugPreparedRectPaint)
		canvas.drawRect(overlay.contentAreaRect, debugContentRectPaint)
		val label = buildString {
			append(if (overlay.detectorAnchored) "DET" else "GRP")
			append(' ')
			append(if (overlay.verticalPreferred) "V" else "H")
		}
		val diagnosis = overlay.diagnosis
		val labelX = overlay.preparedRect.left.toFloat() + dp(2f)
		val labelY = (overlay.preparedRect.top - dp(3f)).coerceAtLeast(dp(10f)).toFloat()
		canvas.drawText(
			label,
			labelX,
			labelY,
			debugLabelPaint,
		)
		canvas.drawText(
			diagnosis,
			labelX,
			labelY + debugLabelPaint.textSize + dp(1f),
			debugLabelPaint,
		)
	}

	private fun buildBubbleDebugOverlay(
		input: BubbleInput,
		preparedRect: Rect,
		padding: Int,
		contentWidth: Int,
		contentHeight: Int,
		segments: List<PreparedBubbleSegment> = emptyList(),
	): BubbleDebugOverlay {
		val contentAreaRect = if (segments.isNotEmpty()) {
			mergeRects(segments.map { it.contentRect }) ?: Rect(
				preparedRect.left + padding,
				preparedRect.top + padding,
				preparedRect.left + padding + contentWidth,
				preparedRect.top + padding + contentHeight,
			)
		} else {
			Rect(
				preparedRect.left + padding,
				preparedRect.top + padding,
				preparedRect.left + padding + contentWidth,
				preparedRect.top + padding + contentHeight,
			)
		}
		val diagnosis = diagnoseBubbleRender(
			contentRect = input.sourceContentRect,
			preparedRect = preparedRect,
			contentAreaRect = contentAreaRect,
		)
		return BubbleDebugOverlay(
			sourceRect = Rect(input.rect),
			contentRect = input.sourceContentRect?.let(::Rect),
			preparedRect = Rect(preparedRect),
			contentAreaRect = contentAreaRect,
			detectorAnchored = input.detectorAnchored,
			verticalPreferred = input.verticalPreferred,
			diagnosis = diagnosis,
		)
	}

	private fun diagnoseBubbleRender(
		contentRect: Rect?,
		preparedRect: Rect,
		contentAreaRect: Rect,
	): String {
		contentRect ?: return "无内容框"
		val contentWidth = contentRect.width().coerceAtLeast(1)
		val contentHeight = contentRect.height().coerceAtLeast(1)
		val preparedWidthRatio = preparedRect.width().toFloat() / contentWidth.toFloat()
		val preparedHeightRatio = preparedRect.height().toFloat() / contentHeight.toFloat()
		val contentAreaWidthRatio = contentAreaRect.width().toFloat() / contentWidth.toFloat()
		val contentAreaHeightRatio = contentAreaRect.height().toFloat() / contentHeight.toFloat()
		return when {
			preparedWidthRatio < 0.96f || preparedHeightRatio < 0.96f -> "渲染框偏小"
			contentAreaWidthRatio < 0.92f || contentAreaHeightRatio < 0.92f -> "内容区偏小"
			preparedWidthRatio > 1.35f || preparedHeightRatio > 1.35f -> "渲染框偏大"
			else -> "基本匹配"
		}
	}

	private fun drawHorizontalLayout(
		canvas: Canvas,
		layout: StaticLayout,
		contentLeft: Float,
		contentTop: Float,
		contentWidth: Int,
		contentHeight: Int,
	) {
		val bounds = computeLayoutBounds(layout)
		val dx = ((contentWidth - bounds.width) / 2f - bounds.left).coerceAtLeast(-bounds.left)
		val dy = ((contentHeight - layout.height) / 2f).coerceAtLeast(0f)
		canvas.save()
		canvas.clipRect(
			contentLeft,
			contentTop,
			contentLeft + contentWidth,
			contentTop + contentHeight,
		)
		canvas.translate(contentLeft + dx, contentTop + dy)
		layout.draw(canvas)
		canvas.restore()
	}

	private fun drawVerticalText(
		canvas: Canvas,
		plan: VerticalLayoutPlan,
		contentLeft: Float,
		contentTop: Float,
		contentWidth: Int,
		contentHeight: Int,
	) {
		val drawPaint = TextPaint(textPaintTemplate).apply {
			color = renderTextColor()
			textSize = plan.textSize
			textAlign = Paint.Align.CENTER
		}
		val fm = drawPaint.fontMetrics
		val baselineOffset = -(fm.ascent + fm.descent) / 2f
		val cell = plan.cellSize.toFloat()
		val usedWidth = computeVerticalUsedWidth(plan).toFloat()
		val usedHeight = computeVerticalUsedHeight(plan).toFloat()
		val offsetX = ((contentWidth - usedWidth) / 2f).coerceAtLeast(0f)
		val offsetY = ((contentHeight - usedHeight) / 2f).coerceAtLeast(0f)
		canvas.save()
		canvas.clipRect(
			contentLeft,
			contentTop,
			contentLeft + contentWidth,
			contentTop + contentHeight,
		)
		plan.glyphs.forEachIndexed { index, glyph ->
			val col = index / plan.rowCapacity
			val row = index % plan.rowCapacity
			val cx = contentLeft + offsetX + usedWidth - cell * (col + 0.5f)
			val cy = contentTop + offsetY + cell * (row + 0.5f)
			canvas.drawText(glyph, cx, cy + baselineOffset, drawPaint)
		}
		canvas.restore()
	}

	private fun buildVerticalPlan(text: String, width: Int, height: Int): VerticalLayoutPlan? {
		val glyphs = textToGlyphs(text)
		if (glyphs.isEmpty()) return null
		val len = glyphs.count { it.isNotBlank() }.coerceAtLeast(1)
		val areaBasedSize = Math.sqrt(width.toDouble() * height * 0.45 / len).toFloat()
		val dimBound = min(
			height * 0.42f,
			width * VERTICAL_TEXT_SIZE_WIDTH_RATIO,
		)
		var textSize = min(
			dp(MAX_VERTICAL_RENDER_TEXT_SIZE_DP).toFloat(),
			min(areaBasedSize, dimBound)
		)
		val minSize = dp(MIN_INITIAL_TEXT_SIZE_DP).toFloat()
		while (textSize >= minSize) {
			val cell = resolveVerticalCellSize(glyphs, textSize)
			val rows = max(1, height / cell)
			val colsMax = max(1, width / cell)
			val colsNeed = ceil(glyphs.size / rows.toDouble()).toInt()
			if (colsNeed <= colsMax) {
				return VerticalLayoutPlan(
					glyphs = glyphs,
					textSize = textSize,
					cellSize = cell,
					rowCapacity = rows,
				)
			}
			textSize -= 1f
		}
		val cell = resolveVerticalCellSize(glyphs, minSize)
		val rows = max(1, height / cell)
		val colsMax = max(1, width / cell)
		val maxGlyphs = max(1, rows * colsMax)
		val trimmed = if (glyphs.size > maxGlyphs) {
			glyphs.take(maxGlyphs - 1) + "…"
		} else {
			glyphs
		}
		return VerticalLayoutPlan(
			glyphs = trimmed,
			textSize = minSize,
			cellSize = cell,
			rowCapacity = rows,
		)
	}

	private fun fitVerticalPlan(
		text: String,
		width: Int,
		height: Int,
		initialTextSize: Float,
	): VerticalLayoutFit? {
		val glyphs = textToGlyphs(text)
		if (glyphs.isEmpty()) return null
		val minSize = dp(MIN_RENDER_TEXT_SIZE_DP).toFloat()
		val maxSize = resolveVerticalMaxTextSize(width, height)
		var textSize = initialTextSize.coerceIn(minSize, maxSize)
		while (textSize >= minSize) {
			val fit = buildVerticalFitAtSize(
				glyphs = glyphs,
				width = width,
				height = height,
				textSize = textSize,
			)
			if (fit != null) {
				return fit
			}
			if (textSize == minSize) break
			textSize = max(minSize, textSize - 1f)
		}
		val cell = resolveVerticalCellSize(glyphs, minSize)
		val rows = max(1, height / cell)
		val colsMax = max(1, width / cell)
		val capacity = max(1, rows * colsMax)
		val neededCols = ceil(glyphs.size / rows.toDouble()).toInt().coerceAtLeast(1)
		return VerticalLayoutFit(
			plan = VerticalLayoutPlan(
				glyphs = glyphs,
				textSize = minSize,
				cellSize = cell,
				rowCapacity = rows,
			),
			requiredWidth = neededCols * cell,
			requiredHeight = min(rows, glyphs.size).coerceAtLeast(1) * cell,
			overflow = (glyphs.size - capacity).coerceAtLeast(0),
			truncated = glyphs.size > capacity,
		)
	}

	private fun buildVerticalFitAtSize(
		glyphs: List<String>,
		width: Int,
		height: Int,
		textSize: Float,
	): VerticalLayoutFit? {
		val cell = resolveVerticalCellSize(glyphs, textSize)
		val rows = max(1, height / cell)
		val colsMax = max(1, width / cell)
		val neededCols = ceil(glyphs.size / rows.toDouble()).toInt().coerceAtLeast(1)
		if (neededCols > colsMax) return null
		return VerticalLayoutFit(
			plan = VerticalLayoutPlan(
				glyphs = glyphs,
				textSize = textSize,
				cellSize = cell,
				rowCapacity = rows,
			),
			requiredWidth = neededCols * cell,
			requiredHeight = min(rows, glyphs.size).coerceAtLeast(1) * cell,
			overflow = 0,
			truncated = false,
		)
	}

	private fun textToGlyphs(text: String): List<String> {
		val cleaned = text.replace("\r", "").replace("\n", "").trim()
		if (cleaned.isBlank()) return emptyList()
		val result = ArrayList<String>(cleaned.length)
		var i = 0
		while (i < cleaned.length) {
			val cp = Character.codePointAt(cleaned, i)
			result.add(String(Character.toChars(cp)))
			i += Character.charCount(cp)
		}
		return result
	}

	private fun expandRectAroundCenter(rect: Rect, scale: Float, maxWidth: Int, maxHeight: Int): Rect {
		val cx = (rect.left + rect.right) / 2f
		val cy = (rect.top + rect.bottom) / 2f
		val halfW = rect.width() * scale * 0.5f
		val halfH = rect.height() * scale * 0.5f
		var left = (cx - halfW).toInt()
		var top = (cy - halfH).toInt()
		var right = (cx + halfW).toInt()
		var bottom = (cy + halfH).toInt()

		left = left.coerceAtLeast(0)
		top = top.coerceAtLeast(0)
		right = right.coerceAtMost(maxWidth)
		bottom = bottom.coerceAtMost(maxHeight)
		if (right <= left) right = (left + 1).coerceAtMost(maxWidth)
		if (bottom <= top) bottom = (top + 1).coerceAtMost(maxHeight)
		return Rect(left, top, right, bottom)
	}

	private fun buildTextLayout(
		text: String,
		width: Int,
		textSize: Float,
		maxLines: Int = Int.MAX_VALUE,
		ellipsize: TextUtils.TruncateAt? = null,
	): StaticLayout {
		val layoutPaint = TextPaint(textPaintTemplate).apply {
			color = renderTextColor()
			this.textSize = textSize
			textAlign = Paint.Align.LEFT
		}
		return StaticLayout.Builder.obtain(text, 0, text.length, layoutPaint, max(1, width))
			.setAlignment(Layout.Alignment.ALIGN_NORMAL)
			.setIncludePad(true)
			.setLineSpacing(0f, 1.05f)
			.setMaxLines(maxLines)
			.setEllipsize(ellipsize)
			.build()
	}

	private fun fitHorizontalLayout(
		text: String,
		width: Int,
		height: Int,
		initialTextSize: Float,
		allowEllipsize: Boolean = true,
	): HorizontalLayoutFit {
		val safeWidth = max(1, width)
		val safeHeight = max(1, height)
		val minTextSize = dp(MIN_RENDER_TEXT_SIZE_DP).toFloat()
		val maxTextSize = resolveHorizontalMaxTextSize(safeWidth, safeHeight)
		var textSize = initialTextSize.coerceIn(minTextSize, maxTextSize)
		var layout = buildTextLayout(text, safeWidth, textSize)
		var bounds = computeLayoutBounds(layout)
		while ((layout.height > safeHeight || hasHorizontalClipOverflow(bounds, safeWidth)) && textSize > minTextSize) {
			textSize = max(minTextSize, textSize - 1f)
			layout = buildTextLayout(text, safeWidth, textSize)
			bounds = computeLayoutBounds(layout)
		}
		val overflow = max(
			(layout.height - safeHeight).coerceAtLeast(0),
			horizontalClipOverflow(bounds, safeWidth),
		)
		if (overflow == 0 || !allowEllipsize) {
			return HorizontalLayoutFit(
				layout = layout,
				textSize = textSize,
				usedWidth = bounds.width,
				usedHeight = layout.height,
				overflow = overflow,
				truncated = overflow > 0,
			)
		}
		val lineHeight = computeLayoutLineHeight(layout)
		val maxLines = max(1, safeHeight / lineHeight)
		val truncatedLayout = buildTextLayout(
			text = text,
			width = safeWidth,
			textSize = textSize,
			maxLines = maxLines,
			ellipsize = TextUtils.TruncateAt.END,
		)
			return HorizontalLayoutFit(
				layout = truncatedLayout,
				textSize = textSize,
				usedWidth = computeLayoutBounds(truncatedLayout).width,
				usedHeight = truncatedLayout.height,
				overflow = overflow,
				truncated = true,
			)
	}

	private fun hasHorizontalClipOverflow(bounds: LayoutBounds, width: Int): Boolean {
		return horizontalClipOverflow(bounds, width) > 0
	}

	private fun horizontalClipOverflow(bounds: LayoutBounds, width: Int): Int {
		val tolerance = max(dp(HORIZONTAL_LAYOUT_WIDTH_OVERFLOW_TOLERANCE_DP), (width * 0.04f).toInt())
		return (bounds.width - width - tolerance).coerceAtLeast(0)
	}

	private fun solveCompactOverlayBubble(
		input: BubbleInput,
		text: String,
		anchorRect: Rect,
		padding: Int,
		verticalPreferred: Boolean,
		bitmapWidth: Int,
		bitmapHeight: Int,
	): PreparedBubble? {
		val preferred = if (verticalPreferred) {
			buildVisibleFirstVerticalBubble(
				input = input,
				text = text,
				anchorRect = anchorRect,
				padding = padding,
				bitmapWidth = bitmapWidth,
				bitmapHeight = bitmapHeight,
			) ?: buildVisibleFirstHorizontalBubble(
				input = input,
				text = text,
				anchorRect = anchorRect,
				padding = padding,
				bitmapWidth = bitmapWidth,
				bitmapHeight = bitmapHeight,
			)
		} else {
			buildVisibleFirstHorizontalBubble(
				input = input,
				text = text,
				anchorRect = anchorRect,
				padding = padding,
				bitmapWidth = bitmapWidth,
				bitmapHeight = bitmapHeight,
			) ?: buildVisibleFirstVerticalBubble(
				input = input,
				text = text,
				anchorRect = anchorRect,
				padding = padding,
				bitmapWidth = bitmapWidth,
				bitmapHeight = bitmapHeight,
			)
		}
		return preferred
	}

	private fun buildVisibleFirstHorizontalBubble(
		input: BubbleInput,
		text: String,
		anchorRect: Rect,
		padding: Int,
		bitmapWidth: Int,
		bitmapHeight: Int,
	): PreparedBubble? {
		for (rect in buildVisibleScaledRects(anchorRect, bitmapWidth, bitmapHeight)) {
			val contentWidth = max(1, rect.width() - padding * 2)
			val contentHeight = max(1, rect.height() - padding * 2)
			val fit = fitHorizontalLayout(
				text = text,
				width = contentWidth,
				height = contentHeight,
				initialTextSize = dp(MAX_RENDER_TEXT_SIZE_DP).toFloat(),
				allowEllipsize = true,
			)
			if (fit.usedHeight > contentHeight || hasHorizontalClipOverflow(computeLayoutBounds(fit.layout), contentWidth)) {
				continue
			}
			return PreparedBubble(
				rect = rect,
				padding = padding,
				contentWidth = contentWidth,
				contentHeight = contentHeight,
				layout = fit.layout,
				verticalPlan = null,
				debugOverlay = buildBubbleDebugOverlay(
					input = input,
					preparedRect = rect,
					padding = padding,
					contentWidth = contentWidth,
					contentHeight = contentHeight,
				),
			)
		}
		return null
	}

	private fun buildVisibleFirstVerticalBubble(
		input: BubbleInput,
		text: String,
		anchorRect: Rect,
		padding: Int,
		bitmapWidth: Int,
		bitmapHeight: Int,
	): PreparedBubble? {
		val glyphs = textToGlyphs(text)
		if (glyphs.isEmpty()) return null
		for (rect in buildVisibleScaledRects(anchorRect, bitmapWidth, bitmapHeight)) {
			val contentWidth = max(1, rect.width() - padding * 2)
			val contentHeight = max(1, rect.height() - padding * 2)
			val fit = fitVerticalPlan(
				text = text,
				width = contentWidth,
				height = contentHeight,
				initialTextSize = dp(MAX_VERTICAL_RENDER_TEXT_SIZE_DP).toFloat(),
			) ?: continue
			val plan = if (fit.truncated) {
				ellipsizeVerticalPlan(fit.plan, contentWidth, contentHeight)
			} else {
				fit.plan
			}
			if (computeVerticalUsedWidth(plan) > contentWidth || computeVerticalUsedHeight(plan) > contentHeight) continue
			return PreparedBubble(
				rect = rect,
				padding = padding,
				contentWidth = contentWidth,
				contentHeight = contentHeight,
				layout = null,
				verticalPlan = plan,
				debugOverlay = buildBubbleDebugOverlay(
					input = input,
					preparedRect = rect,
					padding = padding,
					contentWidth = contentWidth,
					contentHeight = contentHeight,
				),
			)
		}
		return null
	}

	private fun ellipsizeVerticalPlan(
		plan: VerticalLayoutPlan,
		width: Int,
		height: Int,
	): VerticalLayoutPlan {
		val rows = max(1, height / plan.cellSize)
		val columns = max(1, width / plan.cellSize)
		val capacity = rows * columns
		val visibleGlyphs = when {
			plan.glyphs.size <= capacity -> plan.glyphs
			capacity == 1 -> listOf("…")
			else -> plan.glyphs.take(capacity - 1) + "…"
		}
		return plan.copy(
			glyphs = visibleGlyphs,
			rowCapacity = rows,
		)
	}

	private fun buildVisibleScaledRects(
		anchorRect: Rect,
		bitmapWidth: Int,
		bitmapHeight: Int,
	): List<Rect> {
		val candidates = linkedSetOf<Int>()
		val rects = ArrayList<Rect>()
		for (scale in VISIBLE_OVERLAY_EXPAND_SCALES) {
			val rect = if (scale <= 1f) {
				Rect(anchorRect)
			} else {
				expandRectAroundCenter(anchorRect, scale, bitmapWidth, bitmapHeight)
			}
			if (rect.width() <= 1 || rect.height() <= 1) continue
			val key = rect.left * 31 + rect.top * 37 + rect.right * 41 + rect.bottom * 43
			if (candidates.add(key)) {
				rects += rect
			}
		}
		return rects
	}

	private fun computeLayoutLineHeight(layout: StaticLayout): Int {
		if (layout.lineCount <= 0) return 1
		return if (layout.lineCount == 1) {
			max(1, layout.getLineBottom(0) - layout.getLineTop(0))
		} else {
			max(1, layout.getLineTop(1) - layout.getLineTop(0))
		}
	}

	private fun prepareSegmentedBubble(
		text: String,
		sourceContentRect: Rect,
		sourceContentRects: List<Rect>,
		outerRects: List<Rect>,
		verticalPreferred: Boolean,
		input: BubbleInput,
	): PreparedBubble? {
		for (safeRect in outerRects) {
			val projectedRects = projectContentRects(sourceContentRect, sourceContentRects, safeRect)
			if (projectedRects.isEmpty() || hasExcessiveProjectedOverlap(projectedRects)) continue
			val referenceRect = resolveSegmentReferenceRect(projectedRects)
			val fit = if (verticalPreferred) {
				fitVerticalAcrossRects(
					text = text,
					backgroundRects = projectedRects,
					initialTextSize = min(
						dp(14f).toFloat(),
						min(
							(referenceRect.width().toFloat() * VERTICAL_TEXT_SIZE_WIDTH_RATIO).coerceAtLeast(dp(MIN_INITIAL_TEXT_SIZE_DP).toFloat()),
							(referenceRect.height() * 0.42f).coerceAtLeast(dp(MIN_INITIAL_TEXT_SIZE_DP).toFloat()),
						),
					),
				)
			} else {
				fitHorizontalAcrossRects(
					text = text,
					backgroundRects = projectedRects,
					initialTextSize = initialHorizontalTextSize(
						text = text,
						width = referenceRect.width().coerceAtLeast(1),
						height = referenceRect.height().coerceAtLeast(1),
					),
				)
			} ?: continue
			if (fit.segments.isEmpty() || fit.overflowUnits > 0 || fit.truncated) continue
			val preparedRect = mergeRects(fit.segments.map { it.backgroundRect }) ?: safeRect
			val contentAreaRect = mergeRects(fit.segments.map { it.contentRect })
			return PreparedBubble(
				rect = preparedRect,
				padding = dp(4f),
				contentWidth = contentAreaRect?.width()?.coerceAtLeast(1) ?: 1,
				contentHeight = contentAreaRect?.height()?.coerceAtLeast(1) ?: 1,
				layout = null,
				verticalPlan = null,
				segments = fit.segments,
				debugOverlay = buildBubbleDebugOverlay(
					input = input,
					preparedRect = preparedRect,
					padding = dp(4f),
					contentWidth = contentAreaRect?.width()?.coerceAtLeast(1) ?: 1,
					contentHeight = contentAreaRect?.height()?.coerceAtLeast(1) ?: 1,
					segments = fit.segments,
				),
			)
		}
		return null
	}

	private fun resolveRenderOuterRects(
		baseRect: Rect,
		expansionScales: FloatArray,
		bitmapWidth: Int,
		bitmapHeight: Int,
	): List<Rect> {
		val candidates = linkedMapOf<String, Rect>()
		fun add(rect: Rect) {
			if (rect.width() <= 1 || rect.height() <= 1) return
			val normalized = Rect(
				rect.left.coerceIn(0, bitmapWidth - 1),
				rect.top.coerceIn(0, bitmapHeight - 1),
				rect.right.coerceIn(1, bitmapWidth),
				rect.bottom.coerceIn(1, bitmapHeight),
			)
			if (normalized.width() <= 1 || normalized.height() <= 1) return
			val key = "${normalized.left},${normalized.top},${normalized.right},${normalized.bottom}"
			candidates.putIfAbsent(key, normalized)
		}
		add(baseRect)
		for (scale in expansionScales) {
			add(
				if (scale <= 1f) baseRect else expandRectAroundCenter(baseRect, scale, bitmapWidth, bitmapHeight)
			)
		}
		return candidates.values.toList()
	}

	private fun solveSingleBoxBubble(
		input: BubbleInput,
		text: String,
		outerRects: List<Rect>,
		padding: Int,
		sourceContentRect: Rect?,
		bubbleLikeRegion: Boolean,
		verticalPreferred: Boolean,
		bitmapWidth: Int,
		bitmapHeight: Int,
		allowLocalExpansion: Boolean,
		allowEllipsize: Boolean,
	): PreparedBubble? {
		var best: SingleBoxBubbleFit? = null
		for (outerRect in outerRects) {
			val candidate = if (verticalPreferred) {
				solveVerticalSingleBoxBubble(
					input = input,
					text = text,
					outerRect = outerRect,
					padding = padding,
					sourceContentRect = sourceContentRect,
					bubbleLikeRegion = bubbleLikeRegion,
					bitmapWidth = bitmapWidth,
					bitmapHeight = bitmapHeight,
					allowLocalExpansion = allowLocalExpansion,
				)
			} else {
				solveHorizontalSingleBoxBubble(
					input = input,
					text = text,
					outerRect = outerRect,
					padding = padding,
					sourceContentRect = sourceContentRect,
					bubbleLikeRegion = bubbleLikeRegion,
					bitmapWidth = bitmapWidth,
					bitmapHeight = bitmapHeight,
					allowLocalExpansion = allowLocalExpansion,
					allowEllipsize = allowEllipsize,
				)
			} ?: continue
			if (best == null || candidate.score < best!!.score) {
				best = candidate
			}
			if (candidate.score == 0) {
				break
			}
		}
		return best?.bubble
	}

	private fun solveHorizontalSingleBoxBubble(
		input: BubbleInput,
		text: String,
		outerRect: Rect,
		padding: Int,
		sourceContentRect: Rect?,
		bubbleLikeRegion: Boolean,
		bitmapWidth: Int,
		bitmapHeight: Int,
		allowLocalExpansion: Boolean,
		allowEllipsize: Boolean,
	): SingleBoxBubbleFit? {
		val initialContentWidth = max(1, outerRect.width() - padding * 2)
		val initialContentHeight = max(1, outerRect.height() - padding * 2)
		if (initialContentWidth <= 1 || initialContentHeight <= 1) return null
		var fit = fitHorizontalLayout(
			text = text,
			width = initialContentWidth,
			height = initialContentHeight,
			initialTextSize = initialHorizontalTextSize(text, initialContentWidth, initialContentHeight),
			allowEllipsize = allowEllipsize,
		)
		var resolvedRect = resolveSingleBoxRect(
			outerRect = outerRect,
			padding = padding,
			bubbleLikeRegion = bubbleLikeRegion,
			sourceContentRect = sourceContentRect,
			requiredWidth = fit.usedWidth,
			requiredHeight = fit.usedHeight,
		)
		repeat(2) {
			val contentWidth = max(1, resolvedRect.width() - padding * 2)
			val contentHeight = max(1, resolvedRect.height() - padding * 2)
			val nextFit = fitHorizontalLayout(
				text = text,
				width = contentWidth,
				height = contentHeight,
				initialTextSize = initialHorizontalTextSize(text, contentWidth, contentHeight),
				allowEllipsize = allowEllipsize,
			)
			val nextRect = resolveSingleBoxRect(
				outerRect = outerRect,
				padding = padding,
				bubbleLikeRegion = bubbleLikeRegion,
				sourceContentRect = sourceContentRect,
				requiredWidth = nextFit.usedWidth,
				requiredHeight = nextFit.usedHeight,
			)
			fit = nextFit
			resolvedRect = nextRect
		}
		if (allowLocalExpansion && (fit.overflow > 0 || fit.truncated)) {
			val expanded = resolveHorizontalExpandedBubble(
				input = input,
				text = text,
				initialOuterRect = resolvedRect,
				padding = padding,
				sourceContentRect = sourceContentRect,
				bubbleLikeRegion = bubbleLikeRegion,
				bitmapWidth = bitmapWidth,
				bitmapHeight = bitmapHeight,
			)
			if (expanded != null) {
				return expanded
			}
		}
		val contentWidth = max(1, resolvedRect.width() - padding * 2)
		val contentHeight = max(1, resolvedRect.height() - padding * 2)
		return SingleBoxBubbleFit(
			bubble = PreparedBubble(
				rect = resolvedRect,
				padding = padding,
				contentWidth = contentWidth,
				contentHeight = contentHeight,
				layout = fit.layout,
				verticalPlan = null,
				debugOverlay = buildBubbleDebugOverlay(
					input = input,
					preparedRect = resolvedRect,
					padding = padding,
					contentWidth = contentWidth,
					contentHeight = contentHeight,
				),
			),
			score = fit.overflow + if (fit.truncated) contentHeight + 1 else 0,
		)
	}

	private fun solveVerticalSingleBoxBubble(
		input: BubbleInput,
		text: String,
		outerRect: Rect,
		padding: Int,
		sourceContentRect: Rect?,
		bubbleLikeRegion: Boolean,
		bitmapWidth: Int,
		bitmapHeight: Int,
		allowLocalExpansion: Boolean,
	): SingleBoxBubbleFit? {
		val initialContentWidth = max(1, outerRect.width() - padding * 2)
		val initialContentHeight = max(1, outerRect.height() - padding * 2)
		if (initialContentWidth <= 1 || initialContentHeight <= 1) return null
		var fit = fitVerticalPlan(
				text = text,
				width = initialContentWidth,
				height = initialContentHeight,
				initialTextSize = min(
				dp(MAX_VERTICAL_RENDER_TEXT_SIZE_DP).toFloat(),
					min(
						(initialContentHeight * 0.42f).coerceAtLeast(dp(MIN_RENDER_TEXT_SIZE_DP).toFloat()),
						(initialContentWidth * VERTICAL_TEXT_SIZE_WIDTH_RATIO).coerceAtLeast(dp(MIN_RENDER_TEXT_SIZE_DP).toFloat()),
					),
				),
			) ?: return null
		var resolvedRect = resolveSingleBoxRect(
			outerRect = outerRect,
			padding = padding,
			bubbleLikeRegion = bubbleLikeRegion,
			sourceContentRect = sourceContentRect,
			requiredWidth = fit.requiredWidth,
			requiredHeight = fit.requiredHeight,
		)
		repeat(2) {
			val contentWidth = max(1, resolvedRect.width() - padding * 2)
			val contentHeight = max(1, resolvedRect.height() - padding * 2)
			val nextFit = fitVerticalPlan(
				text = text,
				width = contentWidth,
				height = contentHeight,
				initialTextSize = resolveVerticalMaxTextSize(contentWidth, contentHeight),
			) ?: return@repeat
			val nextRect = resolveSingleBoxRect(
				outerRect = outerRect,
				padding = padding,
				bubbleLikeRegion = bubbleLikeRegion,
				sourceContentRect = sourceContentRect,
				requiredWidth = nextFit.requiredWidth,
				requiredHeight = nextFit.requiredHeight,
			)
			fit = nextFit
			resolvedRect = nextRect
		}
		val contentWidthBeforeExpansion = max(1, resolvedRect.width() - padding * 2)
		if (
			allowLocalExpansion &&
			(fit.overflow > 0 || fit.truncated || needsVerticalReadableExpansion(fit, contentWidthBeforeExpansion))
		) {
			val expanded = resolveVerticalExpandedBubble(
				input = input,
				text = text,
				initialOuterRect = resolvedRect,
				padding = padding,
				sourceContentRect = sourceContentRect,
				bubbleLikeRegion = bubbleLikeRegion,
				bitmapWidth = bitmapWidth,
				bitmapHeight = bitmapHeight,
			)
			if (expanded != null) {
				return expanded
			}
		}
		val contentWidth = max(1, resolvedRect.width() - padding * 2)
		val contentHeight = max(1, resolvedRect.height() - padding * 2)
		val finalFit = refitVerticalPlanForContent(
			text = text,
			contentWidth = contentWidth,
			contentHeight = contentHeight,
			fallback = fit,
		)
		return SingleBoxBubbleFit(
			bubble = PreparedBubble(
				rect = resolvedRect,
				padding = padding,
				contentWidth = contentWidth,
				contentHeight = contentHeight,
				layout = null,
				verticalPlan = finalFit.plan,
				debugOverlay = buildBubbleDebugOverlay(
					input = input,
					preparedRect = resolvedRect,
					padding = padding,
					contentWidth = contentWidth,
					contentHeight = contentHeight,
				),
			),
			score = finalFit.overflow + if (finalFit.truncated) contentHeight + 1 else 0,
		)
	}

	private fun resolveHorizontalExpandedBubble(
		input: BubbleInput,
		text: String,
		initialOuterRect: Rect,
		padding: Int,
		sourceContentRect: Rect?,
		bubbleLikeRegion: Boolean,
		bitmapWidth: Int,
		bitmapHeight: Int,
	): SingleBoxBubbleFit? {
		var outerRect = Rect(initialOuterRect)
		var bestAttempt: SingleBoxBubbleFit? = null
		repeat(MAX_LOCAL_EXPANSION_STEPS) {
			val contentWidth = max(1, outerRect.width() - padding * 2)
			val contentHeight = max(1, outerRect.height() - padding * 2)
			val fit = fitHorizontalLayout(
				text = text,
				width = contentWidth,
				height = contentHeight,
				initialTextSize = initialHorizontalTextSize(text, contentWidth, contentHeight),
				allowEllipsize = false,
			)
			val resolvedRect = resolveSingleBoxRect(
				outerRect = outerRect,
				padding = padding,
				bubbleLikeRegion = bubbleLikeRegion,
				sourceContentRect = sourceContentRect,
				requiredWidth = fit.usedWidth,
				requiredHeight = fit.usedHeight,
			)
			val resolvedContentWidth = max(1, resolvedRect.width() - padding * 2)
			val resolvedContentHeight = max(1, resolvedRect.height() - padding * 2)
			val resolvedFit = if (resolvedContentWidth == contentWidth && resolvedContentHeight == contentHeight) {
				fit
			} else {
				fitHorizontalLayout(
					text = text,
					width = resolvedContentWidth,
					height = resolvedContentHeight,
					initialTextSize = initialHorizontalTextSize(text, resolvedContentWidth, resolvedContentHeight),
					allowEllipsize = false,
				)
			}
			val attempt = SingleBoxBubbleFit(
				bubble = PreparedBubble(
					rect = resolvedRect,
					padding = padding,
					contentWidth = resolvedContentWidth,
					contentHeight = resolvedContentHeight,
					layout = resolvedFit.layout,
					verticalPlan = null,
					debugOverlay = buildBubbleDebugOverlay(
						input = input,
						preparedRect = resolvedRect,
						padding = padding,
						contentWidth = resolvedContentWidth,
						contentHeight = resolvedContentHeight,
					),
				),
				score = resolvedFit.overflow + if (resolvedFit.truncated) resolvedContentHeight + 1 else 0,
			)
			if (bestAttempt == null || attempt.score < bestAttempt!!.score) {
				bestAttempt = attempt
			}
			if (resolvedFit.overflow == 0 && !resolvedFit.truncated) {
				return attempt
			}
			val nextOuter = buildLocalExpansionOuterRect(
				currentOuterRect = outerRect,
				anchorRect = sourceContentRect ?: resolvedRect,
				targetContentWidth = max(resolvedContentWidth, resolvedFit.usedWidth),
				targetContentHeight = max(resolvedContentHeight, resolvedFit.usedHeight),
				padding = padding,
				bitmapWidth = bitmapWidth,
				bitmapHeight = bitmapHeight,
			)
			if (nextOuter == outerRect) {
				return bestAttempt
			}
			outerRect = nextOuter
		}
		return bestAttempt
	}

	private fun resolveVerticalExpandedBubble(
		input: BubbleInput,
		text: String,
		initialOuterRect: Rect,
		padding: Int,
		sourceContentRect: Rect?,
		bubbleLikeRegion: Boolean,
		bitmapWidth: Int,
		bitmapHeight: Int,
	): SingleBoxBubbleFit? {
		var outerRect = Rect(initialOuterRect)
		var bestAttempt: SingleBoxBubbleFit? = null
		repeat(MAX_LOCAL_EXPANSION_STEPS) {
			val contentWidth = max(1, outerRect.width() - padding * 2)
			val contentHeight = max(1, outerRect.height() - padding * 2)
			val fit = fitVerticalPlan(
				text = text,
				width = contentWidth,
				height = contentHeight,
				initialTextSize = resolveVerticalMaxTextSize(contentWidth, contentHeight),
			) ?: return bestAttempt
			val resolvedRect = resolveSingleBoxRect(
				outerRect = outerRect,
				padding = padding,
				bubbleLikeRegion = bubbleLikeRegion,
				sourceContentRect = sourceContentRect,
				requiredWidth = fit.requiredWidth,
				requiredHeight = fit.requiredHeight,
			)
			val resolvedContentWidth = max(1, resolvedRect.width() - padding * 2)
			val resolvedContentHeight = max(1, resolvedRect.height() - padding * 2)
			val resolvedFit = if (resolvedContentWidth == contentWidth && resolvedContentHeight == contentHeight) {
				fit
			} else {
				fitVerticalPlan(
					text = text,
					width = resolvedContentWidth,
					height = resolvedContentHeight,
					initialTextSize = resolveVerticalMaxTextSize(resolvedContentWidth, resolvedContentHeight),
				) ?: fit
			}
			val attempt = SingleBoxBubbleFit(
				bubble = PreparedBubble(
					rect = resolvedRect,
					padding = padding,
					contentWidth = resolvedContentWidth,
					contentHeight = resolvedContentHeight,
					layout = null,
					verticalPlan = resolvedFit.plan,
					debugOverlay = buildBubbleDebugOverlay(
						input = input,
						preparedRect = resolvedRect,
						padding = padding,
						contentWidth = resolvedContentWidth,
						contentHeight = resolvedContentHeight,
					),
				),
				score = resolvedFit.overflow + if (resolvedFit.truncated) resolvedContentHeight + 1 else 0,
			)
			if (bestAttempt == null || attempt.score < bestAttempt!!.score) {
				bestAttempt = attempt
			}
			if (resolvedFit.overflow == 0 && !resolvedFit.truncated) {
				return attempt
			}
			val nextOuter = buildLocalExpansionOuterRect(
				currentOuterRect = outerRect,
				anchorRect = sourceContentRect ?: resolvedRect,
				targetContentWidth = verticalExpansionTargetContentWidth(
					fit = resolvedFit,
					currentContentWidth = resolvedContentWidth,
				),
				targetContentHeight = verticalExpansionTargetContentHeight(
					fit = resolvedFit,
					currentContentHeight = resolvedContentHeight,
					maxContentWidth = bitmapWidth - padding * 2,
					maxContentHeight = bitmapHeight - padding * 2,
				),
				padding = padding,
				bitmapWidth = bitmapWidth,
				bitmapHeight = bitmapHeight,
			)
			if (nextOuter == outerRect) {
				return bestAttempt
			}
			outerRect = nextOuter
		}
		return bestAttempt
	}

	private fun refitVerticalPlanForContent(
		text: String,
		contentWidth: Int,
		contentHeight: Int,
		fallback: VerticalLayoutFit,
	): VerticalLayoutFit {
		return fitVerticalPlan(
			text = text,
			width = contentWidth,
			height = contentHeight,
			initialTextSize = resolveVerticalMaxTextSize(contentWidth, contentHeight),
		) ?: fallback
	}

	private fun verticalExpansionTargetContentWidth(
		fit: VerticalLayoutFit,
		currentContentWidth: Int,
	): Int {
		val minReadableWidth = max(
			dp(MIN_VERTICAL_READABLE_WIDTH_DP),
			ceil((fit.plan.cellSize * MIN_VERTICAL_READABLE_WIDTH_COLUMNS).toDouble()).toInt(),
		)
		return max(
			currentContentWidth,
			max(fit.requiredWidth, minReadableWidth),
		)
	}

	private fun needsVerticalReadableExpansion(
		fit: VerticalLayoutFit,
		contentWidth: Int,
	): Boolean {
		return contentWidth < verticalExpansionTargetContentWidth(
			fit = fit,
			currentContentWidth = 1,
		)
	}

	private fun verticalExpansionTargetContentHeight(
		fit: VerticalLayoutFit,
		currentContentHeight: Int,
		maxContentWidth: Int,
		maxContentHeight: Int,
	): Int {
		if (fit.overflow <= 0 && !fit.truncated) {
			return max(currentContentHeight, fit.requiredHeight)
		}
		val safeMaxContentWidth = maxContentWidth.coerceAtLeast(1)
		val safeMaxContentHeight = maxContentHeight.coerceAtLeast(1)
		val cell = fit.plan.cellSize.coerceAtLeast(1)
		val columnsAtMaxWidth = max(1, safeMaxContentWidth / cell)
		val rowsNeededAtMaxWidth = ceil(fit.plan.glyphs.size / columnsAtMaxWidth.toDouble())
			.toInt()
			.coerceAtLeast(1)
		val heightNeededAtMaxWidth = rowsNeededAtMaxWidth * cell
		return max(
			currentContentHeight,
			max(fit.requiredHeight, heightNeededAtMaxWidth),
		).coerceAtMost(safeMaxContentHeight)
	}

	private fun buildLocalExpansionOuterRect(
		currentOuterRect: Rect,
		anchorRect: Rect,
		targetContentWidth: Int,
		targetContentHeight: Int,
		padding: Int,
		bitmapWidth: Int,
		bitmapHeight: Int,
	): Rect {
		val targetWidth = max(
			currentOuterRect.width(),
			((targetContentWidth + padding * 2) * LOCAL_EXPANSION_GROWTH_FACTOR).toInt(),
		).coerceAtMost(bitmapWidth)
		val targetHeight = max(
			currentOuterRect.height(),
			((targetContentHeight + padding * 2) * LOCAL_EXPANSION_GROWTH_FACTOR).toInt(),
		).coerceAtMost(bitmapHeight)
		val cx = anchorRect.centerX()
		val cy = anchorRect.centerY()
		var left = cx - targetWidth / 2
		var top = cy - targetHeight / 2
		var right = left + targetWidth
		var bottom = top + targetHeight
		if (left < 0) {
			right -= left
			left = 0
		}
		if (top < 0) {
			bottom -= top
			top = 0
		}
		if (right > bitmapWidth) {
			left -= right - bitmapWidth
			right = bitmapWidth
		}
		if (bottom > bitmapHeight) {
			top -= bottom - bitmapHeight
			bottom = bitmapHeight
		}
		return Rect(
			left.coerceIn(0, bitmapWidth - 1),
			top.coerceIn(0, bitmapHeight - 1),
			right.coerceIn(1, bitmapWidth),
			bottom.coerceIn(1, bitmapHeight),
		)
	}

	private fun resolveSingleBoxRect(
		outerRect: Rect,
		padding: Int,
		bubbleLikeRegion: Boolean,
		sourceContentRect: Rect?,
		requiredWidth: Int,
		requiredHeight: Int,
	): Rect {
		return if (bubbleLikeRegion) {
			Rect(outerRect)
		} else {
			centerRectByContent(
				outer = outerRect,
				contentWidth = requiredWidth,
				contentHeight = requiredHeight,
				padding = padding,
				sourceContentRect = sourceContentRect,
			)
		}
	}

	private fun projectContentRects(
		sourceUnionRect: Rect,
		sourceRects: List<Rect>,
		outerRect: Rect,
	): List<Rect> {
		val sourceWidth = sourceUnionRect.width().coerceAtLeast(1)
		val sourceHeight = sourceUnionRect.height().coerceAtLeast(1)
		val outerWidth = outerRect.width().coerceAtLeast(1)
		val outerHeight = outerRect.height().coerceAtLeast(1)
		val positionScaleX = outerWidth.toFloat() / sourceWidth.toFloat()
		val positionScaleY = outerHeight.toFloat() / sourceHeight.toFloat()
		val sizeScaleX = if (positionScaleX <= 1f) positionScaleX else min(positionScaleX, SEGMENT_MAX_SIZE_EXPANSION_SCALE)
		val sizeScaleY = if (positionScaleY <= 1f) positionScaleY else min(positionScaleY, SEGMENT_MAX_SIZE_EXPANSION_SCALE)
		return sourceRects.mapNotNull { rect ->
			val centerX = outerRect.left + ((rect.exactCenterX() - sourceUnionRect.left.toFloat()) / sourceWidth.toFloat() * outerWidth.toFloat())
			val centerY = outerRect.top + ((rect.exactCenterY() - sourceUnionRect.top.toFloat()) / sourceHeight.toFloat() * outerHeight.toFloat())
			val targetWidth = max(1, (rect.width().toFloat() * sizeScaleX).roundToInt())
			val targetHeight = max(1, (rect.height().toFloat() * sizeScaleY).roundToInt())
			var left = (centerX - targetWidth / 2f).roundToInt()
			var top = (centerY - targetHeight / 2f).roundToInt()
			var right = left + targetWidth
			var bottom = top + targetHeight
			if (left < outerRect.left) {
				left = outerRect.left
				right = left + targetWidth
			}
			if (top < outerRect.top) {
				top = outerRect.top
				bottom = top + targetHeight
			}
			if (right > outerRect.right) {
				right = outerRect.right
				left = right - targetWidth
			}
			if (bottom > outerRect.bottom) {
				bottom = outerRect.bottom
				top = bottom - targetHeight
			}
			val projected = Rect(
				left.coerceIn(outerRect.left, outerRect.right - 1),
				top.coerceIn(outerRect.top, outerRect.bottom - 1),
				right.coerceIn(left + 1, outerRect.right),
				bottom.coerceIn(top + 1, outerRect.bottom),
			)
			projected.takeIf { it.width() > 1 && it.height() > 1 }
		}
	}

	private fun resolveSegmentReferenceRect(projectedRects: List<Rect>): Rect {
		val sortedByArea = projectedRects.sortedBy { it.width().coerceAtLeast(1) * it.height().coerceAtLeast(1) }
		return sortedByArea[(sortedByArea.size - 1) / 2]
	}

	private fun hasExcessiveProjectedOverlap(projectedRects: List<Rect>): Boolean {
		for (i in projectedRects.indices) {
			for (j in i + 1 until projectedRects.size) {
				val a = projectedRects[i]
				val b = projectedRects[j]
				val overlap = projectedOverlapArea(a, b)
				if (overlap <= 0) continue
				val minArea = min(
					a.width().coerceAtLeast(1) * a.height().coerceAtLeast(1),
					b.width().coerceAtLeast(1) * b.height().coerceAtLeast(1),
				)
				val overlapRatio = overlap.toFloat() / minArea.toFloat()
				if (overlapRatio >= SEGMENT_MAX_OVERLAP_RATIO) {
					return true
				}
			}
		}
		return false
	}

	private fun projectedOverlapArea(a: Rect, b: Rect): Int {
		val left = max(a.left, b.left)
		val top = max(a.top, b.top)
		val right = min(a.right, b.right)
		val bottom = min(a.bottom, b.bottom)
		if (right <= left || bottom <= top) return 0
		return (right - left) * (bottom - top)
	}

	private fun fitHorizontalAcrossRects(
		text: String,
		backgroundRects: List<Rect>,
		initialTextSize: Float,
	): SegmentedBubbleFit? {
		val minTextSize = dp(MIN_RENDER_TEXT_SIZE_DP).toFloat()
		var textSize = initialTextSize.coerceAtLeast(minTextSize)
		while (true) {
			val fit = buildHorizontalFlowSegments(
				text = text,
				backgroundRects = backgroundRects,
				textSize = textSize,
				ellipsizeLast = false,
			)
			if (fit != null && fit.overflowUnits == 0) {
				return fit
			}
			if (textSize <= minTextSize) {
				return buildHorizontalFlowSegments(
					text = text,
					backgroundRects = backgroundRects,
					textSize = minTextSize,
					ellipsizeLast = true,
				)
			}
			textSize = max(minTextSize, textSize - 1f)
		}
	}

	private fun buildHorizontalFlowSegments(
		text: String,
		backgroundRects: List<Rect>,
		textSize: Float,
		ellipsizeLast: Boolean,
	): SegmentedBubbleFit? {
		val regions = buildSegmentRegions(backgroundRects)
		if (regions.isEmpty()) return null
		var cursor = skipFlowLeadingWhitespace(text, 0)
		val segments = ArrayList<PreparedBubbleSegment>(regions.size)
		for ((index, region) in regions.withIndex()) {
			if (cursor >= text.length) break
			val remaining = text.substring(cursor)
			val width = region.contentRect.width().coerceAtLeast(1)
			val baseLayout = buildTextLayout(remaining, width, textSize)
			if (baseLayout.lineCount <= 0) continue
			val lineHeight = computeLayoutLineHeight(baseLayout)
			val maxLines = max(1, region.contentRect.height() / lineHeight)
			val allFits = baseLayout.height <= region.contentRect.height()
			val isLastRegion = index == regions.lastIndex
			if (allFits) {
				segments += PreparedBubbleSegment(
					backgroundRect = region.backgroundRect,
					contentRect = region.contentRect,
					layout = baseLayout,
				)
				cursor = text.length
				break
			}
			if (isLastRegion && ellipsizeLast) {
				segments += PreparedBubbleSegment(
					backgroundRect = region.backgroundRect,
					contentRect = region.contentRect,
					layout = buildTextLayout(
						text = remaining,
						width = width,
						textSize = textSize,
						maxLines = maxLines,
						ellipsize = TextUtils.TruncateAt.END,
					),
				)
				return SegmentedBubbleFit(
					segments = segments,
					textSize = textSize,
					overflowUnits = remaining.length,
					truncated = true,
				)
			}
			val linesThatFit = maxLines.coerceAtMost(baseLayout.lineCount)
			val end = if (linesThatFit > 0) baseLayout.getLineEnd(linesThatFit - 1) else 0
			if (end <= 0) continue
			val segmentText = remaining.substring(0, end).trimEnd()
			if (segmentText.isBlank()) {
				cursor = skipFlowLeadingWhitespace(text, cursor + end)
				continue
			}
			segments += PreparedBubbleSegment(
				backgroundRect = region.backgroundRect,
				contentRect = region.contentRect,
				layout = buildTextLayout(
					text = segmentText,
					width = width,
					textSize = textSize,
					maxLines = linesThatFit,
				),
			)
			cursor = skipFlowLeadingWhitespace(text, cursor + end)
		}
		return SegmentedBubbleFit(
			segments = segments,
			textSize = textSize,
			overflowUnits = (text.length - cursor).coerceAtLeast(0),
			truncated = false,
		)
	}

	private fun fitVerticalAcrossRects(
		text: String,
		backgroundRects: List<Rect>,
		initialTextSize: Float,
	): SegmentedBubbleFit? {
		val minTextSize = dp(MIN_RENDER_TEXT_SIZE_DP).toFloat()
		var textSize = initialTextSize.coerceAtLeast(minTextSize)
		while (true) {
			val fit = buildVerticalFlowSegments(
				text = text,
				backgroundRects = backgroundRects,
				textSize = textSize,
				ellipsizeLast = false,
			)
			if (fit != null && fit.overflowUnits == 0) {
				return fit
			}
			if (textSize <= minTextSize) {
				return buildVerticalFlowSegments(
					text = text,
					backgroundRects = backgroundRects,
					textSize = minTextSize,
					ellipsizeLast = true,
				)
			}
			textSize = max(minTextSize, textSize - 1f)
		}
	}

	private fun buildVerticalFlowSegments(
		text: String,
		backgroundRects: List<Rect>,
		textSize: Float,
		ellipsizeLast: Boolean,
	): SegmentedBubbleFit? {
		val glyphs = textToGlyphs(text)
		if (glyphs.isEmpty()) return null
		val regions = buildSegmentRegions(backgroundRects)
		if (regions.isEmpty()) return null
		val segments = ArrayList<PreparedBubbleSegment>(regions.size)
		var cursor = 0
		val cellSize = resolveVerticalCellSize(glyphs, textSize)
		for ((index, region) in regions.withIndex()) {
			if (cursor >= glyphs.size) break
			val rowCapacity = max(1, region.contentRect.height() / cellSize)
			val columns = max(1, region.contentRect.width() / cellSize)
			val capacity = rowCapacity * columns
			if (capacity <= 0) continue
			val remaining = glyphs.size - cursor
			val isLastRegion = index == regions.lastIndex
			if (remaining <= capacity) {
				segments += PreparedBubbleSegment(
					backgroundRect = region.backgroundRect,
					contentRect = region.contentRect,
					verticalPlan = VerticalLayoutPlan(
						glyphs = glyphs.subList(cursor, glyphs.size),
						textSize = textSize,
						cellSize = cellSize,
						rowCapacity = rowCapacity,
					),
				)
				cursor = glyphs.size
				break
			}
			if (isLastRegion && ellipsizeLast) {
				val visibleCount = capacity.coerceAtLeast(1)
				val visibleGlyphs = if (visibleCount == 1) {
					listOf("…")
				} else {
					glyphs.subList(cursor, cursor + visibleCount - 1) + "…"
				}
				segments += PreparedBubbleSegment(
					backgroundRect = region.backgroundRect,
					contentRect = region.contentRect,
					verticalPlan = VerticalLayoutPlan(
						glyphs = visibleGlyphs,
						textSize = textSize,
						cellSize = cellSize,
						rowCapacity = rowCapacity,
					),
				)
				return SegmentedBubbleFit(
					segments = segments,
					textSize = textSize,
					overflowUnits = remaining - visibleCount,
					truncated = true,
				)
			}
			segments += PreparedBubbleSegment(
				backgroundRect = region.backgroundRect,
				contentRect = region.contentRect,
				verticalPlan = VerticalLayoutPlan(
					glyphs = glyphs.subList(cursor, cursor + capacity),
					textSize = textSize,
					cellSize = cellSize,
					rowCapacity = rowCapacity,
				),
			)
			cursor += capacity
		}
		return SegmentedBubbleFit(
			segments = segments,
			textSize = textSize,
			overflowUnits = (glyphs.size - cursor).coerceAtLeast(0),
			truncated = false,
		)
	}

	private fun buildSegmentRegions(backgroundRects: List<Rect>): List<SegmentRegion> {
		return backgroundRects.mapNotNull { backgroundRect ->
			val padding = resolveSegmentPadding(backgroundRect)
			val contentRect = Rect(backgroundRect)
			contentRect.inset(padding, padding)
			contentRect.takeIf { it.width() > 1 && it.height() > 1 }?.let {
				SegmentRegion(
					backgroundRect = Rect(backgroundRect),
					contentRect = it,
				)
			}
		}
	}

	private fun resolveSegmentPadding(rect: Rect): Int {
		val minSide = min(rect.width(), rect.height()).coerceAtLeast(1)
		return min(dp(4f), max(1, minSide / 8))
	}

	private fun skipFlowLeadingWhitespace(text: String, start: Int): Int {
		var index = start.coerceAtLeast(0)
		while (index < text.length && text[index].isWhitespace()) {
			index++
		}
		return index
	}

	private fun isLikelyGarbledText(text: String): Boolean {
		if (text.isBlank()) return true
		if (text.any { it == '\uFFFD' }) return true
		val len = text.length
		val qCount = text.count { it == '?' || it == '？' }
		if (len >= 4 && qCount * 2 >= len) return true
		val normalized = normalizeForTranslationCompare(text)
		if (normalized.length >= 10) {
			val freq = normalized.groupingBy { it }.eachCount().values.sortedDescending()
			val top1 = freq.firstOrNull() ?: 0
			val top2 = freq.take(2).sum()
			if (top1 * 100 / normalized.length >= 60) return true
			if (top2 * 100 / normalized.length >= 85) return true
		}
		var maxRun = 1
		var run = 1
		for (i in 1 until text.length) {
			if (text[i] == text[i - 1]) {
				run++
				if (run > maxRun) maxRun = run
			} else {
				run = 1
			}
		}
		return len >= 6 && maxRun >= len * 2 / 3
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

	private data class HorizontalLayoutFit(
		val layout: StaticLayout,
		val textSize: Float,
		val usedWidth: Int,
		val usedHeight: Int,
		val overflow: Int,
		val truncated: Boolean,
	)

	private data class LayoutBounds(
		val left: Float,
		val right: Float,
		val width: Int,
	)

	private data class VerticalLayoutFit(
		val plan: VerticalLayoutPlan,
		val requiredWidth: Int,
		val requiredHeight: Int,
		val overflow: Int,
		val truncated: Boolean,
	)

	private data class SingleBoxBubbleFit(
		val bubble: PreparedBubble,
		val score: Int,
	)

	private data class SegmentedBubbleFit(
		val segments: List<PreparedBubbleSegment>,
		val textSize: Float,
		val overflowUnits: Int,
		val truncated: Boolean,
	)

	private data class SegmentRegion(
		val backgroundRect: Rect,
		val contentRect: Rect,
	)

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

	private fun isAcceptableTranslation(
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

	private fun normalizeForTranslationCompare(text: String): String {
		return buildString(text.length) {
			for (ch in text) {
				if (ch.isLetterOrDigit() || ch.isCjkUnifiedIdeograph() || ch.isJapaneseKana()) {
					append(ch)
				}
			}
		}.trim()
	}

	private fun Char.isJapaneseKana(): Boolean {
		return this in '\u3040'..'\u30ff' || this == 'ー'
	}

	private fun Char.isAsciiLetter(): Boolean {
		return this in 'a'..'z' || this in 'A'..'Z'
	}

	private fun Char.isLatinLetterLike(): Boolean {
		if (isAsciiLetter()) return true
		return Character.UnicodeScript.of(code) == Character.UnicodeScript.LATIN
	}

		private fun Char.isCjkUnifiedIdeograph(): Boolean {
			val block = Character.UnicodeBlock.of(this) ?: return false
			val blockName = block.toString()
			return blockName.startsWith("CJK_UNIFIED_IDEOGRAPHS") ||
				block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
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

		private fun oneLine(text: String, limit: Int = 140): String {
		if (text.isBlank()) return ""
		return text.replace('\n', ' ').replace('\r', ' ').trim().let {
			if (it.length <= limit) it else it.take(limit) + "..."
		}
	}

	private inline fun log(message: () -> String) {
		val msg = message()
		val pageId = loggingPageId.get()
		if (pageId != null && pageId != NO_LOGGING_PAGE_ID) {
			appendPageLog(pageId, msg)
		}
		if (settings.isReaderTranslationDebugLogsEnabled) {
			Log.d(LOG_TAG, msg)
		}
	}

	private fun appendPageLog(pageId: Long, message: String) {
		debugLogStore.append(pageId, message)
	}

	private fun getPageRenderEpoch(pageId: Long): Int {
		synchronized(pageStateLock) {
			return pageRenderEpochs[pageId] ?: 0
		}
	}

	private fun buildRenderedCacheKey(
		pageUrl: String,
		sourceUri: String,
		sourceLang: String,
		targetLang: String,
		pageEpoch: Int,
		autoRecognizerLanguage: String?,
	): String {
		val raw = listOf(
			TRANSLATION_PIPELINE_VERSION,
			pageUrl,
			sourceUri,
			sourceLang,
			targetLang,
			renderCacheEpoch.toString(),
			pageEpoch.toString(),
			settings.readerTranslationMode.name,
			settings.readerTranslationOnnxModelId,
			settings.readerTranslationApiProviderPreset,
			settings.readerTranslationApiEndpoint,
			settings.readerTranslationApiModel,
			settings.readerTranslationOcrEngine.name,
			settings.readerTranslationPipelineMode.name,
			OCR_STRATEGY_PAGE_DET_REC,
			settings.isReaderTranslationQualityFilterEnabled.toString(),
			settings.readerTranslationPaddleOfficialModelId,
			autoRecognizerLanguage.orEmpty(),
			settings.readerTranslationPaddleDetModelId,
			settings.readerTranslationPaddleRecModelUrl,
			settings.readerTranslationPaddleRecModelVersion,
			settings.readerTranslationPaddleRecModelSha256,
		).joinToString("|")
		return "${RENDER_CACHE_PREFIX}${raw.sha256()}"
	}

	private fun buildTextCacheKey(text: String, sourceLang: String, targetLang: String): String {
		val raw = listOf(
			TRANSLATION_PIPELINE_VERSION,
			text,
			sourceLang,
			targetLang,
			settings.readerTranslationMode.name,
			settings.readerTranslationOnnxModelId,
			settings.readerTranslationApiProviderPreset,
			settings.readerTranslationApiEndpoint,
			settings.readerTranslationApiModel,
			settings.isReaderTranslationQualityFilterEnabled.toString(),
		).joinToString("|")
		return "${TEXT_CACHE_PREFIX}${raw.sha256()}"
	}

	private fun String.sha256(): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
		return buildString(digest.size * 2) {
			for (b in digest) {
				append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
			}
		}
	}

	private fun buildOcrCacheKey(sourceUri: String, sourceLang: String): String {
		val raw = listOf(
			TRANSLATION_PIPELINE_VERSION,
			sourceUri,
			sourceLang,
			settings.readerTranslationOcrEngine.name,
			settings.readerTranslationPipelineMode.name,
			OCR_STRATEGY_PAGE_DET_REC,
			settings.readerTranslationPaddleOfficialModelId,
			automaticRecognizerLanguage.get().orEmpty(),
			settings.readerTranslationPaddleDetModelId,
			settings.readerTranslationPaddleRecModelUrl,
			settings.readerTranslationPaddleRecModelVersion,
			settings.readerTranslationPaddleRecModelSha256,
			settings.readerTranslationBubbleDetectorModelId,
		).joinToString("|")
		return "${OCR_CACHE_PREFIX}${raw.sha256()}"
	}

	private fun resolveSourceLanguage(page: ContentPage, contextualLanguage: String? = null): String {
		if (isAutoReaderTranslationLanguage(settings.readerTranslationSourceLanguage)) {
			contextualLanguage.normalizeReaderTranslationLanguageTag()?.let { return it }
		}
		return resolveReaderTranslationSourceLanguage(
			preferredLanguage = settings.readerTranslationSourceLanguage,
			contentLanguage = page.source.getLocale()?.language,
		)
	}

	private fun serializeOcrBlocks(blocks: List<OcrTextBlock>): String {
		val arr = JSONArray()
		for (block in blocks) {
			val obj = JSONObject()
			obj.put("text", block.text)
			obj.put("confidence", block.confidence)
			if (block.detectorId.isNotBlank()) {
				obj.put("detector_id", block.detectorId)
			}
			if (block.directionHint != TextDirectionHint.UNKNOWN) {
				obj.put("direction", block.directionHint.name)
			}
			obj.put("angle", block.angleHintDegrees)
			obj.put("axis_aligned", block.isAxisAligned)
			block.quadPoints?.let { quad ->
				val quadArray = JSONArray()
				quad.points.forEach { (x, y) ->
					quadArray.put(JSONArray().put(x).put(y))
				}
				obj.put("quad", quadArray)
			}
			block.boundingBox?.let { box ->
				obj.put("left", box.left)
				obj.put("top", box.top)
				obj.put("right", box.right)
				obj.put("bottom", box.bottom)
			}
			arr.put(obj)
		}
		return arr.toString()
	}

	private fun deserializeOcrBlocks(json: String): List<OcrTextBlock> {
		val arr = JSONArray(json)
		val result = mutableListOf<OcrTextBlock>()
		for (i in 0 until arr.length()) {
			val obj = arr.getJSONObject(i)
			val box = if (obj.has("left")) {
				Rect(obj.getInt("left"), obj.getInt("top"), obj.getInt("right"), obj.getInt("bottom"))
			} else null
			val quad = if (obj.has("quad")) {
				obj.optJSONArray("quad")?.let(::parseTextQuad)
			} else {
				box?.let(::rectToTextQuad)
			}
			result.add(
				OcrTextBlock(
					text = obj.getString("text"),
					boundingBox = box,
					confidence = obj.optDouble("confidence", 1.0).toFloat(),
					directionHint = obj.optString("direction")
						.takeIf { it.isNotBlank() }
						?.let { runCatching { TextDirectionHint.valueOf(it) }.getOrNull() }
						?: inferTextDirectionHint(box, obj.optString("text")),
					angleHintDegrees = obj.optDouble("angle", inferTextAngleHintDegrees(box, obj.optString("text")).toDouble()).toFloat(),
					isAxisAligned = if (obj.has("axis_aligned")) obj.optBoolean("axis_aligned", true) else inferAxisAlignedHint(box),
					quadPoints = quad,
					detectorId = obj.optString("detector_id"),
				)
			)
		}
		return result
	}

	private fun parseTextQuad(arr: JSONArray): TextQuad? {
		if (arr.length() != 4) return null
		val points = buildList {
			for (i in 0 until arr.length()) {
				val point = arr.optJSONArray(i) ?: return null
				if (point.length() != 2) return null
				add(point.optDouble(0).toFloat() to point.optDouble(1).toFloat())
			}
		}
		return runCatching { TextQuad(points) }.getOrNull()
	}

	private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

	private companion object {

		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
		const val MAX_OPENAI_BATCH_SIZE = 3
		const val TRANSLATION_PIPELINE_VERSION = "2026-08-01-overlay-fit-v8"
		const val OPENAI_TRANSLATION_SYSTEM_PROMPT = """
		You translate manga OCR text.
		Output only the translation.
		Do not explain.
"""
		const val MAX_PARALLEL_TRANSLATION_PAGES = 2
		const val MAX_DETECTED_GROUP_FRAGMENTS = 28
		const val MIN_RENDER_COLUMN_WIDTH_RATIO = 0.22f
		const val DETECTOR_ANCHORED_MIN_WIDTH_RATIO = 0.42f
		const val DETECTOR_CONTENT_MERGE_PADDING_DP = 8f
		const val BUBBLE_DETECTOR_ID_PREFIX = "bubble_detector:"
		const val HORIZONTAL_TEXT_SIZE_WIDTH_RATIO = 0.58f
		const val VERTICAL_TEXT_SIZE_WIDTH_RATIO = 0.78f
		const val MAX_RENDER_TEXT_SIZE_DP = 24f
		const val MAX_VERTICAL_RENDER_TEXT_SIZE_DP = 10f
		const val MIN_INITIAL_TEXT_SIZE_DP = 6f
		const val MIN_RENDER_TEXT_SIZE_DP = 2f
		const val HORIZONTAL_LAYOUT_WIDTH_OVERFLOW_TOLERANCE_DP = 2f
		const val MIN_VERTICAL_READABLE_WIDTH_DP = 36f
		const val MIN_VERTICAL_READABLE_WIDTH_COLUMNS = 2.2f
		const val LOCAL_EXPANSION_GROWTH_FACTOR = 1.08f
		const val MAX_LOCAL_EXPANSION_STEPS = 6
		const val SEGMENT_MAX_SIZE_EXPANSION_SCALE = 1.28f
		const val SEGMENT_MAX_OVERLAP_RATIO = 0.18f
		val THINK_TAG_REGEX = Regex("(?is)<think>.*?</think>")
		val HYPHENATED_LINE_BREAK_REGEX = Regex("""(?<=\p{L})[\-‐‑‒–—]\s*[\r\n]+\s*(?=\p{L})""")
		val INLINE_HYPHENATED_WORD_REGEX = Regex("""(?<=\p{L})[\-‐‑‒–—]\s+(?=\p{L})""")
		val BUBBLE_EXPAND_SCALES = floatArrayOf(1f, 1.12f, 1.24f)
		val DETECTOR_ANCHORED_EXPAND_SCALES = floatArrayOf(1f, 1.18f, 1.34f, 1.52f)
		val VISIBLE_OVERLAY_EXPAND_SCALES = floatArrayOf(1f)
		const val TEXT_CACHE_PREFIX = "reader_translate_text_"
		const val RENDER_CACHE_PREFIX = "reader_translate_render_"
		const val OCR_CACHE_PREFIX = "reader_translate_ocr_"
		const val LOG_TAG = "ReaderTranslate"
		const val MAX_PAGE_LOG_LINES = 500
		const val NO_LOGGING_PAGE_ID = Long.MIN_VALUE
		const val FAIL_CODE_OCR_EMPTY = "OCR_EMPTY"
		const val FAIL_CODE_TRANSLATE_EMPTY = "TRANSLATE_EMPTY"
		const val FAIL_CODE_RENDER_FILTERED = "RENDER_FILTERED"
		const val FAIL_CODE_PROCESS_EXCEPTION = "PROCESS_EXCEPTION"
		const val LOW_OCR_CONFIDENCE_THRESHOLD = 0.45f
		const val MIN_AVERAGE_OCR_CONFIDENCE = 0.35f
		const val SOFT_AVERAGE_OCR_CONFIDENCE = 0.45f
		const val MIN_LOW_OCR_CONFIDENCE_RATIO = 0.50f
		const val MOSTLY_LOW_OCR_CONFIDENCE_RATIO = 0.80f
		const val SHORT_OCR_RESULT_CHAR_LIMIT = 24

		private enum class OcrDetectorBackend {
			MLKIT,
			PADDLE,
			CTD,
			DBNET,
			BUBBLE,
		}

		private enum class OcrRecognizerBackend {
			MLKIT,
			PADDLE,
			MANGA_OCR,
		}

		private data class OcrQualityStats(
			val blockCount: Int,
			val nonWhitespaceChars: Int,
			val averageConfidence: Float,
			val lowConfidenceBlockCount: Int,
			val lowConfidenceRatio: Float,
		) {
			fun toLogString(): String {
				return "blocks=$blockCount chars=$nonWhitespaceChars avgConf=$averageConfidence " +
					"lowConf=$lowConfidenceBlockCount ratio=$lowConfidenceRatio"
			}
		}

		private data class PageOcrRoute(
			val detector: OcrDetectorBackend,
			val recognizer: OcrRecognizerBackend,
		) {
			val metricKey: String
				get() = "${detector.name.lowercase()}_${recognizer.name.lowercase()}"
		}

		const val OCR_STRATEGY_PAGE_DET_REC = "page_det_rec"

			private fun sanitizeTranslation(text: String): String {
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
			if (jsonStart != -1 && jsonEnd != -1 && (jsonEnd.toInt() > jsonStart.toInt())) {
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
					it.isBlank() ||
						it == "..." ||
						it == "…"
				}
				.orEmpty()
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
			return THINK_TAG_REGEX.replace(text, "")
				.replace(Regex("(?is)<think>.*$"), "")
				.replace("<analysis>", "", ignoreCase = true)
				.replace("</analysis>", "", ignoreCase = true)
				.trim()
		}
	}
}
