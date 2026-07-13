package org.skepsun.kototoro.reader.ui.pager.doublepage

import android.graphics.PointF
import android.view.Gravity
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.databinding.ItemPageBinding
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.domain.ReaderPageEnhancementController
import org.skepsun.kototoro.reader.ui.config.ReaderSettings
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.standard.PageHolder

class DoublePageHolder internal constructor(
	owner: LifecycleOwner,
	binding: ItemPageBinding,
	loader: PageLoader,
	enhancementController: ReaderPageEnhancementController,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
	private val backgroundCoordinator: DoublePageBackgroundCoordinator,
) : PageHolder(
	owner = owner,
	binding = binding,
	loader = loader,
	enhancementController = enhancementController,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
) {

	private val isEven: Boolean
		get() = bindingAdapterPosition and 1 == 0

	init {
		binding.ssiv.panLimit = SubsamplingScaleImageView.PAN_LIMIT_INSIDE
	}

	internal fun bind(data: ReaderPage, backgroundKey: DoublePageBackgroundKey) {
		backgroundCoordinator.register(this, backgroundKey, data.readerKey)
		super.bind(data)
	}

	override fun onBind(data: ReaderPage) {
		super.onBind(data)
		(binding.textViewNumber.layoutParams as FrameLayout.LayoutParams)
			.gravity = (if (isEven) Gravity.START else Gravity.END) or Gravity.BOTTOM
	}

	override fun onReady() {
		with(binding.ssiv) {
			maxScale = 2f * maxOf(
				width / sWidth.toFloat(),
				height / sHeight.toFloat(),
			)
			binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
			minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
			setScaleAndCenter(
				minScale,
				PointF(if (isEven) 0f else sWidth.toFloat(), sHeight / 2f),
			)
		}
	}

	override fun onAutoBackgroundResolved(color: Int) {
		backgroundCoordinator.onColorResolved(this, color)
	}

	fun applyCoordinatedBackground(color: Int) {
		if (settings.background == ReaderBackground.AUTO) {
			applyResolvedBackground(color)
		}
	}

	override fun onRecycled() {
		backgroundCoordinator.unregister(this)
		super.onRecycled()
	}
}
