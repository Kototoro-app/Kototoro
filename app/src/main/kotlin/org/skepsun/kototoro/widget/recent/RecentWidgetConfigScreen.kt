package org.skepsun.kototoro.widget.recent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

/** Maps the XML toolbar and full-width MaterialSwitch to the Compose configuration surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentWidgetConfigScreen(
	initialHasBackground: Boolean,
	onNavigateUp: () -> Unit,
	onDone: (Boolean) -> Unit,
) {
	var hasBackground by rememberSaveable(initialHasBackground) { mutableStateOf(initialHasBackground) }

	Scaffold(
		modifier = Modifier.fillMaxSize(),
		containerColor = MaterialTheme.colorScheme.background,
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		topBar = {
			TopAppBar(
				modifier = Modifier.windowInsetsPadding(
					WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
				),
				title = { Text(stringResource(R.string.recent_manga)) },
				navigationIcon = {
					IconButton(onClick = onNavigateUp) {
						Icon(
							imageVector = Icons.Default.Close,
							contentDescription = stringResource(R.string.close),
						)
					}
				},
				actions = {
					Button(
						onClick = { onDone(hasBackground) },
						modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.toolbar_button_margin)),
					) {
						Text(stringResource(R.string.done))
					}
				},
				windowInsets = WindowInsets.statusBars,
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
				),
			)
		},
	) { contentPadding ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(contentPadding)
				.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
				.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
		) {
			RowSwitch(
				checked = hasBackground,
				onCheckedChange = { hasBackground = it },
			)
		}
	}
}

@Composable
private fun RowSwitch(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = dimensionResource(R.dimen.list_spacing_large))
			.toggleable(
				value = checked,
				role = Role.Switch,
				onValueChange = onCheckedChange,
			)
			.padding(horizontal = dimensionResource(R.dimen.screen_padding)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = stringResource(R.string.background),
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.weight(1f),
		)
		Switch(
			checked = checked,
			onCheckedChange = null,
		)
	}
}

@Preview(showBackground = true)
@Composable
private fun RecentWidgetConfigScreenPreview() {
	KototoroTheme {
		RecentWidgetConfigScreen(
			initialHasBackground = true,
			onNavigateUp = {},
			onDone = {},
		)
	}
}
