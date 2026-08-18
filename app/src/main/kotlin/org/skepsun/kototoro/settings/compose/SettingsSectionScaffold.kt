package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalSettingsContentTopInset = staticCompositionLocalOf { 0.dp }

@Composable
internal fun settingsContentTopInset(base: Dp = 0.dp): Dp = LocalSettingsContentTopInset.current + base

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSectionScaffold(
	title: String?,
	onNavigateUp: (() -> Unit)?,
	modifier: Modifier = Modifier,
	showTopBar: Boolean = true,
	searchContent: (@Composable () -> Unit)? = null,
	actions: (@Composable BoxScope.() -> Unit)? = null,
	content: @Composable () -> Unit,
) {
	if (showTopBar) {
		SettingsTopBarScaffold(
			title = title,
			onNavigateUp = onNavigateUp,
			modifier = modifier,
			searchContent = searchContent,
			actions = actions,
		) { innerPadding ->
			CompositionLocalProvider(
				LocalSettingsContentTopInset provides innerPadding.calculateTopPadding(),
			) {
				Box(
					modifier = Modifier.fillMaxSize(),
					content = { content() },
				)
			}
		}
	} else {
		Box(
			modifier = modifier.fillMaxSize(),
			content = { content() },
		)
	}
}
