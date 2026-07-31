package org.skepsun.kototoro.image.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.lifecycle
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.image.CoilMemoryCacheKey
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.util.PopupMenuMediator
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.core.util.ext.getDisplayIcon
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import javax.inject.Inject

@AndroidEntryPoint
class ImageActivity : BaseComposeActivity(), ImageRequest.Listener {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel: ImageViewModel by viewModels()
	private lateinit var menuMediator: PopupMenuMediator
	private var menuAnchor: View? = null
	private var inlineImageJob: Job? = null
	private var imageModel by androidx.compose.runtime.mutableStateOf<Any?>(null)
	private var isImageLoading by androidx.compose.runtime.mutableStateOf(false)
	private var imageError by androidx.compose.runtime.mutableStateOf<ImageErrorState?>(null)
	private var isSaving by androidx.compose.runtime.mutableStateOf(false)

	private val inlineImagePath: String?
		get() = intent.getStringExtra(AppRouter.KEY_IMAGE_PATH)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		menuMediator = PopupMenuMediator(
			ImageMenuProvider(
				activity = this,
				snackbarHost = window.decorView,
				viewModel = viewModel,
			),
		)
		viewModel.isLoading.observe(this) { isSaving = it }
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(window.decorView, null))
		viewModel.onImageSaved.observeEvent(this, ::onImageSaved)

		setComposeContent {
			ImageViewerScreen(
				imageModel = imageModel,
				imageLoader = coil,
				showMenu = inlineImagePath == null,
				isSaving = isSaving,
				isLoading = isImageLoading,
				error = imageError,
				onBack = ::navigateUp,
				onMenu = { menuAnchor?.let(menuMediator::onLongClick) },
				onRetry = ::loadImage,
				onMenuAnchorCreated = { menuAnchor = it },
			)
		}
		loadImage()
	}

	override fun onError(request: ImageRequest, result: ErrorResult) {
		isImageLoading = false
		imageError = ImageErrorState(
			message = result.throwable.getDisplayMessage(resources),
			iconRes = result.throwable.getDisplayIcon(),
		)
	}

	override fun onStart(request: ImageRequest) {
		isImageLoading = true
		imageError = null
	}

	override fun onSuccess(request: ImageRequest, result: SuccessResult) {
		isImageLoading = false
		imageError = null
	}

	private fun loadImage() {
		isImageLoading = true
		imageError = null
		inlineImagePath?.let {
			loadInlineImage(it)
			return
		}
		imageModel = ImageRequest.Builder(this)
			.data(intent.data)
			.memoryCacheKey(intent.getParcelableExtraCompat<CoilMemoryCacheKey>(AppRouter.KEY_PREVIEW)?.data)
			.memoryCachePolicy(CachePolicy.READ_ONLY)
			.lifecycle(this)
			.listener(this)
			.mangaSourceExtra(ContentSource(intent.getStringExtra(AppRouter.KEY_SOURCE)))
			.build()
	}

	private fun loadInlineImage(imagePath: String) {
		inlineImageJob?.cancel()
		inlineImageJob = lifecycleScope.launch {
			try {
				@Suppress("UNCHECKED_CAST")
				val headers = intent.getSerializableExtra(AppRouter.KEY_IMAGE_HEADERS) as? HashMap<String, String>
				val bitmap = NovelInlineImageLoader.loadBitmap(
					context = this@ImageActivity,
					imageLoader = coil,
					imagePath = imagePath,
					source = ContentSource(intent.getStringExtra(AppRouter.KEY_SOURCE)),
					epubFilePath = intent.getStringExtra(AppRouter.KEY_EPUB_FILE_PATH),
					chapterPath = intent.getStringExtra(AppRouter.KEY_CHAPTER_PATH),
					headers = headers.orEmpty(),
				) ?: error("Image decode returned null")
				isImageLoading = false
				imageError = null
				imageModel = bitmap
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				isImageLoading = false
				imageError = ImageErrorState(
					message = error.getDisplayMessage(resources),
					iconRes = error.getDisplayIcon(),
				)
			}
		}
	}

	private fun onImageSaved(uri: Uri) {
		Snackbar.make(window.decorView, R.string.page_saved, Snackbar.LENGTH_LONG)
			.setAction(R.string.share) {
				ShareHelper(this).shareImage(uri)
			}.show()
	}

	private fun navigateUp() {
		val upIntent = parentActivityIntent
		if (upIntent != null) {
			if (!navigateUpTo(upIntent)) {
				startActivity(upIntent)
			}
		} else {
			finishAfterTransition()
		}
	}
}
