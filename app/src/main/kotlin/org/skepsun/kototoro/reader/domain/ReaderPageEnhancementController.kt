package org.skepsun.kototoro.reader.domain

import android.net.Uri
import android.util.Log
import androidx.collection.LongSparseArray
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.reader.translate.domain.ReaderPageTranslationProcessor
import org.skepsun.kototoro.reader.translate.domain.RemoteTranslationConsentDecision
import org.skepsun.kototoro.reader.translate.domain.RemoteTranslationConsentPolicy
import org.skepsun.kototoro.reader.translate.domain.RemoteTranslationConsentRequest
import org.skepsun.kototoro.reader.translate.domain.evaluateRemoteTranslationConsent
import org.skepsun.kototoro.reader.translate.domain.resolveAutomaticReaderOcrLanguage
import javax.inject.Inject

@ActivityRetainedScoped
class ReaderPageEnhancementController @Inject constructor(
    private val settings: AppSettings,
    private val translationProcessor: ReaderPageTranslationProcessor,
) {

    private val translationUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    private val translationStatusUpdates = MutableSharedFlow<TranslationLayerStateEvent>(extraBufferCapacity = 128)
    private val remoteConsentRequiredUpdates = MutableSharedFlow<RemoteTranslationConsentRequest>(extraBufferCapacity = 16)
    private val translationLock = Any()
    private val translationJobs = LongSparseArray<Job>()
    @Volatile
    private var translatedLanguage: String? = null
    @Volatile
    private var sourceLanguage: String? = null
    @Volatile
    private var branch: String? = null
    @Volatile
    private var bookKey: String? = null

    fun setTranslationLanguageContext(
        translatedLanguage: String?,
        sourceLanguage: String?,
        branch: String?,
        bookKey: String? = this.bookKey,
    ) {
        this.translatedLanguage = translatedLanguage
        this.sourceLanguage = sourceLanguage
        this.branch = branch
        this.bookKey = bookKey
    }

    data class PreparedPage(
        val displayUri: Uri,
        val translationSourceUri: Uri,
        val state: TranslationLayerState,
        val shouldScheduleTranslation: Boolean,
    )

    fun currentWorkSignature(): String {
        return buildString {
            append(settings.isReaderTranslationEnabled)
            append('|')
            append(settings.readerTranslationSourceLanguage)
            append('|')
            append(settings.readerTranslationTargetLanguage)
            append('|')
            append(settings.readerTranslationOcrEngine.name)
            append('|')
            append(settings.readerTranslationPipelineMode.name)
            append('|')
            append("page_det_rec")
            append('|')
            append(settings.readerTranslationMode.name)
            append('|')
            append(settings.readerTranslationApiProviderPreset)
            append('|')
            append(settings.readerTranslationApiEndpoint)
            append('|')
            append(settings.readerTranslationApiModel)
            append('|')
            append(settings.readerE2eApiProviderPreset)
            append('|')
            append(settings.readerE2eApiEndpoint)
            append('|')
            append(settings.remoteTranslationConsentedEndpoints.sorted().joinToString(","))
            append('|')
            append(settings.readerTranslationOnnxModelId)
            append('|')
            append(settings.readerTranslationPaddleOfficialModelId)
            append('|')
            append(settings.readerTranslationPaddleDetModelId)
            append('|')
            append(translatedLanguage.orEmpty())
            append('|')
            append(sourceLanguage.orEmpty())
            append('|')
            append(branch.orEmpty())
            append('|')
            append(bookKey.orEmpty())
            append('|')
            append(settings.readerTranslationOcrDetectionMaxSide)
            append('|')
            append(settings.readerTranslationOcrDetectionThreshold)
            append('|')
            append(settings.readerTranslationOcrMinBoxSize)
            append('|')
            append(settings.readerTranslationOcrRecognitionThreshold)
            append('|')
            append(settings.readerTranslationOcrRecognitionMaxWidth)
            append('|')
            append(settings.readerTranslationOcrRecognitionBatchSize)
            append('|')
            append(settings.readerTranslationPaddleRecModelUrl)
            append('|')
            append(settings.readerTranslationPaddleRecModelVersion)
            append('|')
            append(settings.readerTranslationPaddleRecModelSha256)
        }
    }

    fun observeTranslationUpdates(): Flow<Long> = translationUpdates

    fun observeTranslationStatusUpdates(): Flow<TranslationLayerStateEvent> = translationStatusUpdates

    fun observeRemoteTranslationConsentRequired(): Flow<RemoteTranslationConsentRequest> = remoteConsentRequiredUpdates

    fun observeTranslationDebugLogUpdates(): Flow<Long> = translationProcessor.observeDebugLogUpdates()

    fun getTranslationDebugLog(pageId: Long): String = translationProcessor.getPageDebugLog(pageId)

    internal suspend fun editableBubbleTexts(pageId: Long): List<String> = translationProcessor.editableBubbleTexts(pageId)

    internal suspend fun retranslateBubble(pageId: Long, index: Int, expectedText: String, originalText: String): Boolean {
        val decision = evaluateMangaConsent()
        if (decision is RemoteTranslationConsentDecision.RequiresConsent) {
            remoteConsentRequiredUpdates.tryEmit(decision.request)
            return false
        }
        return translationProcessor.retranslateBubble(pageId, index, expectedText, originalText, bookKey = bookKey)
    }

    suspend fun resolveDisplayVariant(
        page: ContentPage,
        currentUri: Uri,
        showTranslated: Boolean,
    ): Uri? {
        return if (showTranslated) {
            translationProcessor.peekRendered(page, currentUri, resolveAutoRecognizerLanguage(), bookKey = bookKey)
        } else {
            translationProcessor.peekSourceOfRendered(currentUri) ?: currentUri
        }
    }

    suspend fun preparePage(
        page: ContentPage,
        sourceUri: Uri,
        convertZipBitmap: suspend (Uri) -> Uri,
    ): PreparedPage {
        val isArchivePage = sourceUri.isZipUri() || sourceUri.isContentZipUri()
        val readyUri = if (settings.isReaderTranslationEnabled && isArchivePage) {
            convertZipBitmap(sourceUri)
        } else {
            sourceUri
        }

        if (!settings.isReaderTranslationEnabled) {
            Log.d("ReaderTranslate", "PageLoader debug: translation disabled page=${page.id}")
            emitState(page.id, TranslationLayerState.IDLE)
            return PreparedPage(
                displayUri = readyUri,
                translationSourceUri = readyUri,
                state = TranslationLayerState.IDLE,
                shouldScheduleTranslation = false,
            )
        }

        val cachedRecord = translationProcessor.peekRendered(
            page = page,
            sourceUri = readyUri,
            autoRecognizerLanguage = resolveAutoRecognizerLanguage(),
            bookKey = bookKey,
        )
        if (cachedRecord != null) {
            Log.d("ReaderTranslate", "PageLoader debug: cached record found page=${page.id}")
            emitState(page.id, TranslationLayerState.READY)
            return PreparedPage(
                displayUri = if (settings.isReaderTranslationShowTranslated) cachedRecord else readyUri,
                translationSourceUri = readyUri,
                state = TranslationLayerState.READY,
                shouldScheduleTranslation = false,
            )
        }

        val consentDecision = evaluateMangaConsent()
        if (consentDecision is RemoteTranslationConsentDecision.RequiresConsent) {
            Log.d("ReaderTranslate", "PageLoader debug: remote translation consent required page=${page.id}")
            emitState(page.id, TranslationLayerState.IDLE)
            remoteConsentRequiredUpdates.tryEmit(consentDecision.request)
            return PreparedPage(
                displayUri = readyUri,
                translationSourceUri = readyUri,
                state = TranslationLayerState.IDLE,
                shouldScheduleTranslation = false,
            )
        }

        return PreparedPage(
            displayUri = readyUri,
            translationSourceUri = readyUri,
            state = TranslationLayerState.GENERATING,
            shouldScheduleTranslation = true,
        )
    }

    fun scheduleTranslation(
        page: ContentPage,
        sourceUri: Uri,
        scope: CoroutineScope,
        onRendered: () -> Unit,
    ) {
        val consentDecision = evaluateMangaConsent()
        if (consentDecision is RemoteTranslationConsentDecision.RequiresConsent) {
            Log.d("ReaderTranslate", "schedule skip consent required page=${page.id}")
            emitState(page.id, TranslationLayerState.IDLE)
            remoteConsentRequiredUpdates.tryEmit(consentDecision.request)
            return
        }
        synchronized(translationLock) {
            val existing = translationJobs[page.id]
            if (existing?.isActive == true) {
                Log.d("ReaderTranslate", "schedule skip active job page=${page.id}")
                return
            }
            emitState(page.id, TranslationLayerState.GENERATING)
            translationJobs.put(page.id, scope.launch {
                Log.d("ReaderTranslate", "translation job start page=${page.id}")
                val translated = runCatching {
                    translationProcessor.process(
                        page = page,
                        sourceUri = sourceUri,
                        autoRecognizerLanguage = resolveAutoRecognizerLanguage(),
                        bookKey = bookKey,
                    )
                }.onFailure {
                    Log.d("ReaderTranslate", "translation job exception page=${page.id} err=${it.javaClass.simpleName}: ${it.message.orEmpty()}")
                    it.printStackTraceDebug()
                }.getOrDefault(sourceUri)
                if (translated != sourceUri) {
                    Log.d("ReaderTranslate", "translation job ready page=${page.id}")
                    emitState(page.id, TranslationLayerState.READY)
                    onRendered()
                    translationUpdates.tryEmit(page.id)
                } else {
                    Log.d("ReaderTranslate", "translation job failed page=${page.id}")
                    emitState(page.id, TranslationLayerState.FAILED)
                }
                synchronized(translationLock) {
                    translationJobs.remove(page.id)
                    Log.d("ReaderTranslate", "translation job removed page=${page.id}")
                }
            })
        }
    }

    private fun evaluateMangaConsent(): RemoteTranslationConsentDecision = settings.evaluateRemoteTranslationConsent(
        isNovel = false,
        customProviderLabel = RemoteTranslationConsentPolicy.DEFAULT_PROVIDER_ID,
    )

    private fun resolveAutoRecognizerLanguage(): String? {
        return resolveAutomaticReaderOcrLanguage(
            translatedLanguage = translatedLanguage,
            sourceLanguage = sourceLanguage,
            branch = branch,
        )
    }

    fun invalidateTranslationTask(pageId: Long) {
        synchronized(translationLock) {
            translationJobs[pageId]?.cancel()
            translationJobs.remove(pageId)
        }
        emitState(pageId, TranslationLayerState.IDLE)
    }

    fun cancelAllTranslationTasks() {
        synchronized(translationLock) {
            for (i in 0 until translationJobs.size()) {
                translationJobs.valueAt(i).cancel()
            }
            translationJobs.clear()
        }
    }

    suspend fun invalidateTranslationCacheForPage(pageId: Long) {
        translationProcessor.clearPageCaches(pageId)
    }

    suspend fun invalidateTranslationCaches() {
        translationProcessor.clearAllCaches()
    }

    private fun emitState(pageId: Long, state: TranslationLayerState) {
        translationStatusUpdates.tryEmit(TranslationLayerStateEvent(pageId, state))
    }
}
