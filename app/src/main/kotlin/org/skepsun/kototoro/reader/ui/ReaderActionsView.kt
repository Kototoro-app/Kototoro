package org.skepsun.kototoro.reader.ui

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazePositionStrategy
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.GlassVisualTreatment
import org.skepsun.kototoro.core.ui.glass.LocalHazeState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.performSegmentHapticFeedback
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesSheet
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesSheet.Companion.TAB_PAGES
import org.skepsun.kototoro.reader.ui.ReaderControlDelegate.OnInteractionListener
import javax.inject.Inject

@Immutable
private data class ReaderActionsUiState(
	val controls: Set<ReaderControl> = ReaderControl.DEFAULT,
	val sliderValue: Float = 0f,
	val sliderMax: Int = 1,
	val sliderEnabled: Boolean = false,
	val sliderReversed: Boolean = false,
	val previousEnabled: Boolean = true,
	val nextEnabled: Boolean = true,
	val bookmarkAdded: Boolean = false,
	val timerActive: Boolean = false,
	val translateRequestedVisible: Boolean = false,
	val translateContextualVisible: Boolean = false,
	val translateActive: Boolean = false,
	val translateContentDescription: String = "",
	val autoRotationEnabled: Boolean = false,
	val pagesMode: Boolean = true,
	val pageLabel: String = "",
)

private data class ReaderActionsCallbacks(
	val onPreviousChapter: () -> Unit = {},
	val onNextChapter: () -> Unit = {},
	val onSavePage: () -> Unit = {},
	val onTimer: (Boolean) -> Unit = {},
	val onPages: () -> Unit = {},
	val onPagesLongClick: () -> Unit = {},
	val onScreenRotation: () -> Unit = {},
	val onBookmark: () -> Unit = {},
	val onBookmarkLongClick: () -> Unit = {},
	val onDownload: () -> Unit = {},
	val onTranslate: () -> Unit = {},
	val onTranslateLongClick: () -> Unit = {},
	val onOptions: () -> Unit = {},
	val onOptionsLongClick: () -> Unit = {},
	val onSliderValueChanged: (Float) -> Unit = {},
	val onSliderValueChangeFinished: () -> Unit = {},
)

@AndroidEntryPoint
class ReaderActionsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var settings: AppSettings

	private var uiState by mutableStateOf(ReaderActionsUiState())
	private var isSliderChanged = false
	private var isSliderTracking by mutableStateOf(false)
	private var lastHapticSliderValue: Int? = null
	private var isSettingsRegistered = false
	private var hasComposeContent = false
	private val composeView = ComposeView(context)
	private val initializeAttachedState = Runnable { initializeAttachedState() }
	private val rotationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
		override fun onChange(selfChange: Boolean) {
			post { updateState { copy(autoRotationEnabled = isAutoRotationEnabled()) } }
		}
	}

	var listener: OnInteractionListener? = null

	var isSliderEnabled: Boolean
		get() = uiState.sliderEnabled
		set(value) = updateState { copy(sliderEnabled = value) }

	var isNextEnabled: Boolean
		get() = uiState.nextEnabled
		set(value) = updateState { copy(nextEnabled = value) }

	var isPrevEnabled: Boolean
		get() = uiState.previousEnabled
		set(value) = updateState { copy(previousEnabled = value) }

	var isBookmarkAdded: Boolean
		get() = uiState.bookmarkAdded
		set(value) = updateState { copy(bookmarkAdded = value) }

	init {
		composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		addView(
			composeView,
			FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
				gravity = Gravity.CENTER_VERTICAL
			},
		)
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		post(initializeAttachedState)
	}

	override fun onDetachedFromWindow() {
		removeCallbacks(initializeAttachedState)
		if (isSettingsRegistered) {
			settings.unsubscribe(this)
			context.contentResolver.unregisterContentObserver(rotationObserver)
			isSettingsRegistered = false
		}
		super.onDetachedFromWindow()
	}

	private fun initializeAttachedState() {
		if (!isAttachedToWindow) return
		if (!::settings.isInitialized) {
			post(initializeAttachedState)
			return
		}
		if (!isSettingsRegistered) {
			settings.subscribe(this)
			context.contentResolver.registerContentObserver(
				Settings.System.CONTENT_URI,
				true,
				rotationObserver,
			)
			isSettingsRegistered = true
		}
		refreshSettingsState()
		if (!hasComposeContent) {
			hasComposeContent = true
			composeView.setContent {
				KototoroTheme {
					val controls by settings.observeAsState(AppSettings.KEY_READER_CONTROLS) {
						readerControls
					}
					ReaderActionsContent(
						state = uiState.copy(controls = controls),
						isSliderTracking = isSliderTracking,
						callbacks = callbacks,
					)
				}
			}
		}
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_READER_CONTROLS,
			AppSettings.KEY_PAGES_TAB,
			AppSettings.KEY_DETAILS_TAB,
			AppSettings.KEY_DETAILS_LAST_TAB,
			-> refreshSettingsState()
		}
	}

	fun setSliderValue(value: Int, max: Int) {
		val safeMax = max.coerceAtLeast(1)
		updateState {
			copy(
				sliderValue = value.toFloat().coerceIn(0f, safeMax.toFloat()),
				sliderMax = safeMax,
			)
		}
	}

	fun setPageLabel(current: Int, total: Int) {
		updateState { copy(pageLabel = "$current/$total") }
	}

	fun setSliderReversed(reversed: Boolean) {
		updateState { copy(sliderReversed = reversed) }
	}

	fun setTimerActive(isActive: Boolean) {
		updateState { copy(timerActive = isActive) }
	}

	/** 根据阅读器能力请求显示或隐藏翻译按钮。 */
	fun setTranslateButtonVisible(visible: Boolean) {
		updateState { copy(translateRequestedVisible = visible) }
	}

	fun setTranslateButtonContextualVisible(visible: Boolean) {
		updateState { copy(translateContextualVisible = visible) }
	}

	/** 更新翻译按钮的激活状态。 */
	fun setTranslateActive(isActive: Boolean) {
		updateState { copy(translateActive = isActive) }
	}

	fun setTranslateButtonContentDescription(text: CharSequence) {
		updateState { copy(translateContentDescription = text.toString()) }
	}

	private val callbacks: ReaderActionsCallbacks
		get() = ReaderActionsCallbacks(
			onPreviousChapter = { listener?.switchChapterBy(-1) },
			onNextChapter = { listener?.switchChapterBy(1) },
			onSavePage = { listener?.onSavePageClick() },
			onTimer = { isLongClick -> listener?.onScrollTimerClick(isLongClick) },
			onPages = ::handlePagesClick,
			onPagesLongClick = { listener?.onPagesButtonLongClick() },
			onScreenRotation = { listener?.toggleScreenOrientation() },
			onBookmark = { listener?.onBookmarkClick() },
			onBookmarkLongClick = {
				AppRouter.from(this)?.showChapterPagesSheet(ChaptersPagesSheet.TAB_BOOKMARKS)
			},
			onDownload = { listener?.onDownloadClick() },
			onTranslate = { listener?.onTranslateClick() },
			onTranslateLongClick = { listener?.onTranslateLongClick() },
			onOptions = { listener?.openMenu() },
			onOptionsLongClick = { AppRouter.from(this)?.openReaderSettings() },
			onSliderValueChanged = { value ->
				val page = value.toInt()
				if (page != lastHapticSliderValue) {
					performSegmentHapticFeedback()
					lastHapticSliderValue = page
				}
				isSliderTracking = true
				isSliderChanged = true
				updateState { copy(sliderValue = value) }
			},
			onSliderValueChangeFinished = {
				if (isSliderTracking && isSliderChanged) {
					listener?.switchPageTo(uiState.sliderValue.toInt())
				}
				isSliderTracking = false
				isSliderChanged = false
				lastHapticSliderValue = null
			},
		)

	private fun handlePagesClick() {
		if (listener?.onPagesButtonClick() != true) {
			AppRouter.from(this)?.showChapterPagesSheet()
		}
	}

	private fun refreshSettingsState() {
		updateState {
			copy(
				controls = settings.readerControls,
				pagesMode = settings.defaultDetailsTab == TAB_PAGES,
				autoRotationEnabled = isAutoRotationEnabled(),
			)
		}
	}

	private fun updateState(transform: ReaderActionsUiState.() -> ReaderActionsUiState) {
		uiState = uiState.transform()
	}

	private fun isAutoRotationEnabled(): Boolean = Settings.System.getInt(
		context.contentResolver,
		Settings.System.ACCELEROMETER_ROTATION,
		0,
	) == 1
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderActionsContent(
	state: ReaderActionsUiState,
	isSliderTracking: Boolean,
	callbacks: ReaderActionsCallbacks,
) {
	val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
	val isSliderVisible = ReaderControl.SLIDER in state.controls
	val equalButtonWidth = !isSliderVisible
	val visibleTranslate = state.translateRequestedVisible && (
		state.translateContextualVisible || ReaderControl.TRANSLATE in state.controls
		)
	val normalContentColor = MaterialTheme.colorScheme.onSurface
	val disabledContentColor = normalContentColor.copy(alpha = 0.38f)
	val pageDescription = if (state.pagesMode) {
		stringResource(R.string.pages)
	} else {
		stringResource(R.string.chapters)
	}
	val translateDescription = state.translateContentDescription.ifEmpty {
		stringResource(R.string.novel_translate)
	}
	val sliderReversed = state.sliderReversed != isRtl
	val hazeState = remember {
		HazeState().apply {
			positionStrategy = HazePositionStrategy.Screen
		}
	}
	val backdropBackground = MaterialTheme.colorScheme.background
	val backdrop = rememberLayerBackdrop {
		drawRect(backdropBackground)
		drawContent()
	}

	CompositionLocalProvider(
		LocalHazeState provides hazeState,
		LocalLiquidGlassBackdrop provides backdrop,
		LocalLiquidGlassLayerBackdrop provides backdrop,
	) {
		Box(
			modifier = Modifier.fillMaxWidth(),
		) {
			Box(
				modifier = Modifier
					.matchParentSize()
					.layerBackdrop(backdrop)
					.hazeSource(hazeState),
			)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 48.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = if (equalButtonWidth) Arrangement.SpaceEvenly else Arrangement.Start,
			) {
		if (ReaderControl.PREV_CHAPTER in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(equalButtonWidth),
				iconRes = R.drawable.ic_prev,
				contentDescription = stringResource(R.string.prev_chapter),
				enabled = state.previousEnabled,
				contentColor = if (state.previousEnabled) normalContentColor else disabledContentColor,
				onClick = callbacks.onPreviousChapter,
			)
		}

		if (isSliderVisible) {
			Box(
				modifier = Modifier
					.weight(1f)
					.height(48.dp),
			) {
				KototoroSlider(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 4.dp)
						.semantics {
							if (state.pageLabel.isNotEmpty()) contentDescription = state.pageLabel
						},
					value = (if (sliderReversed) {
						state.sliderMax - state.sliderValue
					} else {
						state.sliderValue
					}).coerceIn(0f, state.sliderMax.toFloat()),
					onValueChange = { value ->
						val actualValue = if (sliderReversed) state.sliderMax - value else value
						if (actualValue.toInt() != state.sliderValue.toInt()) {
							callbacks.onSliderValueChanged(actualValue)
						}
					},
					onValueChangeFinished = callbacks.onSliderValueChangeFinished,
					enabled = state.sliderEnabled,
					steps = (state.sliderMax - 1).coerceAtLeast(0),
					valueRange = 0f..state.sliderMax.toFloat(),
					colors = SliderDefaults.colors(
						thumbColor = normalContentColor,
						disabledThumbColor = Color.Transparent,
					),
				)
				if (isSliderTracking && state.pageLabel.isNotEmpty()) {
					androidx.compose.material3.Text(
						text = state.pageLabel,
						color = normalContentColor,
						style = MaterialTheme.typography.labelSmall,
						modifier = Modifier.align(Alignment.TopCenter),
					)
				}
			}
		}

		if (ReaderControl.NEXT_CHAPTER in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(equalButtonWidth),
				iconRes = R.drawable.ic_next,
				contentDescription = stringResource(R.string.next_chapter),
				enabled = state.nextEnabled,
				contentColor = if (state.nextEnabled) normalContentColor else disabledContentColor,
				onClick = callbacks.onNextChapter,
			)
		}
		if (ReaderControl.SAVE_PAGE in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(equalButtonWidth),
				iconRes = R.drawable.ic_save,
				contentDescription = stringResource(R.string.save_page),
				contentColor = normalContentColor,
				onClick = callbacks.onSavePage,
			)
		}
		if (ReaderControl.TIMER in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(equalButtonWidth),
				iconRes = if (state.timerActive) R.drawable.ic_timer_run else R.drawable.ic_timer,
				contentDescription = stringResource(R.string.automatic_scroll),
				contentColor = normalContentColor,
				onClick = { callbacks.onTimer(false) },
				onLongClick = { callbacks.onTimer(true) },
			)
		}
		if (ReaderControl.SCREEN_ROTATION in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(equalButtonWidth),
				iconRes = if (state.autoRotationEnabled) {
					R.drawable.ic_screen_rotation_lock
				} else {
					R.drawable.ic_screen_rotation
				},
				contentDescription = stringResource(
					if (state.autoRotationEnabled) R.string.lock_screen_rotation else R.string.rotate_screen,
				),
				contentColor = normalContentColor,
				onClick = callbacks.onScreenRotation,
			)
		}
		if (ReaderControl.BOOKMARK in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(equalButtonWidth),
				iconRes = if (state.bookmarkAdded) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark,
				contentDescription = stringResource(
					if (state.bookmarkAdded) R.string.bookmark_remove else R.string.bookmark_add,
				),
				contentColor = normalContentColor,
				onClick = callbacks.onBookmark,
				onLongClick = callbacks.onBookmarkLongClick,
			)
		}
		if (ReaderControl.DOWNLOAD in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(equalButtonWidth),
				iconRes = R.drawable.ic_download,
				contentDescription = stringResource(R.string.download),
				contentColor = normalContentColor,
				onClick = callbacks.onDownload,
			)
		}
		if (ReaderControl.PAGES_SHEET in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(equalButtonWidth),
				iconRes = if (state.pagesMode) R.drawable.ic_grid else R.drawable.ic_list,
				contentDescription = pageDescription,
				contentColor = normalContentColor,
				onClick = callbacks.onPages,
				onLongClick = callbacks.onPagesLongClick,
			)
		}
		if (visibleTranslate) {
			ReaderActionButton(
				modifier = actionModifier(equalButtonWidth),
				iconRes = R.drawable.ic_translate,
				contentDescription = translateDescription,
				contentColor = if (state.translateActive) {
					MaterialTheme.colorScheme.primary
				} else {
					normalContentColor
				},
				onClick = callbacks.onTranslate,
				onLongClick = callbacks.onTranslateLongClick,
			)
		}
		ReaderActionButton(
			modifier = actionModifier(equalButtonWidth),
			iconRes = androidx.appcompat.R.drawable.abc_ic_menu_overflow_material,
			contentDescription = stringResource(R.string.options),
			contentColor = normalContentColor,
			onClick = callbacks.onOptions,
			onLongClick = callbacks.onOptionsLongClick,
		)
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ReaderActionButton(
	modifier: Modifier,
	iconRes: Int,
	contentDescription: String,
	enabled: Boolean = true,
	contentColor: Color,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)? = null,
) {
	Box(
		modifier = modifier
			.combinedClickable(
				enabled = enabled,
				onClick = onClick,
				onLongClick = onLongClick,
			)
			.semantics {
				this.contentDescription = contentDescription
				role = Role.Button
			},
		contentAlignment = Alignment.Center,
	) {
		GlassSurface(
			modifier = Modifier
				.fillMaxSize()
				.padding(4.dp),
			shape = CircleShape,
			style = GlassDefaults.subtleStyle(),
			componentRole = GlassComponentRole.Surface,
			visualTreatment = GlassVisualTreatment.Standard,
		) {
			Icon(
				painter = painterResource(iconRes),
				contentDescription = null,
				tint = contentColor,
				modifier = Modifier
					.align(Alignment.Center)
					.size(24.dp),
			)
		}
	}
}

private fun RowScope.actionModifier(equalButtonWidth: Boolean): Modifier = if (equalButtonWidth) {
	Modifier
		.weight(1f)
		.height(48.dp)
} else {
	Modifier.size(48.dp)
}

@Preview(showBackground = true, widthDp = 640)
@Composable
private fun ReaderActionsPreview() {
	KototoroTheme {
		ReaderActionsContent(
			state = ReaderActionsUiState(
				controls = ReaderControl.entries.toSet(),
				sliderValue = 6f,
				sliderMax = 20,
				sliderEnabled = true,
				pageLabel = "7/20",
			),
			callbacks = ReaderActionsCallbacks(),
			isSliderTracking = false,
		)
	}
}
