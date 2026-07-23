package org.skepsun.kototoro.reader.ui.compose.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import org.skepsun.kototoro.R
import kotlin.math.roundToInt

enum class ReaderControlDestination {
	NAVIGATION,
	DISPLAY,
	TOOLS,
	TRANSLATION,
	PROGRESS,
	CHAPTERS_PANEL,
}

@Immutable
data class ReaderControlItem(
	val destination: ReaderControlDestination,
	val label: String,
	@DrawableRes val icon: Int,
	val active: Boolean = false,
	val indicator: Boolean = false,
)

object ReaderControlTokens {
	val TouchTarget = 48.dp
	val BottomBarMinHeight = 56.dp
	val GroupPadding = 12.dp
	val ItemSpacing = 8.dp
	val SheetHorizontalPadding = 16.dp
	val SheetMaxWidth = 760.dp
}

@Composable
fun ReaderPrimaryControlBar(
	items: List<ReaderControlItem>,
	onDestinationSelected: (ReaderControlDestination) -> Unit,
	onDestinationLongPressed: (ReaderControlDestination) -> Unit = {},
	transparentContainer: Boolean = false,
	showLabels: Boolean = false,
	modifier: Modifier = Modifier,
) {
	require(items.map { it.destination }.distinct().size == items.size)
	val defaultContentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
	var hintDestination by remember { mutableStateOf<ReaderControlDestination?>(null) }
	LaunchedEffect(hintDestination) {
		if (hintDestination != null) {
			delay(1500L)
			hintDestination = null
		}
	}
	Surface(
		shape = RoundedCornerShape(32.dp),
		color = if (transparentContainer) {
			androidx.compose.ui.graphics.Color.Transparent
		} else {
			MaterialTheme.colorScheme.surfaceContainerHigh
		},
		contentColor = MaterialTheme.colorScheme.onSurface,
		modifier = modifier.width((items.size * 56 + (items.size - 1) * 8 + 16).dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.padding(
				horizontal = 8.dp,
				vertical = if (showLabels) 0.dp else 4.dp,
			),
		) {
			items.forEach { item ->
				NavigationBarItem(
					modifier = Modifier.width(56.dp),
					selected = item.active,
					onClick = { onDestinationSelected(item.destination) },
					icon = {
						Box {
							Icon(
								painterResource(item.icon),
								contentDescription = item.label,
								modifier = Modifier.combinedClickable(
									interactionSource = remember { MutableInteractionSource() },
									indication = null,
									onClick = { onDestinationSelected(item.destination) },
									onLongClick = {
										hintDestination = item.destination
										onDestinationLongPressed(item.destination)
									},
								),
							)
							if (hintDestination == item.destination) {
								Popup(
									alignment = androidx.compose.ui.Alignment.TopCenter,
									offset = androidx.compose.ui.unit.IntOffset(0, -56),
									properties = PopupProperties(focusable = false),
								) {
									Surface(
										shape = MaterialTheme.shapes.small,
										color = MaterialTheme.colorScheme.inverseSurface,
										contentColor = MaterialTheme.colorScheme.inverseOnSurface,
									) {
										Text(item.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
									}
								}
							}
						}
					},
					label = if (showLabels) {
						{
							Text(
								text = item.label,
								style = MaterialTheme.typography.labelSmall,
								maxLines = 1,
							)
						}
					} else {
						null
					},
					alwaysShowLabel = showLabels,
					colors = NavigationBarItemDefaults.colors(
						selectedIconColor = MaterialTheme.colorScheme.primary,
						selectedTextColor = MaterialTheme.colorScheme.primary,
						unselectedIconColor = defaultContentColor.copy(alpha = 0.92f),
						unselectedTextColor = defaultContentColor.copy(alpha = 0.86f),
						indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
					),
				)
			}
		}
	}
}

@Composable
fun ReaderControlGroup(
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	Surface(
		shape = MaterialTheme.shapes.medium,
		color = MaterialTheme.colorScheme.surfaceContainer,
		modifier = modifier.fillMaxWidth(),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(ReaderControlTokens.ItemSpacing),
			modifier = Modifier.padding(ReaderControlTokens.GroupPadding),
		) {
			content()
		}
	}
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ReaderProgressBar(
	value: Float,
	max: Float,
	onValueChange: (Float) -> Unit,
	onValueChangeFinished: () -> Unit,
	onPreviousChapter: () -> Unit,
	onNextChapter: () -> Unit,
	previousEnabled: Boolean = true,
	nextEnabled: Boolean = true,
	isIosStyle: Boolean = true,
	modifier: Modifier = Modifier,
) {
	var dragValue by remember { mutableStateOf<Float?>(null) }
	var trackPosition by remember { mutableStateOf(IntOffset.Zero) }
	var trackWidthPx by remember { mutableStateOf(0) }
	val effectiveMax = max.coerceAtLeast(1f)
	val displayedValue = (dragValue ?: value).coerceIn(0f, effectiveMax)
	if (dragValue != null) {
		val fraction = displayedValue / effectiveMax
		Popup(
			alignment = Alignment.TopStart,
			offset = IntOffset(
				trackPosition.x + (trackWidthPx * fraction).roundToInt() - 32,
				trackPosition.y - 52,
			),
			properties = PopupProperties(focusable = false),
		) {
			Surface(
				shape = MaterialTheme.shapes.small,
				color = MaterialTheme.colorScheme.inverseSurface,
				contentColor = MaterialTheme.colorScheme.inverseOnSurface,
			) {
				Text(
					text = "${displayedValue.toInt() + 1}/${max.toInt() + 1}",
					style = MaterialTheme.typography.labelMedium,
					modifier = Modifier.padding(8.dp),
				)
			}
		}
	}
	BoxWithConstraints(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 12.dp, vertical = 2.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			IconButton(
				onClick = onPreviousChapter,
				enabled = previousEnabled,
				modifier = Modifier.size(48.dp),
			) {
				Icon(
					painterResource(R.drawable.ic_prev),
					stringResource(R.string.prev_chapter),
					tint = LocalContentColor.current,
				)
			}
			Column(
				modifier = Modifier
					.weight(1f)
					.onGloballyPositioned { coordinates ->
						val position = coordinates.positionInWindow()
						trackPosition = IntOffset(position.x.roundToInt(), position.y.roundToInt())
						trackWidthPx = coordinates.size.width
					},
			) {
				Slider(
					value = displayedValue,
					onValueChange = {
						dragValue = it
						onValueChange(it)
					},
					onValueChangeFinished = {
						dragValue = null
						onValueChangeFinished()
					},
					valueRange = 0f..effectiveMax,
					thumb = {
						Box(
							modifier = Modifier
								.size(if (isIosStyle) 14.dp else 18.dp)
								.background(MaterialTheme.colorScheme.primary, CircleShape),
						)
					},
					track = { sliderState ->
						SliderDefaults.Track(
							sliderState = sliderState,
							modifier = Modifier.height(if (isIosStyle) 4.dp else 10.dp),
							thumbTrackGapSize = 0.dp,
						)
					},
				)
			}
			IconButton(
				onClick = onNextChapter,
				enabled = nextEnabled,
				modifier = Modifier.size(48.dp),
			) {
				Icon(
					painterResource(R.drawable.ic_next),
					stringResource(R.string.next_chapter),
					tint = LocalContentColor.current,
				)
			}
		}
	}
}

@Composable
fun ReaderPanelSection(
	embedded: Boolean,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = if (embedded) {
			MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.16f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
		border = if (embedded) {
			BorderStroke(
				width = 1.dp,
				color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f),
			)
		} else {
			null
		},
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
		modifier = modifier.fillMaxWidth(),
		content = content,
	)
}
