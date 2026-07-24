package org.skepsun.kototoro.image.ui

import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.lifecycle
import coil3.target.GenericViewTarget
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
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
import org.skepsun.kototoro.core.util.ext.enqueueWith
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
	private var imageView: SubsamplingScaleImageView? = null
	private var menuAnchor: View? = null
	private var inlineImageJob: Job? = null
	private var hasStartedImageLoad = false
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
				showMenu = inlineImagePath == null,
				isSaving = isSaving,
				isLoading = isImageLoading,
				error = imageError,
				onBack = ::navigateUp,
				onMenu = { menuAnchor?.let(menuMediator::onLongClick) },
				onRetry = { loadImage() },
				onImageViewCreated = { view ->
					imageView = view
					if (!hasStartedImageLoad) {
						hasStartedImageLoad = true
						loadImage(view)
					}
				},
				onMenuAnchorCreated = { menuAnchor = it },
			)
		}
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

	private fun loadImage(view: SubsamplingScaleImageView? = imageView) {
		val targetView = view ?: return
		isImageLoading = true
		imageError = null
		inlineImagePath?.let {
			loadInlineImage(targetView, it)
			return
		}
		ImageRequest.Builder(this)
			.data(intent.data)
			.memoryCacheKey(intent.getParcelableExtraCompat<CoilMemoryCacheKey>(AppRouter.KEY_PREVIEW)?.data)
			.memoryCachePolicy(CachePolicy.READ_ONLY)
			.lifecycle(this)
			.listener(this)
			.mangaSourceExtra(ContentSource(intent.getStringExtra(AppRouter.KEY_SOURCE)))
			.target(SsivTarget(targetView))
			.enqueueWith(coil)
	}

	private fun loadInlineImage(view: SubsamplingScaleImageView, imagePath: String) {
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
				view.setImage(ImageSource.bitmap(bitmap))
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

	private class SsivTarget(
		override val view: SubsamplingScaleImageView,
	) : GenericViewTarget<SubsamplingScaleImageView>() {

		override var drawable: Drawable? = null
			set(value) {
				field = value
				setImageDrawable(value)
			}

		override fun equals(other: Any?): Boolean {
			return (this === other) || (other is SsivTarget && view == other.view)
		}

		override fun hashCode() = view.hashCode()

		override fun toString() = "SsivTarget(view=$view)"

		private fun setImageDrawable(drawable: Drawable?) {
			if (drawable != null) {
				view.setImage(ImageSource.bitmap(drawable.toBitmap()))
			} else {
				view.recycle()
			}
		}
	}
}
