package org.skepsun.kototoro.reader.ui.compose

import android.net.Uri
import androidx.compose.ui.unit.IntRect
import androidx.core.net.toFile
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Compose-owned image pipeline. It intentionally does not expose the legacy reader page state. */
interface ComposeReaderImagePipeline {

    fun observe(page: ReaderPage, force: Boolean = false): Flow<ComposeReaderImageState>

    fun cachedState(pageKey: Long): ComposeReaderImageState? = null

    suspend fun getTrimmedBounds(uri: Uri): IntRect? = null

    /** Reports decoded dimensions so shared reader state can create wide-page splits. */
    fun onImageDecoded(page: ReaderPage, width: Int, height: Int) = Unit
}

sealed interface ComposeReaderImageState {
    data object LoadingOriginal : ComposeReaderImageState

    data class Downloading(
        val progress: Float?,
    ) : ComposeReaderImageState

    data class PreviewReady(
        val previewUrl: String,
    ) : ComposeReaderImageState

    data class OriginalReady(
        val original: Uri,
        val isAnimated: Boolean = false,
    ) : ComposeReaderImageState

    data class Enhancing(
        val original: Uri,
        val progress: Float?,
    ) : ComposeReaderImageState

    data class EnhancedReady(
        val original: Uri,
        val enhanced: Uri,
        val isAnimated: Boolean = false,
    ) : ComposeReaderImageState

    data class Failed(
        val original: Uri?,
        val cause: Throwable,
    ) : ComposeReaderImageState
}

/** A cancellable enhancement stage. Implementations are scoped to the Compose reader lifecycle. */
fun interface ComposeImageEnhancer {

    suspend fun enhance(request: ComposeImageEnhancementRequest): Uri?
}

data class ComposeImageEnhancementRequest(
    val pageKey: Long,
    val original: Uri,
    val engine: String,
    val model: String,
    val noiseLevel: Int,
)

@ActivityRetainedScoped
class DefaultComposeReaderImagePipeline @Inject constructor(
    @LocalizedAppContext private val context: android.content.Context,
    private val pageLoader: PageLoader,
    private val settings: AppSettings,
) : ComposeReaderImagePipeline {

    val imageLoader get() = pageLoader.imageLoader
    private val metadataCache = ReaderImageMetadataCache()
    private val displayCache = ConcurrentHashMap<Long, Uri>()
    private val stateCache = ConcurrentHashMap<Long, ComposeReaderImageState>()
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var warmJob: Job? = null
    private var warmedPageKey: Long? = null

    fun cachedDisplay(pageKey: Long?): Uri? = pageKey?.let(displayCache::get)
    override fun cachedState(pageKey: Long): ComposeReaderImageState? = stateCache[pageKey]
    override suspend fun getTrimmedBounds(uri: Uri): IntRect? = pageLoader.getTrimmedBounds(uri)?.let {
        IntRect(it.left, it.top, it.right, it.bottom)
    }

    /**
     * Decodes the page under the progress slider while the thumb is still moving, for reader modes
     * that do not use the scene pipeline adapter (legacy webtoon, paged and double page).
     * The request mirrors the readers' own Coil request so the warmed bitmap is a cache hit when
     * the jump actually composes the page, and a differently cropped/split image never poisons the cache.
     */
    fun warmPage(
        page: ReaderPage,
        isCropEnabled: Boolean,
        isReaderOptimizationEnabled: Boolean,
    ) {
        if (page.readerKey == warmedPageKey) return
        warmedPageKey = page.readerKey
        warmJob?.cancel()
        warmJob = warmScope.launch {
            val ready = observe(page).firstOrNull { state ->
                state is ComposeReaderImageState.OriginalReady ||
                    state is ComposeReaderImageState.EnhancedReady ||
                    state is ComposeReaderImageState.Failed
            } ?: return@launch
            val (uri, animated) = when (ready) {
                is ComposeReaderImageState.OriginalReady -> ready.original to ready.isAnimated
                is ComposeReaderImageState.EnhancedReady -> ready.enhanced to ready.isAnimated
                else -> return@launch
            }
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(!animated)
                .apply {
                    if (!animated) transformations(ComposeReaderPageTransformation(isCropEnabled, page.split))
                    if (isReaderOptimizationEnabled) memoryCachePolicy(CachePolicy.DISABLED)
                }
                .build()
            runCatching { imageLoader.execute(request) }
        }
    }

    override fun observe(page: ReaderPage, force: Boolean): Flow<ComposeReaderImageState> = channelFlow {
        val cachedState = stateCache[page.readerKey].takeUnless { force }
        if (cachedState != null) {
            send(cachedState)
        } else {
            send(ComposeReaderImageState.LoadingOriginal)
            resolveReaderPreviewUrl(page.preview, page.source.name)
                ?.let { send(ComposeReaderImageState.PreviewReady(it)) }
        }
        val task = pageLoader.loadPageAsync(page.toContentPage(), force)
        val progressJob = launch {
            task.progressAsFlow().collect { progress ->
                if (cachedState == null) {
                    send(ComposeReaderImageState.Downloading(progress.takeIf { it in 0f..1f }))
                }
            }
        }
        try {
            val display = task.await()
            val isAnimated = display.isFileUri() && metadataCache.isAnimated(display.toString(), refresh = force) {
                withContext(Dispatchers.IO) { BitmapDecoderCompat.isAnimated(display.toFile()) }
            }
            val ready = ComposeReaderImageState.OriginalReady(display, isAnimated)
            displayCache[page.readerKey] = display
            stateCache[page.readerKey] = ready
            send(ready)
        } finally {
            progressJob.cancel()
            pageLoader.releasePageTask(page.toContentPage(), task)
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(ComposeReaderImageState.Failed(original = null, cause = error))
    }

    override fun onImageDecoded(page: ReaderPage, width: Int, height: Int) {
        if (page.split == ReaderPageSplit.NONE && isWideReaderPage(width, height)) {
            pageLoader.widePageDetectedEvent.tryEmit(page.id)
        }
    }
}

internal class ReaderImageMetadataCache(
    private val maxEntries: Int = 512,
) {

    private val animated = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun isAnimated(key: String, refresh: Boolean = false, probe: suspend () -> Boolean): Boolean {
        val candidate = CompletableDeferred<Boolean>()
        val existing = if (refresh) {
            animated.put(key, candidate)
            null
        } else {
            animated.putIfAbsent(key, candidate)
        }
        if (existing != null) return existing.await()
        return try {
            probe().also { result ->
                candidate.complete(result)
                trimCompletedEntries()
            }
        } catch (error: Throwable) {
            candidate.completeExceptionally(error)
            animated.remove(key, candidate)
            throw error
        }
    }

    private fun trimCompletedEntries() {
        if (animated.size <= maxEntries) return
        animated.entries.asSequence()
            .filter { it.value.isCompleted }
            .take(animated.size - maxEntries)
            .forEach { animated.remove(it.key, it.value) }
    }
}

internal fun isWideReaderPage(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && width > height * WIDE_PAGE_RATIO

private const val WIDE_PAGE_RATIO = 1.15f

internal fun resolveReaderPreviewUrl(previewUrl: String?, sourceName: String): String? {
    return previewUrl?.takeUnless { it.isBlank() || sourceName.startsWith("JSON_") }
}
