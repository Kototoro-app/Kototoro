package org.skepsun.kototoro.reader.ui.compose

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.reader.ui.ReaderErrorHost
import org.skepsun.kototoro.reader.ui.ReaderNavigator
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.reader.ui.ReaderViewModel
import org.skepsun.kototoro.reader.ui.ReaderActionsUiState
import org.skepsun.kototoro.reader.ui.resolveReaderCurrentPagePosition
import org.skepsun.kototoro.reader.ui.resolveReaderInitialPagePosition
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_CHAPTERS
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionUiState

/** Activity-owned Compose reader surface. It replaces the mode-specific Fragment hosts. */
internal class ComposeReaderController(
	private val lifecycleOwner: LifecycleOwner,
	private val viewModel: ReaderViewModel,
	private val imagePipeline: DefaultComposeReaderImagePipeline,
	private val errorHost: ReaderErrorHost,
	private val chromeCallbacks: ComposeReaderChromeCallbacks,
	private val chaptersPanelContent: @Composable (
		Int,
		ReaderChapterPanelUiState,
		(ChapterSelectionUiState?) -> Unit,
	) -> Unit = { _, _, _ -> },
) : ReaderNavigator {

	private var currentPageKey: Long? = null
	private var currentInternalScroll by mutableIntStateOf(viewModel.getCurrentState()?.scroll ?: 0)
	private var requestedPageKey by mutableStateOf<Long?>(null)
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
		val infoBarEmbedded = readerMode != ReaderMode.WEBTOON
		val systemStatus = if (infoBarEmbedded) rememberReaderSystemStatus() else null
		ComposeReaderActivityScaffold(
					state = chromeState,
					showControlLabels = showControlLabels,
					infoBarEmbedded = infoBarEmbedded,
					chapterPanelTabId = chaptersTabId,
					chaptersPanelContent = { selectedTabId, panelState, onSelectionStateChange ->
						chaptersPanelContent(selectedTabId, panelState, onSelectionStateChange)
					},
					translationTaskPanelContent = {
						ComposeTranslationTaskPanelContent(viewModel = viewModel, modifier = Modifier.fillMaxSize())
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
						requestedPageKey = requestedPageKey,
						requestedPageSmooth = requestedPositionSmooth,
						webtoonScrollRequest = scrollRequest,
						zoomCommand = zoomCommand,
						webtoonZoomCommand = webtoonZoomCommand,
						isDoublePage = isDoublePage,
						layoutGeneration = readerLayoutGeneration,
						pageOverlay = {
							systemStatus?.let {
									ReaderPageInfoBar(
										state = chromeState.infoBar,
										controlsVisible = chromeState.controlsVisible,
										systemStatus = it,
									)
								}
							},
							shouldAcceptReaderPosition = { position -> shouldAcceptPosition(position) },
							shouldAcceptReaderPageKey = { pageKey -> shouldAcceptPageKey(pageKey) },
						onShowErrorDetails = errorHost::showReaderErrorDetails,
						onRetryError = errorHost::resolveReaderError,
						resolveErrorStringId = errorHost::getReaderErrorActionStringId,
							onReaderPositionChanged = positionChanged@ { position, internalScroll ->
							val pendingPosition = resolveRequestedPosition()
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
								requestedPageKey = null
							}
							},
							onReaderPageKeyChanged = { pageKey, internalScroll ->
								currentPageKey = pageKey
								currentInternalScroll = internalScroll
								val pendingPageKey = requestedPageKey
								if (pendingPageKey != null && areReaderPageKeysAdjacent(pageKey, pendingPageKey)) {
									requestedPageKey = null
								}
							},
						onReaderInternalScrollChanged = { pageKey, internalScroll ->
							if (pageKey == currentPageKey) {
								currentInternalScroll = internalScroll
							}
						},
						onReaderInteraction = chromeCallbacks.onReaderInteraction,
						onGridTap = chromeCallbacks.onGridTap,
						onGridLongTap = chromeCallbacks.onGridLongTap,
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
			requestPagePosition(resolveReaderInitialPagePosition(viewModel.content.value.pages, state), smooth = false)
			lastLayoutAnchor = state
			layoutGeneration++
			Log.d(READER_DEBUG_TAG, "resyncReaderLayout state=$state requestedKey=$requestedPageKey generation=$layoutGeneration")
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
				"requestedKey=$requestedPageKey contentState=${viewModel.getCurrentState()}",
		)
		if (anchorState != null) {
			lastLayoutAnchor = anchorState
			requestPagePosition(anchorPosition, smooth = false)
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

	fun showAutoScroll() {
		chromeState = chromeState.copy(
			autoScroll = chromeState.autoScroll.copy(visible = true),
			options = chromeState.options.copy(visible = false),
			toolsVisible = false,
			chaptersVisible = false,
		)
	}

	fun toggleAutoScroll() {
		if (chromeState.autoScroll.visible) {
			updateAutoScroll { copy(visible = false) }
		} else {
			showAutoScroll()
		}
	}

	fun updateActions(transform: ReaderActionsUiState.() -> ReaderActionsUiState) {
		chromeState = chromeState.copy(actions = chromeState.actions.transform())
	}

	fun updateChapterPanel(transform: ReaderChapterPanelUiState.() -> ReaderChapterPanelUiState) {
		chromeState = chromeState.copy(chapterPanel = chromeState.chapterPanel.transform())
	}

	fun toggleChapterSearch() {
		val nextVisible = !chromeState.chapterPanel.searchVisible
		updateChapterPanel { copy(searchVisible = nextVisible) }
		if (!nextVisible) viewModel.performChapterSearch(null)
	}

	fun selectChaptersTab(tabId: Int) {
		chaptersTabId = tabId
		if (tabId != DETAILS_TAB_CHAPTERS) {
			updateChapterPanel { copy(searchVisible = false) }
			viewModel.performChapterSearch(null)
		}
	}

	fun showOptions(state: ComposeReaderOptionsState) {
		val cachedPreview = imagePipeline.cachedDisplay(currentPageKey)?.toString()
		chromeState = chromeState.copy(
			options = state.copy(
				visible = true,
				appearancePreviewOriginalUri = cachedPreview,
				appearancePreviewProcessedUri = cachedPreview,
			),
			autoScroll = chromeState.autoScroll.copy(visible = false),
			toolsVisible = false,
			chaptersVisible = false,
		)
	}

	fun refreshAppearancePreview() {
		val page = viewModel.content.value.pages.firstOrNull { it.readerKey == currentPageKey } ?: return
		updateOptions { copy(appearancePreviewLoading = true) }
		lifecycleOwner.lifecycleScope.launch {
			val result = imagePipeline.observe(page, force = true).first {
				it is ComposeReaderImageState.OriginalReady || it is ComposeReaderImageState.Failed
			}
			updateOptions {
				val resultUri = when (result) {
					is ComposeReaderImageState.OriginalReady -> result.original.toString()
					is ComposeReaderImageState.Failed -> result.original?.toString()
					else -> null
				}
				copy(
					appearancePreviewOriginalUri = appearancePreviewOriginalUri ?: resultUri,
					appearancePreviewProcessedUri = resultUri ?: appearancePreviewProcessedUri,
					appearancePreviewLoading = false,
				)
			}
		}
	}

	fun showTools() {
		chromeState = chromeState.copy(
			options = chromeState.options.copy(visible = false),
			autoScroll = chromeState.autoScroll.copy(visible = false),
			toolsVisible = true,
			chaptersVisible = false,
		)
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

	fun closeExpandedPanel(): Boolean {
		return when {
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
			chromeState.options.visible ||
			chromeState.toolsVisible ||
			chromeState.chaptersVisible ||
			chromeState.autoScroll.visible
		if (!isVisible) return false
		areControlsVisible = false
		chromeState = chromeState.copy(
			controlsVisible = false,
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
				autoScroll = chromeState.autoScroll.copy(visible = false),
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

	fun closeOptions(): Boolean {
		if (!chromeState.options.visible) return false
		hideOptions()
		return true
	}

	override val isReaderResumed: Boolean
		get() = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

	override fun switchPageBy(delta: Int) {
		val pages = viewModel.content.value.pages
		val basePosition = resolvePageNavigationBasePosition(
			pageKeys = pages.map { it.readerKey },
			requestedPageKey = requestedPageKey,
			settledPosition = resolveCurrentPosition(),
		)
		val targetPosition = if (isDoublePage) {
			resolveDoublePageNavigationTarget(
				displayItems = buildDoublePageDisplayItems(
					pages = pages,
					coverPage = viewModel.readerSettingsProducer.value.isReaderDoubleCoverPage,
				),
				currentPosition = basePosition,
				delta = delta,
			) ?: return
		} else {
			resolvePageNavigationTarget(basePosition, delta, pageStep = 1)
		}
		switchPageTo(
			position = targetPosition,
			smooth = true,
		)
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		requestPagePosition(position, smooth)
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
		val pendingPosition = resolveRequestedPosition()
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

	private fun shouldAcceptPageKey(pageKey: Long): Boolean {
		val pages = viewModel.content.value.pages
		val initialPageKey = viewModel.getCurrentState()?.let { state ->
			pages.firstOrNull { it.chapterId == state.chapterId && it.index == state.page }?.readerKey
		}
		return shouldAcceptReaderPageKey(
			pageKeys = pages.map { it.readerKey },
			pageKey = pageKey,
			requestedPageKey = requestedPageKey,
			currentPageKey = currentPageKey,
			initialPageKey = initialPageKey,
		)
	}

	private fun areReaderPageKeysAdjacent(firstPageKey: Long, secondPageKey: Long): Boolean {
		val pageKeys = viewModel.content.value.pages.map { it.readerKey }
		val firstPosition = pageKeys.indexOf(firstPageKey)
		val secondPosition = pageKeys.indexOf(secondPageKey)
		return firstPosition >= 0 && secondPosition >= 0 &&
			kotlin.math.abs(firstPosition - secondPosition) <= 1
	}

	private fun resolveRequestedPosition(): Int? {
		return resolvePageKeyPosition(
			pageKeys = viewModel.content.value.pages.map { it.readerKey },
			pageKey = requestedPageKey,
		)
	}

	private fun requestPagePosition(position: Int, smooth: Boolean) {
		val page = viewModel.content.value.pages.getOrNull(position) ?: return
		requestedPageKey = page.readerKey
		requestedPositionSmooth = smooth
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

internal fun shouldAcceptReaderPageKey(
	pageKeys: List<Long>,
	pageKey: Long,
	requestedPageKey: Long?,
	currentPageKey: Long?,
	initialPageKey: Long?,
): Boolean {
	val position = pageKeys.indexOf(pageKey)
	if (position < 0) return false
	if (requestedPageKey != null) {
		val requestedPosition = pageKeys.indexOf(requestedPageKey)
		return requestedPosition >= 0 && kotlin.math.abs(position - requestedPosition) <= 1
	}
	return currentPageKey != null || pageKey == initialPageKey
}

internal fun resolvePageKeyPosition(pageKeys: List<Long>, pageKey: Long?): Int? {
	if (pageKey == null) return null
	return pageKeys.indexOf(pageKey).takeIf { it >= 0 }
}

internal fun resolvePageNavigationBasePosition(
	pageKeys: List<Long>,
	requestedPageKey: Long?,
	settledPosition: Int,
): Int = resolvePageKeyPosition(pageKeys, requestedPageKey) ?: settledPosition
