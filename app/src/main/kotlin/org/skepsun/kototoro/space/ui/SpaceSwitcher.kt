package org.skepsun.kototoro.space.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.BreakIterator
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.GlassVisualTreatment
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.LocalRootGlassMenuHost
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy

private const val SPACE_SWITCHER_FAB_MIN_ALPHA = 0.60f

@Composable
fun SpaceSwitcherFab(
	activeSpaceId: SpaceId,
	activeSpace: SpaceContext? = null,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val presentation = activeSpace?.presentation() ?: activeSpaceId.presentation()
	val label = activeSpace?.title ?: stringResource(presentation.labelRes)
	val iconState = SpaceIconState(presentation, activeSpace?.customMonogram())
	val description = stringResource(
		R.string.space_switcher_content_description,
		label,
	)
	val colorScheme = MaterialTheme.colorScheme
	val fabAccentColor = colorScheme.primaryContainer
	val backdrop = LocalLiquidGlassBackdrop.current
    val useBackdrop = LocalInterfaceStyle.current == InterfaceStyle.IOS
	val fabModifier = modifier
		.clickable(
			interactionSource = remember { MutableInteractionSource() },
			indication = null,
			role = Role.Button,
			onClick = onClick,
		)
		.semantics { contentDescription = description }
	val content: @Composable BoxScope.() -> Unit = {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					color = if (useBackdrop) {
						Color.White.copy(alpha = 0.06f)
					} else {
						fabAccentColor.copy(alpha = SPACE_SWITCHER_FAB_MIN_ALPHA)
					},
					shape = CircleShape,
				),
			contentAlignment = Alignment.Center,
		) {
			CompositionLocalProvider(
				LocalContentColor provides if (useBackdrop) {
					colorScheme.onSurface
				} else {
					colorScheme.onPrimaryContainer
				},
			) {
				SpaceGlyph(iconState.presentation, iconState.monogram)
			}
		}
	}
    if (useBackdrop) {
        Box(
            modifier = fabModifier
                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { CircleShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx())
                            },
                        )
                    } else {
                        Modifier
                    },
                )
				.border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape),
			content = content,
		)
	} else {
		GlassSurface(
			modifier = fabModifier,
			style = GlassDefaults.topBarChromeStyle().copy(
				containerAlpha = SPACE_SWITCHER_FAB_MIN_ALPHA,
				borderAlpha = 0.24f,
			),
			shape = CircleShape,
			expandHazeLayerBounds = false,
			visualTreatment = GlassVisualTreatment.TopBarPrototype,
			componentRole = GlassComponentRole.TopBar,
			content = content,
		)
	}
}

@Composable
fun SpaceSwitcherRailButton(
	activeSpaceId: SpaceId,
	activeSpace: SpaceContext? = null,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	IconButton(
		onClick = onClick,
		modifier = modifier.size(48.dp),
	) {
		SpaceSwitcherIcon(activeSpaceId = activeSpaceId, activeSpace = activeSpace)
	}
}

@Composable
fun SpaceSwitcherIcon(
	activeSpaceId: SpaceId,
	activeSpace: SpaceContext? = null,
	modifier: Modifier = Modifier,
) {
	val presentation = activeSpace?.presentation() ?: activeSpaceId.presentation()
	val label = activeSpace?.title ?: stringResource(presentation.labelRes)
	Box(
		modifier = modifier.semantics {
			contentDescription = label
		},
		contentAlignment = Alignment.Center,
	) {
		SpaceGlyph(presentation, activeSpace?.customMonogram())
	}
}

@Composable
internal fun spaceDisplayLabel(spaceId: SpaceId, space: SpaceContext?): String =
	space?.title ?: stringResource((space?.presentation() ?: spaceId.presentation()).labelRes)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceSwitcherSheet(
	state: SpaceUiState,
	onAction: (SpaceAction) -> Unit,
	resumeItems: Map<SpaceId, SpaceResumeItem> = emptyMap(),
	onResume: (SpaceId) -> Unit = {},
	anchorBounds: Rect? = null,
	useGlobalRootMenu: Boolean = false,
) {
	val backdrop = LocalLiquidGlassBackdrop.current
	val rootMenuHost = LocalRootGlassMenuHost.current
	if (!state.switcherVisible) return
	if (useGlobalRootMenu && (anchorBounds == null || backdrop == null || rootMenuHost == null)) return
	val compactMenu = useGlobalRootMenu
	val menuContent: @Composable ColumnScope.() -> Unit = {
		if (compactMenu) {
			CompactDropdownMenuText(
				text = stringResource(R.string.space_switcher_title),
				modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
			)
		} else {
			CompactDropdownMenuText(stringResource(R.string.space_switcher_title))
		}
		state.spaces.forEach { context ->
			SpaceRow(
				context = context,
				selected = context.id == state.activeSpaceId,
				enabled = !state.switchInProgress,
				resumeItem = resumeItems[context.id],
				onResume = { onResume(context.id) },
				onClick = { onAction(SpaceAction.SelectSpace(context.id)) },
				compactMenu = compactMenu,
			)
		}
		if (state.switchInProgress) {
			Box(
				modifier = Modifier.fillMaxWidth().padding(8.dp),
				contentAlignment = Alignment.Center,
			) {
				CircularProgressIndicator(modifier = Modifier.size(28.dp))
			}
		}
	}
	if (useGlobalRootMenu) {
		GlassDropdownMenu(
			expanded = true,
			onDismissRequest = { onAction(SpaceAction.DismissSwitcher) },
			anchorBounds = anchorBounds,
			useRootOverlay = true,
			openAboveAnchor = true,
		) {
			menuContent()
		}
	} else {
		ModalBottomSheet(onDismissRequest = { onAction(SpaceAction.DismissSwitcher) }) {
			LazyColumn(
				modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
			) {
				item { menuContent() }
			}
		}
	}
}

@Composable
private fun SpaceRow(
	context: SpaceContext,
	selected: Boolean,
	enabled: Boolean,
	resumeItem: SpaceResumeItem?,
	onResume: () -> Unit,
	onClick: () -> Unit,
	compactMenu: Boolean = false,
) {
	val presentation = context.presentation()
	val hapticFeedback = LocalHapticFeedback.current
	Row(
		modifier = Modifier
			.then(
				if (compactMenu) {
					Modifier.wrapContentWidth()
				} else {
					Modifier.fillMaxWidth()
				},
			)
			.selectable(
				selected = selected,
				enabled = enabled,
				onClick = {
					if (!selected) {
						hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
					}
					onClick()
				},
				role = Role.RadioButton,
			)
			.padding(
				horizontal = if (compactMenu) 12.dp else 24.dp,
				vertical = if (compactMenu) 6.dp else 12.dp,
			),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(16.dp),
	) {
		SpaceGlyph(
			presentation = presentation,
			monogram = context.customMonogram(),
			modifier = Modifier.size(24.dp),
		)
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = context.title ?: stringResource(presentation.labelRes),
				style = if (compactMenu) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
			)
			resumeItem?.let { item ->
				Text(
					text = stringResource(R.string.space_recent_context, item.title),
					style = if (compactMenu) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
		if (resumeItem?.canResume == true) {
			IconButton(onClick = onResume, enabled = enabled) {
				Icon(
					painter = painterResource(presentation.resumeIconRes),
					contentDescription = stringResource(
						R.string.space_continue_content_description,
						resumeItem.title,
					),
				)
			}
		}
			RadioButton(
				selected = selected,
				onClick = null,
				enabled = enabled,
				modifier = if (compactMenu) Modifier.size(28.dp) else Modifier,
			)
	}
}

private data class SpacePresentation(
	@StringRes val labelRes: Int,
	@DrawableRes val iconRes: Int,
	@DrawableRes val resumeIconRes: Int,
)

private data class SpaceIconState(
	val presentation: SpacePresentation,
	val monogram: String?,
)

@Composable
private fun SpaceGlyph(
	presentation: SpacePresentation,
	monogram: String?,
	modifier: Modifier = Modifier,
) {
	if (monogram == null) {
		Icon(
			painter = painterResource(presentation.iconRes),
			contentDescription = null,
			modifier = modifier,
		)
	} else {
		Box(modifier = modifier.size(24.dp), contentAlignment = Alignment.Center) {
			Text(text = monogram, style = MaterialTheme.typography.titleMedium, maxLines = 1)
		}
	}
}

internal fun SpaceContext.customMonogram(): String? {
	if (isBuiltIn) return null
	val value = title?.trim().orEmpty()
	if (value.isEmpty()) return null
	val iterator = BreakIterator.getCharacterInstance()
	iterator.setText(value)
	val start = iterator.first()
	val end = iterator.next()
	return value.substring(start, end.takeUnless { it == BreakIterator.DONE } ?: value.offsetByCodePoints(0, 1))
}

private fun SpaceId.presentation(): SpacePresentation = when (this) {
	BuiltInSpaces.Novel -> SpacePresentation(R.string.space_novel, R.drawable.ic_content_novel, R.drawable.ic_read)
	BuiltInSpaces.Anime -> SpacePresentation(R.string.space_anime, R.drawable.ic_content_video, R.drawable.ic_play)
	else -> SpacePresentation(R.string.space_manga, R.drawable.ic_content_manga, R.drawable.ic_read)
}

private fun SpaceContext.presentation(): SpacePresentation = when (kind) {
	org.skepsun.kototoro.space.domain.SpaceKind.NOVEL ->
		SpacePresentation(R.string.space_novel, R.drawable.ic_content_novel, R.drawable.ic_read)
	org.skepsun.kototoro.space.domain.SpaceKind.ANIME ->
		SpacePresentation(R.string.space_anime, R.drawable.ic_content_video, R.drawable.ic_play)
	org.skepsun.kototoro.space.domain.SpaceKind.MANGA ->
		SpacePresentation(R.string.space_manga, R.drawable.ic_content_manga, R.drawable.ic_read)
}
