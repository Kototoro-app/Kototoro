package org.skepsun.kototoro.download.ui.worker

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
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
import org.skepsun.kototoro.BuildConfig
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
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.local.data.isPdfPageUri
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
import org.skepsun.kototoro.download.domain.DownloadByteProgress
import org.skepsun.kototoro.download.domain.DownloadState
import org.skepsun.kototoro.download.domain.FailedChapterImage
import org.skepsun.kototoro.download.domain.NovelFailedImageStore
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiXSourceAdapter
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
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.EbookFormat
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.requireBody
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.util.ext.asLegacyEbookFormat
import org.skepsun.kototoro.core.util.ext.isEbookChapter
import org.skepsun.kototoro.core.util.ext.isPageEbookChapter
import org.skepsun.kototoro.core.util.ext.isTextEbookChapter
import org.skepsun.kototoro.core.util.ext.primaryEbookFormat
import org.jsoup.Jsoup
import com.hippo.unifile.UniFile
import java.io.File
import java.net.URLDecoder
import java.util.UUID
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
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
    private val queuePreferences: DownloadQueuePreferences,
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
        get() = checkNotNull(lastPublishedState) {
            "Download state was never published for work $id (an error was thrown before the first publishState)"
        }

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

        queuePreferences.registerActive(id, task.isPaused)

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
                    DownloadTaskKind.TRANSLATE,
                    DownloadTaskKind.PREPARE_SUPER_RESOLUTION -> {
                        Log.i(
                            "DownloadWorker",
                            "doWork before prepareContentImpl: workId=$id mangaId=${executionContext.executionManga.id} kind=${task.kind}",
                        )
                        if (task.kind == DownloadTaskKind.PREPARE_SUPER_RESOLUTION) {
                            // Super resolution can be requested for chapters that are not on disk yet.
                            ensureChapterSourcesDownloaded(executionContext.executionManga, task)
                        }
                        prepareContentImpl(executionContext.executionManga, task)
                        Log.i(
                            "DownloadWorker",
                            "doWork after prepareContentImpl: workId=$id mangaId=${executionContext.executionManga.id} kind=${task.kind}",
                        )
                    }
                }
            }
            queuePreferences.remove(id)
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
            if (DownloadPolicy.shouldRetry(e) && runAttemptCount < DownloadPolicy.MAX_WORK_RETRIES) {
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
            queuePreferences.resetAcquired(id)
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
            // 视频下载已从本构建移除；历史队列里的视频任务直接按不可处理结束。
            return
        }
        Log.d("DownloadWorker", "downloadContentImpl start: mangaId=${subject.id} title=${subject.title} excluded=${excludedIds.size}")
        val chaptersToSkip = excludedIds.toMutableSet()
        mangaLock.withLock(subject) {
            var rootCandidates = localContentRepository.getOutputDirCandidates(subject, task.destination)
            val primaryRoot = checkNotNull(rootCandidates.firstOrNull()) {
                applicationContext.getString(R.string.cannot_find_available_storage)
            }
            val destination = File(applicationContext.cacheDir, "downloads").apply { mkdirs() }
            Log.d("DownloadWorker", "downloadContentImpl outputDir=${primaryRoot.displayPath}")
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
                // 小说与电子书下载已从本构建移除：一律走标准漫画章节流程。
                val downloadFormat = task.format ?: settings.preferredDownloadFormat
                output = createOutputWithFallback(
                    candidates = rootCandidates,
                    manga = executionDetails,
                    format = downloadFormat,
                    cacheDir = applicationContext.cacheDir,
                )
                val coverUrl = executionDetails.largeCoverUrl.ifNullOrEmpty { executionDetails.coverUrl }
                if (!coverUrl.isNullOrEmpty()) {
                    downloadFile(repo, coverUrl, destination, isCover = true).let { file ->
                        output.addCover(file, getMediaType(coverUrl, file))
                        file.deleteAwait()
                    }
                }
                processStandardChapters(executionDetails, task, repo, destination, chaptersToSkip, output)
                publishState(currentState.copy(isIndeterminate = true, eta = -1L, isStuck = false))
                output.mergeWithExisting()
                output.finish()
                val localContent = LocalContentParser(output.rootFile, applicationContext.cacheDir).getContent(withDetails = true)
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

    /**
     * Downloads the requested chapters that are not on disk yet, so translating or preparing super resolution
     * works as "download if needed, then process".
     */
    private suspend fun ensureChapterSourcesDownloaded(
        subject: Content,
        task: DownloadTask,
    ) {
        val requestedChapterIds = task.executionChapterIds?.toSet().orEmpty()
        if (requestedChapterIds.isEmpty()) return
        val downloadedChapterIds = getDoneChapters(subject)
        val missingChapterIds = requestedChapterIds - downloadedChapterIds
        if (missingChapterIds.isEmpty()) return
        Log.i(
            "DownloadWorker",
            "ensureChapterSourcesDownloaded downloading ${missingChapterIds.size} missing chapter(s) " +
                "mangaId=${subject.id} kind=${task.kind}",
        )
        val resolvedContent = resolveExecutionContent(subject)
        val storedExecutionDetails = mangaDataRepository.storeContentAndReturn(
            resolvedContent.executionDetails,
            replaceExisting = true,
        )
        downloadContentImpl(
            subject = resolvedContent.executionManga,
            resolvedContent = resolvedContent.copy(executionDetails = storedExecutionDetails),
            task = task,
            excludedIds = downloadedChapterIds,
        )
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
                    // 小说功能已从本构建移除。
                    error("Novel content is not supported in this build")
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
        val chapters = resolvePreparationChapters(manga, task)
        check(chapters.any { isPreparationChapterEligible(it.value) }) {
            "No downloaded chapter to prepare for ${manga.title}"
        }
        for ((chapterIndex, chapter) in chapters.withIndex()) {
            checkIsPaused()
            if (!isPreparationChapterEligible(chapter.value)) {
                // Its download failed or was skipped; the other chapters are still worth preparing.
                Log.w("DownloadWorker", "prepareMangaContent skips chapter without local copy id=${chapter.value.id}")
                continue
            }
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
                // 画面超分与翻译准备均已从本构建移除，准备任务无事可做。
                when (task.kind) {
                    DownloadTaskKind.PREPARE_TRANSLATION,
                    DownloadTaskKind.PREPARE_SUPER_RESOLUTION,
                    DownloadTaskKind.DOWNLOAD,
                    DownloadTaskKind.TRANSLATE -> Unit
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

    /**
     * The requested chapters, with those downloaded by this task swapped for their local copies: the stored record
     * still lists the source chapter for them, and only the local copy has pages to read. Local chapters keep the
     * source chapter's id, or are matched by number or title the way the details screen merges them.
     */
    private suspend fun resolvePreparationChapters(
        manga: Content,
        task: DownloadTask,
    ): List<IndexedValue<ContentChapter>> {
        val requested = getChapters(manga, task)
        if (requested.all { isPreparationChapterEligible(it.value) }) {
            return requested
        }
        val stored = mangaDataRepository.findContentById(task.executionMangaId, withChapters = true) ?: manga
        val localChapters = runCatchingCancellable { localContentRepository.getDetails(stored) }
            .getOrNull()?.chapters.orEmpty()
        return requested.map { item ->
            val chapter = item.value
            if (isPreparationChapterEligible(chapter)) {
                return@map item
            }
            val local = matchLocalChapter(chapter, localChapters)
            if (local != null) IndexedValue(item.index, local) else item
        }
    }

    /**
     * The local counterpart of [chapter]: the same id first, then the same number and branch, then the same title.
     */
    private fun matchLocalChapter(
        chapter: ContentChapter,
        localChapters: List<ContentChapter>,
    ): ContentChapter? {
        return localChapters.find { it.id == chapter.id }
            ?: localChapters.find { chapter.number > 0f && it.number == chapter.number && it.branch == chapter.branch }
            ?: localChapters.find { !chapter.title.isNullOrBlank() && it.title == chapter.title }
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
            url.startsWith("content+zip:") ||
            url.startsWith("file+pdf:") ||
            url.startsWith("content+pdf:") ||
            url.startsWith("content:") ||
            url.startsWith("epub:") ||
            url.startsWith("localepub:")
    }

    private suspend fun resolvePreparationPageUri(page: ContentPage): Uri? {
        val uri = page.url.toUri()
        return when {
            uri.isFileUri() -> uri
            uri.isZipUri() -> cacheZipPage(uri)
            uri.isContentZipUri() -> cacheContentZipPage(uri)
            uri.isPdfPageUri() -> cachePdfPage(uri)
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

    private suspend fun cacheContentZipPage(uri: Uri): Uri? {
        cache[uri.toString()]?.let { return it.toUri() }
        val entryName = uri.fragment?.takeIf(String::isNotEmpty) ?: return null
        return runCatching {
            org.skepsun.kototoro.core.util.ContentZipArchive.withEntry(
                applicationContext.contentResolver,
                uri,
            ) { stream ->
                BitmapDecoderCompat.decode(
                    stream,
                    MimeTypes.getMimeTypeFromExtension(entryName),
                )
            }.use { image ->
                cache.set(uri.toString(), image).toUri()
            }
        }.onFailure {
            it.printStackTraceDebug()
        }.getOrNull()
    }

    private suspend fun cachePdfPage(uri: Uri): Uri? {
        cache[uri.toString()]?.let { return it.toUri() }
        return runCatching {
            val bitmap = org.skepsun.kototoro.local.data.LocalPdfPages.renderPage(
                contentResolver = applicationContext.contentResolver,
                pageUri = uri,
            )
            try {
                cache.set(uri.toString(), bitmap).toUri()
            } finally {
                bitmap.recycle()
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

    /** 翻译输入就是原图：画面超分已移除，不再预处理。 */
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

            // Do not fetch a page list for a chapter that is already complete. Apart from
            // wasting a request, several sources rate-limit page-list calls more aggressively
            // than image requests, making resume appear unnecessarily slow.
            if (chaptersToSkip.remove(chapter.value.id)) {
                publishState(currentState.copy(downloadedChapters = currentState.downloadedChapters + 1))
                continue
            }

            val fullChapters = mangaDetails.chapters ?: emptyList()
            val currentInFull = fullChapters.indexOfFirst { it.id == chapter.value.id }
            val nextChapterUrl = if (currentInFull != -1) fullChapters.getOrNull(currentInFull + 1)?.url else null
            val pages = runFailsafe {
                repo.getPages(chapter.value, nextChapterUrl)
            } ?: continue
            if (pages.isEmpty()) {
                Log.d("DownloadWorker", "processStandardChapters empty pages: idx=$chapterIndex title=${chapter.value.title}")
            }

            println("DownloadWorker: Chapter ${chapter.index}: ${chapter.value.title}")
            println("DownloadWorker: Pages count: ${pages.size}")
            if (pages.isNotEmpty()) {
                println("DownloadWorker: First page preview: ${pages[0].preview}")
                println("DownloadWorker: First page url: ${pages[0].url}")
            }

            val tempDir = File(destination, "tmp_${chapter.value.id}")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }

            val pageCounter = AtomicInteger(0)
            val successCounter = AtomicInteger(0)
            channelFlow {
                val semaphore = Semaphore(settings.downloadThreads)
                for ((pageIndex, page) in pages.withIndex()) {
                    checkIsPaused()
                    launch {
                        val prefix = String.format("%04d.", pageIndex)
                        val existingFile = tempDir.listFiles { _, name -> name.startsWith(prefix) }?.firstOrNull()
                        val file = semaphore.withPermit {
                            runFailsafe {
                                if (existingFile != null && existingFile.length() > 0) {
                                    existingFile
                                } else {
                                    val url = repo.getPageUrl(page)
                                            val downloadedFile = cache[url]
                                                ?: downloadFile(repo, url, destination, page = page)
                                            val ext = downloadedFile.extension.takeIf { it != "tmp" } ?: "jpg"
                                            val targetFile = File(tempDir, prefix + ext)
                                            val moved = downloadedFile.extension == "tmp" &&
                                                downloadedFile.parentFile == destination &&
                                                downloadedFile.renameTo(targetFile)
                                            if (!moved) {
                                                downloadedFile.copyTo(targetFile, overwrite = true)
                                            }
                                            if (!moved && downloadedFile.extension == "tmp") {
                                                downloadedFile.deleteAwait()
                                            }
                                    targetFile
                                }
                            }
                        }
                        val success = file?.let { downloadedFile ->
                            // Network permits cover URL resolution and response transfer only;
                            // serialized SAF/ZIP writes must not block other page requests.
                            output.addPage(
                                chapter = chapter,
                                file = downloadedFile,
                                pageNumber = pageIndex,
                                type = getMediaType(downloadedFile.name, downloadedFile),
                            )
                            true
                        } ?: false
                        if (success) {
                            successCounter.incrementAndGet()
                            send(pageIndex)
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
                    localStorageChanges.emit(LocalContentParser(output.rootFile, applicationContext.cacheDir).getContent(withDetails = false))
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
        val settingRetryDelay = settings.downloadRetryDelayMs
        var countDown = maxAttempts
        failsafe@ while (true) {
            try {
                return block()
            } catch (e: IOException) {
                val retryDelay = if (e is TooManyRequestExceptions) {
                    DownloadPolicy.retryDelayMs(settingRetryDelay, e.getRetryDelay())
                } else {
                    DownloadPolicy.retryDelayMs(settingRetryDelay, -1L)
                }
                Log.w(
                    "DownloadWorker",
                    "runFailsafe failed: ${e.javaClass.simpleName} msg=${e.message} retryDelay=$retryDelay remaining=$countDown",
                    e,
                )
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
            val limit = DownloadPolicy.activeSeriesLimit(settings.downloadMaxActiveSeries, AppSettings.UNLIMITED_SERIES)
            if (limit == null || ActiveDownloadRegistry.isTurn(id, limit)) {
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

        val requestStartedAt = SystemClock.elapsedRealtime()
        slowdownDispatcher.delay(repo.source)
        val networkStartedAt = SystemClock.elapsedRealtime()
        val imageClient = repo.getImageClient() ?: okHttp
        val response = if (page != null) {
            // Mihon/extension repositories may implement image loading with a custom client,
            // token refresh, or a source-specific interceptor. Reuse that exact path first so
            // downloads have the same semantics as the reader.
            repo.fetchPageResponse(url, page) ?: if (useProxy) {
                imageProxyInterceptor.interceptPageRequest(finalRequest, imageClient)
            } else {
                imageClient.newCall(finalRequest).await()
            }
        } else if (useProxy) {
            imageProxyInterceptor.interceptPageRequest(finalRequest, imageClient)
        } else {
            imageClient.newCall(finalRequest).await()
        }
        return response
            .ensureSuccess()
            .use { response ->
                var file: File? = null
                var bytesWritten = 0L
                try {
                    val body = response.body ?: error("Response body is null")
                    body.use {
                        file = destination.createTempFile(
                            ext = MimeTypes.getExtension(body.contentType()?.toMimeType())
                        )
                        file.sink(append = false).buffer().use { sink ->
                            bytesWritten = sink.writeAllCancellable(body.source())
                        }
                    }
                } catch (e: Exception) {
                    file?.delete()
                    throw e
                }
                checkNotNull(file) { "Temporary file was not created for $url" }
                    .also {
                        val elapsedMs = (SystemClock.elapsedRealtime() - networkStartedAt).coerceAtLeast(1L)
                        Log.d(
                            "DownloadWorker",
                            "image transfer source=${repo.source.name} bytes=$bytesWritten " +
                                "throttleMs=${networkStartedAt - requestStartedAt} networkMs=$elapsedMs " +
                                "rateBytesPerSec=${bytesWritten * 1000L / elapsedMs}",
                        )
                    }
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
    private suspend fun publishState(state: DownloadState) {
        val previousState = currentState
        lastPublishedState = state
        if (previousState.isParticularProgress && state.isParticularProgress) {
            etaEstimator.onProgressChanged(state.progress, state.max)
        } else {
            etaEstimator.reset()
            // Unknown-length videos still publish byte updates; keep those updates throttled.
            if (previousState.byteProgress == null || state.byteProgress == null ||
                previousState.isIndeterminate != state.isIndeterminate || previousState.isPaused != state.isPaused
            ) {
                notificationThrottler.reset()
            }
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

    private fun scanDownloadedFile(output: UniFile) {
        val filePath = output.filePath ?: return
        runCatching {
            MediaScannerConnection.scanFile(
                applicationContext,
                arrayOf(filePath),
                null,
                null,
            )
        }.onFailure { e ->
            Log.w("DownloadWorker", "scanDownloadedFile failed: $filePath", e)
        }
    }

    private suspend fun getDoneChapters(manga: Content) = runCatchingCancellable {
        val start = System.currentTimeMillis()
        val contentType = manga.source.getContentType()
        if (contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO) {
            return@runCatchingCancellable emptySet<Long>()
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
        private val queuePreferences: DownloadQueuePreferences,
    ) {
        private val scheduleMutex = Mutex()

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

        /** Execution and display content ids of every recorded download job, finished ones included. */
        suspend fun getTrackedContentIds(): Set<Long> {
            val ids = HashSet<Long>()
            for (work in workManager.awaitWorkInfosByTag(TAG)) {
                val task = workManager.getWorkInputData(work.id)?.let { DownloadTask(it) } ?: continue
                ids += task.executionMangaId
                task.displayMangaId?.let { ids += it }
            }
            return ids
        }

        suspend fun cancel(id: UUID) {
            workManager.cancelWorkById(id).await()
            queuePreferences.remove(id)
        }

        /**
         * Cancels every download job of this queue.
         *
         * Chapter translation jobs share the WorkManager tag but are a separate user action, so they are
         * skipped here: stopping downloads must not silently drop a translation the user started (and
         * [cancelTranslation] is the only place that stops those).
         */
        suspend fun cancelAll() {
            for (work in workManager.awaitWorkInfosByTag(TAG)) {
                if (work.state.isFinished) continue
                val task = workManager.getWorkInputData(work.id)?.let { DownloadTask(it) } ?: continue
                if (task.kind == DownloadTaskKind.TRANSLATE) continue
                cancel(work.id)
            }
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
            cancel(id)
            workManager.deleteWorks(listOf(id))
        }

        suspend fun delete(ids: Collection<UUID>) {
            val wm = workManager
            ids.forEach { id -> cancel(id) }
            workManager.deleteWorks(ids)
        }

        suspend fun removeCompleted() {
            val finishedWorks = workManager.awaitFinishedWorkInfosByTag(TAG)
            workManager.deleteWorks(finishedWorks.mapToSet { it.id })
            finishedWorks.forEach { queuePreferences.remove(it.id) }
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
                    .keepResultsForAtLeast(30, TimeUnit.DAYS)
                    .setId(work.id)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                workManager.awaitUpdateWork(request)
            }
        }

        /** Chapter ids of translation jobs still running for [mangaId]. */
        suspend fun activeTranslationChapterIds(mangaId: Long): Set<Long> {
            val result = mutableSetOf<Long>()
            for (work in workManager.awaitWorkInfosByTag(TAG)) {
                if (work.state.isFinished) continue
                val task = workManager.getWorkInputData(work.id)?.let { DownloadTask(it) } ?: continue
                if (task.kind != DownloadTaskKind.TRANSLATE) continue
                if (task.executionMangaId != mangaId && task.displayMangaId != mangaId) continue
                result += task.executionChapterIds?.toList().orEmpty()
            }
            return result
        }

        /** Cancels every translation job of [mangaId]; downloads and other job kinds are untouched. */
        suspend fun cancelTranslation(mangaId: Long) {
            for (work in workManager.awaitWorkInfosByTag(TAG)) {
                if (work.state.isFinished) continue
                val task = workManager.getWorkInputData(work.id)?.let { DownloadTask(it) } ?: continue
                if (task.kind != DownloadTaskKind.TRANSLATE) continue
                if (task.executionMangaId != mangaId && task.displayMangaId != mangaId) continue
                cancel(work.id)
            }
        }

        suspend fun schedule(tasks: Collection<Pair<Content, DownloadTask>>) {
            scheduleMutex.withLock {
                scheduleInternal(tasks)
            }
        }

        private suspend fun scheduleInternal(tasks: Collection<Pair<Content, DownloadTask>>) {
            if (tasks.isEmpty()) {
                return
            }
            val activeTasks = buildList {
                for (work in workManager.awaitWorkInfosByTag(TAG)) {
                    if (work.state.isFinished) {
                        continue
                    }
                    workManager.getWorkInputData(work.id)?.let { add(DownloadTask(it)) }
                }
            }
            val pendingTasks = buildList<Pair<Content, DownloadTask>> {
                for (entry in tasks) {
                    val task = entry.second
                    if (activeTasks.any { activeTask -> activeTask.hasSameRequestAs(task) }) {
                        continue
                    }
                    if (any { existing -> existing.second.hasSameRequestAs(task) }) {
                        continue
                    }
                    add(entry)
                }
            }
            if (pendingTasks.isEmpty()) {
                return
            }
            val requests = pendingTasks.map { (manga, task) ->
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
                    includeNovelImages = task.includeNovelImages,
                    allowMeteredNetwork = task.allowMeteredNetwork,
                    preferredQuality = task.preferredQuality,
                    kind = task.kind,
                    isRetranslation = task.isRetranslation,
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
     * 验证文件是否为有效的电子书文件（按格式校验 header）。
     *
     * - EPUB/FB2/…（ZIP 容器）：PK magic（0x50 0x4B）
     * - PDF：%PDF- header
     * - DJVU：AT&TFORM header
     * - TXT：任意 UTF-8 文本（仅排除 HTML 错误页）
     */
    private fun isValidEbookFile(file: File, format: EbookFormat?): Boolean {
        if (!file.exists() || file.length() < 4) {
            return false
        }
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(8)
                val read = input.read(header)
                if (read < 2) return false
                when (format) {
                    EbookFormat.PDF -> {
                        val sig = String(header, 0, minOf(5, read), Charsets.US_ASCII)
                        sig == "%PDF-"
                    }
                    EbookFormat.DJVU -> {
                        header[0] == 0x41.toByte() && header[1] == 0x54.toByte() && // 'A''T'
                            header[2] == 0x26.toByte() && header[3] == 0x54.toByte() // '&''T'
                    }
                    else -> {
                        // EPUB/FB2/TXT 等：ZIP/PK 或纯文本（排除 HTML）
                        val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                        if (isZip) return true
                        val text = String(header, 0, read, Charsets.UTF_8)
                        !text.contains("<!DOCTYPE", ignoreCase = true) && !text.startsWith("<html", ignoreCase = true)
                    }
                }
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
        private const val VIDEO_PROBE_BYTE_LIMIT = 1024L
        private const val HLS_ESTIMATE_TIMEOUT_MS = 8_000L
    }

    @AssistedFactory
    interface Factory : WorkerAssistedFactory<DownloadWorker>
}
