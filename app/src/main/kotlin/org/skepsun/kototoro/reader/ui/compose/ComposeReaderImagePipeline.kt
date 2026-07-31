package org.skepsun.kototoro.reader.ui.compose

import android.net.Uri
import androidx.core.net.toFile
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.domain.ReaderSuperResolutionManager
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Compose-owned image pipeline. It intentionally does not expose the legacy reader page state. */
interface ComposeReaderImagePipeline {

	fun observe(page: ReaderPage, force: Boolean = false): Flow<ComposeReaderImageState>

	fun cachedState(pageKey: Long): ComposeReaderImageState? = null

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
	private val pageLoader: PageLoader,
	private val settings: AppSettings,
	private val enhancer: ComposeSuperResolutionEnhancer,
) : ComposeReaderImagePipeline {

	val imageLoader get() = pageLoader.imageLoader
	private val metadataCache = ReaderImageMetadataCache()
	private val displayCache = ConcurrentHashMap<Long, Uri>()
	private val stateCache = ConcurrentHashMap<Long, ComposeReaderImageState>()

	fun cachedDisplay(pageKey: Long?): Uri? = pageKey?.let(displayCache::get)
	override fun cachedState(pageKey: Long): ComposeReaderImageState? = stateCache[pageKey]

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

/**
 * Compose-scoped super-resolution stage. The current engine is shared with downloads
 * while native model ownership is migrated; callers only observe the Compose contract.
 */
class ComposeSuperResolutionEnhancer @Inject constructor(
	private val manager: ReaderSuperResolutionManager,
	private val settings: AppSettings,
) : ComposeImageEnhancer {

	override suspend fun enhance(request: ComposeImageEnhancementRequest): Uri? {
		return manager.processImage(
			originalUri = request.original,
			modelId = request.model,
			noiseLevel = request.noiseLevel,
			cacheLimitMb = settings.readerSuperResolutionCacheLimitMb,
		)
	}
}
