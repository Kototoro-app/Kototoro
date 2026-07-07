package org.skepsun.kototoro.download.ui.worker

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.WorkerAssistedFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.internal.closeQuietly
import okio.IOException
import okio.buffer
import okio.sink
import okio.source
import okio.use
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.ids
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.network.imageproxy.ImageProxyInterceptor
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.requireAvailableRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.Throttler
import org.skepsun.kototoro.core.util.ext.MimeType
import org.skepsun.kototoro.core.util.ext.awaitFinishedWorkInfosByTag
import org.skepsun.kototoro.core.util.ext.awaitUpdateWork
import org.skepsun.kototoro.core.util.ext.awaitWorkInfosByTag
import org.skepsun.kototoro.core.util.ext.deleteAwait
import org.skepsun.kototoro.core.util.ext.deleteWork
import org.skepsun.kototoro.core.util.ext.deleteWorks
import org.skepsun.kototoro.core.util.ext.ensureSuccess
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getWorkInputData
import org.skepsun.kototoro.core.util.ext.getWorkSpec
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.openSource
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toFileOrNull
import org.skepsun.kototoro.core.util.ext.toFileNameSafe
import org.skepsun.kototoro.core.util.ext.toMimeType
import org.skepsun.kototoro.core.util.ext.toMimeTypeOrNull
import org.skepsun.kototoro.core.util.ext.use
import org.skepsun.kototoro.core.util.ext.withTicker
import org.skepsun.kototoro.core.util.ext.writeAllCancellable
import org.skepsun.kototoro.core.util.progress.RealtimeEtaEstimator
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.download.domain.DownloadProgress
import org.skepsun.kototoro.download.domain.DownloadState
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.data.LocalStorageCache
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.data.PageCache
import org.skepsun.kototoro.local.data.TempFileFilter
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.data.output.LocalContentOutput
import org.skepsun.kototoro.local.data.output.LocalContentDirOutput
import org.skepsun.kototoro.local.domain.ContentLock
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.video.data.VideoDownloadIndex
import org.skepsun.kototoro.video.domain.resolveVideoCandidates
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.requireBody
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.domain.ReaderSuperResolutionManager
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.novel.NovelContentLoader
import org.skepsun.kototoro.reader.novel.NovelParagraphSplitter
import org.skepsun.kototoro.reader.novel.NovelParagraphType
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelTranslationProcessor
import org.skepsun.kototoro.reader.translate.domain.ReaderPageTranslationProcessor
import org.jsoup.Jsoup
import java.io.File
import java.net.URLDecoder
import java.util.UUID
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import javax.inject.Inject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@HiltWorker
class DownloadWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	@ContentHttpClient private val okHttp: OkHttpClient,
	@PageCache private val cache: LocalStorageCache,
	private val localContentRepository: LocalMangaRepository,
	private val mangaLock: ContentLock,
	private val mangaDataRepository: ContentDataRepository,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val settings: AppSettings,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalContent?>,
	private val slowdownDispatcher: DownloadSlowdownDispatcher,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	notificationFactoryFactory: DownloadNotificationFactory.Factory,
	private val mangaDatabase: org.skepsun.kototoro.core.db.MangaDatabase,
	private val epubStorageManager: org.skepsun.kototoro.local.epub.EpubStorageManager,
	private val localStorageManager: org.skepsun.kototoro.local.data.LocalStorageManager,
	private val videoDownloadIndex: VideoDownloadIndex,
	private val translationProcessor: ReaderPageTranslationProcessor,
	private val novelTranslationProcessor: NovelTranslationProcessor,
	private val novelContentLoader: NovelContentLoader,
	private val superResolutionManager: ReaderSuperResolutionManager,
) : CoroutineWorker(appContext, params) {

	private data class DownloadExecutionContext(
		val executionManga: Content,
		val displayMangaId: Long,
	)

	private data class DownloadResolvedContent(
		val executionManga: Content,
		val executionDetails: Content,
	)

	private val task = DownloadTask(params.inputData)
	private val notificationFactory = notificationFactoryFactory.create(uuid = params.id, isSilent = task.isSilent)
	private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

	@Volatile
	private var lastPublishedState: DownloadState? = null
	private val currentState: DownloadState
		get() = checkNotNull(lastPublishedState)

	private val etaEstimator = RealtimeEtaEstimator()
	private val notificationThrottler = Throttler(400)

	private suspend fun resolveExecutionContext(executionManga: Content): DownloadExecutionContext {
		val displayMangaId = task.displayMangaId
			?: mangaDataRepository.findDisplayContentById(executionManga.id, withChapters = false)?.id
			?: executionManga.id
		return DownloadExecutionContext(
			executionManga = executionManga,
			displayMangaId = displayMangaId,
		)
	}

	private suspend fun resolveExecutionContent(executionManga: Content): DownloadResolvedContent {
		if (executionManga.isLocal) {
			val remoteExecutionManga = localContentRepository.getRemoteContent(executionManga)
				?: error("Cannot obtain remote manga instance")
			val repo = mangaRepositoryFactory.createWithDiagnostics(remoteExecutionManga.source).requireAvailableRepository(
				tag = "DownloadWorker",
				prefix = "resolveExecutionContent_repository_unavailable",
			) { "Download source ${remoteExecutionManga.source.name} is not available" }
			val executionDetails = if (
				remoteExecutionManga.chapters.isNullOrEmpty() ||
				remoteExecutionManga.description.isNullOrEmpty()
			) {
				repo.getDetails(remoteExecutionManga)
			} else {
				remoteExecutionManga
			}
			return DownloadResolvedContent(
				executionManga = remoteExecutionManga,
				executionDetails = executionDetails,
			)
		}
		val executionDetails = if (
			executionManga.chapters.isNullOrEmpty() ||
			executionManga.description.isNullOrEmpty()
		) {
			val repo = mangaRepositoryFactory.createWithDiagnostics(executionManga.source).requireAvailableRepository(
				tag = "DownloadWorker",
				prefix = "resolveExecutionContent_repository_unavailable",
			) { "Download source ${executionManga.source.name} is not available" }
			repo.getDetails(executionManga)
		} else {
			executionManga
		}
		return DownloadResolvedContent(
			executionManga = executionManga,
			executionDetails = executionDetails,
		)
	}

	override suspend fun doWork(): Result = withContext(org.skepsun.kototoro.core.parser.legado.RequestPriority(org.skepsun.kototoro.core.parser.legado.RequestPriority.BACKGROUND)) {
		setForeground(getForegroundInfo())
		val executionManga = mangaDataRepository.findContentById(task.executionMangaId, withChapters = true) ?: return@withContext Result.failure()
		val executionContext = resolveExecutionContext(executionManga)
		publishState(
			DownloadState(
				manga = executionContext.executionManga,
				displayMangaId = executionContext.displayMangaId,
				isIndeterminate = true,
				taskKind = task.kind,
			).also { lastPublishedState = it },
		)
		Log.i(
			"DownloadWorker",
			"doWork start: workId=$id mangaId=${executionContext.executionManga.id} title=${executionContext.executionManga.title} " +
				"displayMangaId=${executionContext.displayMangaId} kind=${task.kind} " +
				"chapters=${executionContext.executionManga.chapters?.size ?: 0} taskChapters=${task.executionChapterIds?.size ?: -1}",
		)

		ActiveDownloadRegistry.register(id, isPaused = task.isPaused)

		val pausingHandle = PausingHandle()
		if (task.isPaused) {
			Log.i("DownloadWorker", "doWork start paused: workId=$id mangaId=${executionContext.executionManga.id}")
			pausingHandle.pause()
		}

		val pausingReceiver = PausingReceiver(id, pausingHandle)
		ContextCompat.registerReceiver(
			applicationContext,
			pausingReceiver,
			PausingReceiver.createIntentFilter(id),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)

		try {
			withContext(pausingHandle) {
				checkIsPaused()
				when (task.kind) {
					DownloadTaskKind.DOWNLOAD -> {
						val resolvedContent = resolveExecutionContent(executionContext.executionManga)
						val storedExecutionDetails = mangaDataRepository.storeContentAndReturn(
							resolvedContent.executionDetails,
							replaceExisting = true,
						)
						val storedResolvedContent = resolvedContent.copy(executionDetails = storedExecutionDetails)
						publishExecutionDetailsState(storedExecutionDetails)
						Log.i("DownloadWorker", "doWork before downloadContentImpl: workId=$id mangaId=${executionContext.executionManga.id}")
						val downloadedIds = getDoneChapters(storedExecutionDetails)
						Log.i(
							"DownloadWorker",
							"doWork after getDoneChapters: downloadedIds=${downloadedIds.size} workId=$id mangaId=${executionContext.executionManga.id}",
						)
						downloadContentImpl(
							subject = executionContext.executionManga,
							resolvedContent = storedResolvedContent,
							task = task,
							excludedIds = downloadedIds,
						)
						Log.i("DownloadWorker", "doWork after downloadContentImpl: workId=$id mangaId=${executionContext.executionManga.id}")
					}

					DownloadTaskKind.PREPARE_TRANSLATION,
					DownloadTaskKind.PREPARE_SUPER_RESOLUTION -> {
						Log.i(
							"DownloadWorker",
							"doWork before prepareContentImpl: workId=$id mangaId=${executionContext.executionManga.id} kind=${task.kind}",
						)
						prepareContentImpl(executionContext.executionManga, task)
						Log.i(
							"DownloadWorker",
							"doWork after prepareContentImpl: workId=$id mangaId=${executionContext.executionManga.id} kind=${task.kind}",
						)
					}
				}
			}
			Result.success(currentState.toWorkData())
		} catch (_: CancellationException) {
			withContext(NonCancellable) {
				val notification = notificationFactory.create(currentState.copy(isStopped = true))
				notificationManager.notify(id.hashCode(), notification)
			}
			Result.failure(
				currentState.copy(eta = -1L, isStuck = false).toWorkData(),
			)
		} catch (e: Exception) {
			Log.e(
				"DownloadWorker",
				"doWork failed: workId=$id mangaId=${task.executionMangaId} error=${e.javaClass.simpleName} msg=${e.message}",
				e,
			)
			e.printStackTraceDebug()
			if (settings.isDownloadAutoRetryOnNetworkError && e is IOException) {
				Log.w("DownloadWorker", "Retrying work due to IOException: ${e.message}", e)
				return@withContext Result.retry()
			}
			Result.failure(
				currentState.copy(
					error = e,
					errorMessage = e.getDisplayMessage(applicationContext.resources),
					eta = -1L,
					isStuck = false,
				).toWorkData(),
			)
		} finally {
			ActiveDownloadRegistry.unregister(id)
			try {
				applicationContext.unregisterReceiver(pausingReceiver)
			} catch (_: Exception) {
			}
			notificationManager.cancel(id.hashCode())
		}
	}

	override suspend fun getForegroundInfo() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
		ForegroundInfo(
			id.hashCode(),
			notificationFactory.create(lastPublishedState),
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
	} else {
		ForegroundInfo(
			id.hashCode(),
			notificationFactory.create(lastPublishedState),
		)
	}

	private suspend fun downloadContentImpl(
		subject: Content,
		resolvedContent: DownloadResolvedContent,
		task: DownloadTask,
		excludedIds: Set<Long>,
	) {
		val contentType = subject.source.getContentType()
		if (contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO) {
			downloadVideoImpl(subject, task, excludedIds)
			return
		}
		Log.d("DownloadWorker", "downloadContentImpl start: mangaId=${subject.id} title=${subject.title} excluded=${excludedIds.size}")
		val chaptersToSkip = excludedIds.toMutableSet()
		mangaLock.withLock(subject) {
			var destination = localContentRepository.getOutputDir(subject, task.destination)
			checkNotNull(destination) { applicationContext.getString(R.string.cannot_find_available_storage) }
			Log.d("DownloadWorker", "downloadContentImpl outputDir=${destination.absolutePath}")
			var output: LocalContentOutput? = null
			try {
				val executionManga = resolvedContent.executionManga
				val executionDetails = resolvedContent.executionDetails
				val repo = mangaRepositoryFactory.createWithDiagnostics(executionManga.source).requireAvailableRepository(
					tag = "DownloadWorker",
					prefix = "downloadContentImpl_repository_unavailable",
				) { "Download source ${executionManga.source.name} is not available" }
				Log.d("DownloadWorker", "downloadContentImpl repo=${repo.source.name}")
				Log.d("DownloadWorker", "downloadContentImpl detailsChapters=${executionDetails.chapters?.size ?: 0}")
				val contentType = executionDetails.source.getContentType()
				val isNovel = when (contentType) {
					ContentType.NOVEL, ContentType.HENTAI_NOVEL -> true
					else -> false
				} || executionDetails.source.name.uppercase() in setOf("BILINOVEL", "LKNOVEL_US", "LIGHTNOVEL_WIKI", "NOVELIA", "WENKU8", "BIQUGE") ||
					executionDetails.source.name.startsWith("JSON_LEGADO", ignoreCase = true)
				
				// 检测是否包含EPUB章节（仅小说需要，漫画全量扫描会导致长时间阻塞）
				val hasEpubChapters = if (isNovel) {
					runCatchingCancellable {
						val fullChapters = executionDetails.chapters ?: emptyList()
						val chaptersToCheck = getChapters(executionDetails, task).take(3)
						chaptersToCheck.any { chapter ->
							val currentInFull = fullChapters.indexOfFirst { it.id == chapter.value.id }
							val nextChapterUrl = if (currentInFull != -1) fullChapters.getOrNull(currentInFull + 1)?.url else null
							val pages = repo.getPages(chapter.value, nextChapterUrl)
							pages.size == 1 && pages[0].preview == "EPUB"
						}
					}.getOrNull() ?: false
				} else {
					false
				}
				
				// 如果包含EPUB章节，强制使用MULTIPLE_CBZ格式
				val downloadFormat = if (hasEpubChapters) {
					println("DownloadWorker: Detected EPUB chapters, using MULTIPLE_CBZ format")
					android.util.Log.i("DownloadWorker", "Detected EPUB chapters, automatically using MULTIPLE_CBZ format for proper chapter extraction")
					DownloadFormat.MULTIPLE_CBZ
				} else {
					task.format ?: settings.preferredDownloadFormat
				}
				Log.d("DownloadWorker", "downloadContentImpl isNovel=$isNovel hasEpubChapters=$hasEpubChapters format=$downloadFormat")

				if (isNovel && !hasEpubChapters) {
					// 尝试获取小说专用的输出目录
					destination = localStorageManager.getDefaultNovelWriteableDir() ?: localStorageManager.getNovelWriteableDirs().firstOrNull() ?: destination
					Log.d("DownloadWorker", "downloadContentImpl novel outputDir=${destination.absolutePath}")
				}

				output = LocalContentOutput.getOrCreate(
					root = destination,
					manga = executionDetails,
					format = downloadFormat,
				)
				val coverUrl = executionDetails.largeCoverUrl.ifNullOrEmpty { executionDetails.coverUrl }
				if (!coverUrl.isNullOrEmpty()) {
					downloadFile(repo, coverUrl, destination, isCover = true).let { file ->
						output.addCover(file, getMediaType(coverUrl, file))
						file.deleteAwait()
					}
				}
				if (isNovel && !hasEpubChapters) {
					downloadNovelChapters(executionDetails, task, repo, destination, output, chaptersToSkip)
					output.mergeWithExisting()
					output.finish()
					val localContent = LocalContentParser(output.rootFile).getContent(withDetails = true)
					// 刷新缓存，确保 UI 能识别到本地 icon
					localContentRepository.findSavedContent(executionDetails)
					android.util.Log.d("DownloadWorker", "Novel download completed, emitting localStorageChanges for ${output.rootFile}")
					localStorageChanges.emit(localContent)
					publishState(currentState.copy(localContent = localContent, eta = -1L, isStuck = false, isCompleted = true))
					return@withLock
				}
				processStandardChapters(executionDetails, task, repo, destination, chaptersToSkip, output)
				publishState(currentState.copy(isIndeterminate = true, eta = -1L, isStuck = false))
				output.mergeWithExisting()
				output.finish()
				val localContent = LocalContentParser(output.rootFile).getContent(withDetails = true)
				// 刷新缓存
				localContentRepository.findSavedContent(executionDetails)
				localStorageChanges.emit(localContent)
				publishState(currentState.copy(localContent = localContent, eta = -1L, isStuck = false, isCompleted = true))
			} catch (e: Exception) {
				Log.e(
					"DownloadWorker",
					"downloadContentImpl failed: mangaId=${subject.id} title=${subject.title} error=${e.javaClass.simpleName} msg=${e.message}",
					e,
				)
				if (e !is CancellationException) {
					publishState(
						currentState.copy(
							error = e,
							errorMessage = e.getDisplayMessage(applicationContext.resources),
						),
					)
				}
				throw e
			} finally {
				withContext(NonCancellable) {
					output?.closeQuietly()
					output?.cleanup()
					val tempFiles = destination.listFiles(TempFileFilter())
					if (tempFiles != null) {
						for (file in tempFiles) {
							runCatchingCancellable { file.deleteAwait() }
						}
					}
				}
			}
		}
	}

	private suspend fun prepareContentImpl(
		subject: Content,
		task: DownloadTask,
	) {
		require(task.kind != DownloadTaskKind.DOWNLOAD) { "Prepare flow cannot use DOWNLOAD task kind" }
		val contentType = subject.source.getContentType()
		mangaLock.withLock(subject) {
			when (contentType) {
				ContentType.NOVEL, ContentType.HENTAI_NOVEL -> {
					check(task.kind == DownloadTaskKind.PREPARE_TRANSLATION) {
						"Novel content only supports translation preparation"
					}
					prepareNovelTranslation(subject, task)
				}

				ContentType.VIDEO, ContentType.HENTAI_VIDEO -> {
					error("Video content does not support preparation tasks")
				}

				else -> {
					prepareMangaContent(subject, task)
				}
			}
		}
	}

	private suspend fun prepareMangaContent(
		manga: Content,
		task: DownloadTask,
	) {
		val chapters = getChapters(manga, task)
		for ((chapterIndex, chapter) in chapters.withIndex()) {
			checkIsPaused()
			val pages = loadLocalPages(chapter.value)
			check(pages.isNotEmpty()) { "No local pages found for chapter ${chapter.value.title ?: chapter.value.id}" }
			for ((pageIndex, page) in pages.withIndex()) {
				checkIsPaused()
				publishState(
					currentState.copy(
						totalChapters = chapters.size,
						currentChapter = chapterIndex,
						totalPages = pages.size,
						currentPage = pageIndex,
						isIndeterminate = false,
						eta = -1L,
						isStuck = false,
					),
				)
				val sourceUri = resolvePreparationPageUri(page)
					?: error("Cannot resolve page uri for ${page.url}")
				when (task.kind) {
					DownloadTaskKind.PREPARE_TRANSLATION -> {
						val translationInputUri = prepareTranslationInputUri(sourceUri)
						translationProcessor.process(
							page = page,
							sourceUri = translationInputUri,
							forceEnabled = true,
						)
					}

					DownloadTaskKind.PREPARE_SUPER_RESOLUTION -> {
						superResolutionManager.processImage(
							originalUri = sourceUri,
							modelId = getSuperResolutionModelId(),
							noiseLevel = settings.readerSuperResolutionNoiseLevel,
							cacheLimitMb = settings.readerSuperResolutionCacheLimitMb,
						)
					}

					DownloadTaskKind.DOWNLOAD -> Unit
				}
			}
			publishState(
				currentState.copy(
					downloadedChapters = currentState.downloadedChapters + 1,
				),
			)
		}
		publishState(
			currentState.copy(
				isIndeterminate = false,
				eta = -1L,
				isStuck = false,
				isCompleted = true,
			),
		)
	}

	private suspend fun prepareNovelTranslation(
		manga: Content,
		task: DownloadTask,
	) {
		val chapters = getChapters(manga, task)
		val repo = mangaRepositoryFactory.createWithDiagnostics(manga.source).requireAvailableRepository(
			tag = "DownloadWorker",
			prefix = "prepareNovelTranslation_repository_unavailable",
		) { "Prepare translation source ${manga.source.name} is not available" }
		val sourceLang = settings.readerTranslationSourceLanguage
		val targetLang = settings.readerTranslationTargetLanguage
		val displayMode = NovelReaderSettings.load(applicationContext).translationDisplayMode
		for ((chapterIndex, chapter) in chapters.withIndex()) {
			checkIsPaused()
			val content = novelContentLoader.loadChapterContent(
				repository = repo,
				chapter = chapter.value,
			)
			val totalParagraphs = NovelParagraphSplitter.split(content)
				.count { it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank() }
				.coerceAtLeast(1)
			novelTranslationProcessor.translateChapterFlow(
				chapterIndex = chapterIndex,
				content = content,
				sourceLang = sourceLang,
				targetLang = targetLang,
				displayMode = displayMode,
			).collect { translation ->
				checkIsPaused()
				val translatedCount = translation.translations.size.coerceAtMost(totalParagraphs)
				publishState(
					currentState.copy(
						totalChapters = chapters.size,
						currentChapter = chapterIndex,
						totalPages = totalParagraphs,
						currentPage = translatedCount.coerceAtLeast(1) - 1,
						isIndeterminate = false,
						eta = -1L,
						isStuck = false,
						downloadedChapters = if (translation.isComplete) chapterIndex + 1 else chapterIndex,
					),
				)
			}
		}
		publishState(
			currentState.copy(
				isIndeterminate = false,
				eta = -1L,
				isStuck = false,
				isCompleted = true,
			),
		)
	}

	private suspend fun loadLocalPages(chapter: ContentChapter): List<ContentPage> {
		check(isPreparationChapterEligible(chapter)) {
			"Chapter ${chapter.id} is not downloaded or local"
		}
		return LocalContentParser(chapter.url.toUri()).getPages(chapter)
	}

	private fun isPreparationChapterEligible(chapter: ContentChapter): Boolean {
		return chapter.source.isLocal || isLocalChapterUrl(chapter.url)
	}

	private fun isLocalChapterUrl(url: String): Boolean {
		return url.startsWith("file:") ||
			url.startsWith("zip:") ||
			url.startsWith("file+zip:") ||
			url.startsWith("content:") ||
			url.startsWith("epub:") ||
			url.startsWith("localepub:")
	}

	private suspend fun resolvePreparationPageUri(page: ContentPage): Uri? {
		val uri = page.url.toUri()
		return when {
			uri.isFileUri() -> uri
			uri.isZipUri() -> cacheZipPage(uri)
			uri.scheme == "content" || uri.scheme == "android.resource" -> cacheContentUri(page.url, uri)
			uri.scheme == "data" -> cacheDataUri(page.url)
			else -> null
		}
	}

	private suspend fun cacheZipPage(uri: Uri): Uri? {
		cache[uri.toString()]?.let { return it.toUri() }
		return runCatching {
			val zipFile = when (uri.scheme) {
				"file+zip" -> File(uri.host.orEmpty() + uri.path.orEmpty())
				else -> File(uri.schemeSpecificPart)
			}
			ZipFile(zipFile).use { zip ->
				val entry = zip.getEntry(uri.fragment) ?: return@runCatching null
				BitmapDecoderCompat.decode(
					zip.getInputStream(entry),
					MimeTypes.getMimeTypeFromExtension(entry.name),
				)
			}.use { image ->
				cache.set(uri.toString(), image).toUri()
			}
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	private suspend fun cacheContentUri(cacheKey: String, uri: Uri): Uri? {
		cache[cacheKey]?.let { return it.toUri() }
		return runCatching {
			val type = applicationContext.contentResolver.getType(uri)?.toMimeTypeOrNull()
			applicationContext.contentResolver.openInputStream(uri)?.use { input ->
				cache.set(cacheKey, input.source(), type).toUri()
			}
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	private suspend fun cacheDataUri(dataUrl: String): Uri? {
		cache[dataUrl]?.let { return it.toUri() }
		return runCatching {
			val commaIndex = dataUrl.indexOf(',')
			check(commaIndex != -1) { "Invalid data URL" }
			val header = dataUrl.substring(0, commaIndex)
			val data = dataUrl.substring(commaIndex + 1)
			val isBase64 = header.contains(";base64")
			val contentType = header.substringAfter("data:").substringBefore(";").toMimeTypeOrNull()
			val bytes = if (isBase64) {
				Base64.getDecoder().decode(data)
			} else {
				URLDecoder.decode(data, "UTF-8").toByteArray()
			}
			cache.set(dataUrl, bytes.inputStream().source(), contentType).toUri()
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	private suspend fun prepareTranslationInputUri(sourceUri: Uri): Uri {
		if (!settings.isReaderSuperResolutionEnabled) {
			return sourceUri
		}
		return superResolutionManager.processImage(
			originalUri = sourceUri,
			modelId = getSuperResolutionModelId(),
			noiseLevel = settings.readerSuperResolutionNoiseLevel,
			cacheLimitMb = settings.readerSuperResolutionCacheLimitMb,
		) ?: sourceUri
	}

	private fun getSuperResolutionModelId(): String {
		return if (settings.readerSuperResolutionEngine == "ANIME4K") {
			settings.readerSuperResolutionAnime4kMode
		} else {
			settings.readerSuperResolutionModel
		}
	}

	private suspend fun processStandardChapters(
		mangaDetails: Content,
		task: DownloadTask,
		repo: ContentRepository,
		destination: File,
		chaptersToSkip: MutableSet<Long>,
		output: LocalContentOutput,
	) {
		val chapters = getChapters(mangaDetails, task)
		Log.d("DownloadWorker", "processStandardChapters total=${chapters.size} mangaId=${mangaDetails.id}")
		for ((chapterIndex, chapter) in chapters.withIndex()) {
			checkIsPaused()

			val fullChapters = mangaDetails.chapters ?: emptyList()
			val currentInFull = fullChapters.indexOfFirst { it.id == chapter.value.id }
			val nextChapterUrl = if (currentInFull != -1) fullChapters.getOrNull(currentInFull + 1)?.url else null
			val pages = runFailsafe {
				repo.getPages(chapter.value, nextChapterUrl)
			} ?: continue
			if (pages.isEmpty()) {
				Log.w("DownloadWorker", "processStandardChapters empty pages: idx=$chapterIndex title=${chapter.value.title}")
			}

			println("DownloadWorker: Chapter ${chapter.index}: ${chapter.value.title}")
			println("DownloadWorker: Pages count: ${pages.size}")
			if (pages.isNotEmpty()) {
				println("DownloadWorker: First page preview: ${pages[0].preview}")
				println("DownloadWorker: First page url: ${pages[0].url}")
			}

			val isEpubChapter = pages.size == 1 && pages[0].preview == "EPUB"
			if (!isEpubChapter && chaptersToSkip.remove(chapter.value.id)) {
				println("DownloadWorker: Skipping already downloaded chapter")
				publishState(currentState.copy(downloadedChapters = currentState.downloadedChapters + 1))
				continue
			}

			if (isEpubChapter) {
				println("DownloadWorker: EPUB detected! Using NEW ARCHITECTURE")
				android.util.Log.i("DownloadWorker", "EPUB chapter detected, using new LocalEpubSource architecture")
				chaptersToSkip.remove(chapter.value.id)

				// Publish initial progress for EPUB download
				publishState(currentState.copy(
					totalChapters = chapters.size,
					currentChapter = chapterIndex,
					totalPages = 1,
					currentPage = 0,
					isIndeterminate = false
				))

				val result = runFailsafe {
					downloadEpubToStorage(
						manga = mangaDetails,
						chapter = chapter,
						page = pages[0],
						epubUrl = pages[0].url,
						destination = destination,
						repo = repo,
					)
					true
				}
				if (result == true) {
					publishState(currentState.copy(
						downloadedChapters = currentState.downloadedChapters + 1,
						currentChapter = chapterIndex + 1,
						currentPage = 1
					))
					android.util.Log.i("DownloadWorker", "EPUB downloaded successfully to epub storage")

					runCatchingCancellable {
						val epubDir = epubStorageManager.getEpubDir(mangaDetails.id)
						val epubFileName = "chapter_${chapter.value.id}.epub"
						val epubFile = File(epubDir, epubFileName)

						if (!epubFile.exists()) {
							android.util.Log.e("DownloadWorker", "EPUB file not found: ${epubFile.absolutePath}")
							return@runCatchingCancellable
						}

						val parser = org.skepsun.kototoro.local.epub.LocalEpubParser(epubFile)
						val epubContent = parser.parseContent() ?: run {
							android.util.Log.e("DownloadWorker", "Failed to parse EPUB file")
							return@runCatchingCancellable
						}

						android.util.Log.d("DownloadWorker", "Parsed ${epubContent.chapters?.size} chapters from EPUB")

						val epubChapterMappingDao = mangaDatabase.getEpubChapterMappingDao()
						for ((index, epubChapter) in epubContent.chapters.orEmpty().withIndex()) {
							val internalChapterId = chapter.value.id + (index * 1000000L) + 1

							val mapping = org.skepsun.kototoro.core.db.entity.EpubChapterMappingEntity(
								internalChapterId = internalChapterId,
								parentChapterId = chapter.value.id,
								epubFilePath = epubFile.absolutePath,
								epubFileName = chapter.value.title ?: epubFileName,
								chapterIndex = index,
								chapterTitle = epubChapter.title ?: "Chapter ${index + 1}",
							)
							epubChapterMappingDao.insert(mapping)
						}

						android.util.Log.i("DownloadWorker", "EPUB chapters parsed and saved to database: ${epubContent.chapters?.size} chapters")
						android.util.Log.i("DownloadWorker", "EPUB file saved at: ${epubFile.absolutePath}")
						
						// Notify UI about the new local chapters
						localStorageChanges.emit(LocalContentParser(output.rootFile).getContent(withDetails = false))
					}.onFailure { e ->
						android.util.Log.e("DownloadWorker", "Failed to parse EPUB chapters", e)
						e.printStackTrace()
					}
				}
				continue
			} else {
				println("DownloadWorker: Not EPUB, using normal download")
			}

			val tempDir = File(destination, "tmp_${chapter.value.id}")
			if (!tempDir.exists()) {
				tempDir.mkdirs()
			}

			val pageCounter = AtomicInteger(0)
			val successCounter = AtomicInteger(0)
			channelFlow {
				val downloadThreads = if (settings.isDownloadAlignedWithReader) {
					settings.readerThreads
				} else {
					settings.downloadThreads
				}
				val semaphore = Semaphore(downloadThreads)
				for ((pageIndex, page) in pages.withIndex()) {
					checkIsPaused()
					launch {
						semaphore.withPermit {
							val success = runFailsafe {
								val prefix = String.format("%04d.", pageIndex)
								val existingFile = tempDir.listFiles { _, name -> name.startsWith(prefix) }?.firstOrNull()
								val file = if (existingFile != null && existingFile.length() > 0) {
									existingFile
								} else {
									val url = repo.getPageUrl(page)
									val downloadedFile = cache[url]
										?: downloadFile(repo, url, destination, page = page)
									val ext = downloadedFile.extension.takeIf { it != "tmp" } ?: "jpg"
									val targetFile = File(tempDir, prefix + ext)
									downloadedFile.copyTo(targetFile, overwrite = true)
									if (downloadedFile.extension == "tmp") {
										downloadedFile.deleteAwait()
									}
									targetFile
								}
								output.addPage(
									chapter = chapter,
									file = file,
									pageNumber = pageIndex,
									type = getMediaType(file.name, file),
								)
								true
							} ?: false
							if (success) {
								successCounter.incrementAndGet()
								send(pageIndex)
							}
						}
					}
				}
			}.map {
				DownloadProgress(
					totalChapters = chapters.size,
					currentChapter = chapterIndex,
					totalPages = pages.size,
					currentPage = pageCounter.getAndIncrement(),
				)
			}.withTicker(2L, TimeUnit.SECONDS).collect { progress ->
				publishState(
					currentState.copy(
						totalChapters = progress.totalChapters,
						currentChapter = progress.currentChapter,
						totalPages = progress.totalPages,
						currentPage = progress.currentPage,
						isIndeterminate = false,
						eta = etaEstimator.getEta(),
						isStuck = etaEstimator.isStuck(),
					),
				)
			}
			if (successCounter.get() == 0) {
				throw IOException("No pages downloaded for chapter: ${chapter.value.title ?: chapter.value.id}")
			}
			if (output.flushChapter(chapter.value)) {
				tempDir.deleteRecursively()
				runCatchingCancellable {
					localStorageChanges.emit(LocalContentParser(output.rootFile).getContent(withDetails = false))
				}.onFailure(Throwable::printStackTraceDebug)
			}
			publishState(currentState.copy(downloadedChapters = currentState.downloadedChapters + 1))
		}
	}

	private suspend fun <R> runFailsafe(
		block: suspend () -> R,
	): R? {
		checkIsPaused()
		val maxAttempts = settings.downloadRetryCount
		var countDown = maxAttempts
		failsafe@ while (true) {
			try {
				return block()
			} catch (e: IOException) {
				val retryDelay = if (e is TooManyRequestExceptions) {
					e.getRetryDelay()
				} else {
					settings.downloadRetryDelayMs.toLong()
				}
				Log.w(
					"DownloadWorker",
					"runFailsafe failed: ${e.javaClass.simpleName} msg=${e.message} retryDelay=$retryDelay remaining=$countDown",
					e,
				)
				if (settings.isDownloadAutoRetryOnNetworkError && e !is TooManyRequestExceptions && countDown <= 0) {
					throw e
				}
				if (countDown <= 0 || retryDelay < 0 || retryDelay > MAX_RETRY_DELAY) {
					val pausingHandle = PausingHandle.current()
					if (pausingHandle.skipAllErrors()) {
						return null
					}
					publishState(
						currentState.copy(
							isPaused = true,
							error = e,
							errorMessage = e.getDisplayMessage(applicationContext.resources),
							eta = -1L,
							isStuck = false,
						),
					)
					countDown = maxAttempts
					pausingHandle.pause()
					try {
						pausingHandle.awaitResumed()
						if (pausingHandle.skipCurrentError()) {
							return null
						}
					} finally {
						publishState(currentState.copy(isPaused = false, error = null, errorMessage = null))
					}
				} else {
					countDown--
					delay(retryDelay)
				}
			}
		}
	}

	private suspend fun checkIsPaused() {
		val pausingHandle = PausingHandle.current()
		while (true) {
			if (pausingHandle.isPaused) {
				publishState(currentState.copy(isPaused = true, eta = -1L, isStuck = false))
				try {
					pausingHandle.awaitResumed()
				} finally {
					publishState(currentState.copy(isPaused = false))
				}
			}
			val limit = settings.downloadMaxActiveSeries
			if (ActiveDownloadRegistry.isTurn(id, limit)) {
				break
			}
			delay(1000)
		}
	}

	private suspend fun getMediaType(url: String, file: File): MimeType? = runInterruptible(Dispatchers.IO) {
		BitmapDecoderCompat.probeMimeType(file)?.let {
			return@runInterruptible it
		}
		MimeTypes.getMimeTypeFromUrl(url)
	}

	/**
	 * 小说章节下载：复用漫画的输出格式（单本/多本 CBZ），章节内写入 HTML + 插图。
	 */
	private suspend fun downloadNovelChapters(
		manga: Content,
		task: DownloadTask,
		repo: ContentRepository,
		destination: File,
		output: LocalContentOutput,
		chaptersToSkip: MutableSet<Long>,
	) {
		val chapters = getChapters(manga, task)
		for ((chapterIndex, chapter) in chapters.withIndex()) {
			checkIsPaused()
			if (chaptersToSkip.remove(chapter.value.id)) {
				publishState(currentState.copy(downloadedChapters = currentState.downloadedChapters + 1))
				continue
			}

			val fullChapters = manga.chapters ?: emptyList()
			val currentInFull = fullChapters.indexOfFirst { it.id == chapter.value.id }
			val nextChapterUrl = if (currentInFull != -1) fullChapters.getOrNull(currentInFull + 1)?.url else null

			val content = runFailsafe { repo.getChapterContent(chapter.value, nextChapterUrl) }
				?: runFailsafe { decodeDataPage(repo.getPages(chapter.value, nextChapterUrl).firstOrNull()) }
				?: run {
					android.util.Log.w("DownloadWorker", "downloadNovelChapters: skip chapter ${chapter.value.title} (no content)")
					continue
				}

			val imageHeaderMap = LinkedHashMap<String, Map<String, String>>()
			content.images.forEach { imageHeaderMap[it.url] = it.headers }
			runCatching {
				val parsed = Jsoup.parse(content.html)
				parsed.select("img").forEach { img ->
					val src = img.attr("data-src").ifBlank { img.attr("src") }.trim()
					if (src.isNotBlank() && !src.startsWith("data:", true)) {
						imageHeaderMap.putIfAbsent(src, emptyMap())
					}
				}
			}

			val nameMap = LinkedHashMap<String, ImageDownload>()
			var pageNumber = 1
			imageHeaderMap.entries.forEach { entry ->
				val originalUrl = entry.key
				if (originalUrl.startsWith("data:", ignoreCase = true) || originalUrl.startsWith("file:", ignoreCase = true)) {
					return@forEach
				}
				val ext = MimeTypes.getNormalizedExtension(originalUrl.substringAfterLast('/').substringBefore('?'))?.ifBlank { "jpg" } ?: "jpg"
				val name = buildPageName(chapter, pageNumber, ext)
				nameMap[originalUrl] = ImageDownload(
					url = originalUrl,
					headers = entry.value,
					name = name,
					pageNumber = pageNumber,
					mime = MimeTypes.getMimeTypeFromExtension(ext),
				)
				pageNumber++
			}

				val rewrittenHtml = rewriteHtmlWithCustomNames(content.html, nameMap.mapValues { it.value.name })
				val htmlFile = destination.createTempFile("html").apply {
					writeText(rewrittenHtml)
				}
				val htmlName = buildPageName(chapter, 0, "html")
				output.addPage(
					chapter = chapter,
					file = htmlFile,
					pageNumber = 0,
					type = "text/html".toMimeTypeOrNull(),
				)

			val totalImages = nameMap.size
			val normalizedTotal = 100
			
			// 初始章节进度：设为 1% 以显示已开始
			publishState(currentState.copy(
				totalChapters = chapters.size,
				currentChapter = chapterIndex,
				totalPages = normalizedTotal,
				currentPage = 1,
				isIndeterminate = false,
				eta = etaEstimator.getEta(),
				isStuck = etaEstimator.isStuck(),
			))

			nameMap.values.forEachIndexed { imageIndex, download ->
				val headers = download.headers.toMutableMap()
				if (headers.none { it.key.equals("referer", ignoreCase = true) }) {
					headers["Referer"] = deriveReferer(download.url, manga)
				}
				runCatching {
					val file = downloadFile(
						repo = repo,
						url = download.url,
						destination = destination,
						headers = headers,
					)
					val type = download.mime ?: getMediaType(download.url, file)
					output.addPage(
						chapter = chapter,
						file = file,
						pageNumber = download.pageNumber,
						type = type,
					)
					if (file.extension == "tmp") file.deleteAwait()
					
					// 归一化当前进度
					val imageProgress = ((imageIndex + 1).toFloat() / totalImages * normalizedTotal).toInt().coerceIn(1, normalizedTotal)
					publishState(currentState.copy(
						totalChapters = chapters.size,
						currentChapter = chapterIndex,
						totalPages = normalizedTotal,
						currentPage = imageProgress,
						eta = etaEstimator.getEta(),
						isStuck = etaEstimator.isStuck(),
					))
				}.onFailure {
					android.util.Log.w("DownloadWorker", "downloadNovelChapters: image download failed ${it.message}")
				}
			}

			val mapping = nameMap.mapValues { it.value.name }
			output.putChapterImages(chapter.value.id, mapping)
			if (output.flushChapter(chapter.value)) {
				runCatchingCancellable {
					localStorageChanges.emit(LocalContentParser(output.rootFile).getContent(withDetails = false))
				}.onFailure(Throwable::printStackTraceDebug)
			}

			publishState(currentState.copy(
				downloadedChapters = currentState.downloadedChapters + 1,
				currentChapter = chapterIndex + 1,
				currentPage = 0
			))

			// Apply delay between chapters if configured (to avoid rate limiting)
			val delaySeconds = settings.downloadChapterDelay
			if (delaySeconds > 0 && chapterIndex < chapters.size - 1) {
				// Only delay if not the last chapter
				kotlinx.coroutines.delay(delaySeconds * 1000L)
			}
		}
	}

	private fun buildPageName(chapter: IndexedValue<ContentChapter>, pageNumber: Int, ext: String): String {
		val branchHash = chapter.value.branch?.hashCode() ?: 0
		return buildString {
			append(PAGE_NAME_PATTERN.format(branchHash, chapter.index + 1, pageNumber))
			if (ext.isNotBlank()) {
				append('.')
				append(ext)
			}
		}
	}

	private fun rewriteHtmlWithCustomNames(html: String, nameMap: Map<String, String>): String {
		if (nameMap.isEmpty()) return html
		return runCatching {
			val doc = Jsoup.parse(html)
			doc.select("img").forEach { img ->
				val src = (img.attr("data-src").ifBlank { img.attr("src") }).trim()
				val local = nameMap[src]
				if (local != null) {
					img.attr("src", local)
					img.attr("referrerpolicy", "no-referrer")
				}
			}
			doc.outerHtml()
		}.getOrDefault(html)
	}

	private data class ImageDownload(
		val url: String,
		val headers: Map<String, String>,
		val name: String,
		val pageNumber: Int,
		val mime: MimeType?,
	)

	private fun decodeDataPage(page: ContentPage?): NovelChapterContent? {
		if (page == null) return null
		val url = page.url
		if (!url.startsWith("data:", ignoreCase = true)) return null
		val data = url.removePrefix("data:")
		val commaIndex = data.indexOf(',')
		if (commaIndex <= 0) return null
		val meta = data.substring(0, commaIndex)
		val contentPart = data.substring(commaIndex + 1)
		val isBase64 = meta.contains(";base64", ignoreCase = true)
		val html = if (isBase64) {
			String(Base64.getDecoder().decode(contentPart), Charsets.UTF_8)
		} else {
			URLDecoder.decode(contentPart, "UTF-8")
		}
		return NovelChapterContent(html = html, images = emptyList())
	}

	private fun deriveReferer(url: String, manga: Content): String {
		return runCatching {
			val uri = java.net.URI(url)
			val scheme = if (uri.scheme.isNullOrBlank()) "https" else uri.scheme
			val host = uri.host ?: return@runCatching manga.publicUrl
			"$scheme://$host/"
		}.getOrElse { manga.publicUrl }
	}

	private suspend fun downloadFile(
		repo: ContentRepository,
		url: String,
		destination: File,
		useProxy: Boolean = true,
		headers: Map<String, String> = emptyMap(),
		page: ContentPage? = null,
		isCover: Boolean = false,
	): File {
		if (url.startsWith("data:", ignoreCase = true)) {
			val data = url.removePrefix("data:")
			val commaIndex = data.indexOf(',')
			require(commaIndex >= 0) { "Invalid data URL: missing comma separator" }
			val meta = data.substring(0, commaIndex)
			val contentPart = data.substring(commaIndex + 1)
			val isBase64 = meta.contains(";base64", ignoreCase = true)
			val mimeType = meta.substringBefore(';').takeIf { it.isNotBlank() }?.toMimeTypeOrNull()
			val ext = MimeTypes.getExtension(mimeType)
			val bytes = if (isBase64) {
				Base64.getDecoder().decode(contentPart)
			} else {
				URLDecoder.decode(contentPart, "UTF-8").toByteArray(Charsets.UTF_8)
			}
			val file = destination.createTempFile(ext)
			file.sink(append = false).buffer().use { sink ->
				sink.write(bytes)
			}
			return file
		}
		if (url.startsWith("content:", ignoreCase = true) || url.startsWith("file:", ignoreCase = true)) {
			val uri = url.toUri()
			val cr = applicationContext.contentResolver
			val ext = uri.toFileOrNull()?.let {
				MimeTypes.getNormalizedExtension(it.name)
			} ?: cr.getType(uri)?.toMimeTypeOrNull()?.let { MimeTypes.getExtension(it) }
			val file = destination.createTempFile(ext)
			try {
				cr.openSource(uri).use { input ->
					file.sink(append = false).buffer().use {
						it.writeAllCancellable(input)
					}
				}
			} catch (e: Exception) {
				file.delete()
				throw e
			}
			return file
		}
		if (url.startsWith("zip:", ignoreCase = true) || url.startsWith("file+zip:", ignoreCase = true)) {
			val uri = url.toUri()
			val zipFile = when (uri.scheme) {
				"zip" -> File(uri.schemeSpecificPart)
				"file+zip" -> File(uri.host.orEmpty() + uri.path.orEmpty())
				else -> throw IllegalArgumentException("Unsupported scheme: ${uri.scheme}")
			}
			val fragment = uri.fragment ?: ""
			val ext = MimeTypes.getNormalizedExtension(fragment)
			val file = destination.createTempFile(ext)
			try {
				runInterruptible(Dispatchers.IO) {
					java.util.zip.ZipFile(zipFile).use { zip ->
						val entry = checkNotNull(zip.getEntry(fragment)) {
							"Zip entry not found: $fragment in ${zipFile.absolutePath}"
						}
						zip.getInputStream(entry).use { input ->
							file.outputStream().use { output ->
								input.copyTo(output)
							}
						}
					}
				}
			} catch (e: Exception) {
				file.delete()
				throw e
			}
			return file
		}

		val request = when {
			page != null -> repo.createPageRequest(url, page)
			isCover -> repo.createCoverRequest(url)
			else -> org.skepsun.kototoro.reader.domain.PageLoader.createPageRequest(url, repo.source)
		}

		val requestBuilder = request.newBuilder()
		headers.forEach { (k, v) -> requestBuilder.header(k, v) }
		val finalRequest = requestBuilder.build()

		slowdownDispatcher.delay(repo.source)
		val response = if (useProxy) {
			imageProxyInterceptor.interceptPageRequest(finalRequest, okHttp)
		} else {
			okHttp.newCall(finalRequest).await()
		}
		return response
			.ensureSuccess()
			.use { response ->
				var file: File? = null
				try {
					val body = response.body ?: error("Response body is null")
					body.use {
						file = destination.createTempFile(
							ext = MimeTypes.getExtension(body.contentType()?.toMimeType())
						)
						file.sink(append = false).buffer().use { sink ->
							sink.writeAllCancellable(body.source())
						}
					}
				} catch (e: Exception) {
					file?.delete()
					throw e
				}
				checkNotNull(file)
			}
	}

	private fun File.createTempFile(ext: String?): File {
		// Ensure parent directory exists
		if (!exists()) {
			mkdirs()
		}
		return File(
			this,
			buildString {
				append(UUID.randomUUID().toString())
				if (!ext.isNullOrEmpty()) {
					append('.')
					append(ext)
				}
				append(".tmp")
			},
		)
	}

	/**
	 * 下载EPUB章节
	 * 
	 * EPUB本质上是ZIP格式，保存为.epub文件以符合标准
	 * 
	 * 特殊处理：
	 * - 对于LocalContentDirOutput：使用addEpubChapter直接保存EPUB
	 * - 对于LocalContentZipOutput：会导致ZIP嵌套（暂不支持）
	 * 
	 * Requirements: 1.1, 1.2, 1.3, 1.4
	 * - 1.1: Save with .epub extension
	 * - 1.2: Preserve EPUB format without converting to CBZ
	 * - 1.3: Store in dedicated EPUB directory
	 * - 1.4: Generate unique filename using parent chapter ID
	 */
	private suspend fun downloadEpubChapter(
		chapter: IndexedValue<ContentChapter>,
		page: ContentPage?,
		epubUrl: String,
		output: LocalContentOutput,
		destination: File,
		repo: ContentRepository,
	) {
		println("DownloadWorker.downloadEpubChapter: Starting EPUB download")
		println("DownloadWorker.downloadEpubChapter: URL = $epubUrl")
		println("DownloadWorker.downloadEpubChapter: Destination = ${destination.absolutePath}")
		
		// 下载EPUB文件到临时位置
		val tempFile = try {
			println("DownloadWorker.downloadEpubChapter: Calling downloadFile...")
			val file = downloadFile(repo, epubUrl, destination, useProxy = true, page = page)
			println("DownloadWorker.downloadEpubChapter: Downloaded to ${file.absolutePath}, size=${file.length()} bytes")
			file
		} catch (e: Exception) {
			println("DownloadWorker.downloadEpubChapter: Download failed - ${e.javaClass.simpleName}: ${e.message}")
			e.printStackTrace()
			throw e
		}
		
		try {
			// 验证文件是否真的是EPUB/ZIP
			if (!isValidEpubFile(tempFile)) {
				val fileHead = readFileHead(tempFile, 200)
				println("DownloadWorker.downloadEpubChapter: ERROR - Downloaded file is not a valid EPUB!")
				println("DownloadWorker.downloadEpubChapter: File content: $fileHead")
				println("DownloadWorker.downloadEpubChapter: URL: $epubUrl")
				println("DownloadWorker.downloadEpubChapter: Source: ${repo.source.name}")
				
				tempFile.deleteAwait()
				
				// Check if it's an HTML login/error page
				val lowerContent = fileHead.lowercase()
				when {
					lowerContent.contains("login") || lowerContent.contains("sign in") || lowerContent.contains("authentication") -> {
						throw IOException("Authentication required. Please log in to ${repo.source.name} again in the app settings.")
					}
					lowerContent.contains("not found") || lowerContent.contains("404") -> {
						throw IOException("Book not found or no longer available on ${repo.source.name}.")
					}
					lowerContent.contains("access denied") || lowerContent.contains("forbidden") -> {
						throw IOException("Access denied. You may not have permission to download this book.")
					}
					lowerContent.contains("<!doctype") || lowerContent.contains("<html") -> {
						throw IOException("Downloaded an HTML page instead of EPUB. This usually means authentication failed or the download link is invalid.")
					}
					else -> {
						throw IOException("Downloaded file is not a valid EPUB format. The file may be corrupted or the download link may be incorrect.")
					}
				}
			}
			
			println("DownloadWorker.downloadEpubChapter: File validation passed - is valid EPUB/ZIP")
			
			// Requirement 1.1 & 1.2: Preserve .epub extension (do NOT convert to .cbz)
			// Requirement 1.4: Generate unique filename using parent chapter ID
			val epubFileName = generateEpubFileName(chapter.value.id)
			val epubFile = if (tempFile.name.endsWith(".epub", ignoreCase = true)) {
				// If already has .epub extension, rename to use our naming pattern
				val newFile = File(tempFile.parentFile, epubFileName)
				if (tempFile.renameTo(newFile)) {
					newFile
				} else {
					newFile.outputStream().use { output ->
						tempFile.inputStream().use { input ->
							input.copyTo(output)
						}
					}
					tempFile.deleteAwait()
					newFile
				}
			} else {
				// Add .epub extension if missing
				val newFile = File(tempFile.parentFile, epubFileName)
				if (tempFile.renameTo(newFile)) {
					newFile
				} else {
					newFile.outputStream().use { output ->
						tempFile.inputStream().use { input ->
							input.copyTo(output)
						}
					}
					tempFile.deleteAwait()
					newFile
				}
			}
			
			println("DownloadWorker.downloadEpubChapter: Renamed to ${epubFile.absolutePath}")
			println("DownloadWorker.downloadEpubChapter: Extension preserved as: ${epubFile.extension}")
			
			// 根据output类型选择处理方式
			when (output) {
				is LocalContentDirOutput -> {
					// MULTIPLE_CBZ格式：保存EPUB并解析章节
					println("DownloadWorker.downloadEpubChapter: Using MULTIPLE_CBZ format - saving as EPUB file")
					
					// Get the DAO for storing chapter mappings (Requirements 5.3)
					val epubChapterMappingDao = mangaDatabase.getEpubChapterMappingDao()
					
					// 保存EPUB文件（保持.epub扩展名）并存储章节映射到数据库
					output.addEpubChapter(chapter, epubFile, epubChapterMappingDao)
					println("DownloadWorker.downloadEpubChapter: Successfully saved EPUB with .epub extension and stored chapter mappings")
				}
				else -> {
					// SINGLE_CBZ格式：不支持EPUB解析，抛出错误提示用户更改下载格式
					println("DownloadWorker.downloadEpubChapter: ERROR - SINGLE_CBZ format does not support EPUB chapters")
					epubFile.deleteAwait()
					throw IOException("EPUB chapters require MULTIPLE_CBZ download format. Please change download format in settings to 'Multiple CBZ files' and try again.")
				}
			}
			
			// 通知本地存储变化
			runCatchingCancellable {
				localStorageChanges.emit(LocalContentParser(output.rootFile).getContent(withDetails = false))
			}.onFailure(Throwable::printStackTraceDebug)
			
			println("DownloadWorker.downloadEpubChapter: Completed successfully")
			
		} catch (e: Exception) {
			println("DownloadWorker.downloadEpubChapter: ERROR - ${e.javaClass.simpleName}: ${e.message}")
			e.printStackTraceDebug()
			// Clean up the file (might be tempFile or epubFile depending on where error occurred)
			tempFile.deleteAwait()
			// Also try to delete epubFile if it was created
			val possibleEpubFile = File(tempFile.parentFile, generateEpubFileName(chapter.value.id))
			if (possibleEpubFile.exists() && possibleEpubFile != tempFile) {
				possibleEpubFile.deleteAwait()
			}
			throw e
		}
	}
	
	/**
	 * Generates a unique EPUB filename using the parent chapter ID.
	 * Pattern: chapter_{chapterId}_{timestamp}.epub
	 * 
	 * Requirement 1.4: Generate unique filenames using parent chapter ID
	 */
	private fun generateEpubFileName(chapterId: Long): String {
		val timestamp = System.currentTimeMillis()
		return "chapter_${chapterId}_${timestamp}.epub"
	}
	
	/**
	 * Download EPUB file to independent epub storage (NEW ARCHITECTURE)
	 * 
	 * This method implements the new EPUB architecture where:
	 * - EPUB files are stored in files/epub/{manga_id}/book.epub
	 * - No parsing or chapter extraction during download
	 * - LocalEpubSource will handle parsing when needed
	 * 
	 * @param manga The manga being downloaded
	 * @param chapter The chapter (EPUB download link)
	 * @param epubUrl The URL to download EPUB from
	 * @param destination Temporary download destination
	 * @param repo The manga repository
	 */
	private suspend fun downloadEpubToStorage(
		manga: Content,
		chapter: IndexedValue<ContentChapter>,
		page: ContentPage?,
		epubUrl: String,
		destination: File,
		repo: ContentRepository,
	) {
		android.util.Log.i("DownloadWorker", "========================================")
		android.util.Log.i("DownloadWorker", "downloadEpubToStorage: Starting NEW ARCHITECTURE EPUB download")
		android.util.Log.i("DownloadWorker", "downloadEpubToStorage: Content ID=${manga.id}")
		android.util.Log.i("DownloadWorker", "downloadEpubToStorage: Content Title=${manga.title}")
		android.util.Log.i("DownloadWorker", "downloadEpubToStorage: Chapter=${chapter.value.title}")
		android.util.Log.i("DownloadWorker", "downloadEpubToStorage: URL=$epubUrl")
		android.util.Log.i("DownloadWorker", "========================================")
		
		// 1. Download EPUB file to temporary location
		// IMPORTANT: useProxy = true to ensure cookies are sent for authentication
		val tempFile = try {
			android.util.Log.d("DownloadWorker", "downloadEpubToStorage: Downloading file with authentication...")
			downloadFile(repo, epubUrl, destination, useProxy = true, page = page)
		} catch (e: Exception) {
			android.util.Log.e("DownloadWorker", "downloadEpubToStorage: Download failed", e)
			throw e
		}
		
		android.util.Log.d("DownloadWorker", "downloadEpubToStorage: Downloaded to ${tempFile.absolutePath}")
		android.util.Log.d("DownloadWorker", "downloadEpubToStorage: File size=${tempFile.length()} bytes")
		
		try {
			// 2. Validate file is actually EPUB/ZIP
			if (!isValidEpubFile(tempFile)) {
				val fileHead = readFileHead(tempFile, 200)
				android.util.Log.e("DownloadWorker", "downloadEpubToStorage: Invalid EPUB file!")
				android.util.Log.e("DownloadWorker", "downloadEpubToStorage: File head: $fileHead")
				tempFile.deleteAwait()
				throw IOException("Downloaded file is not a valid EPUB (possible authentication error or HTML error page)")
			}
			
			android.util.Log.d("DownloadWorker", "downloadEpubToStorage: File validated successfully")
			
			// 3. Save to epub storage using EpubStorageManager
			// 使用chapter ID来区分同一manga的多个EPUB文件
			val savedFile = epubStorageManager.saveEpubFile(manga.id, tempFile, chapter.value.id)
			android.util.Log.i("DownloadWorker", "downloadEpubToStorage: Saved to ${savedFile.absolutePath}, size=${savedFile.length()} bytes")
			
			// 4. Delete temporary file
			tempFile.deleteAwait()
			
			android.util.Log.i("DownloadWorker", "downloadEpubToStorage: Completed successfully")
			android.util.Log.i("DownloadWorker", "========================================")
			
		} catch (e: Exception) {
			android.util.Log.e("DownloadWorker", "downloadEpubToStorage: Error during save", e)
			tempFile.deleteAwait()
			throw e
		}
	}

	private suspend fun downloadVideoImpl(
		manga: Content,
		task: DownloadTask,
		excludedIds: Set<Long>,
	) {
		val chapters = getChapters(manga, task)
		val totalChapters = chapters.size
		var downloaded = 0
		val videoRoot = localStorageManager.getVideoRoot()
		checkNotNull(videoRoot) { applicationContext.getString(R.string.cannot_find_available_storage) }
		val mangaDir = File(videoRoot, manga.title.toFileNameSafe()).apply { mkdirs() }
		val repo = mangaRepositoryFactory.createWithDiagnostics(manga.source).requireAvailableRepository(
			tag = "DownloadWorker",
			prefix = "downloadVideoImpl_repository_unavailable",
		) { "Download source ${manga.source.name} is not available" }
		
		val indexFile = File(mangaDir, "index.json")
		val index = org.skepsun.kototoro.local.data.ContentIndex.read(indexFile) ?: org.skepsun.kototoro.local.data.ContentIndex(null).apply {
			if (!manga.isLocal) {
				setContentInfo(manga)
			}
		}

		for ((iterationIndex, chapter) in chapters.withIndex()) {
			if (chapter.value.id in excludedIds) {
				downloaded += 1
				continue
			}
			publishState(
				currentState.copy(
					isIndeterminate = false,
					totalChapters = totalChapters,
					currentChapter = iterationIndex,
					totalPages = 1,
					currentPage = 0,
					downloadedChapters = downloaded,
				),
			)
			val target = resolveVideoTarget(repo, chapter.value, task) ?: continue
			val fileName = buildVideoFileName(chapter, target.extension)
			val outputFile = File(mangaDir, fileName)
			if (outputFile.exists() && outputFile.length() > 0L) {
				videoDownloadIndex.put(manga.id, chapter.value.id, outputFile.absolutePath)
				downloaded += 1
				continue
			}
			outputFile.parentFile?.mkdirs()
			try {
				val progress: suspend (Int, Int) -> Unit = { cur, total ->
					publishState(
						currentState.copy(
							isIndeterminate = false,
							totalChapters = totalChapters,
							currentChapter = iterationIndex,
							totalPages = total,
							currentPage = cur.coerceAtLeast(0),
							downloadedChapters = downloaded,
						),
					)
				}
				if (target.isHls) {
					downloadHls(repo.source, target.url, target.headers, outputFile, progress)
				} else {
					downloadDirectVideo(repo.source, target.url, target.headers, outputFile, progress)
				}
				
				// Download external tracks (subtitles, audio)
				val baseName = outputFile.nameWithoutExtension
				target.subtitles.forEach { track ->
					val rawExt = track.url.substringBefore('?').substringAfterLast('.', "srt").lowercase()
					val ext = if (rawExt.length <= 5) rawExt else "srt"
					val langSafe = track.lang.toFileNameSafe().ifEmpty { "Unknown" }
					val trackFile = File(mangaDir, "${baseName}_sub_${langSafe}.$ext")
					if (!trackFile.exists() || trackFile.length() == 0L) {
						try {
							downloadTrackFile(repo.source, track, target.headers, trackFile)
						} catch (e: Exception) {
							android.util.Log.w("DownloadWorker", "Failed to download subtitle track: ${track.lang} url=${track.url}", e)
							trackFile.delete()
						}
					}
				}
				target.audios.forEach { track ->
					val rawExt = track.url.substringBefore('?').substringAfterLast('.', "m4a").lowercase()
					val ext = if (rawExt.length <= 5) rawExt else "m4a"
					val langSafe = track.lang.toFileNameSafe().ifEmpty { "Unknown" }
					val trackFile = File(mangaDir, "${baseName}_aud_${langSafe}.$ext")
					if (!trackFile.exists() || trackFile.length() == 0L) {
						try {
							downloadTrackFile(repo.source, track, target.headers, trackFile)
						} catch (e: Exception) {
							android.util.Log.w("DownloadWorker", "Failed to download audio track: ${track.lang} url=${track.url}", e)
							trackFile.delete()
						}
					}
				}
				
				videoDownloadIndex.put(manga.id, chapter.value.id, outputFile.absolutePath)
				index.addChapter(chapter, fileName)
				indexFile.writeText(index.toString())
				scanDownloadedFile(outputFile)
				downloaded += 1
				publishState(currentState.copy(downloadedChapters = downloaded))
			} catch (e: Exception) {
				outputFile.delete()
				throw e
			}
		}
		publishState(currentState.copy(isIndeterminate = true, eta = -1L, isStuck = false))
	}

	private suspend fun resolveVideoTarget(
		repo: ContentRepository,
		chapter: ContentChapter,
		task: DownloadTask,
	): VideoDownloadTarget? {
		val candidates = repo.resolveVideoCandidates(chapter)
		if (candidates.isNotEmpty()) {
			var selected = selectVideoCandidate(candidates, task.preferredQuality)
			if (selected == null) {
				val globalPrefs = settings.preferredVideoQuality.split(',').map { it.trim() }.filter { it.isNotEmpty() }
				for (pref in globalPrefs) {
					selected = selectVideoCandidate(candidates, pref)
					if (selected != null) break
				}
			}
			selected = selected ?: candidates.firstOrNull() ?: return null
			return VideoDownloadTarget(
				url = selected.url,
				headers = selected.headers,
				subtitles = selected.subtitleTracks,
				audios = selected.audioTracks,
			)
		}
		val pages = repo.getPages(chapter, nextChapterUrl = null)
		val page = pages.firstOrNull() ?: return null
		val url = repo.getPageUrl(page)
		return VideoDownloadTarget(
			url = url,
			headers = page.headers,
		)
	}

	private fun buildVideoFileName(chapter: IndexedValue<ContentChapter>, ext: String): String {
		val title = chapter.value.title.ifNullOrEmpty {
			val num = chapter.value.numberString() ?: (chapter.index + 1).toString()
			"Episode $num"
		}
		val safeTitle = title.toFileNameSafe()
		return "$safeTitle.$ext"
	}

	private suspend fun downloadDirectVideo(
		source: ContentSource,
		url: String,
		headers: Map<String, String>?,
		outputFile: File,
		onProgress: suspend (Int, Int) -> Unit,
	) {
		val request = PageLoader.createPageRequest(url, source, headers)
			.newBuilder()
			.build()
		val response = okHttp.newCall(request).await().ensureSuccess()
		response.use { resp ->
			val body = resp.body ?: error("Response body is null")
			val totalBytes = body.contentLength().takeIf { it > 0 } ?: -1L
			body.use {
				outputFile.sink(append = false).buffer().use { sink ->
					val sourceStream = body.source()
					val buffer = okio.Buffer()
					var written = 0L
					var lastNotify = 0L
					while (true) {
						val read = sourceStream.read(buffer, 64 * 1024)
						if (read == -1L) break
						sink.write(buffer, read)
						written += read
						if (totalBytes > 0) {
							if (written - lastNotify >= 256 * 1024) {
								lastNotify = written
								val percent = ((written * 100) / totalBytes).toInt().coerceIn(0, 100)
								onProgress(percent, 100)
							}
						}
					}
					if (totalBytes > 0) {
						onProgress(100, 100)
					}
				}
			}
		}
	}

	/**
	 * Downloads a subtitle/audio track file, handling both HTTP URLs and local file:// URIs.
	 *
	 * Some Aniyomi extractors (e.g. RapidCloudExtractor via PlaylistUtils.fixSubtitles())
	 * convert subtitle URLs to temporary local file:// URIs. These cannot be downloaded
	 * via OkHttp, so we copy from the local file instead.
	 */
	private suspend fun downloadTrackFile(
		source: ContentSource,
		track: eu.kanade.tachiyomi.animesource.model.Track,
		headers: Map<String, String>?,
		outputFile: File,
	) {
		val url = track.url
		if (url.startsWith("file://") || url.startsWith("content://")) {
			val uri = android.net.Uri.parse(url)
			if (url.startsWith("file://")) {
				val sourceFile = uri.path?.let { File(it) }
				if (sourceFile != null && sourceFile.exists() && sourceFile.length() > 0) {
					sourceFile.inputStream().use { input ->
						outputFile.outputStream().use { output ->
							input.copyTo(output)
						}
					}
					android.util.Log.d("DownloadWorker", "Copied local track file: ${track.lang} ${sourceFile.length()} bytes")
					return
				}
			} else {
				val resolver = applicationContext.contentResolver
				resolver.openInputStream(uri)?.use { input ->
					outputFile.outputStream().use { output ->
						input.copyTo(output)
					}
					android.util.Log.d("DownloadWorker", "Copied content:// track: ${track.lang}")
					return
				}
			}
			android.util.Log.w("DownloadWorker", "Local track file not found or empty: $url")
		}
		// Normal HTTP URL — download via network
		downloadDirectVideo(source, url, headers, outputFile) { _, _ -> }
	}

	private suspend fun downloadHls(
		source: ContentSource,
		url: String,
		headers: Map<String, String>?,
		outputFile: File,
		onProgress: suspend (Int, Int) -> Unit,
	) {
		val masterText = fetchText(source, url, headers)
		val mediaUrl = resolveHlsMediaPlaylist(url, masterText)
		val mediaText = fetchText(source, mediaUrl, headers)
		val lines = mediaText.lineSequence().map { it.trim() }.toList()
		val mediaSequence = parseHlsMediaSequence(lines)
		val segments = parseHlsSegments(mediaUrl, lines, mediaSequence)
		android.util.Log.d(
			"DownloadWorker",
			"HLS parsed: mediaUrl=$mediaUrl segments=${segments.size} keys=${
				segments.mapNotNull { it.key?.method }.distinct().joinToString()
			}",
		)
		segments.firstOrNull()?.let {
			android.util.Log.d("DownloadWorker", "HLS first segment: url=${it.url} seq=${it.sequence}")
		}
		android.util.Log.i("DownloadWorker", "HLS output file: ${outputFile.absolutePath}")
		val keyCache = HashMap<String, ByteArray>()
		var writtenTotal = 0L
		outputFile.sink(append = false).buffer().use { sink ->
			val total = segments.size.coerceAtLeast(1)
			segments.forEachIndexed { index, segment ->
				val req = PageLoader.createPageRequest(segment.url, source, headers)
					.newBuilder()
					.apply { segment.range?.let { header("Range", it) } }
					.build()
				val response = okHttp.newCall(req).await().ensureSuccess()
				response.use { resp ->
					val body = resp.body ?: error("Response body is null")
					body.use {
						val bytes = body.bytes()
						val decrypted = decryptIfNeeded(
							source = source,
							baseUrl = mediaUrl,
							key = segment.key,
							headers = headers,
							keyCache = keyCache,
							sequence = segment.sequence,
							data = bytes,
						)
						sink.write(decrypted)
						writtenTotal += decrypted.size.toLong()
						if (index < 3 || index == total - 1) {
							android.util.Log.d(
								"DownloadWorker",
								"HLS seg[$index/$total] bytes=${bytes.size} decrypted=${decrypted.size} out=${outputFile.length()}",
							)
						}
						if (index % 5 == 0) {
							sink.flush()
						}
						if (index % 25 == 0) {
							android.util.Log.d(
								"DownloadWorker",
								"HLS progress[$index/$total] written=$writtenTotal out=${outputFile.length()}",
							)
						}
					}
				}
				onProgress(index + 1, total)
			}
		}
		android.util.Log.i(
			"DownloadWorker",
			"HLS complete: written=$writtenTotal out=${outputFile.length()} segments=${segments.size}",
		)
	}

	private suspend fun fetchText(source: ContentSource, url: String, headers: Map<String, String>?): String {
		val request = PageLoader.createPageRequest(url, source, headers)
			.newBuilder()
			.build()
		val response = okHttp.newCall(request).await().ensureSuccess()
		return response.use { resp ->
			resp.body?.string().orEmpty()
		}
	}

	private fun resolveHlsMediaPlaylist(baseUrl: String, masterText: String): String {
		if (!masterText.contains("#EXT-X-STREAM-INF")) {
			return baseUrl
		}
		val lines = masterText.lineSequence().map { it.trim() }.toList()
		var bestUrl: String? = null
		var bestBandwidth = -1
		for (i in lines.indices) {
			val line = lines[i]
			if (line.startsWith("#EXT-X-STREAM-INF")) {
				val bandwidth = line.substringAfter("BANDWIDTH=", "")
					.substringBefore(",")
					.toIntOrNull() ?: 0
				val next = lines.getOrNull(i + 1).orEmpty()
				if (next.isNotBlank() && !next.startsWith("#")) {
					if (bandwidth >= bestBandwidth) {
						bestBandwidth = bandwidth
						bestUrl = next
					}
				}
			}
		}
		val resolved = bestUrl ?: return baseUrl
		return resolveUrl(baseUrl, resolved)
	}

	private fun parseHlsSegments(baseUrl: String, lines: List<String>, mediaSequence: Int): List<HlsSegment> {
		val result = ArrayList<HlsSegment>()
		var pendingRange: String? = null
		var lastUri: String? = null
		var seq = mediaSequence
		var currentKey: HlsKey? = null
		var previousRangeEnd = 0L
		lines.forEach { line ->
			when {
				line.startsWith("#EXT-X-KEY") -> {
					currentKey = parseHlsKey(baseUrl, line)
				}
				line.startsWith("#EXT-X-MAP") -> {
					val uri = parseHlsAttribute(line, "URI") ?: return@forEach
					val rangeAttr = parseHlsAttribute(line, "BYTERANGE")
					val resolved = resolveUrl(baseUrl, uri)
					var rangeHeader: String? = null
					if (rangeAttr != null) {
						val (header, _) = rangeAttr.toRangeHeader(0L)
						rangeHeader = header
					}
					result.add(HlsSegment(resolved, rangeHeader, seq, currentKey))
					lastUri = resolved
				}
				line.startsWith("#EXT-X-BYTERANGE") -> {
					// 如果连续出现 BYTERANGE，表示复用上一个 URI
					if (pendingRange != null && lastUri != null) {
						result.add(HlsSegment(lastUri!!, pendingRange, seq, currentKey))
						seq += 1
					}
					val (header, newEnd) = line.substringAfter(":").trim().toRangeHeader(previousRangeEnd)
					pendingRange = header
					previousRangeEnd = newEnd
				}
				line.isNotEmpty() && !line.startsWith("#") -> {
					val resolved = resolveUrl(baseUrl, line)
					result.add(HlsSegment(resolved, pendingRange, seq, currentKey))
					lastUri = resolved
					pendingRange = null
					seq += 1
				}
			}
		}
		// 如果最后一个 BYTERANGE 没有 URI，复用上一个 URI
		if (pendingRange != null && lastUri != null) {
			result.add(HlsSegment(lastUri!!, pendingRange, seq, currentKey))
		}
		return result
	}

	private fun parseHlsMediaSequence(lines: List<String>): Int {
		val line = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE") } ?: return 0
		return line.substringAfter(":").trim().toIntOrNull() ?: 0
	}

	private fun parseHlsKey(baseUrl: String, line: String): HlsKey? {
		if (!line.startsWith("#EXT-X-KEY")) return null
		val method = parseHlsAttribute(line, "METHOD") ?: return null
		if (method == "NONE") return null
		val uri = parseHlsAttribute(line, "URI") ?: return null
		val ivRaw = parseHlsAttribute(line, "IV")
		val iv = ivRaw?.let { parseHexIv(it) }
		return HlsKey(method = method, uri = resolveUrl(baseUrl, uri), iv = iv)
	}

	private suspend fun fetchHlsKey(
		source: ContentSource,
		baseUrl: String,
		key: HlsKey,
		headers: Map<String, String>?,
	): ByteArray {
		val keyUrl = resolveUrl(baseUrl, key.uri)
		val request = PageLoader.createPageRequest(keyUrl, source, headers)
			.newBuilder()
			.build()
		val response = okHttp.newCall(request).await().ensureSuccess()
		return response.use { resp ->
			resp.body?.bytes() ?: error("Key response body is null")
		}
	}

	private fun parseHexIv(raw: String): ByteArray {
		val hex = raw.removePrefix("0x").removePrefix("0X")
		val padded = hex.padStart(32, '0')
		val bytes = ByteArray(16)
		for (i in 0 until 16) {
			val idx = i * 2
			bytes[i] = padded.substring(idx, idx + 2).toInt(16).toByte()
		}
		return bytes
	}

	private fun buildHlsIv(sequence: Int): ByteArray {
		val bytes = ByteArray(16)
		val value = sequence.toLong()
		for (i in 0 until 8) {
			bytes[15 - i] = ((value shr (i * 8)) and 0xFF).toByte()
		}
		return bytes
	}

	private fun decryptHlsSegment(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
		val mode = if (data.size % 16 == 0) "AES/CBC/NoPadding" else "AES/CBC/PKCS5Padding"
		val cipher = Cipher.getInstance(mode)
		val keySpec = SecretKeySpec(key, "AES")
		val ivSpec = IvParameterSpec(iv)
		cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
		return cipher.doFinal(data)
	}

	private suspend fun decryptIfNeeded(
		source: ContentSource,
		baseUrl: String,
		key: HlsKey?,
		headers: Map<String, String>?,
		keyCache: MutableMap<String, ByteArray>,
		sequence: Int,
		data: ByteArray,
	): ByteArray {
		if (key == null || key.method != "AES-128") return data
		val keyBytes = keyCache.getOrPut(key.uri) { fetchHlsKey(source, baseUrl, key, headers) }
		val iv = key.iv ?: buildHlsIv(sequence)
		return decryptHlsSegment(data, keyBytes, iv)
	}

	private fun parseHlsAttribute(line: String, key: String): String? {
		val token = "$key="
		val index = line.indexOf(token)
		if (index < 0) return null
		val raw = line.substring(index + token.length)
		return raw.trim().trim('"').substringBefore(',').trim('"')
	}

	private fun String.toRangeHeader(previousEnd: Long = 0L): Pair<String, Long> {
		val value = trim().trim('"')
		val size = value.substringBefore("@").toLongOrNull() ?: return "bytes=0-" to previousEnd
		val offsetStr = value.substringAfter("@", "")
		val offset = if (offsetStr.isNotEmpty()) {
			offsetStr.toLongOrNull() ?: previousEnd
		} else {
			previousEnd
		}
		return "bytes=$offset-${offset + size - 1}" to (offset + size)
	}

	private fun resolveUrl(baseUrl: String, relative: String): String {
		val base = baseUrl.toHttpUrlOrNull() ?: return relative
		return base.resolve(relative)?.toString() ?: relative
	}

	private data class HlsSegment(
		val url: String,
		val range: String? = null,
		val sequence: Int = 0,
		val key: HlsKey? = null,
	)

	private data class HlsKey(
		val method: String,
		val uri: String,
		val iv: ByteArray?,
	)

	private data class VideoDownloadTarget(
		val url: String,
		val headers: Map<String, String>?,
		val subtitles: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList(),
		val audios: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList(),
	) {
		val isHls: Boolean = url.contains(".m3u8", ignoreCase = true)
		val extension: String = if (isHls) "ts" else guessExt(url)

		private fun guessExt(u: String): String {
			val ext = u.substringAfterLast('.', "").lowercase()
			return if (ext.isNotBlank() && ext.length <= 5) ext else "mp4"
		}
	}

	private fun selectVideoCandidate(
		candidates: List<org.skepsun.kototoro.video.domain.VideoCandidate>,
		preferredQuality: String?,
	): org.skepsun.kototoro.video.domain.VideoCandidate? {
		val preferred = preferredQuality?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		return candidates.firstOrNull { candidate ->
			candidate.title.contains(preferred, ignoreCase = true) ||
				candidate.resolution?.let { "${it}p".contains(preferred, ignoreCase = true) } == true
		}
	}

	private suspend fun publishState(state: DownloadState) {
		val previousState = currentState
		lastPublishedState = state
		if (previousState.isParticularProgress && state.isParticularProgress) {
			etaEstimator.onProgressChanged(state.progress, state.max)
		} else {
			etaEstimator.reset()
			notificationThrottler.reset()
		}
		val notification = notificationFactory.create(state)
		if (state.isFinalState) {
			if (!notificationFactory.isSilent) {
				notificationManager.notify(id.toString(), id.hashCode(), notification)
			}
		} else if (notificationThrottler.throttle()) {
			notificationManager.notify(id.hashCode(), notification)
		} else {
			return
		}
		setProgress(state.toWorkData())
	}

	private suspend fun publishExecutionDetailsState(executionDetails: Content) {
		val state = currentState
		if (state.manga == executionDetails) {
			return
		}
		publishState(
			state.copy(
				manga = executionDetails,
			),
		)
	}

	private fun scanDownloadedFile(file: File) {
		runCatching {
			MediaScannerConnection.scanFile(
				applicationContext,
				arrayOf(file.absolutePath),
				null,
				null,
			)
		}.onFailure { e ->
			Log.w("DownloadWorker", "scanDownloadedFile failed: ${file.absolutePath}", e)
		}
	}

	private suspend fun getDoneChapters(manga: Content) = runCatchingCancellable {
		val start = System.currentTimeMillis()
		val contentType = manga.source.getContentType()
		if (contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO) {
			val ids = videoDownloadIndex.getDownloadedChapterIds(manga.id)
			Log.i("DownloadWorker", "getDoneChapters(video): mangaId=${manga.id} count=${ids.size}")
			return@runCatchingCancellable ids
		}
		val result = withTimeoutOrNull(3000L) {
			localContentRepository.getDetails(manga).chapters
				?.filter { it.source.isLocal }
				?.ids()
		}
		if (result == null) {
			Log.w(
				"DownloadWorker",
				"getDoneChapters timeout: mangaId=${manga.id} title=${manga.title}",
			)
			emptySet()
		} else {
			Log.i(
				"DownloadWorker",
				"getDoneChapters success: mangaId=${manga.id} took=${System.currentTimeMillis() - start}ms count=${result.size}",
			)
			result
		}
	}.onFailure { e ->
		Log.w(
			"DownloadWorker",
			"getDoneChapters failed: mangaId=${manga.id} title=${manga.title} error=${e.javaClass.simpleName} msg=${e.message}",
			e,
		)
	}.getOrNull().orEmpty()

	private fun getChapters(
		manga: Content,
		task: DownloadTask,
	): List<IndexedValue<ContentChapter>> {
		val chapters = checkNotNull(manga.chapters) { "Chapters list must not be null" }
		val requestedChapterIds = task.executionChapterIds
		val chaptersIdsSet = requestedChapterIds?.toMutableSet()
		val result = ArrayList<IndexedValue<ContentChapter>>((chaptersIdsSet ?: chapters).size)
		val counters = HashMap<String?, Int>()
		for (chapter in chapters) {
			val index = counters[chapter.branch] ?: 0
			counters[chapter.branch] = index + 1
			if (chaptersIdsSet != null && !chaptersIdsSet.remove(chapter.id)) {
				continue
			}
			result.add(IndexedValue(index, chapter))
		}
		if (chaptersIdsSet != null) {
			resolveMissingExecutionChapters(
				chapters = chapters,
				requestedChapterIds = requestedChapterIds ?: LongArray(0),
				requestedChapterRefs = task.executionChapterRefs.orEmpty(),
				missingChapterIds = chaptersIdsSet,
				result = result,
				counters = counters,
			)
			check(chaptersIdsSet.isEmpty()) {
				"${chaptersIdsSet.size} of ${task.executionChapterIds?.size ?: 0} requested chapters not found in manga"
			}
		}
		check(result.isNotEmpty()) { "Chapters list must not be empty" }
		return result.sortedWith(compareBy<IndexedValue<ContentChapter>> { it.index }.thenBy { it.value.number }.thenBy { it.value.id })
	}

	private fun resolveMissingExecutionChapters(
		chapters: List<ContentChapter>,
		requestedChapterIds: LongArray,
		requestedChapterRefs: List<ExecutionChapterRef>,
		missingChapterIds: MutableSet<Long>,
		result: MutableList<IndexedValue<ContentChapter>>,
		counters: MutableMap<String?, Int>,
	) {
		if (missingChapterIds.isEmpty()) {
			return
		}
		val usedChapterIds = result.mapTo(mutableSetOf()) { it.value.id }
		val requestedChapterRefsById = requestedChapterRefs.associateBy { it.id }
		for (requestedChapterId in requestedChapterIds) {
			if (!missingChapterIds.contains(requestedChapterId)) {
				continue
			}
			val requestedChapter = requestedChapterRefsById[requestedChapterId] ?: continue
			val matchedChapter = chapters.firstOrNull { candidate ->
				candidate.id !in usedChapterIds && chapterExecutionIdentityMatches(requestedChapter, candidate)
			} ?: continue
			val branchIndex = counters.getOrPut(matchedChapter.branch) { 0 }
			counters[matchedChapter.branch] = branchIndex + 1
			result.add(IndexedValue(branchIndex, matchedChapter))
			usedChapterIds += matchedChapter.id
			missingChapterIds.remove(requestedChapterId)
			Log.w(
				"DownloadWorker",
				"getChapters: remapped executionChapterId=$requestedChapterId to chapterId=${matchedChapter.id} " +
					"title=${matchedChapter.title} branch=${matchedChapter.branch}",
			)
		}
	}

	private fun chapterExecutionIdentityMatches(
		requested: ExecutionChapterRef,
		candidate: ContentChapter,
	): Boolean {
		if (requested.branch != candidate.branch) {
			return false
		}
		if (requested.url.isNotBlank() && requested.url == candidate.url) {
			return true
		}
		val sameTitle = requested.title?.takeIf { it.isNotBlank() } == candidate.title?.takeIf { it.isNotBlank() }
		if (sameTitle && requested.number > 0f && candidate.number > 0f && requested.number == candidate.number) {
			return true
		}
		if (sameTitle && requested.volume > 0 && candidate.volume > 0 && requested.volume == candidate.volume) {
			return true
		}
		return requested.number > 0f &&
			candidate.number > 0f &&
			requested.number == candidate.number &&
			requested.volume == candidate.volume
	}

	@Reusable
	class Scheduler @Inject constructor(
		@ApplicationContext private val context: Context,
		private val mangaDataRepository: ContentDataRepository,
		private val workManager: WorkManager,
	) {

		fun observeWorks(): Flow<List<WorkInfo>> = workManager
			.getWorkInfosByTagFlow(TAG)

		@SuppressLint("RestrictedApi")
		suspend fun getInputData(id: UUID): Data? {
			val spec = workManager.getWorkSpec(id) ?: return null
			return Data.Builder()
				.putAll(spec.input)
				.putLong(DownloadState.DATA_TIMESTAMP, spec.scheduleRequestedAt)
				.build()
		}

		suspend fun getTask(workId: UUID): DownloadTask? {
			return workManager.getWorkInputData(workId)?.let { DownloadTask(it) }
		}

		suspend fun cancel(id: UUID) {
			workManager.cancelWorkById(id).await()
		}

		suspend fun cancelAll() {
			workManager.cancelAllWorkByTag(TAG).await()
		}

		fun pause(id: UUID) = context.sendBroadcast(
			PausingReceiver.getPauseIntent(context, id),
		)

		fun resume(id: UUID) = context.sendBroadcast(
			PausingReceiver.getResumeIntent(context, id),
		)

		fun skip(id: UUID) = context.sendBroadcast(
			PausingReceiver.getSkipIntent(context, id),
		)

		fun skipAll(id: UUID) = context.sendBroadcast(
			PausingReceiver.getSkipAllIntent(context, id),
		)

		suspend fun delete(id: UUID) {
			workManager.cancelWorkById(id).await()
			workManager.deleteWorks(listOf(id))
		}

		suspend fun delete(ids: Collection<UUID>) {
			val wm = workManager
			ids.forEach { id -> wm.cancelWorkById(id).await() }
			workManager.deleteWorks(ids)
		}

		suspend fun removeCompleted() {
			val finishedWorks = workManager.awaitFinishedWorkInfosByTag(TAG)
			workManager.deleteWorks(finishedWorks.mapToSet { it.id })
		}

		suspend fun updateConstraints(allowMeteredNetwork: Boolean) {
			val constraints = createConstraints(allowMeteredNetwork)
			val works = workManager.awaitWorkInfosByTag(TAG)
			for (work in works) {
				if (work.state.isFinished) {
					continue
				}
				val request = OneTimeWorkRequestBuilder<DownloadWorker>()
					.setConstraints(constraints)
					.addTag(TAG)
					.setId(work.id)
					.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
					.build()
				workManager.awaitUpdateWork(request)
			}
		}

		suspend fun schedule(tasks: Collection<Pair<Content, DownloadTask>>) {
			if (tasks.isEmpty()) {
				return
			}
			val requests = tasks.map { (manga, task) ->
				val storedManga = mangaDataRepository.storeContentAndReturn(manga, replaceExisting = true)
				val currentManga = mangaDataRepository.findContentById(storedManga.id, withChapters = true) ?: storedManga
				val displayManga = if (task.displayMangaId != null && task.displayMangaId != task.executionMangaId) {
					mangaDataRepository.findDisplayContentById(task.displayMangaId, withChapters = false)
				} else {
					mangaDataRepository.findDisplayContentById(currentManga.id, withChapters = false)
				}
				val storedDisplayManga = displayManga
					?.takeIf { it.id != currentManga.id }
					?.let { representativeManga ->
						mangaDataRepository.storeContentAndReturn(representativeManga, replaceExisting = false)
					}
				val displayMangaId = storedDisplayManga?.id ?: displayManga?.id ?: currentManga.id
				val normalizedTask = DownloadTask.createExecutionTask(
					executionMangaId = currentManga.id,
					displayMangaId = displayMangaId,
					isPaused = task.isPaused,
					isSilent = task.isSilent,
					executionChapterIds = task.executionChapterIds,
					executionChapterRefs = task.executionChapterRefs,
					destination = task.destination,
					format = task.format,
					allowMeteredNetwork = task.allowMeteredNetwork,
					preferredQuality = task.preferredQuality,
					kind = task.kind,
				)
				OneTimeWorkRequestBuilder<DownloadWorker>()
					.setConstraints(createConstraints(task.allowMeteredNetwork))
					.addTag(TAG)
					.keepResultsForAtLeast(30, TimeUnit.DAYS)
					.setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
					.setInputData(normalizedTask.toData())
					.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
					.build()
			}
			workManager.enqueue(requests).await()
		}

		private fun createConstraints(allowMeteredNetwork: Boolean) = Constraints.Builder()
			.setRequiredNetworkType(if (allowMeteredNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED)
			.build()
	}

	/**
	 * 验证文件是否为有效的EPUB/ZIP文件
	 * EPUB文件本质上是ZIP格式，magic bytes应该是 PK (0x50 0x4B)
	 */
	private fun isValidEpubFile(file: File): Boolean {
		if (!file.exists() || file.length() < 4) {
			return false
		}
		
		return try {
			file.inputStream().use { input ->
				val header = ByteArray(4)
				val read = input.read(header)
				if (read < 2) return false
				
				// ZIP/EPUB magic bytes: PK\x03\x04 (0x50 0x4B 0x03 0x04)
				header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
			}
		} catch (e: Exception) {
			false
		}
	}

	/**
	 * 读取文件头部用于调试
	 */
	private fun readFileHead(file: File, maxBytes: Int): String {
		if (!file.exists()) return "[File does not exist]"
		
		return try {
			file.inputStream().use { input ->
				val bytes = ByteArray(minOf(maxBytes, file.length().toInt()))
				input.read(bytes)
				
				// 尝试作为文本读取（如果是HTML错误页）
				val text = String(bytes, Charsets.UTF_8)
				if (text.contains("<!DOCTYPE", ignoreCase = true) || 
				    text.contains("<html", ignoreCase = true)) {
					"[HTML detected] $text"
				} else {
					// 显示hex dump
					bytes.joinToString(" ") { "%02X".format(it) }
				}
			}
		} catch (e: Exception) {
			"[Error reading file: ${e.message}]"
		}
	}

	private companion object {

		const val MAX_RETRY_DELAY = 7_200_000L // 2 hours
		const val TAG = "download"
		private const val PAGE_NAME_PATTERN = "%08d_%04d%04d"
	}

	@AssistedFactory
	interface Factory : WorkerAssistedFactory<DownloadWorker>
}
