package org.skepsun.kototoro.reader.ui.compose.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

enum class ReaderControlDestination {
	NAVIGATION,
	DISPLAY,
	TOOLS,
	TRANSLATION,
	PROGRESS,
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
	modifier: Modifier = Modifier,
) {
	require(items.map { it.destination }.distinct().size == items.size)
	var hintDestination by remember { mutableStateOf<ReaderControlDestination?>(null) }
	LaunchedEffect(hintDestination) {
		if (hintDestination != null) {
			delay(1500L)
			hintDestination = null
		}
	}
	Surface(
		shape = RoundedCornerShape(32.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		contentColor = MaterialTheme.colorScheme.onSurface,
		modifier = modifier.width((items.size * 56 + (items.size - 1) * 8 + 16).dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
						label = null,
					alwaysShowLabel = false,
					colors = NavigationBarItemDefaults.colors(
						selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
						selectedTextColor = MaterialTheme.colorScheme.onSurface,
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
