package org.skepsun.kototoro.space.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
			Icon(
				painter = painterResource(presentation.iconRes),
				contentDescription = null,
			)
		},
		text = { Text(stringResource(presentation.labelRes)) },
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
	val presentation = activeSpaceId.presentation()
	IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
		Icon(
			painter = painterResource(presentation.iconRes),
			contentDescription = stringResource(
				R.string.space_switcher_content_description,
				stringResource(presentation.labelRes),
			),
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceSwitcherSheet(
	state: SpaceUiState,
	onAction: (SpaceAction) -> Unit,
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
	onClick: () -> Unit,
) {
	val presentation = context.id.presentation()
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.selectable(
				selected = selected,
				enabled = enabled,
				onClick = onClick,
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
		Text(
			text = stringResource(presentation.labelRes),
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.weight(1f),
		)
		RadioButton(selected = selected, onClick = null, enabled = enabled)
	}
}

private data class SpacePresentation(
	@StringRes val labelRes: Int,
	@DrawableRes val iconRes: Int,
)

private fun SpaceId.presentation(): SpacePresentation = when (this) {
	BuiltInSpaces.Novel -> SpacePresentation(R.string.space_novel, R.drawable.ic_content_novel)
	BuiltInSpaces.Anime -> SpacePresentation(R.string.space_anime, R.drawable.ic_content_video)
	else -> SpacePresentation(R.string.space_manga, R.drawable.ic_content_manga)
}
