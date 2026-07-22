package org.skepsun.kototoro.reader.ui.compose

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.reader.ui.ReaderErrorHost
import org.skepsun.kototoro.reader.ui.ReaderNavigator
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.reader.ui.ReaderViewModel
import org.skepsun.kototoro.reader.ui.ReaderActionsUiState

/** Activity-owned Compose reader surface. It replaces the mode-specific Fragment hosts. */
internal class ComposeReaderController(
	context: Context,
	private val lifecycleOwner: LifecycleOwner,
	private val viewModel: ReaderViewModel,
	private val imagePipeline: DefaultComposeReaderImagePipeline,
	private val errorHost: ReaderErrorHost,
	private val chromeCallbacks: ComposeReaderChromeCallbacks,
) : ReaderNavigator {

	val view = ComposeView(context)

	private var currentPosition by mutableIntStateOf(0)
	private var currentInternalScroll by mutableIntStateOf(0)
	private var requestedPosition by mutableIntStateOf(NO_REQUEST)
	private var requestedPositionSmooth by mutableStateOf(false)
	private var scrollRequest: ComposeReaderScrollRequest? by mutableStateOf(null)
	private var zoomCommand: ComposeReaderZoomCommand? by mutableStateOf(null)
	private var webtoonZoomCommand: ComposeWebtoonZoomCommand? by mutableStateOf(null)
	private var readerMode by mutableStateOf(ReaderMode.STANDARD)
	private var isDoublePage by mutableStateOf(false)
	private var chromeState by mutableStateOf(ComposeReaderChromeState(controlsVisible = false))
	private var isChromeEnabled = false
	private var areControlsVisible = true
	private var nextCommandId = 0L
	private var nextMessageId = 0L

	init {
		view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		view.setContent {
			KototoroTheme {
				ComposeReaderActivityScaffold(
					state = chromeState,
					callbacks = chromeCallbacks.copy(
						onZoomIn = ::onZoomIn,
						onZoomOut = ::onZoomOut,
						onMessageExpired = ::hideMessage,
					),
				) {
					ComposeReaderScreenRoot(
						viewModel = viewModel,
						imageLoader = imagePipeline.imageLoader,
						imagePipeline = imagePipeline,
						requestedPage = requestedPosition.takeIf { it != NO_REQUEST },
						requestedPageSmooth = requestedPositionSmooth,
						webtoonScrollRequest = scrollRequest,
						zoomCommand = zoomCommand,
						webtoonZoomCommand = webtoonZoomCommand,
						isDoublePage = isDoublePage,
						onShowErrorDetails = errorHost::showReaderErrorDetails,
						onRetryError = errorHost::resolveReaderError,
						resolveErrorStringId = errorHost::getReaderErrorActionStringId,
						onReaderPositionChanged = { position, internalScroll ->
							currentPosition = position
							currentInternalScroll = internalScroll
							requestedPosition = NO_REQUEST
						},
					)
				}
			}
		}
	}

	fun updateConfiguration(mode: ReaderMode, doublePage: Boolean) {
		readerMode = mode
		isDoublePage = doublePage && mode != ReaderMode.WEBTOON && mode != ReaderMode.VERTICAL
	}

	fun setChromeEnabled(enabled: Boolean) {
		isChromeEnabled = enabled
		chromeState = chromeState.copy(controlsVisible = enabled && areControlsVisible)
	}

	fun setControlsVisible(visible: Boolean) {
		areControlsVisible = visible
		chromeState = chromeState.copy(controlsVisible = isChromeEnabled && visible)
	}

	fun setLoadingVisible(visible: Boolean) {
		chromeState = chromeState.copy(loadingVisible = visible)
	}

	fun setTitle(title: String, subtitle: String) {
		chromeState = chromeState.copy(title = title, subtitle = subtitle)
	}

	fun setZoomVisible(visible: Boolean) {
		chromeState = chromeState.copy(zoomVisible = visible)
	}

	fun updateInfoBar(transform: ReaderInfoBarState.() -> ReaderInfoBarState) {
		chromeState = chromeState.copy(infoBar = chromeState.infoBar.transform())
	}

	fun showMessage(text: CharSequence, durationMillis: Long? = null) {
		chromeState = chromeState.copy(message = ReaderMessage(++nextMessageId, text.toString(), durationMillis))
	}

	fun hideMessage(id: Long? = null) {
		if (id == null || chromeState.message?.id == id) chromeState = chromeState.copy(message = null)
	}

	fun updateAutoScroll(transform: ReaderAutoScrollUiState.() -> ReaderAutoScrollUiState) {
		chromeState = chromeState.copy(autoScroll = chromeState.autoScroll.transform())
	}

	fun updateActions(transform: ReaderActionsUiState.() -> ReaderActionsUiState) {
		chromeState = chromeState.copy(actions = chromeState.actions.transform())
	}

	override val isReaderResumed: Boolean
		get() = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

	override fun switchPageBy(delta: Int) {
		val pageStep = if (isDoublePage) 2 else 1
		val direction = if (readerMode == ReaderMode.REVERSED) -1 else 1
		switchPageTo(
			position = resolvePageNavigationTarget(currentPosition, delta, pageStep, direction),
			smooth = true,
		)
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		if (position in viewModel.content.value.pages.indices) {
			requestedPosition = position
			requestedPositionSmooth = smooth
		}
	}

	override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
		if (readerMode != ReaderMode.WEBTOON) return false
		scrollRequest = ComposeReaderScrollRequest(++nextCommandId, delta, smooth)
		return true
	}

	override fun getCurrentState(): ReaderState? {
		val page = viewModel.content.value.pages.getOrNull(currentPosition) ?: return null
		return ReaderState(page.chapterId, page.index, currentInternalScroll)
	}

	override fun onZoomIn() = issueZoomCommand(1.1f)

	override fun onZoomOut() = issueZoomCommand(0.9f)

	private fun issueZoomCommand(factor: Float) {
		if (readerMode == ReaderMode.WEBTOON) {
			webtoonZoomCommand = ComposeWebtoonZoomCommand(++nextCommandId, factor)
			return
		}
		val page = viewModel.content.value.pages.getOrNull(currentPosition) ?: return
		zoomCommand = ComposeReaderZoomCommand(++nextCommandId, page.readerKey, factor)
	}

	private companion object {
		const val NO_REQUEST = -1
	}
}
