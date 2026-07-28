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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
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
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_CHAPTERS
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.reader.ui.ReaderActionsCallbacks
import org.skepsun.kototoro.reader.ui.ReaderActionsContent
import org.skepsun.kototoro.reader.ui.ReaderActionsUiState
import org.skepsun.kototoro.reader.ui.ReaderToolbarChrome
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDock
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressDock
import kotlin.math.roundToInt

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
	val translationTaskPanelVisible: Boolean = false,
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
	val onPrimaryDestination: (ReaderControlDestination) -> Unit = {},
	val onPrimaryDestinationLongPress: (ReaderControlDestination) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeReaderActivityScaffold(
	state: ComposeReaderChromeState,
	callbacks: ComposeReaderChromeCallbacks,
	showControlLabels: Boolean,
	modifier: Modifier = Modifier,
	chaptersPanelContent: @Composable (Int) -> Unit = {},
	translationTaskPanelContent: @Composable () -> Unit = {},
	content: @Composable () -> Unit,
) {
	BackHandler {
		callbacks.onBackPressed()
	}
	val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
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
			enter = slideInVertically { -it } + fadeIn(),
			exit = slideOutVertically { -it } + fadeOut(),
			modifier = Modifier.align(Alignment.TopCenter),
		) {
			ReaderComposeTopBar(state, callbacks.onNavigateBack)
		}

		AnimatedVisibility(
			visible = state.infoBar.visible && !state.controlsVisible,
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
			visible = state.controlsVisible || state.autoScroll.visible,
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier.align(Alignment.BottomCenter),
		) {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
				modifier = Modifier
					.navigationBarsPadding()
					.padding(horizontal = 12.dp, vertical = 4.dp),
			) {
				if (state.actions.sliderEnabled) {
					ReaderProgressDock(isIosStyle = isIosStyle) {
						ReaderProgressControl(
							state = state.actions,
							callbacks = callbacks.actions,
							isIosStyle = isIosStyle,
						)
					}
				}
				ReaderControlDock(
					isIosStyle = isIosStyle,
					expanded = state.autoScroll.visible || state.chaptersVisible || state.options.visible ||
						state.toolsVisible,
				) {
					if (state.autoScroll.visible) {
						ReaderAutoScrollPanel(state.autoScroll, callbacks.autoScroll)
						HorizontalDivider(
							modifier = Modifier.padding(horizontal = 12.dp),
							color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
						)
					}
					if (state.chaptersVisible) {
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.height(420.dp),
							) {
								Column {
									Box(modifier = Modifier.weight(1f)) {
										chaptersPanelContent(DETAILS_TAB_CHAPTERS)
									}
								}
							}
						HorizontalDivider(
							modifier = Modifier.padding(horizontal = 12.dp),
							color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
						)
					}
					if (state.options.visible) {
						ComposeReaderOptionsSheet(
							state = state.options,
							callbacks = callbacks.options,
							embedded = true,
							modifier = Modifier.fillMaxWidth(),
						)
						HorizontalDivider(
							modifier = Modifier.padding(horizontal = 12.dp),
							color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
						)
					}
					if (state.toolsVisible) {
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
						HorizontalDivider(
							modifier = Modifier.padding(horizontal = 12.dp),
							color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
						)
					}
					if (state.controlsVisible) {
						ReaderActionsContent(
							state = state.actions,
							isSliderTracking = false,
							callbacks = callbacks.actions,
						)
					}
				}
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
					CircularProgressIndicator()
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

		translationTaskPanelContent()
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
private fun ReaderComposeInfoBar(state: ReaderInfoBarState) {
	val systemStatus = rememberReaderSystemStatus()
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
private data class ReaderSystemStatus(val time: String = "", val battery: String = "")

@Composable
private fun rememberReaderSystemStatus(): ReaderSystemStatus {
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
private fun ReaderMessageHost(
	message: ReaderMessage?,
	onExpired: (Long) -> Unit,
	onAction: () -> Unit,
	modifier: Modifier = Modifier,
) {
	LaunchedEffect(message?.id) {
		val current = message ?: return@LaunchedEffect
		delay(current.durationMillis ?: return@LaunchedEffect)
		onExpired(current.id)
	}
	AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
		Surface(shape = MaterialTheme.shapes.small, color = Color.Black.copy(alpha = 0.78f), contentColor = Color.White) {
			Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
				Text(
					text = message?.text.orEmpty(),
					style = MaterialTheme.typography.bodySmall,
					modifier = Modifier.padding(vertical = 10.dp),
				)
				message?.actionLabel?.let { label ->
					TextButton(onClick = onAction) { Text(label) }
				}
			}
		}
	}
}

@Composable
private fun ReaderComposeTopBar(state: ComposeReaderChromeState, onNavigateBack: () -> Unit) {
	val contentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
	val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
	Box(modifier = Modifier.fillMaxWidth()) {
		ReaderToolbarChrome()
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.statusBarsPadding()
				.padding(horizontal = if (isIosStyle) 18.dp else 4.dp, vertical = 4.dp),
		) {
			IconButton(onClick = onNavigateBack) {
				Icon(
					painter = painterResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material),
					contentDescription = stringResource(androidx.appcompat.R.string.abc_action_bar_up_description),
					tint = contentColor,
				)
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = state.title,
					color = contentColor,
					style = if (isIosStyle) {
						MaterialTheme.typography.titleMedium.copy(
							fontSize = 15.sp,
							lineHeight = 20.sp,
						)
					} else {
						MaterialTheme.typography.titleMedium
					},
					maxLines = 1,
				)
				if (state.subtitle.isNotEmpty()) {
					Text(
						text = state.subtitle,
						color = contentColor.copy(alpha = 0.78f),
						style = if (isIosStyle) {
							MaterialTheme.typography.bodySmall.copy(
								fontSize = 11.sp,
								lineHeight = 14.sp,
							)
						} else {
							MaterialTheme.typography.bodySmall
						},
						maxLines = 1,
					)
				}
			}
		}
	}
}
