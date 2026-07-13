package org.skepsun.kototoro.reader.ui.pager

import android.os.Build
import android.util.Log
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.PageTransformer
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.ui.list.lifecycle.PagerLifecycleDispatcher
import org.skepsun.kototoro.core.util.ext.doOnPageChanged
import org.skepsun.kototoro.core.util.ext.findCurrentViewHolder
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.recyclerView
import org.skepsun.kototoro.core.util.ext.resetTransformations
import org.skepsun.kototoro.databinding.FragmentReaderPagerBinding
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.domain.ReaderPageEnhancementController
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.reader.ui.pager.standard.NoAnimPageTransformer
import org.skepsun.kototoro.reader.ui.pager.standard.PageAnimTransformer
import org.skepsun.kototoro.reader.ui.pager.standard.PageHolder
import org.skepsun.kototoro.reader.ui.pager.standard.PagerEventSupplier
import org.skepsun.kototoro.reader.ui.pager.standard.PagesAdapter
import javax.inject.Inject
import kotlin.math.absoluteValue
import kotlin.math.sign

@AndroidEntryPoint
abstract class BasePagerReaderFragment : BaseReaderFragment<FragmentReaderPagerBinding>(),
	View.OnGenericMotionListener {

	@Inject
	lateinit var networkState: NetworkState

	@Inject
	lateinit var pageLoader: PageLoader

	@Inject
	lateinit var enhancementController: ReaderPageEnhancementController

	private var pagerLifecycleDispatcher: PagerLifecycleDispatcher? = null

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentReaderPagerBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: FragmentReaderPagerBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		with(binding.pager) {
			onInitPager(this)
			doOnPageChanged(::notifyPageChanged)
			setOnGenericMotionListener(this@BasePagerReaderFragment)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				recyclerView?.defaultFocusHighlightEnabled = false
			}
			PagerEventSupplier(this).attach()
			pagerLifecycleDispatcher = PagerLifecycleDispatcher(this).also {
				registerOnPageChangeCallback(it)
			}
			adapter = readerAdapter
		}

		viewModel.pageAnimation.observe(viewLifecycleOwner) {
			val transformer = when (it) {
				ReaderAnimation.NONE -> NoAnimPageTransformer(binding.pager.orientation)
				ReaderAnimation.DEFAULT -> null
				ReaderAnimation.ADVANCED -> onCreateAdvancedTransformer()
			}
			binding.pager.setPageTransformer(transformer)
			if (transformer == null) {
				binding.pager.recyclerView?.children?.forEach { view ->
					view.resetTransformations()
				}
			}
		}
	}

	override fun onDestroyView() {
		pagerLifecycleDispatcher = null
		requireViewBinding().pager.adapter = null
		super.onDestroyView()
	}

	override fun onZoomIn() {
		(viewBinding?.pager?.findCurrentViewHolder() as? PageHolder)?.onZoomIn()
	}

	override fun onZoomOut() {
		(viewBinding?.pager?.findCurrentViewHolder() as? PageHolder)?.onZoomOut()
	}

	override fun onGenericMotion(v: View?, event: MotionEvent): Boolean {
		if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
			if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
				val axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
				val withCtrl = event.metaState and KeyEvent.META_CTRL_MASK != 0
				if (!withCtrl) {
					onWheelScroll(axisValue)
					return true
				}
			}
		}
		return false
	}

	override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) = coroutineScope {
		val items = launch {
			requireAdapter().setItems(pages)
			yield()
			pagerLifecycleDispatcher?.postInvalidate()
		}
		if (pendingState != null) {
			val position = pages.indexOfFirst {
				it.chapterId == pendingState.chapterId && it.index == pendingState.page
			}
			Log.d(LOG_TAG, "pager.onPagesChanged: pages=${pages.size}, pending=$pendingState, pos=$position")
			items.join()
			if (position != -1) {
				requireViewBinding().pager.setCurrentItem(position, false)
				notifyPageChanged(position)
			} else {
				Snackbar.make(requireView(), R.string.not_found_404, Snackbar.LENGTH_SHORT)
					.show()
			}
		} else {
			items.join()
		}
	}

	override fun onCreateAdapter(): BaseReaderAdapter<*> = PagesAdapter(
		lifecycleOwner = viewLifecycleOwner,
		loader = pageLoader,
		enhancementController = enhancementController,
		readerSettingsProducer = viewModel.readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
	)

	override fun switchPageBy(delta: Int) {
		with(requireViewBinding().pager) {
			setCurrentItem(currentItem + delta, isAnimationEnabled())
		}
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		Log.d(LOG_TAG, "pager.switchPageTo: position=$position, smooth=$smooth")
		with(requireViewBinding().pager) {
			setCurrentItem(
				position,
				smooth && isAnimationEnabled() && (currentItem - position).absoluteValue < SMOOTH_SCROLL_LIMIT,
			)
		}
	}

	override fun getCurrentState(): ReaderState? = viewBinding?.run {
		val adapter = pager.adapter as? BaseReaderAdapter<*>
		val page = adapter?.getItemOrNull(pager.currentItem) ?: return@run null
		ReaderState(
			chapterId = page.chapterId,
			page = page.index,
			scroll = 0,
		)
	}

	protected open fun onWheelScroll(axisValue: Float) {
		switchPageBy(-axisValue.sign.toInt())
	}

	protected open fun onCreateAdvancedTransformer(): PageTransformer = PageAnimTransformer()

	protected open fun onInitPager(pager: ViewPager2) {
		pager.offscreenPageLimit = 1
		pager.recyclerView?.apply {
			itemAnimator = null
			setHasFixedSize(true)
			setItemViewCacheSize(2)
		}
	}

	protected open fun notifyPageChanged(page: Int) {
		viewModel.onCurrentPageChanged(page, page)
	}

	companion object {

		const val SMOOTH_SCROLL_LIMIT = 3
		private const val LOG_TAG = "ReaderDebug"
	}
}
