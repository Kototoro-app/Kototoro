package org.skepsun.kototoro.reader.ui.pager

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import androidx.annotation.CallSuper
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.target
import coil3.size.Size
import coil3.toBitmap
import coil3.util.CoilUtils
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.javascript.BrowserVerificationBridge
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.ui.list.lifecycle.LifecycleAwareViewHolder
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.isLowRamDevice
import org.skepsun.kototoro.core.util.ext.isSerializable
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.databinding.LayoutPageInfoBinding
import org.skepsun.kototoro.parsers.util.ifZero
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.domain.ReaderPageEnhancementController
import org.skepsun.kototoro.reader.ui.config.ReaderSettings
import org.skepsun.kototoro.reader.ui.pager.vm.PageState
import org.skepsun.kototoro.reader.ui.pager.vm.PageViewModel
import org.skepsun.kototoro.reader.ui.pager.webtoon.WebtoonHolder

abstract class BasePageHolder<B : ViewBinding>(
	protected val binding: B,
	loader: PageLoader,
	enhancementController: ReaderPageEnhancementController,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
	lifecycleOwner: LifecycleOwner,
) : LifecycleAwareViewHolder(binding.root, lifecycleOwner), DefaultOnImageEventListener, ComponentCallbacks2 {

	protected val viewModel = PageViewModel(
		loader = loader,
		enhancementController = enhancementController,
		settingsProducer = readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
		isWebtoon = this is WebtoonHolder,
	)
	protected val bindingInfo = LayoutPageInfoBinding.bind(binding.root)
	protected abstract val ssiv: SubsamplingScaleImageView
	protected abstract val animatedView: ImageView

	protected val settings: ReaderSettings
		get() = viewModel.settingsProducer.value

	val context: Context
		get() = itemView.context

	var boundData: ReaderPage? = null
		private set
	private var lastTranslationDisplaySignature: String? = null
	private var lastTranslationContentSignature: String? = null
	private var pendingAnimatedUri: Uri? = null
	private var autoBackgroundJob: Job? = null
	private var browserVerificationJob: Job? = null
	private var autoBackgroundSource: Uri? = null
	private var autoBackgroundColor: Int? = null

	init {
		lifecycleScope.launch(Dispatchers.Main) {
			ssiv.bindToLifecycle(this@BasePageHolder)
			ssiv.isEagerLoadingEnabled = !context.isLowRamDevice()
			ssiv.addOnImageEventListener(viewModel)
			ssiv.addOnImageEventListener(this@BasePageHolder)
		}
		val clickListener = View.OnClickListener { v ->
			when (v.id) {
				R.id.button_retry -> viewModel.retry(
					page = boundData?.toContentPage() ?: return@OnClickListener,
					isFromUser = true,
				)

				R.id.button_error_details -> viewModel.showErrorDetails(boundData?.url)

				R.id.button_open_in_browser -> {
					val page = boundData?.toContentPage() ?: return@OnClickListener
					viewModel.openInBrowser(page) { url ->
						browserVerificationJob?.cancel()
						browserVerificationJob = lifecycleScope.launch {
							val token = BrowserVerificationBridge.register()
							context.startActivity(
								AppRouter.browserIntent(context, url, page.source, null)
									.putExtra(AppRouter.KEY_BROWSER_WAIT_TOKEN, token)
									.putExtra(AppRouter.KEY_BROWSER_REFETCH_AFTER_SUCCESS, false),
							)
							val result = withContext(Dispatchers.IO) {
								BrowserVerificationBridge.await(token, BROWSER_WAIT_TIMEOUT_SECONDS)
							}
							val verifiedUrl = result?.url?.takeIf {
								it != url && (it.startsWith("http://") || it.startsWith("https://"))
							}
							if (verifiedUrl != null && boundData?.id == page.id) {
								viewModel.retry(page, isFromUser = false, pageUrlOverride = verifiedUrl)
							}
						}
					}
				}
			}
		}
		bindingInfo.buttonRetry.setOnClickListener(clickListener)
		bindingInfo.buttonErrorDetails.setOnClickListener(clickListener)
		bindingInfo.buttonOpenInBrowser.setOnClickListener(clickListener)
	}

	@CallSuper
	protected open fun onConfigChanged(settings: ReaderSettings) {
		val translationDisplaySignature = settings.translationDisplaySignature()
		val translationContentSignature = settings.translationContentSignature()
		val translationDisplayChanged = lastTranslationDisplaySignature != null &&
			lastTranslationDisplaySignature != translationDisplaySignature
		val translationContentChanged = lastTranslationContentSignature != null &&
			lastTranslationContentSignature != translationContentSignature
		lastTranslationDisplaySignature = translationDisplaySignature
		lastTranslationContentSignature = translationContentSignature
		settings.applyBackground(itemView)
		applyAutoBackground(currentImageSource())
		if (translationDisplayChanged || translationContentChanged) {
			val page = boundData?.toContentPage()
			if (page != null) {
				if (translationDisplayChanged && !translationContentChanged) {
					viewModel.switchDisplayLayer(page)
				} else {
					viewModel.refreshDisplayVariant(page)
				}
			} else {
				reloadImage()
			}
		} else if (settings.applyBitmapConfig(ssiv)) {
			reloadImage()
		} else if (viewModel.state.value is PageState.Shown) {
			onReady()
		}
		ssiv.applyDownSampling(isResumed())
	}

	fun reloadImage() {
		val source = when (val state = viewModel.state.value) {
			is PageState.Shown -> state.source
			is PageState.AwaitingTranslation -> state.source
			else -> null
		} ?: return
		ssiv.setImage(source)
	}

	fun bind(data: ReaderPage) {
		autoBackgroundJob?.cancel()
		autoBackgroundSource = null
		autoBackgroundColor = null
		boundData = data
		viewModel.onBind(data.toContentPage(), data.split)
		onBind(data)
	}

	@CallSuper
	protected open fun onBind(data: ReaderPage) = Unit

	override fun onCreate() {
		super.onCreate()
		context.registerComponentCallbacks(this)
		viewModel.state.observe(this, ::onStateChanged)
		viewModel.settingsProducer.observe(this, ::onConfigChanged)
	}

	override fun onResume() {
		super.onResume()
		ssiv.applyDownSampling(isForeground = true)
		if (viewModel.state.value is PageState.Error && !viewModel.isLoading()) {
			boundData?.let { viewModel.retry(it.toContentPage(), isFromUser = false) }
		}
		val uri = pendingAnimatedUri ?: return
		val current = animatedView.drawable
		if (current == null) {
			// Holder just became the current page — kick off the deferred animated decode now.
			enqueueAnimated(uri)
		} else {
			(current as? Animatable)?.let { if (!it.isRunning) it.start() }
		}
	}

	override fun onPause() {
		super.onPause()
		ssiv.applyDownSampling(isForeground = false)
		// Adjacent (offscreen) holders are technically attached to the window, so the
		// drawable's own setVisible(false) hook won't fire. Stop here to keep the frame
		// timer from running on pages the user can't see.
		(animatedView.drawable as? Animatable)?.stop()
	}

	override fun onDestroy() {
		browserVerificationJob?.cancel()
		context.unregisterComponentCallbacks(this)
		super.onDestroy()
	}

	open fun onAttachedToWindow() = Unit

	open fun onDetachedFromWindow() = Unit

	@CallSuper
	open fun onRecycled() {
		autoBackgroundJob?.cancel()
		viewModel.onRecycle()
		ssiv.isVisible = true
		ssiv.recycle()
		pendingAnimatedUri = null
		releaseAnimatedDrawable()
		CoilUtils.dispose(animatedView)
		animatedView.isVisible = false
	}

	override fun onTrimMemory(level: Int) {
		// TODO
	}

	override fun onConfigurationChanged(newConfig: Configuration) = Unit

	@Deprecated("Deprecated in Java")
	final override fun onLowMemory() = onTrimMemory(TRIM_MEMORY_COMPLETE)

	protected open fun onStateChanged(state: PageState) {
		bindingInfo.layoutError.isVisible = state is PageState.Error
		bindingInfo.layoutProgress.isGone = state.isFinalState()
		val progress = (state as? PageState.Loading)?.progress ?: -1
		if (progress in 0..100) {
			bindingInfo.progressBar.isIndeterminate = false
			bindingInfo.progressBar.setProgressCompat(progress, true)
			bindingInfo.textViewStatus.text = context.getString(R.string.percent_string_pattern, progress.toString())
		} else {
			bindingInfo.progressBar.isIndeterminate = true
			bindingInfo.textViewStatus.setText(R.string.loading_)
		}
		when (state) {
			is PageState.Converting -> {
				bindingInfo.textViewStatus.setText(R.string.processing_)
			}

			is PageState.AwaitingTranslation -> {
				bindingInfo.layoutProgress.isGone = true
				ssiv.setImage(state.source)
			}

			is PageState.Empty -> Unit

			is PageState.Error -> {
				val e = state.error
				bindingInfo.textViewError.text = e.getDisplayMessage(context.resources)
				bindingInfo.buttonRetry.setText(
					ExceptionResolver.getResolveStringId(e).ifZero { R.string.try_again },
				)
				bindingInfo.buttonErrorDetails.isVisible = e.isSerializable()
				bindingInfo.buttonOpenInBrowser.isVisible = viewModel.canOpenInBrowser()
				bindingInfo.layoutError.isVisible = true
				bindingInfo.progressBar.hide()
			}

			is PageState.Loaded -> {
				// Reset view visibility in case this holder just showed an animated page
				// (without this the animatedView can linger with stale content when the
				// holder is reused across pages of different formats).
				if (animatedView.isVisible) {
					pendingAnimatedUri = null
					releaseAnimatedDrawable()
					CoilUtils.dispose(animatedView)
					animatedView.isVisible = false
				}
				ssiv.isVisible = true
				bindingInfo.textViewStatus.setText(R.string.preparing_)
				ssiv.setImage(state.source)
				applyAutoBackground(state.source)
			}

			is PageState.Loading -> {
				if (state.preview != null && ssiv.getState() == null) {
					ssiv.setImage(state.preview)
				}
			}

			is PageState.Shown -> {
				applyAutoBackground(state.source)
				if (state.isAnimated) {
					prepareAnimated((state.source as? ImageSource.Uri)?.uri ?: return)
				}
			}
		}
	}

	private fun currentImageSource(): ImageSource? = when (val state = viewModel.state.value) {
		is PageState.Loaded -> state.source
		is PageState.AwaitingTranslation -> state.source
		is PageState.Shown -> state.source
		else -> null
	}

	private fun applyAutoBackground(source: ImageSource?) {
		if (settings.background != ReaderBackground.AUTO || this is WebtoonHolder) {
			autoBackgroundJob?.cancel()
			autoBackgroundSource = null
			autoBackgroundColor = null
			return
		}
		val uri = (source as? ImageSource.Uri)?.uri ?: return
		val pageKey = boundData?.readerKey ?: return
		if (autoBackgroundSource == uri) {
			autoBackgroundColor?.let(::onAutoBackgroundResolved)
			return
		}
		autoBackgroundJob?.cancel()
		autoBackgroundSource = uri
		autoBackgroundJob = lifecycleScope.launch {
			val result = viewModel.imageLoader.execute(
				ImageRequest.Builder(context)
					.data(uri)
					.size(AUTO_BACKGROUND_SAMPLE_SIZE, AUTO_BACKGROUND_SAMPLE_SIZE)
					.allowHardware(false)
					.build(),
			) as? SuccessResult ?: return@launch
			val color = withContext(Dispatchers.Default) {
				ReaderAutoBackground.resolve(result.image.toBitmap())
			}
			if (boundData?.readerKey != pageKey || settings.background != ReaderBackground.AUTO) {
				return@launch
			}
			autoBackgroundColor = color
			onAutoBackgroundResolved(color)
		}
	}

	protected open fun onAutoBackgroundResolved(color: Int) {
		applyResolvedBackground(color)
	}

	protected fun applyResolvedBackground(color: Int) {
		itemView.background = ColorDrawable(color)
		itemView.backgroundTintList = if (color == Color.WHITE) {
			settings.colorFilter?.getBackgroundTint()
		} else {
			null
		}
	}

	private fun releaseAnimatedDrawable() {
		(animatedView.drawable as? org.skepsun.kototoro.core.image.AvifAnimatedDrawable)?.release()
	}

	private fun prepareAnimated(uri: Uri) {
		// Always swap the views so the layout is consistent — but defer the heavy
		// libavif full-sequence decode until this holder is actually the current page.
		// Adjacent holders (offscreen page limit > 0) get bound and would otherwise
		// decode every frame of an AVIS in the background, which is the main source
		// of stutter while the user is sitting on a different page.
		pendingAnimatedUri = uri
		ssiv.recycle()
		ssiv.isVisible = false
		animatedView.isVisible = true
		if (isResumed()) {
			enqueueAnimated(uri)
		} else {
			// Drop any stale drawable from a previous binding so we don't show wrong frames
			// briefly when the user lands on this page.
			releaseAnimatedDrawable()
			CoilUtils.dispose(animatedView)
			animatedView.setImageDrawable(null)
		}
	}

	private fun enqueueAnimated(uri: Uri) {
		releaseAnimatedDrawable()
		CoilUtils.dispose(animatedView)
		viewModel.imageLoader.enqueue(
			ImageRequest.Builder(context)
				.data(uri)
				.target(animatedView)
				// Size.ORIGINAL preserves intrinsic dimensions; any scaling is delegated to the ImageView's fitCenter.
				.size(Size.ORIGINAL)
				// AnimatedImageDrawable cannot be backed by an immutable hardware bitmap;
				// without this the first frame is decoded as a hardware bitmap and playback never starts.
				.allowHardware(false)
				.listener(
					onSuccess = { _, _ ->
						(animatedView.drawable as? Animatable)?.let { anim ->
							if (!anim.isRunning) anim.start()
						}
					},
				)
				.build(),
		)
	}

	protected fun SubsamplingScaleImageView.applyDownSampling(isForeground: Boolean) {
		downSampling = when {
			isForeground || !settings.isReaderOptimizationEnabled -> 1
			BuildConfig.DEBUG -> 32
			context.isLowRamDevice() -> 8
			else -> 4
		}
	}

	private companion object {
		const val AUTO_BACKGROUND_SAMPLE_SIZE = 96
		const val BROWSER_WAIT_TIMEOUT_SECONDS = 300L
	}
}
