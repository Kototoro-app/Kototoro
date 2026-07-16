package org.skepsun.kototoro.space.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId

@Composable
fun SpaceSwitcherFab(
	activeSpaceId: SpaceId,
	activeSpace: SpaceContext? = null,
	expanded: Boolean,
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
	ExtendedFloatingActionButton(
		onClick = onClick,
		modifier = modifier.semantics { contentDescription = description },
		expanded = expanded,
		icon = {
			SpaceGlyph(iconState.presentation, iconState.monogram)
		},
		text = {
			Text(label)
		},
		containerColor = MaterialTheme.colorScheme.primaryContainer,
		contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
	)
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
) {
	if (!state.switcherVisible) return
	ModalBottomSheet(onDismissRequest = { onAction(SpaceAction.DismissSwitcher) }) {
		LazyColumn(
			modifier = Modifier
				.fillMaxWidth()
				.padding(bottom = 24.dp),
		) {
			item {
				Text(
					text = stringResource(R.string.space_switcher_title),
					style = MaterialTheme.typography.titleLarge,
					modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
				)
			}
			item {
				Column(modifier = Modifier.selectableGroup()) {
					state.spaces.forEach { context ->
						SpaceRow(
							context = context,
							selected = context.id == state.activeSpaceId,
							enabled = !state.switchInProgress,
							resumeItem = resumeItems[context.id],
							onResume = { onResume(context.id) },
							onClick = { onAction(SpaceAction.SelectSpace(context.id)) },
						)
					}
				}
			}
			if (state.switchInProgress) {
				item {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(8.dp),
						contentAlignment = Alignment.Center,
					) {
						CircularProgressIndicator(modifier = Modifier.size(32.dp))
					}
				}
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
) {
	val presentation = context.presentation()
	val hapticFeedback = LocalHapticFeedback.current
	Row(
		modifier = Modifier
			.fillMaxWidth()
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
			.padding(horizontal = 24.dp, vertical = 12.dp),
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
				style = MaterialTheme.typography.titleMedium,
			)
			resumeItem?.let { item ->
				Text(
					text = stringResource(R.string.space_recent_context, item.title),
					style = MaterialTheme.typography.bodySmall,
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
		RadioButton(selected = selected, onClick = null, enabled = enabled)
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
