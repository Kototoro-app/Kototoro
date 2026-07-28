package org.skepsun.kototoro.reader.ui.compose

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.reader.ui.ReaderErrorHost
import org.skepsun.kototoro.reader.ui.ReaderNavigator
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.reader.ui.ReaderViewModel
import org.skepsun.kototoro.reader.ui.ReaderActionsUiState
import org.skepsun.kototoro.reader.ui.resolveReaderCurrentPagePosition
import org.skepsun.kototoro.reader.ui.resolveReaderInitialPagePosition
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_CHAPTERS

/** Activity-owned Compose reader surface. It replaces the mode-specific Fragment hosts. */
internal class ComposeReaderController(
	private val lifecycleOwner: LifecycleOwner,
	private val viewModel: ReaderViewModel,
	private val imagePipeline: DefaultComposeReaderImagePipeline,
	private val errorHost: ReaderErrorHost,
	private val chromeCallbacks: ComposeReaderChromeCallbacks,
	private val chaptersPanelContent: @Composable (Int) -> Unit = {},
) : ReaderNavigator {

	private var currentPageKey: Long? = null
	private var currentInternalScroll by mutableIntStateOf(viewModel.getCurrentState()?.scroll ?: 0)
	private var requestedPosition by mutableIntStateOf(NO_REQUEST)
	private var requestedPositionSmooth by mutableStateOf(false)
	private var scrollRequest: ComposeReaderScrollRequest? by mutableStateOf(null)
	private var zoomCommand: ComposeReaderZoomCommand? by mutableStateOf(null)
	private var webtoonZoomCommand: ComposeWebtoonZoomCommand? by mutableStateOf(null)
	var readerMode by mutableStateOf(viewModel.readerMode.value ?: ReaderMode.STANDARD)
		private set
	private var isDoublePage by mutableStateOf(false)
	private var layoutGeneration by mutableIntStateOf(0)
	val readerLayoutGeneration: Int
		get() = layoutGeneration
	private var chromeState by mutableStateOf(ComposeReaderChromeState(controlsVisible = false))
	private var chaptersTabId by mutableIntStateOf(DETAILS_TAB_CHAPTERS)
	private var selectionDialog by mutableStateOf<ReaderSelectionDialogState?>(null)
	private var isChromeEnabled = false
	private var areControlsVisible = true
	private var nextCommandId = 0L
	private var nextMessageId = 0L
	private var messageAction: (() -> Unit)? = null
	private var lastLayoutAnchor: ReaderState? = null

	@Composable
	fun Content(showControlLabels: Boolean) {
		ComposeReaderActivityScaffold(
					state = chromeState,
					showControlLabels = showControlLabels,
					chaptersPanelContent = { chaptersPanelContent(chaptersTabId) },
					translationTaskPanelContent = {
						if (chromeState.translationTaskPanelVisible) {
							ComposeTranslationTaskPanel(viewModel = viewModel, onDismiss = ::hideTranslationTaskPanel)
						}
					},
					callbacks = chromeCallbacks.copy(
						onZoomIn = ::onZoomIn,
						onZoomOut = ::onZoomOut,
						onMessageExpired = ::hideMessage,
						onMessageAction = ::performMessageAction,
						options = chromeCallbacks.options.copy(onDismiss = ::hideOptions),
						onPrimaryDestination = { destination ->
							when {
								destination == org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination.DISPLAY &&
									chromeState.options.visible -> hideOptions()
								destination == org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination.TOOLS &&
									chromeState.toolsVisible -> hideTools()
								else -> chromeCallbacks.onPrimaryDestination(destination)
							}
						},
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
						layoutGeneration = readerLayoutGeneration,
						shouldAcceptReaderPosition = { position -> shouldAcceptPosition(position) },
						onShowErrorDetails = errorHost::showReaderErrorDetails,
						onRetryError = errorHost::resolveReaderError,
						resolveErrorStringId = errorHost::getReaderErrorActionStringId,
						onReaderPositionChanged = positionChanged@ { position, internalScroll ->
							val pendingPosition = requestedPosition.takeIf { it != NO_REQUEST }
							val statePosition = resolveReaderInitialPagePosition(
								viewModel.content.value.pages,
								viewModel.getCurrentState(),
							)
							if (pendingPosition == null && currentPageKey == null && position != statePosition) {
								Log.d(
									READER_DEBUG_TAG,
									"Ignore stale initial page callback position=$position statePosition=$statePosition " +
										"state=${viewModel.getCurrentState()}",
								)
								return@positionChanged
							}
							Log.d(
								READER_DEBUG_TAG,
								"positionCallback mode=$readerMode double=$isDoublePage position=$position " +
									"pending=$pendingPosition currentKey=$currentPageKey state=${viewModel.getCurrentState()} " +
									"pages=${viewModel.content.value.pages.size}",
							)
							if (!shouldAcceptReaderPosition(position, pendingPosition)) {
								Log.d(
									READER_DEBUG_TAG,
									"Ignore transitional page callback position=$position pending=$pendingPosition",
								)
								return@positionChanged
							}
							currentPageKey = viewModel.content.value.pages.getOrNull(position)?.readerKey
							currentInternalScroll = internalScroll
							if (pendingPosition != null && kotlin.math.abs(position - pendingPosition) <= 1) {
				requestedPosition = NO_REQUEST
			}
						},
						onReaderInternalScrollChanged = { pageKey, internalScroll ->
							if (pageKey == currentPageKey) {
								currentInternalScroll = internalScroll
							}
						},
					)
				}
		selectionDialog?.let { state ->
			ComposeReaderSelectionDialog(state = state, onDismiss = ::hideSelectionDialog)
		}
		}

	fun updateConfiguration(mode: ReaderMode, doublePage: Boolean) {
		applyReaderLayout(mode, doublePage)
	}

	fun setDoublePageEnabled(enabled: Boolean) {
		val effectiveMode = viewModel.readerMode.value ?: readerMode
		val state = viewModel.getCurrentState()
		if (readerMode == effectiveMode && isDoublePage == enabled && currentPageKey == null &&
			state != null && state != lastLayoutAnchor
		) {
			requestedPosition = resolveReaderInitialPagePosition(viewModel.content.value.pages, state)
			requestedPositionSmooth = false
			lastLayoutAnchor = state
			layoutGeneration++
			Log.d(READER_DEBUG_TAG, "resyncReaderLayout state=$state requested=$requestedPosition generation=$layoutGeneration")
			return
		}
		applyReaderLayout(effectiveMode, enabled)
	}

	private fun applyReaderLayout(mode: ReaderMode, doublePage: Boolean) {
		val nextDoublePage = doublePage && mode != ReaderMode.WEBTOON && mode != ReaderMode.VERTICAL
		if (readerMode == mode && isDoublePage == nextDoublePage) return
		val anchorPosition = resolveCurrentPosition()
		val anchorState = getCurrentState()
		Log.d(
			READER_DEBUG_TAG,
			"applyReaderLayout from=$readerMode/$isDoublePage to=$mode/$nextDoublePage " +
				"anchorPosition=$anchorPosition anchorState=$anchorState currentKey=$currentPageKey " +
				"requested=$requestedPosition contentState=${viewModel.getCurrentState()}",
		)
		if (anchorState != null) {
			lastLayoutAnchor = anchorState
			requestedPosition = anchorPosition
			requestedPositionSmooth = false
		}
		readerMode = mode
		isDoublePage = nextDoublePage
		layoutGeneration++
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

	fun showMessage(
		text: CharSequence,
		durationMillis: Long? = null,
		actionLabel: String? = null,
		onAction: (() -> Unit)? = null,
	) {
		messageAction = onAction
		chromeState = chromeState.copy(
			message = ReaderMessage(++nextMessageId, text.toString(), durationMillis, actionLabel),
		)
	}

	fun hideMessage(id: Long? = null) {
		if (id == null || chromeState.message?.id == id) {
			messageAction = null
			chromeState = chromeState.copy(message = null)
		}
	}

	private fun performMessageAction() {
		val action = messageAction
		hideMessage()
		action?.invoke()
	}

	fun updateAutoScroll(transform: ReaderAutoScrollUiState.() -> ReaderAutoScrollUiState) {
		chromeState = chromeState.copy(autoScroll = chromeState.autoScroll.transform())
	}

	fun updateActions(transform: ReaderActionsUiState.() -> ReaderActionsUiState) {
		chromeState = chromeState.copy(actions = chromeState.actions.transform())
	}

	fun showOptions(state: ComposeReaderOptionsState) {
		chromeState = chromeState.copy(
			options = state.copy(visible = true),
			toolsVisible = false,
			chaptersVisible = false,
		)
	}

	fun showTools() {
		chromeState = chromeState.copy(
			options = chromeState.options.copy(visible = false),
			toolsVisible = true,
			chaptersVisible = false,
		)
	}

	fun showTranslationTaskPanel() {
		chromeState = chromeState.copy(translationTaskPanelVisible = true)
		hideTools()
	}

	fun showSelectionDialog(
		title: String,
		entries: List<String>,
		selectedIndex: Int? = null,
		onSelected: (Int) -> Unit,
	) {
		selectionDialog = ReaderSelectionDialogState(
			title = title,
			entries = entries,
			selectedIndex = selectedIndex,
			onSelected = { index ->
				hideSelectionDialog()
				onSelected(index)
			},
		)
	}

	private fun hideSelectionDialog() {
		selectionDialog = null
	}

	private fun hideTranslationTaskPanel() {
		chromeState = chromeState.copy(translationTaskPanelVisible = false)
	}

	fun closeExpandedPanel(): Boolean {
		return when {
			chromeState.translationTaskPanelVisible -> {
				hideTranslationTaskPanel()
				true
			}
			chromeState.options.visible -> {
				hideOptions()
				true
			}
			chromeState.toolsVisible -> {
				hideTools()
				true
			}
			chromeState.chaptersVisible -> {
				hideChapters()
				true
			}
			chromeState.autoScroll.visible -> {
				updateAutoScroll { copy(visible = false) }
				true
			}
			else -> false
		}
	}

	fun closeChrome(): Boolean {
		val isVisible = chromeState.controlsVisible ||
			chromeState.translationTaskPanelVisible ||
			chromeState.options.visible ||
			chromeState.toolsVisible ||
			chromeState.chaptersVisible ||
			chromeState.autoScroll.visible
		if (!isVisible) return false
		areControlsVisible = false
		chromeState = chromeState.copy(
			controlsVisible = false,
			translationTaskPanelVisible = false,
			options = chromeState.options.copy(visible = false),
			toolsVisible = false,
			chaptersVisible = false,
			autoScroll = chromeState.autoScroll.copy(visible = false),
		)
		return true
	}

	val isChromeControlsVisible: Boolean
		get() = chromeState.controlsVisible

	fun toggleChapters(defaultTab: Int = DETAILS_TAB_CHAPTERS) {
		chromeState = if (chromeState.chaptersVisible) {
			chromeState.copy(chaptersVisible = false)
		} else {
			chaptersTabId = defaultTab
			chromeState.copy(
				chaptersVisible = true,
				options = chromeState.options.copy(visible = false),
				toolsVisible = false,
			)
		}
	}

	fun hideChapters() {
		chromeState = chromeState.copy(chaptersVisible = false)
	}

	private fun hideTools() {
		chromeState = chromeState.copy(toolsVisible = false)
	}

	fun updateOptions(transform: ComposeReaderOptionsState.() -> ComposeReaderOptionsState) {
		chromeState = chromeState.copy(options = chromeState.options.transform())
	}

	private fun hideOptions() {
		chromeState = chromeState.copy(options = chromeState.options.copy(visible = false))
	}

	override val isReaderResumed: Boolean
		get() = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

	override fun switchPageBy(delta: Int) {
		val pageStep = if (isDoublePage) 2 else 1
		val direction = if (readerMode == ReaderMode.REVERSED) -1 else 1
		switchPageTo(
			position = resolvePageNavigationTarget(resolveCurrentPosition(), delta, pageStep, direction),
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
		val page = viewModel.content.value.pages.getOrNull(resolveCurrentPosition())
			?: return viewModel.getCurrentState()
		return ReaderState(page.chapterId, page.index, currentInternalScroll)
	}

	private fun resolveCurrentPosition(): Int {
		val pages = viewModel.content.value.pages
		return resolveReaderCurrentPagePosition(pages, currentPageKey, viewModel.getCurrentState())
	}

	private fun shouldAcceptPosition(position: Int): Boolean {
		val pendingPosition = requestedPosition.takeIf { it != NO_REQUEST }
		if (!shouldAcceptReaderPosition(position, pendingPosition)) return false
		if (pendingPosition == null && currentPageKey == null) {
			val statePosition = resolveReaderInitialPagePosition(
				viewModel.content.value.pages,
				viewModel.getCurrentState(),
			)
			return position == statePosition
		}
		return true
	}

	override fun onZoomIn() = issueZoomCommand(1.1f)

	override fun onZoomOut() = issueZoomCommand(0.9f)

	private fun issueZoomCommand(factor: Float) {
		if (readerMode == ReaderMode.WEBTOON) {
			webtoonZoomCommand = ComposeWebtoonZoomCommand(++nextCommandId, factor)
			return
		}
		val page = viewModel.content.value.pages.getOrNull(resolveCurrentPosition()) ?: return
		zoomCommand = ComposeReaderZoomCommand(++nextCommandId, page.readerKey, factor)
	}

	private companion object {
		const val NO_REQUEST = -1
		const val READER_DEBUG_TAG = "ReaderDebug"
	}
}

internal fun shouldAcceptReaderPosition(position: Int, requestedPosition: Int?): Boolean {
	// A double-page settled callback reports the selected page in the spread,
	// which can be the neighbour of the requested anchor (usually the lower
	// page). Accept that callback so the transition request cannot remain
	// pending forever and block all later page callbacks.
	return requestedPosition == null || kotlin.math.abs(position - requestedPosition) <= 1
}
