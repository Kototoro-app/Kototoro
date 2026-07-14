package org.skepsun.kototoro.space.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import org.skepsun.kototoro.R
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId

@Composable
fun SpaceSwitcherFab(
	activeSpaceId: SpaceId,
	expanded: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val presentation = activeSpaceId.presentation()
	val description = stringResource(
		R.string.space_switcher_content_description,
		stringResource(presentation.labelRes),
	)
	ExtendedFloatingActionButton(
		onClick = onClick,
		modifier = modifier.semantics { contentDescription = description },
		expanded = expanded,
		icon = {
			Crossfade(
				targetState = presentation,
				animationSpec = tween(SpaceMotion.IconCrossfadeMillis),
				label = "space_fab_icon",
			) { target ->
				Icon(
					painter = painterResource(target.iconRes),
					contentDescription = null,
				)
			}
		},
		text = {
			Crossfade(
				targetState = presentation,
				animationSpec = tween(SpaceMotion.IconCrossfadeMillis),
				label = "space_fab_label",
			) { target ->
				Text(stringResource(target.labelRes))
			}
		},
		containerColor = MaterialTheme.colorScheme.primaryContainer,
		contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
	)
}

@Composable
fun SpaceSwitcherRailButton(
	activeSpaceId: SpaceId,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	IconButton(
		onClick = onClick,
		modifier = modifier.size(48.dp),
	) {
		Crossfade(
			targetState = activeSpaceId,
			animationSpec = tween(SpaceMotion.IconCrossfadeMillis),
			label = "space_rail_icon",
		) { target ->
			SpaceSwitcherIcon(activeSpaceId = target)
		}
	}
}

@Composable
fun SpaceSwitcherIcon(
	activeSpaceId: SpaceId,
	modifier: Modifier = Modifier,
) {
	val presentation = activeSpaceId.presentation()
	Icon(
		painter = painterResource(presentation.iconRes),
		contentDescription = stringResource(
			R.string.space_switcher_content_description,
			stringResource(presentation.labelRes),
		),
		modifier = modifier,
	)
}

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
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.selectableGroup()
				.padding(bottom = 24.dp),
		) {
			Text(
				text = stringResource(R.string.space_switcher_title),
				style = MaterialTheme.typography.titleLarge,
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
			)
			BuiltInSpaces.contexts.forEach { context ->
				SpaceRow(
					context = context,
					selected = context.id == state.activeSpaceId,
					enabled = !state.switchInProgress,
					resumeItem = resumeItems[context.id],
					onResume = { onResume(context.id) },
					onClick = { onAction(SpaceAction.SelectSpace(context.id)) },
				)
			}
			if (state.switchInProgress) {
				Spacer(Modifier.height(8.dp))
				CircularProgressIndicator(
					modifier = Modifier
						.size(32.dp)
						.align(Alignment.CenterHorizontally),
				)
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
	val presentation = context.id.presentation()
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
		Icon(
			painter = painterResource(presentation.iconRes),
			contentDescription = null,
			modifier = Modifier.size(24.dp),
		)
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = stringResource(presentation.labelRes),
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

private fun SpaceId.presentation(): SpacePresentation = when (this) {
	BuiltInSpaces.Novel -> SpacePresentation(R.string.space_novel, R.drawable.ic_content_novel, R.drawable.ic_read)
	BuiltInSpaces.Anime -> SpacePresentation(R.string.space_anime, R.drawable.ic_content_video, R.drawable.ic_play)
	else -> SpacePresentation(R.string.space_manga, R.drawable.ic_content_manga, R.drawable.ic_read)
}
