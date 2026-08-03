package org.skepsun.kototoro.reader.ui.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import org.skepsun.kototoro.R
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_BOOKMARKS
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_CHAPTERS
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_PAGES
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionBar
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionUiState
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeGradient
import org.skepsun.kototoro.core.ui.compose.toTransparentImmersiveColor
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.reader.ui.ReaderActionsCallbacks
import org.skepsun.kototoro.reader.ui.ReaderActionsUiState
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlTokens
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressDock
import org.skepsun.kototoro.reader.ui.compose.design.readerControlContentColor
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import kotlin.math.roundToInt

private val ReaderTopImmersiveFeatherExtension = 72.dp
private val ReaderBottomImmersiveFeatherExtension = 48.dp
private val ReaderTopImmersiveStops = listOf(0f, 0.24f, 0.50f, 0.70f, 0.86f, 1f)
private val ReaderBottomImmersiveStops = listOf(0f, 0.18f, 0.38f, 0.70f, 1f)

@Immutable
internal data class ComposeReaderChromeState(
	val controlsVisible: Boolean = true,
	val loadingVisible: Boolean = false,
	val title: String = "",
	val subtitle: String = "",
	val zoomVisible: Boolean = false,
	val infoBar: ReaderInfoBarState = ReaderInfoBarState(),
	val message: ReaderMessage? = null,
	val autoScroll: ReaderAutoScrollUiState = ReaderAutoScrollUiState(),
	val actions: ReaderActionsUiState = ReaderActionsUiState(),
	val options: ComposeReaderOptionsState = ComposeReaderOptionsState(),
	val toolsVisible: Boolean = false,
	val chaptersVisible: Boolean = false,
	val chapterPanel: ReaderChapterPanelUiState = ReaderChapterPanelUiState(),
)

@Immutable
internal data class ReaderChapterPanelUiState(
	val searchEnabled: Boolean = true,
	val searchVisible: Boolean = false,
	val searchQuery: String = "",
	val chaptersReversed: Boolean = false,
	val chaptersInGridView: Boolean = false,
	val hideReadChapters: Boolean = false,
	val mergeRepeatedChapters: Boolean = false,
	val showMergeRepeatedChapters: Boolean = false,
	val downloadedOnly: Boolean = false,
	val downloadedFilterVisible: Boolean = false,
)

@Immutable
internal data class ReaderInfoBarState(
	val visible: Boolean = false,
	val text: String = "",
	val showSystemStatus: Boolean = true,
	val drawBackground: Boolean = false,
	val darkContent: Boolean = false,
)

@Immutable
internal data class ReaderMessage(
	val id: Long,
	val text: String,
	val durationMillis: Long?,
	val actionLabel: String? = null,
)

@Immutable
internal data class ReaderAutoScrollUiState(
	val visible: Boolean = false,
	val active: Boolean = false,
	val manuallyPaused: Boolean = false,
	val speed: Float = 0.5f,
	val fabVisible: Boolean = false,
	val pauseOnUi: Boolean = true,
	val showPageDelay: Boolean = false,
	val pageDelaySeconds: Long = 0L,
)

internal data class ReaderAutoScrollCallbacks(
	val onOpen: () -> Unit = {},
	val onClose: () -> Unit = {},
	val onActiveChanged: (Boolean) -> Unit = {},
	val onPausedChanged: (Boolean) -> Unit = {},
	val onSpeedChanged: (Float) -> Unit = {},
	val onFabChanged: (Boolean) -> Unit = {},
	val onPauseOnUiChanged: (Boolean) -> Unit = {},
)

internal data class ComposeReaderChromeCallbacks(
	val onNavigateBack: () -> Unit = {},
	val onZoomIn: () -> Unit = {},
	val onZoomOut: () -> Unit = {},
	val onMessageExpired: (Long) -> Unit = {},
	val onMessageAction: () -> Unit = {},
	val autoScroll: ReaderAutoScrollCallbacks = ReaderAutoScrollCallbacks(),
	val actions: ReaderActionsCallbacks = ReaderActionsCallbacks(),
	val onReaderInteraction: () -> Unit = {},
	val onGridTap: (TapGridArea) -> Unit = {},
	val onGridLongTap: (TapGridArea, Offset, IntSize) -> Unit = { _, _, _ -> },
	val onBackPressed: () -> Unit = {},
	val options: ComposeReaderOptionsCallbacks = ComposeReaderOptionsCallbacks(),
	val chapterPanel: ReaderChapterPanelCallbacks = ReaderChapterPanelCallbacks(),
	val onPrimaryDestination: (ReaderControlDestination) -> Unit = {},
	val onPrimaryDestinationLongPress: (ReaderControlDestination) -> Unit = {},
)

internal data class ReaderChapterPanelCallbacks(
	val onTabSelected: (Int) -> Unit = {},
	val onSearchToggle: () -> Unit = {},
	val onSearchQueryChange: (String) -> Unit = {},
	val onToggleChaptersReversed: () -> Unit = {},
	val onToggleChaptersGrid: () -> Unit = {},
	val onToggleHideReadChapters: () -> Unit = {},
	val onToggleMergeRepeatedChapters: () -> Unit = {},
	val onToggleDownloadedOnly: () -> Unit = {},
)

@Composable
private fun ReaderChapterPanelToolbar(
	selectedTabId: Int,
	isFullyExpanded: Boolean,
	state: ReaderChapterPanelUiState,
	callbacks: ReaderChapterPanelCallbacks,
) {
	var moreMenuExpanded by remember { mutableStateOf(false) }
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(52.dp)
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		ReaderChapterPanelTab(
			selected = selectedTabId == DETAILS_TAB_CHAPTERS,
			iconResId = R.drawable.ic_list,
			contentDescription = stringResource(R.string.chapters),
			onClick = { callbacks.onTabSelected(DETAILS_TAB_CHAPTERS) },
		)
		ReaderChapterPanelTab(
			selected = selectedTabId == DETAILS_TAB_PAGES,
			iconResId = R.drawable.ic_grid,
			contentDescription = stringResource(R.string.pages),
			onClick = { callbacks.onTabSelected(DETAILS_TAB_PAGES) },
		)
		ReaderChapterPanelTab(
			selected = selectedTabId == DETAILS_TAB_BOOKMARKS,
			iconResId = R.drawable.ic_bookmark,
			contentDescription = stringResource(R.string.bookmarks),
			onClick = { callbacks.onTabSelected(DETAILS_TAB_BOOKMARKS) },
		)

		Box(modifier = Modifier.weight(1f))

		AnimatedVisibility(visible = isFullyExpanded && selectedTabId == DETAILS_TAB_CHAPTERS) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				IconButton(
					onClick = callbacks.onSearchToggle,
					enabled = state.searchEnabled,
					modifier = Modifier.size(44.dp),
				) {
					Icon(
						imageVector = Icons.Default.Search,
						contentDescription = stringResource(R.string.search_chapters),
						tint = if (state.searchVisible) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
					)
				}
				Box {
					IconButton(
						onClick = { moreMenuExpanded = true },
						modifier = Modifier.size(44.dp),
					) {
						Icon(
							imageVector = Icons.Default.MoreVert,
							contentDescription = stringResource(R.string.options),
						)
					}
					ReaderChapterPanelMoreMenu(
						expanded = moreMenuExpanded,
						state = state,
						callbacks = callbacks,
						onDismissRequest = { moreMenuExpanded = false },
					)
				}
			}
		}
	}
}

@Composable
private fun ReaderChapterPanelTab(
	selected: Boolean,
	iconResId: Int,
	contentDescription: String,
	onClick: () -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = if (selected) {
			MaterialTheme.colorScheme.surfaceContainerHigh
		} else {
			Color.Transparent
		},
	) {
		IconButton(
			onClick = onClick,
			modifier = Modifier.size(44.dp),
		) {
			Icon(
				painter = painterResource(iconResId),
				contentDescription = contentDescription,
				tint = if (selected) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}
	}
}

@Composable
private fun ReaderChapterPanelMoreMenu(
	expanded: Boolean,
	state: ReaderChapterPanelUiState,
	callbacks: ReaderChapterPanelCallbacks,
	onDismissRequest: () -> Unit,
) {
	GlassDropdownMenu(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
	) {
		ReaderChapterPanelMenuItem(
			text = stringResource(R.string.search_chapters),
			selected = state.searchVisible,
			enabled = state.searchEnabled,
			onClick = {
				onDismissRequest()
				callbacks.onSearchToggle()
			},
		)
		ReaderChapterPanelMenuItem(
			text = stringResource(R.string.reverse),
			selected = state.chaptersReversed,
			onClick = {
				onDismissRequest()
				callbacks.onToggleChaptersReversed()
			},
		)
		ReaderChapterPanelMenuItem(
			text = stringResource(R.string.chapters_grid_view),
			selected = state.chaptersInGridView,
			onClick = {
				onDismissRequest()
				callbacks.onToggleChaptersGrid()
			},
		)
		ReaderChapterPanelMenuItem(
			text = stringResource(R.string.hide_read_chapters),
			selected = state.hideReadChapters,
			onClick = {
				onDismissRequest()
				callbacks.onToggleHideReadChapters()
			},
		)
		if (state.showMergeRepeatedChapters) {
			ReaderChapterPanelMenuItem(
				text = stringResource(R.string.merge_repeated_chapters),
				selected = state.mergeRepeatedChapters,
				onClick = {
					onDismissRequest()
					callbacks.onToggleMergeRepeatedChapters()
				},
			)
		}
		if (state.downloadedFilterVisible) {
			ReaderChapterPanelMenuItem(
				text = stringResource(R.string.downloaded),
				selected = state.downloadedOnly,
				onClick = {
					onDismissRequest()
					callbacks.onToggleDownloadedOnly()
				},
			)
		}
	}
}

@Composable
private fun ReaderChapterPanelMenuItem(
	text: String,
	selected: Boolean,
	enabled: Boolean = true,
	onClick: () -> Unit,
) {
	CompactDropdownMenuItem(
		text = { Text(text) },
		leadingIcon = {
			if (selected) {
				Icon(
					imageVector = Icons.Default.Check,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(18.dp),
				)
			}
		},
		enabled = enabled,
		onClick = onClick,
	)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeReaderActivityScaffold(
	state: ComposeReaderChromeState,
	callbacks: ComposeReaderChromeCallbacks,
	showControlLabels: Boolean,
	infoBarEmbedded: Boolean = false,
	modifier: Modifier = Modifier,
	chapterPanelTabId: Int = DETAILS_TAB_CHAPTERS,
	chaptersPanelContent: @Composable (Int, ReaderChapterPanelUiState, (ChapterSelectionUiState?) -> Unit) -> Unit =
		{ _, _, _ -> },
	translationTaskPanelContent: @Composable () -> Unit = {},
	content: @Composable () -> Unit,
) {
	var chapterSelectionState by remember { mutableStateOf<ChapterSelectionUiState?>(null) }
	BackHandler { callbacks.onBackPressed() }
	val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
	val immersiveBaseColor = if (isSystemInDarkTheme()) Color.Black else Color.White
	val immersiveTransparent = immersiveBaseColor.toTransparentImmersiveColor()
	val topImmersiveHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 76.dp
	val bottomImmersiveHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 84.dp
	val readerBackdrop = if (isIosStyle) {
		rememberLayerBackdrop { drawContent() }
	} else {
		null
	}
	CompositionLocalProvider(
		LocalLiquidGlassBackdrop provides readerBackdrop,
		LocalLiquidGlassLayerBackdrop provides readerBackdrop,
	) {
		Box(modifier = modifier.fillMaxSize()) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.then(readerBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
		) {
			content()
		}

		AnimatedVisibility(
			visible = state.controlsVisible,
			enter = fadeIn(),
			exit = fadeOut(),
			modifier = Modifier.fillMaxSize(),
		) {
			Box(modifier = Modifier.fillMaxSize()) {
				ImmersiveEdgeGradient(
					height = topImmersiveHeight + ReaderTopImmersiveFeatherExtension,
					colors = listOf(
						immersiveBaseColor.copy(alpha = 0.86f),
						immersiveBaseColor.copy(alpha = 0.62f),
						immersiveBaseColor.copy(alpha = 0.32f),
						immersiveBaseColor.copy(alpha = 0.12f),
						immersiveBaseColor.copy(alpha = 0.035f),
						immersiveTransparent,
					),
					stops = ReaderTopImmersiveStops,
					modifier = Modifier
						.align(Alignment.TopCenter)
						.fillMaxWidth(),
				)
				ImmersiveEdgeGradient(
					height = bottomImmersiveHeight + ReaderBottomImmersiveFeatherExtension,
					colors = listOf(
						immersiveTransparent,
						immersiveBaseColor.copy(alpha = 0.035f),
						immersiveBaseColor.copy(alpha = 0.16f),
						immersiveBaseColor.copy(alpha = 0.42f),
						immersiveBaseColor.copy(alpha = 0.78f),
					),
					stops = ReaderBottomImmersiveStops,
					modifier = Modifier
						.align(Alignment.BottomCenter)
						.fillMaxWidth(),
				)
			}
		}

		AnimatedVisibility(
			visible = state.controlsVisible,
			// Alpha animations create an offscreen layer that clips Backdrop shadows to these bounds.
			enter = slideInVertically { -it },
			exit = slideOutVertically { -it },
			modifier = Modifier.align(Alignment.TopCenter),
		) {
			ReaderComposeTopBar(
				state = state,
				onNavigateBack = callbacks.onNavigateBack,
				onChapters = callbacks.actions.onPages,
				onOptions = callbacks.actions.onOptions,
			)
		}

		AnimatedVisibility(
			visible = state.infoBar.visible && !state.controlsVisible && !infoBarEmbedded,
			enter = fadeIn(
				animationSpec = tween(
					durationMillis = 140,
					delayMillis = 160,
				),
			),
			exit = fadeOut(animationSpec = tween(durationMillis = 80)),
			modifier = Modifier.align(Alignment.TopCenter),
		) {
			ReaderComposeInfoBar(state.infoBar)
		}

		AnimatedVisibility(
			visible = state.controlsVisible && state.actions.sliderEnabled,
			// Alpha transitions clip the rounded Backdrop shadow to a rectangular layer.
			enter = slideInVertically { it },
			exit = slideOutVertically { it },
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(horizontal = 12.dp, vertical = 4.dp),
		) {
			ReaderProgressDock(isIosStyle = isIosStyle) {
				ReaderProgressControl(
					state = state.actions,
					callbacks = callbacks.actions,
					isIosStyle = isIosStyle,
				)
			}
		}

		val floatingControls = resolveReaderFloatingControls(
			configured = state.actions.controls,
			translationAvailable = state.actions.translateRequestedVisible,
			translationContextualVisible = state.actions.translateContextualVisible,
		)
		val floatingControlExitOffset = with(LocalDensity.current) { 32.dp.roundToPx() }
		AnimatedVisibility(
			visible = state.controlsVisible && !state.chaptersVisible && floatingControls.isNotEmpty(),
			// Keep Backdrop shadows out of the alpha layer used by fade transitions.
			enter = slideInHorizontally { it + floatingControlExitOffset },
			exit = slideOutHorizontally { it + floatingControlExitOffset },
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.navigationBarsPadding()
				.padding(end = 16.dp, bottom = 62.dp),
		) {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				floatingControls.forEach { control ->
					ReaderFloatingControlButton(
						control = control,
						state = state.actions,
						callbacks = callbacks.actions,
					)
				}
			}
		}

			if (state.chaptersVisible) {
				ReaderAnchoredBottomSheet(
					onDismissRequest = callbacks.onBackPressed,
				) { sheetDragModifier ->
					Column(
						modifier = Modifier.fillMaxSize(),
					) {
						val selectionState = chapterSelectionState
						Box(modifier = sheetDragModifier.fillMaxWidth()) {
							if (chapterPanelTabId == DETAILS_TAB_CHAPTERS && selectionState != null) {
								ChapterSelectionBar(
									state = selectionState,
									modifier = Modifier.height(52.dp),
								)
							} else {
								ReaderChapterPanelToolbar(
									selectedTabId = chapterPanelTabId,
									isFullyExpanded = true,
									state = state.chapterPanel,
									callbacks = callbacks.chapterPanel,
								)
							}
						}
						Box(modifier = Modifier.weight(1f)) {
							chaptersPanelContent(
								chapterPanelTabId,
								state.chapterPanel,
								{ chapterSelectionState = it },
							)
						}
					}
				}
			}

			if (state.options.visible) {
				ReaderAnchoredBottomSheet(
					onDismissRequest = callbacks.options.onDismiss,
				) { sheetDragModifier ->
					ComposeReaderOptionsSheet(
						state = state.options,
						callbacks = callbacks.options,
						embedded = true,
						translationTaskPanelContent = translationTaskPanelContent,
						headerModifier = sheetDragModifier,
						modifier = Modifier
							.fillMaxWidth()
							.fillMaxSize(),
					)
				}
		}

		if (state.toolsVisible) {
			ModalBottomSheet(
				onDismissRequest = {
					callbacks.onPrimaryDestination(ReaderControlDestination.TOOLS)
				},
			) {
				ComposeReaderToolsSheet(
					visible = true,
					translateActive = state.actions.translateActive,
					callbacks = callbacks.options,
					onDismiss = {
						callbacks.onPrimaryDestination(ReaderControlDestination.TOOLS)
					},
					embedded = true,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}

		if (state.autoScroll.visible) {
			ModalBottomSheet(onDismissRequest = callbacks.autoScroll.onClose) {
				ReaderAutoScrollPanel(state.autoScroll, callbacks.autoScroll)
			}
		}

		if (state.zoomVisible) {
			Column(
				modifier = Modifier
					.align(Alignment.CenterEnd)
					.padding(12.dp),
			) {
				IconButton(onClick = callbacks.onZoomIn, modifier = Modifier.size(48.dp)) {
					Icon(painterResource(R.drawable.ic_zoom_in), stringResource(R.string.zoom_in))
				}
				IconButton(onClick = callbacks.onZoomOut, modifier = Modifier.size(48.dp)) {
					Icon(painterResource(R.drawable.ic_zoom_out), stringResource(R.string.zoom_out))
				}
			}
		}

		if (state.loadingVisible) {
			Surface(
				shape = MaterialTheme.shapes.medium,
				color = MaterialTheme.colorScheme.surfaceContainer,
				modifier = Modifier.align(Alignment.Center),
			) {
				Column(
					horizontalAlignment = Alignment.CenterHorizontally,
					modifier = Modifier.padding(20.dp),
				) {
					KototoroLoadingIndicator()
					Text(
						text = stringResource(R.string.loading_),
						style = MaterialTheme.typography.titleMedium,
						modifier = Modifier.padding(top = 10.dp),
					)
				}
			}
		}

		ReaderMessageHost(
			message = state.message,
			onExpired = callbacks.onMessageExpired,
			onAction = callbacks.onMessageAction,
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(bottom = if (state.controlsVisible) 104.dp else 20.dp),
		)

		if (state.autoScroll.active && state.autoScroll.fabVisible && !state.controlsVisible && !state.autoScroll.visible) {
			SmallFloatingActionButton(
				onClick = callbacks.autoScroll.onOpen,
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.navigationBarsPadding()
					.padding(end = 16.dp, bottom = 16.dp),
			) {
				Icon(
					painter = painterResource(R.drawable.ic_timer_run),
					contentDescription = stringResource(R.string.automatic_scroll),
				)
			}
		}

	}
	}
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReaderProgressControl(
	state: ReaderActionsUiState,
	callbacks: ReaderActionsCallbacks,
	isIosStyle: Boolean,
) {
	ReaderProgressBar(
		value = state.sliderValue,
		max = state.sliderMax.toFloat(),
		onValueChange = callbacks.onSliderValueChanged,
		onValueChangeFinished = callbacks.onSliderValueChangeFinished,
		onPreviousChapter = callbacks.onPreviousChapter,
		onNextChapter = callbacks.onNextChapter,
		previousEnabled = state.previousEnabled,
		nextEnabled = state.nextEnabled,
		isIosStyle = isIosStyle,
	)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReaderAutoScrollPanel(state: ReaderAutoScrollUiState, callbacks: ReaderAutoScrollCallbacks) {
	Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.reader_autoscroll), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
			IconButton(onClick = callbacks.onClose) { Text("×", style = MaterialTheme.typography.titleLarge) }
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.reader_autoscroll), modifier = Modifier.weight(1f))
			Switch(checked = state.active, onCheckedChange = callbacks.onActiveChanged)
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(if (state.manuallyPaused) stringResource(R.string.play) else stringResource(R.string.pause), modifier = Modifier.weight(1f))
			Switch(checked = !state.manuallyPaused, onCheckedChange = { callbacks.onPausedChanged(!it) })
		}
		Text(text = stringResource(R.string.speed_value, 0.1f + state.speed * 10f))
		Slider(value = state.speed, onValueChange = callbacks.onSpeedChanged, valueRange = 0f..1f)
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.reader_autoscroll_fab), modifier = Modifier.weight(1f))
			Switch(checked = state.fabVisible, onCheckedChange = callbacks.onFabChanged)
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.reader_autoscroll_pause_on_ui), modifier = Modifier.weight(1f))
			Switch(checked = state.pauseOnUi, onCheckedChange = callbacks.onPauseOnUiChanged)
		}
		if (state.showPageDelay) {
			Text(stringResource(R.string.page_switch_timer, state.pageDelaySeconds), style = MaterialTheme.typography.bodySmall)
		}
	}
}

@Composable
internal fun ReaderComposeInfoBar(
	state: ReaderInfoBarState,
	systemStatus: ReaderSystemStatus = rememberReaderSystemStatus(),
) {
	val contentColor = if (state.darkContent) Color.Black.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.78f)
	val backgroundColor = if (state.darkContent) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)
	val textStyle = TextStyle(
		color = contentColor,
		fontSize = 12.sp,
		shadow = if (state.drawBackground) null else Shadow(color = backgroundColor, blurRadius = 2f),
	)
	Surface(
		color = if (state.drawBackground) backgroundColor else Color.Transparent,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.padding(horizontal = 8.dp, vertical = 6.dp),
		) {
			Text(text = state.text, style = textStyle, maxLines = 1, modifier = Modifier.weight(1f))
			if (state.showSystemStatus) {
				Icon(
					painter = painterResource(R.drawable.ic_battery_outline),
					contentDescription = null,
					tint = contentColor,
					modifier = Modifier.size(16.dp),
				)
				Text(text = systemStatus.battery, style = textStyle, modifier = Modifier.width(38.dp))
				Text(text = systemStatus.time, style = textStyle, maxLines = 1)
			}
		}
	}
}

@Immutable
internal data class ReaderSystemStatus(val time: String = "", val battery: String = "")

@Composable
internal fun rememberReaderSystemStatus(): ReaderSystemStatus {
	val context = LocalContext.current
	var status by remember { mutableStateOf(ReaderSystemStatus()) }
	DisposableEffect(context) {
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
				val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
				val battery = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else status.battery
				val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
				status = ReaderSystemStatus(time = time, battery = battery)
			}
		}
		ContextCompat.registerReceiver(
			context,
			receiver,
			IntentFilter().apply {
				addAction(Intent.ACTION_TIME_TICK)
				addAction(Intent.ACTION_BATTERY_CHANGED)
			},
			ContextCompat.RECEIVER_EXPORTED,
		)
		onDispose { context.unregisterReceiver(receiver) }
	}
	return status
}

@Composable
internal fun BoxScope.ReaderPageInfoBar(
	state: ReaderInfoBarState,
	controlsVisible: Boolean,
	systemStatus: ReaderSystemStatus,
) {
	AnimatedVisibility(
		visible = state.visible && !controlsVisible,
		enter = fadeIn(animationSpec = tween(durationMillis = 140, delayMillis = 160)),
		exit = fadeOut(animationSpec = tween(durationMillis = 80)),
		modifier = Modifier.align(Alignment.TopCenter),
	) {
		ReaderComposeInfoBar(state, systemStatus)
	}
}

@Composable
private fun ReaderMessageHost(
	message: ReaderMessage?,
	onExpired: (Long) -> Unit,
	onAction: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var displayedMessage by remember { mutableStateOf(message) }
	LaunchedEffect(message) {
		if (message != null) displayedMessage = message
	}
	LaunchedEffect(message?.id) {
		val current = message ?: return@LaunchedEffect
		delay(current.durationMillis ?: return@LaunchedEffect)
		onExpired(current.id)
	}
	AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
		Surface(shape = MaterialTheme.shapes.small, color = Color.Black.copy(alpha = 0.78f), contentColor = Color.White) {
			Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
				Text(
					text = displayedMessage?.text.orEmpty(),
					style = MaterialTheme.typography.bodySmall,
					modifier = Modifier.padding(vertical = 10.dp),
				)
				displayedMessage?.actionLabel?.let { label ->
					TextButton(onClick = onAction) { Text(label) }
				}
			}
		}
	}
}

@Composable
private fun ReaderComposeTopBar(
	state: ComposeReaderChromeState,
	onNavigateBack: () -> Unit,
	onChapters: () -> Unit,
	onOptions: () -> Unit,
) {
	val contentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
	val chapterControlShape = RoundedCornerShape(24.dp)
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.statusBarsPadding()
			.padding(horizontal = 14.dp, vertical = 6.dp),
	) {
		ReaderTopControlSurface(
			shape = CircleShape,
			modifier = Modifier
				.align(Alignment.CenterStart)
				.size(48.dp),
		) {
			IconButton(onClick = onNavigateBack) {
				Icon(
					painter = painterResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material),
					contentDescription = stringResource(androidx.appcompat.R.string.abc_action_bar_up_description),
					tint = contentColor,
				)
			}
		}
		ReaderTopControlSurface(
			shape = chapterControlShape,
			modifier = Modifier
				.align(Alignment.Center)
				.widthIn(min = 148.dp, max = 176.dp)
				.height(48.dp),
			contentModifier = Modifier
				.clip(chapterControlShape)
				.clickable(onClick = onChapters),
		) {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
			) {
				Text(
					text = state.title,
					color = contentColor,
					style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
					maxLines = 1,
				)
				if (state.subtitle.isNotEmpty()) {
					Text(
						text = state.subtitle,
						color = contentColor.copy(alpha = 0.78f),
						style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
						maxLines = 1,
					)
				}
			}
		}
		ReaderTopControlSurface(
			shape = CircleShape,
			modifier = Modifier
				.align(Alignment.CenterEnd)
				.size(48.dp),
		) {
			IconButton(onClick = onOptions) {
				Icon(
					imageVector = Icons.Default.MoreVert,
					contentDescription = stringResource(R.string.options),
					tint = contentColor,
				)
			}
		}
	}
}

internal fun resolveReaderFloatingControls(
	configured: Set<ReaderControl>,
	translationAvailable: Boolean,
	translationContextualVisible: Boolean,
): List<ReaderControl> {
	val configuredControls = ReaderControl.FLOATING
		.filter { control -> control in configured && (control != ReaderControl.TRANSLATE || translationAvailable) }
	if (!translationAvailable || !translationContextualVisible || ReaderControl.TRANSLATE in configuredControls) {
		return configuredControls.take(ReaderControl.MAX_FLOATING_CONTROLS)
	}
	return configuredControls
		.take(ReaderControl.MAX_FLOATING_CONTROLS - 1)
		.plus(ReaderControl.TRANSLATE)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderFloatingControlButton(
	control: ReaderControl,
	state: ReaderActionsUiState,
	callbacks: ReaderActionsCallbacks,
) {
	val icon = when (control) {
		ReaderControl.SCREEN_ROTATION -> if (state.autoRotationEnabled) {
			R.drawable.ic_screen_rotation_lock
		} else {
			R.drawable.ic_screen_rotation
		}
		ReaderControl.SAVE_PAGE -> R.drawable.ic_save
		ReaderControl.TIMER -> if (state.timerActive) R.drawable.ic_timer_run else R.drawable.ic_timer
		ReaderControl.BOOKMARK -> if (state.bookmarkAdded) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark
		ReaderControl.TRANSLATE -> R.drawable.ic_translate
		ReaderControl.DOWNLOAD -> R.drawable.ic_download
		else -> return
	}
	val contentDescription = when (control) {
		ReaderControl.SCREEN_ROTATION -> stringResource(
			if (state.autoRotationEnabled) R.string.lock_screen_rotation else R.string.rotate_screen,
		)
		ReaderControl.SAVE_PAGE -> stringResource(R.string.save_page)
		ReaderControl.TIMER -> stringResource(R.string.automatic_scroll)
		ReaderControl.BOOKMARK -> stringResource(
			if (state.bookmarkAdded) R.string.bookmark_remove else R.string.bookmark_add,
		)
		ReaderControl.TRANSLATE -> state.translateContentDescription.ifEmpty {
			stringResource(R.string.novel_translate)
		}
		ReaderControl.DOWNLOAD -> stringResource(R.string.download)
		else -> return
	}
	val onClick: () -> Unit = when (control) {
		ReaderControl.SCREEN_ROTATION -> callbacks.onScreenRotation
		ReaderControl.SAVE_PAGE -> callbacks.onSavePage
		ReaderControl.TIMER -> { { callbacks.onTimer(false) } }
		ReaderControl.BOOKMARK -> callbacks.onBookmark
		ReaderControl.TRANSLATE -> callbacks.onTranslate
		ReaderControl.DOWNLOAD -> callbacks.onDownload
		else -> return
	}
	val onLongClick: (() -> Unit)? = when (control) {
		ReaderControl.TIMER -> { { callbacks.onTimer(true) } }
		ReaderControl.BOOKMARK -> callbacks.onBookmarkLongClick
		ReaderControl.TRANSLATE -> callbacks.onTranslateLongClick
		else -> null
	}
	val active = when (control) {
		ReaderControl.TIMER -> state.timerActive
		ReaderControl.BOOKMARK -> state.bookmarkAdded
		ReaderControl.TRANSLATE -> state.translateActive
		else -> false
	}
	ReaderTopControlSurface(
		shape = CircleShape,
		modifier = Modifier.size(44.dp),
		contentModifier = Modifier
			.clip(CircleShape)
			.combinedClickable(
				role = Role.Button,
				onClickLabel = contentDescription,
				onLongClickLabel = if (onLongClick != null) contentDescription else null,
				onClick = onClick,
				onLongClick = onLongClick,
			),
	) {
		Icon(
			painter = painterResource(icon),
			contentDescription = contentDescription,
			tint = if (active) MaterialTheme.colorScheme.primary else readerControlContentColor(),
		)
	}
}

@Composable
private fun ReaderTopControlSurface(
	shape: Shape,
	modifier: Modifier = Modifier,
	contentModifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	GlassSurface(
		modifier = modifier,
		shape = shape,
		style = GlassDefaults.topBarChromeStyle().copy(
			containerAlpha = 0.84f,
			shadowElevation = ReaderControlTokens.ChromeShadowElevation,
		),
		componentRole = GlassComponentRole.TopBar,
	) {
		Box(
			contentAlignment = Alignment.Center,
			modifier = Modifier.fillMaxSize().then(contentModifier),
		) {
			content()
		}
	}
}
