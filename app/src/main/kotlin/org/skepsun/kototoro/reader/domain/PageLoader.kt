package org.skepsun.kototoro.reader.domain

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.CheckResult
import androidx.collection.LongSparseArray
import androidx.collection.set
import androidx.core.net.toFile
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.request.ImageRequest
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.source
import okio.use
import org.jetbrains.annotations.Blocking
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.network.imageproxy.ImageProxyInterceptor
import org.skepsun.kototoro.core.parser.CachingContentRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.logUnavailable
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.URI_SCHEME_ZIP
import org.skepsun.kototoro.core.util.ext.cancelChildrenAndJoin
import org.skepsun.kototoro.core.util.ext.compressToPNG
import org.skepsun.kototoro.core.util.ext.ensureRamAtLeast
import org.skepsun.kototoro.core.util.ext.ensureSuccess
import org.skepsun.kototoro.core.util.ext.getCompletionResultOrNull
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isNotEmpty
import org.skepsun.kototoro.core.util.ext.isPowerSaveMode
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.lifecycleScope
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.ramAvailable
import org.skepsun.kototoro.core.util.ext.toBitmapOrNull
import org.skepsun.kototoro.core.util.ext.toMimeType
import org.skepsun.kototoro.core.util.ext.toMimeTypeOrNull
import org.skepsun.kototoro.core.util.ext.use
import org.skepsun.kototoro.core.util.ext.withProgress
import org.skepsun.kototoro.core.util.progress.ProgressDeferred
import org.skepsun.kototoro.download.ui.worker.DownloadSlowdownDispatcher
import org.skepsun.kototoro.local.data.LocalStorageCache
import org.skepsun.kototoro.local.data.PageCache
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.parsers.util.requireBody
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import java.io.File
import java.io.IOException
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@ActivityRetainedScoped
class PageLoader @Inject constructor(
	@LocalizedAppContext private val context: Context,
	lifecycle: ActivityRetainedLifecycle,
	@ContentHttpClient private val okHttp: OkHttpClient,
	@PageCache private val cache: LocalStorageCache,
	val imageLoader: ImageLoader,
	private val settings: AppSettings,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	private val downloadSlowdownDispatcher: DownloadSlowdownDispatcher,
	private val enhancementController: ReaderPageEnhancementController,
	private val srManager: ReaderSuperResolutionManager,
) {

	val loaderScope = lifecycle.lifecycleScope + InternalErrorHandler() + Dispatchers.Default

	private val tasks = LongSparseArray<PageTaskRecord>()
	private val downloadPermits = PriorityPermitPool(settings.readerThreads)
	private val convertLock = Mutex()
	private val prefetchLock = Mutex()

	val widePageDetectedEvent = MutableSharedFlow<Long>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)

	@Volatile
	private var repository: ContentRepository? = null
	private val prefetchQueue = LinkedList<ContentPage>()
	private val counter = AtomicInteger(0)
	private var prefetchQueueLimit = settings.readerPrefetchLimit
	private val edgeDetector = EdgeDetector(context)

	fun setTranslationLanguageContext(translatedLanguage: String?, sourceLanguage: String?, branch: String?) {
		enhancementController.setTranslationLanguageContext(translatedLanguage, sourceLanguage, branch)
	}

	private data class PageTaskRecord(
		val task: ProgressDeferred<Uri, Float>,
		val translationWorkSignature: String,
		val ticket: PriorityPermitPool.Ticket,
		val isPrefetch: Boolean,
		val consumers: AtomicInteger,
	)

	fun isPrefetchApplicable(): Boolean {
		return repository is CachingContentRepository
			&& settings.isPagesPreloadEnabled
			&& !context.isPowerSaveMode()
			&& !isLowRam()
	}

	@AnyThread
	fun prefetch(pages: List<ReaderPage>) = loaderScope.launch {
		prefetchLock.withLock {
			for (page in pages.asReversed()) {
				val contentPage = page.toContentPage()
				if (synchronized(tasks) { tasks.containsKey(contentPage.taskKey()) }) {
					continue
				}
				prefetchQueue.offerFirst(contentPage)
				if (prefetchQueue.size > prefetchQueueLimit) {
					prefetchQueue.pollLast()
				}
			}
		}
		if (counter.get() == 0) {
			onIdle()
		}
	}

	fun loadPageAsync(
		page: ContentPage,
		force: Boolean,
		pageUrlOverride: String? = null,
	): ProgressDeferred<Uri, Float> {
		val currentSignature = enhancementController.currentWorkSignature()
		val taskKey = page.taskKey()
		return synchronized(tasks) {
			val record = tasks[taskKey]
				?.takeIf { it.translationWorkSignature == currentSignature }
				?.takeIf { it.task.isValid() }
			if (force) {
				record?.task?.cancel()
			} else if (record?.task?.isCancelled == false) {
				record.consumers.incrementAndGet()
				downloadPermits.promote(record.ticket, LOAD_PRIORITY_VISIBLE)
				return@synchronized record.task
			}
			val newRecord = createPageTask(
				page = page,
				skipCache = force,
				isPrefetch = false,
				priority = if (force) LOAD_PRIORITY_RETRY else LOAD_PRIORITY_VISIBLE,
				translationWorkSignature = currentSignature,
				initialConsumers = 1,
				pageUrlOverride = pageUrlOverride,
			)
			tasks[taskKey] = newRecord
			newRecord.task
		}
	}

	suspend fun loadPage(page: ContentPage, force: Boolean): Uri {
		val task = loadPageAsync(page, force)
		return try {
			task.await()
		} finally {
			releasePageTask(page, task)
		}
	}

	fun releasePageTask(page: ContentPage, task: ProgressDeferred<Uri, Float>) {
		synchronized(tasks) {
			val taskKey = page.taskKey()
			val record = tasks[taskKey]?.takeIf { it.task === task } ?: return
			val remainingConsumers = record.consumers.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
			if (remainingConsumers == 0 && !record.isPrefetch && !record.task.isCompleted) {
				record.task.cancel()
				tasks.remove(taskKey)
			}
		}
	}

	/** Loads the cached source image without reader translation or super-resolution stages. */
	suspend fun loadOriginalPage(page: ContentPage, force: Boolean): Uri {
		return loadOriginalPageImpl(
			page = page,
			progress = MutableStateFlow(PROGRESS_UNDEFINED),
			isPrefetch = false,
			skipCache = force,
			pageUrlOverride = null,
			ticket = downloadPermits.ticket(if (force) LOAD_PRIORITY_RETRY else LOAD_PRIORITY_VISIBLE),
		)
	}

	@CheckResult
	suspend fun convertBimap(uri: Uri): Uri = convertLock.withLock {
		if (uri.isZipUri()) {
			val image = decodeZipBitmap(uri)
			image.use {
				cache.set(uri.toString(), image).toUri()
			}
		} else {
			val file = uri.toFile()
			val image = decodeFileBitmap(file)
			image.use {
				image.compressToPNG(file)
			}
			uri
		}
	}

	private suspend fun decodeZipBitmap(uri: Uri): android.graphics.Bitmap {
		val svgBytes = runInterruptible(Dispatchers.IO) {
			ZipFile(uri.schemeSpecificPart).use { zip ->
				val entry = checkNotNull(zip.getEntry(uri.fragment)) {
					"Zip entry not found: ${uri.fragment}"
				}
				val mimeType = MimeTypes.getMimeTypeFromExtension(entry.name)
				if (mimeType?.subtype == "svg+xml") {
					zip.getInputStream(entry).use { it.readBytes() }
				} else {
					null
				}
			}
		}
		if (svgBytes != null) {
			return decodeSvgBitmap(svgBytes)
		}
		return runInterruptible(Dispatchers.IO) {
			ZipFile(uri.schemeSpecificPart).use { zip ->
				val entry = checkNotNull(zip.getEntry(uri.fragment)) {
					"Zip entry not found: ${uri.fragment}"
				}
				context.ensureRamAtLeast(entry.size * 2)
				zip.getInputStream(entry).use {
					BitmapDecoderCompat.decode(it, MimeTypes.getMimeTypeFromExtension(entry.name))
				}
			}
		}
	}

	private suspend fun decodeFileBitmap(file: File): android.graphics.Bitmap {
		val mimeType = runInterruptible(Dispatchers.IO) {
			BitmapDecoderCompat.probeMimeType(file)
		}
		if (mimeType?.subtype == "svg+xml") {
			return decodeSvgBitmap(file)
		}
		return runInterruptible(Dispatchers.IO) {
			context.ensureRamAtLeast(file.length() * 2)
			BitmapDecoderCompat.decode(file)
		}
	}

	private suspend fun decodeSvgBitmap(data: Any) = imageLoader.execute(
		ImageRequest.Builder(context)
			.data(data)
			.build(),
	).toBitmapOrNull() ?: error("Failed to decode svg image")

	suspend fun getTrimmedBounds(uri: Uri): Rect? = runCatchingCancellable {
		edgeDetector.getBounds(uri)
	}.onFailure { error ->
		error.printStackTraceDebug()
	}.getOrNull()

	suspend fun getPageUrl(page: ContentPage): String {
		val uri = page.url.toUri()
		if (uri.isZipUri() || uri.isFileUri() || uri.scheme == "data" || uri.scheme == "content") {
			return page.url
		}
		return getRepository(page.source).getPageUrl(page)
	}

	suspend fun invalidate(clearCache: Boolean) {
		synchronized(tasks) {
			tasks.clear()
		}
		enhancementController.cancelAllTranslationTasks()
		srManager.release()
		loaderScope.cancelChildrenAndJoin()
		if (clearCache) {
			cache.clear()
		}
	}

	fun invalidateTask(pageId: Long) {
		synchronized(tasks) {
			tasks[pageId]?.task?.cancel()
			tasks.remove(pageId)
		}
	}

	fun invalidateTask(page: ContentPage) {
		synchronized(tasks) {
			val taskKey = page.taskKey()
			tasks[taskKey]?.task?.cancel()
			tasks.remove(taskKey)
		}
	}

	private fun onIdle() = loaderScope.launch {
		prefetchLock.withLock {
			while (prefetchQueue.isNotEmpty()) {
				val page = prefetchQueue.pollFirst() ?: return@launch
				synchronized(tasks) {
					val taskKey = page.taskKey()
					val signature = enhancementController.currentWorkSignature()
					val existing = tasks[taskKey]
					if (existing?.translationWorkSignature == signature && existing.task.isValid()) {
						return@synchronized
					}
					tasks[taskKey] = createPageTask(
						page = page,
						skipCache = false,
						isPrefetch = true,
						priority = LOAD_PRIORITY_PREFETCH,
						translationWorkSignature = signature,
					)
				}
			}
		}
	}

	private fun createPageTask(
		page: ContentPage,
		skipCache: Boolean,
		isPrefetch: Boolean,
		priority: Int,
		translationWorkSignature: String,
		initialConsumers: Int = 0,
		pageUrlOverride: String? = null,
	): PageTaskRecord {
		val progress = MutableStateFlow(PROGRESS_UNDEFINED)
		val ticket = downloadPermits.ticket(priority)
		val deferred = loaderScope.async {
			counter.incrementAndGet()
			try {
				loadPageImpl(
					page = page,
					progress = progress,
					isPrefetch = isPrefetch,
					skipCache = skipCache,
					pageUrlOverride = pageUrlOverride,
					ticket = ticket,
				)
			} finally {
				if (counter.decrementAndGet() == 0) {
					onIdle()
				}
			}
		}
		return PageTaskRecord(
			task = ProgressDeferred(deferred, progress),
			translationWorkSignature = translationWorkSignature,
			ticket = ticket,
			isPrefetch = isPrefetch,
			consumers = AtomicInteger(initialConsumers),
		)
	}

	@Synchronized
	private fun getRepository(source: ContentSource): ContentRepository {
		val result = repository
		return if (result != null && result.source == source) {
			result
		} else {
			val creation = mangaRepositoryFactory.createWithDiagnostics(source)
			creation.logUnavailable("PageLoader", "repository_unavailable")
			creation.repository.also { repository = it }
		}
	}

	private suspend fun loadPageImpl(
		page: ContentPage,
		progress: MutableStateFlow<Float>,
		isPrefetch: Boolean,
		skipCache: Boolean,
		pageUrlOverride: String?,
		ticket: PriorityPermitPool.Ticket,
	): Uri {
		val sourceUri = loadOriginalPageImpl(page, progress, isPrefetch, skipCache, pageUrlOverride, ticket)
		val preparedPage = enhancementController.preparePage(
			page = page,
			sourceUri = sourceUri,
			convertZipBitmap = ::convertBimap,
		)

		// Super-resolution runs outside the download permit pool and remains legacy-reader behavior.
		var displayUri = preparedPage.displayUri
		if (settings.isReaderSuperResolutionEnabled && !isLowRam() && !context.isPowerSaveMode()) {
			val engine = settings.readerSuperResolutionEngine
			val modelId = if (engine == "ANIME4K") {
				settings.readerSuperResolutionAnime4kMode
			} else {
				settings.readerSuperResolutionModel
			}

			val srUri = srManager.processImage(
				originalUri = displayUri,
				modelId = modelId,
				noiseLevel = settings.readerSuperResolutionNoiseLevel,
				cacheLimitMb = settings.readerSuperResolutionCacheLimitMb,
			)
			if (srUri != null) {
				displayUri = srUri
			}
		}

		if (preparedPage.shouldScheduleTranslation) {
			Log.d("ReaderTranslate", "PageLoader debug: scheduling translation for page=${page.id} (show=${settings.isReaderTranslationShowTranslated})")
			enhancementController.scheduleTranslation(
				page = page,
				sourceUri = preparedPage.translationSourceUri,
				scope = loaderScope,
			) {
				synchronized(tasks) {
					tasks.remove(page.taskKey())
				}
			}
		}
		return displayUri
	}

	private suspend fun loadOriginalPageImpl(
		page: ContentPage,
		progress: MutableStateFlow<Float>,
		isPrefetch: Boolean,
		skipCache: Boolean,
		pageUrlOverride: String?,
		ticket: PriorityPermitPool.Ticket,
	): Uri = downloadPermits.withPermit(ticket) {
			val pageUrl = pageUrlOverride ?: getPageUrl(page)
			check(pageUrl.isNotBlank()) { "Cannot obtain full image url for $page" }
			val sourceUri = if (!skipCache) {
				cache.get(pageUrl)?.toUri()
			} else {
				null
			} ?: run {
				val uri = pageUrl.toUri()
				when {
					uri.isZipUri() -> if (uri.scheme == URI_SCHEME_ZIP) {
						uri
					} else { // legacy uri
						uri.buildUpon().scheme(URI_SCHEME_ZIP).build()
					}

					uri.isFileUri() -> uri
					uri.scheme == "data" -> {
						val dataUrl = pageUrl
						val commaIndex = dataUrl.indexOf(',')
						if (commaIndex == -1) error("Invalid data URL: $dataUrl")

						val header = dataUrl.substring(0, commaIndex)
						val data = dataUrl.substring(commaIndex + 1)
						val isBase64 = header.contains(";base64")
						val contentType = header.substringAfter("data:").substringBefore(";")

						val bytes = if (isBase64) {
							android.util.Base64.decode(data, android.util.Base64.DEFAULT)
						} else {
							java.net.URLDecoder.decode(data, "UTF-8").toByteArray()
						}

						cache.set(pageUrl, bytes.inputStream().source(), contentType.toMimeTypeOrNull()).toUri()
					}

					else -> {
						if (isPrefetch) {
							downloadSlowdownDispatcher.delay(page.source)
						}
						val repo = getRepository(page.source)
						val response = repo.fetchPageResponse(pageUrl, page)
							?: run {
								val request = repo.createPageRequest(pageUrl, page)
								val imageClient = repo.getImageClient() ?: okHttp
								imageProxyInterceptor.interceptPageRequest(request, imageClient)
							}
						Log.d(
							"JsPageResponse",
							"resp code=${response.code} protocol=${response.protocol} redirected=${response.priorResponse != null} reqUrl=${response.request.url} prior=${response.priorResponse?.code} server=${response.header("server")} cf-ray=${response.header("cf-ray")} cf-mitigated=${response.header("cf-mitigated")}"
						)
						response.ensureSuccess().use { resp ->
							val body = resp.requireBody()
							val contentType = body.contentType()
							if (
								contentType?.type.equals("text", ignoreCase = true) &&
								contentType?.subtype.equals("html", ignoreCase = true)
							) {
								throw IOException(
									"Expected an image but received $contentType from ${resp.request.url}",
								)
							}
							body.withProgress(progress).use {
								cache.set(pageUrl, it.source(), it.contentType()?.toMimeType())
							}
						}.toUri()
					}
				}
			}
			sourceUri
		}

	private fun isLowRam(): Boolean {
		return context.ramAvailable <= FileSize.MEGABYTES.convert(PREFETCH_MIN_RAM_MB, FileSize.BYTES)
	}

	private fun ContentPage.taskKey(): Long {
		return "${source.name}#$id#$url".longHashCode()
	}

	private fun Deferred<Uri>.isValid(): Boolean {
		return getCompletionResultOrNull()?.map { uri ->
			uri.exists() && uri.isTargetNotEmpty()
		}?.getOrDefault(false) != false
	}

	private class InternalErrorHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler),
		CoroutineExceptionHandler {

		override fun handleException(context: CoroutineContext, exception: Throwable) {
			exception.printStackTraceDebug()
		}
	}

	companion object {

		private const val PROGRESS_UNDEFINED = -1f
		private const val LOAD_PRIORITY_PREFETCH = 0
		private const val LOAD_PRIORITY_VISIBLE = 1
		private const val LOAD_PRIORITY_RETRY = 2
		private const val PREFETCH_LIMIT_DEFAULT = 6
		private const val PREFETCH_MIN_RAM_MB = 80L

		fun createPageRequest(pageUrl: String, page: ContentPage): Request {
			val builder = Request.Builder()
				.url(pageUrl)
				.get()
				.header(CommonHeaders.ACCEPT, "image/avif,image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
				.cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
				.tag(ContentSource::class.java, page.source)
				// 传递 source 名称，便于下游拦截器获知来源
				.header(CommonHeaders.MANGA_SOURCE, page.source.name)
			page.headers?.forEach { (k, v) -> builder.header(k, v) }
			val lowerHeaders = page.headers?.keys?.associateBy { it.lowercase() } ?: emptyMap()
			if (!lowerHeaders.containsKey("referer") &&
				(pageUrl.contains("gold-usergeneratedcontent.net") || pageUrl.contains("hitomi.la"))
			) {
				builder.header("Referer", "https://hitomi.la/")
			}
			val request = builder.build()
			Log.d(
				"JsPageRequest",
				"build request url=$pageUrl headers=${request.headers} source=${page.source.name}"
			)
			return request
		}

		// Backward-compatible helper; strongly prefer the ContentPage overload to carry headers.
		fun createPageRequest(pageUrl: String, mangaSource: ContentSource, headers: Map<String, String>? = null): Request {
			val builder = Request.Builder()
				.url(pageUrl)
				.get()
				.header(CommonHeaders.ACCEPT, "image/avif,image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
				.cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
				// 传递来源，便于下游拦截器注入默认 UA/Referer
				.header(CommonHeaders.MANGA_SOURCE, mangaSource.name)
				.tag(ContentSource::class.java, mangaSource)
			headers?.forEach { (k, v) -> builder.header(k, v) }
			return builder.build()
		}


		@Blocking
		private fun Uri.exists(): Boolean = when {
			isFileUri() -> toFile().exists()
			isZipUri() -> {
				val file = File(requireNotNull(schemeSpecificPart))
				file.exists() && ZipFile(file).use { it.getEntry(fragment) != null }
			}

			else -> false
		}

		@Blocking
		private fun Uri.isTargetNotEmpty(): Boolean = when {
			isFileUri() -> toFile().isNotEmpty()
			isZipUri() -> {
				val file = File(requireNotNull(schemeSpecificPart))
				file.exists() && ZipFile(file).use { (it.getEntry(fragment)?.size ?: 0L) != 0L }
			}

			else -> false
		}
	}
}
