package org.skepsun.kototoro.reader.ui.pager.standard

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.Build
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setMargins
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.davemorrissey.labs.subscaleview.OnStateChangedListener
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.ui.widgets.ZoomControl
import org.skepsun.kototoro.core.util.ext.isLowRamDevice
import org.skepsun.kototoro.databinding.ItemPageBinding
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.domain.ReaderPageEnhancementController
import org.skepsun.kototoro.reader.ui.config.ReaderSettings
import org.skepsun.kototoro.reader.ui.pager.BasePageHolder
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

open class PageHolder(
	owner: LifecycleOwner,
	binding: ItemPageBinding,
	loader: PageLoader,
	enhancementController: ReaderPageEnhancementController,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
) : BasePageHolder<ItemPageBinding>(
	binding = binding,
	loader = loader,
	enhancementController = enhancementController,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
	lifecycleOwner = owner,
), ZoomControl.ZoomControlListener, OnApplyWindowInsetsListener {

	override val ssiv = binding.ssiv
	override val animatedView = binding.imageViewAnimated
	private val holderScope: CoroutineScope = owner.lifecycleScope
	private val ssivOriginal = binding.ssivOriginal
	private var dualLayerLoadJob: Job? = null
	private var translatedLayerReady = false
	private var suppressLayerSync = false
	private var layerSyncScheduled = false

	init {
		ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
		holderScope.launch(Dispatchers.Main) {
			ssivOriginal.bindToLifecycle(this@PageHolder)
			ssivOriginal.isEagerLoadingEnabled = !context.isLowRamDevice()
			ssivOriginal.onStateChangedListener = object : OnStateChangedListener {
				override fun onScaleChanged(newScale: Float, origin: Int) = Unit
				override fun onScaleChanged(view: SubsamplingScaleImageView, newScale: Float, origin: Int) = Unit
				override fun onCenterChanged(newCenter: PointF, origin: Int) = Unit
				override fun onCenterChanged(view: SubsamplingScaleImageView, newCenter: PointF, origin: Int) = Unit
			}
			ssiv.onStateChangedListener = object : OnStateChangedListener {
				override fun onScaleChanged(newScale: Float, origin: Int) {
					scheduleOriginalLayerSync()
				}

				override fun onScaleChanged(view: SubsamplingScaleImageView, newScale: Float, origin: Int) {
					scheduleOriginalLayerSync()
				}

				override fun onCenterChanged(newCenter: PointF, origin: Int) {
					scheduleOriginalLayerSync()
				}

				override fun onCenterChanged(view: SubsamplingScaleImageView, newCenter: PointF, origin: Int) {
					scheduleOriginalLayerSync()
				}
			}
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			insets.toWindowInsets()?.let {
				applyRoundedCorners(it)
			}
		}
		return insets
	}

	override fun onConfigChanged(settings: ReaderSettings) {
		super.onConfigChanged(settings)
		binding.textViewNumber.isVisible = settings.isPagesNumbersEnabled
		ssivOriginal.colorFilter = settings.colorFilter?.toColorFilter()
		applyDualLayerVisibility()
	}

	@SuppressLint("SetTextI18n")
	override fun onBind(data: ReaderPage) {
		super.onBind(data)
		binding.textViewNumber.text = (data.index + 1).toString()
	}

	override fun onStateChanged(state: org.skepsun.kototoro.reader.ui.pager.vm.PageState) {
		super.onStateChanged(state)
		when (state) {
			is org.skepsun.kototoro.reader.ui.pager.vm.PageState.Loaded -> refreshDualLayers()
			is org.skepsun.kototoro.reader.ui.pager.vm.PageState.Shown -> if (!state.isAnimated) refreshDualLayers()
			else -> Unit
		}
	}

	override fun onReady() {
		binding.ssiv.maxScale = 2f * maxOf(
			binding.ssiv.width / binding.ssiv.sWidth.toFloat(),
			binding.ssiv.height / binding.ssiv.sHeight.toFloat(),
		)
		binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
		when (settings.zoomMode) {
			ZoomMode.FIT_CENTER -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
				binding.ssiv.resetScaleAndCenter()
			}

			ZoomMode.FIT_HEIGHT -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
				binding.ssiv.minScale = binding.ssiv.height / binding.ssiv.sHeight.toFloat()
				binding.ssiv.setScaleAndCenter(
					binding.ssiv.minScale,
					PointF(0f, binding.ssiv.sHeight / 2f),
				)
			}

			ZoomMode.FIT_WIDTH -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
				binding.ssiv.minScale = binding.ssiv.width / binding.ssiv.sWidth.toFloat()
				binding.ssiv.setScaleAndCenter(
					binding.ssiv.minScale,
					PointF(binding.ssiv.sWidth / 2f, 0f),
				)
			}

			ZoomMode.KEEP_START -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
				binding.ssiv.setScaleAndCenter(
					binding.ssiv.maxScale,
					PointF(0f, 0f),
				)
			}
		}
		ssivOriginal.colorFilter = settings.colorFilter?.toColorFilter()
		syncOriginalLayerState()
		applyDualLayerVisibility()
	}

	override fun onRecycled() {
		dualLayerLoadJob?.cancel()
		ssivOriginal.recycle()
		super.onRecycled()
	}

	override fun onZoomIn() {
		scaleBy(1.2f)
	}

	override fun onZoomOut() {
		scaleBy(0.8f)
	}

	@SuppressLint("RtlHardcoded")
	@RequiresApi(Build.VERSION_CODES.S)
	protected open fun applyRoundedCorners(insets: WindowInsets) {
		binding.textViewNumber.updateLayoutParams<FrameLayout.LayoutParams> {
			val baseMargin = context.resources.getDimensionPixelOffset(R.dimen.margin_small)
			val absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection)
			val corner = when {
				absoluteGravity and Gravity.LEFT == Gravity.LEFT -> {
					insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
				}

				absoluteGravity and Gravity.RIGHT == Gravity.RIGHT -> {
					insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
				}

				else -> {
					null
				}
			}
			setMargins(baseMargin + (corner?.radius ?: 0))
		}
	}

	private fun scaleBy(factor: Float) {
		val ssiv = binding.ssiv
		val center = ssiv.getCenter() ?: return
		val newScale = ssiv.scale * factor
		ssiv.animateScaleAndCenter(newScale, center)?.apply {
			withDuration(ssiv.resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
			withInterpolator(DecelerateInterpolator())
			start()
		}
	}

	private fun refreshDualLayers() {
		val page = boundData?.toContentPage() ?: return
		val prev = dualLayerLoadJob
		dualLayerLoadJob = holderScope.launch(Dispatchers.Default) {
			prev?.cancelAndJoin()
			val layers = viewModel.resolveLayerSources(page) ?: return@launch
			val currentState = ssiv.getState()
			holderScope.launch(Dispatchers.Main) {
				suppressLayerSync = true
				ssivOriginal.setImage(layers.original, null, currentState)
				if (layers.translated != null) {
					ssiv.setImage(layers.translated, null, currentState)
					translatedLayerReady = true
				} else {
					translatedLayerReady = false
				}
				suppressLayerSync = false
				syncOriginalLayerState()
				applyDualLayerVisibility()
			}
		}
	}

	private fun applyDualLayerVisibility() {
		if (!translatedLayerReady) {
			ssiv.alpha = 1f
			ssivOriginal.isVisible = false
			return
		}
		ssivOriginal.isVisible = true
		ssiv.alpha = if (settings.isTranslationShowTranslated) 1f else 0f
	}

	private fun syncOriginalLayerState() {
		if (suppressLayerSync || !translatedLayerReady || !ssiv.isReady || !ssivOriginal.isReady) {
			return
		}
		val center = ssiv.getCenter() ?: return
		suppressLayerSync = true
		ssivOriginal.setScaleAndCenter(ssiv.scale, PointF(center.x, center.y))
		suppressLayerSync = false
	}

	private fun scheduleOriginalLayerSync() {
		if (layerSyncScheduled) return
		layerSyncScheduled = true
		ssiv.postOnAnimation {
			layerSyncScheduled = false
			syncOriginalLayerState()
		}
	}
}
