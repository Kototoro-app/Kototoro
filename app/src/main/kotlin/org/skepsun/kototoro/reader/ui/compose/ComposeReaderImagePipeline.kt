package org.skepsun.kototoro.reader.ui.compose

import android.net.Uri
import androidx.core.net.toFile
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.domain.ReaderSuperResolutionManager
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit
import javax.inject.Inject

/** Compose-owned image pipeline. It intentionally does not expose the legacy reader page state. */
interface ComposeReaderImagePipeline {

	fun observe(page: ReaderPage, force: Boolean = false): Flow<ComposeReaderImageState>

	/** Reports decoded dimensions so shared reader state can create wide-page splits. */
	fun onImageDecoded(page: ReaderPage, width: Int, height: Int) = Unit
}

sealed interface ComposeReaderImageState {
	data object LoadingOriginal : ComposeReaderImageState

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

	override fun observe(page: ReaderPage, force: Boolean): Flow<ComposeReaderImageState> = flow {
		emit(ComposeReaderImageState.LoadingOriginal)
		resolveReaderPreviewUrl(page.preview, page.source.name)
			?.let { emit(ComposeReaderImageState.PreviewReady(it)) }
		val original = pageLoader.loadOriginalPage(page.toContentPage(), force)
		val isAnimated = original.isFileUri() && BitmapDecoderCompat.isAnimated(original.toFile())
		emit(ComposeReaderImageState.OriginalReady(original, isAnimated))
		if (isAnimated) return@flow
		if (!settings.isReaderSuperResolutionEnabled) return@flow

		emit(ComposeReaderImageState.Enhancing(original, progress = null))
		val enhanced = enhancer.enhance(
			ComposeImageEnhancementRequest(
				pageKey = page.readerKey,
				original = original,
				engine = settings.readerSuperResolutionEngine,
				model = if (settings.readerSuperResolutionEngine == "ANIME4K") {
					settings.readerSuperResolutionAnime4kMode
				} else {
					settings.readerSuperResolutionModel
				},
				noiseLevel = settings.readerSuperResolutionNoiseLevel,
			),
		)
		if (enhanced != null) {
			emit(ComposeReaderImageState.EnhancedReady(original, enhanced))
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
