package org.skepsun.kototoro.reader.ui.pager

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.viewbinding.ViewBinding
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.ui.BaseFragment
import org.skepsun.kototoro.core.ui.widgets.ZoomControl
import org.skepsun.kototoro.core.util.ext.isAnimationsEnabled
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.reader.ui.ReaderViewModel

abstract class BaseReaderFragment<B : ViewBinding> : BaseFragment<B>(), ZoomControl.ZoomControlListener {

	protected val viewModel by viewModels<ReaderViewModel>(
		ownerProducer = { parentFragment ?: requireActivity() },
	)

	protected var readerAdapter: BaseReaderAdapter<*>? = null
		private set

	override fun onViewBindingCreated(binding: B, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		readerAdapter = onCreateAdapter()

		viewModel.content.observe(viewLifecycleOwner) {
			// 避免刚恢复列表时立即触发前/后章节自动加载
			viewModel.skipBoundaryLoadNext()
			// Determine which state to use for restoring position:
			// - content.state: explicitly set state (e.g., after mode switch or chapter change)
			// - getCurrentState(): current reading position saved in SavedStateHandle
			val currentState = viewModel.getCurrentState()
			val pendingState = when {
				// If content.state is null and we have pages, use getCurrentState
				it.state == null
					&& it.pages.isNotEmpty()
					&& readerAdapter?.hasItems != true -> currentState

				// use currentState only if it matches the current pages (to avoid the error message)
				readerAdapter?.hasItems != true
					&& it.state != currentState
					&& currentState != null
					&& it.pages.any { page -> page.chapterId == currentState.chapterId } -> currentState

				// 当已有列表且 content.state 为空（例如预加载上下章节），如果当前进度仍存在于新页列表，则继续使用当前进度
				it.state == null
					&& readerAdapter?.hasItems == true
					&& currentState != null
					&& it.pages.any { page -> page.chapterId == currentState.chapterId && page.index == currentState.page } -> currentState

			// Otherwise, use content.state (normal flow, mode switch, chapter change)
			else -> it.state
		}
			Log.d(
				LOG_TAG,
				"onContent: pages=${it.pages.size}, contentState=${it.state}, " +
					"currentState=$currentState, pendingState=$pendingState",
			)
			viewModel.beginTransientStateSuppression(pendingState)
			onPagesChanged(it.pages, pendingState)
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onPause() {
		super.onPause()
		saveReaderStateForLifecycle("onPause")
	}

	override fun onDestroyView() {
		Log.d(
			LOG_TAG,
			"onDestroyView: persist existing state only=${viewModel.getCurrentState()}",
		)
		viewModel.saveCurrentState()
		readerAdapter = null
		super.onDestroyView()
	}

	private fun saveReaderStateForLifecycle(source: String) {
		val activity = activity
		if (activity?.isChangingConfigurations == true) {
			Log.d(
				LOG_TAG,
				"$source: activity is changing configurations, keep existing state=${viewModel.getCurrentState()}",
			)
			viewModel.saveCurrentState()
			return
		}
		val state = getCurrentState()
		Log.d(LOG_TAG, "$source: saving fragment state=$state")
		viewModel.saveCurrentState(state)
	}

	protected fun requireAdapter() = checkNotNull(readerAdapter) {
		"Adapter was not created or already destroyed"
	}

	protected fun isAnimationEnabled(): Boolean {
		return context?.isAnimationsEnabled == true && viewModel.pageAnimation.value != ReaderAnimation.NONE
	}

	abstract fun switchPageBy(delta: Int)

	abstract fun switchPageTo(position: Int, smooth: Boolean)

	open fun scrollBy(delta: Int, smooth: Boolean): Boolean = false

	abstract fun getCurrentState(): ReaderState?

	protected abstract fun onCreateAdapter(): BaseReaderAdapter<*>

	protected abstract suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?)

	companion object {
		private const val LOG_TAG = "ReaderDebug"
	}
}
