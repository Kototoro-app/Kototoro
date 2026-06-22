package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSectionScaffold(
	title: String,
	onNavigateUp: (() -> Unit)?,
	modifier: Modifier = Modifier,
	showTopBar: Boolean = true,
	actions: (@Composable BoxScope.() -> Unit)? = null,
	content: @Composable () -> Unit,
) {
	if (showTopBar) {
		SettingsTopBarScaffold(
			title = title,
			onNavigateUp = onNavigateUp,
			modifier = modifier,
			actions = actions,
		) { innerPadding ->
			Box(
				modifier = Modifier
					.fillMaxSize()
					.padding(innerPadding),
				content = { content() },
			)
		}
	} else {
		Box(
			modifier = modifier.fillMaxSize(),
			content = { content() },
		)
	}
}
