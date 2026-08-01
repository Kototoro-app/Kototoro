package org.skepsun.kototoro.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.reader.ui.compose.design.readerControlContentColor

@Immutable
internal data class ReaderActionsUiState(
	val controls: Set<ReaderControl> = ReaderControl.FLOATING_DEFAULT,
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

internal data class ReaderActionsCallbacks(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReaderActionsContent(
	state: ReaderActionsUiState,
	isSliderTracking: Boolean,
	callbacks: ReaderActionsCallbacks,
) {
	val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
	val isSliderVisible = ReaderControl.SLIDER in state.controls
	val widthModifier = if (isSliderVisible) Modifier.fillMaxWidth() else Modifier.wrapContentWidth()
	val visibleTranslate = state.translateRequestedVisible && (
		state.translateContextualVisible || ReaderControl.TRANSLATE in state.controls
		)
	val normalContentColor = readerControlContentColor()
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
	Box(
		modifier = widthModifier,
	) {
		Row(
			modifier = widthModifier
				.heightIn(min = 48.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Start,
		) {
		if (ReaderControl.PREV_CHAPTER in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(),
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
				modifier = actionModifier(),
				iconRes = R.drawable.ic_next,
				contentDescription = stringResource(R.string.next_chapter),
				enabled = state.nextEnabled,
				contentColor = if (state.nextEnabled) normalContentColor else disabledContentColor,
				onClick = callbacks.onNextChapter,
			)
		}
		if (ReaderControl.SAVE_PAGE in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(),
				iconRes = R.drawable.ic_save,
				contentDescription = stringResource(R.string.save_page),
				contentColor = normalContentColor,
				onClick = callbacks.onSavePage,
			)
		}
		if (ReaderControl.TIMER in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(),
				iconRes = if (state.timerActive) R.drawable.ic_timer_run else R.drawable.ic_timer,
				contentDescription = stringResource(R.string.automatic_scroll),
				contentColor = normalContentColor,
				onClick = { callbacks.onTimer(false) },
				onLongClick = { callbacks.onTimer(true) },
			)
		}
		if (ReaderControl.SCREEN_ROTATION in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(),
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
				modifier = actionModifier(),
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
				modifier = actionModifier(),
				iconRes = R.drawable.ic_download,
				contentDescription = stringResource(R.string.download),
				contentColor = normalContentColor,
				onClick = callbacks.onDownload,
			)
		}
		if (ReaderControl.PAGES_SHEET in state.controls) {
			ReaderActionButton(
				modifier = actionModifier(),
				iconRes = if (state.pagesMode) R.drawable.ic_grid else R.drawable.ic_list,
				contentDescription = pageDescription,
				contentColor = normalContentColor,
				onClick = callbacks.onPages,
				onLongClick = callbacks.onPagesLongClick,
			)
		}
		if (visibleTranslate) {
			ReaderActionButton(
				modifier = actionModifier(),
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
			modifier = actionModifier(),
			iconRes = androidx.appcompat.R.drawable.abc_ic_menu_overflow_material,
			contentDescription = stringResource(R.string.options),
			contentColor = normalContentColor,
			onClick = callbacks.onOptions,
			onLongClick = callbacks.onOptionsLongClick,
		)
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
		Icon(
			painter = painterResource(iconRes),
			contentDescription = null,
			tint = contentColor,
			modifier = Modifier.size(24.dp),
		)
	}
}

private fun actionModifier(): Modifier = Modifier.size(48.dp)

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
