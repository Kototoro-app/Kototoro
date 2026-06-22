package org.skepsun.kototoro.settings.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.settings.SettingsDestination

private val SettingsListPaneWidth = 360.dp

@Composable
fun SettingsAdaptiveShell(
	isTwoPane: Boolean,
	destination: SettingsDestination?,
	destinationKey: (SettingsDestination) -> String,
	modifier: Modifier = Modifier,
	rootContent: @Composable (Modifier) -> Unit,
	destinationContent: @Composable (SettingsDestination) -> Unit,
) {
	if (isTwoPane) {
		SettingsTwoPaneShell(
			destination = destination ?: SettingsDestination.Root,
			modifier = modifier,
			rootContent = rootContent,
			destinationContent = destinationContent,
		)
	} else {
		SettingsSinglePaneShell(
			destination = destination,
			destinationKey = destinationKey,
			modifier = modifier,
			destinationContent = destinationContent,
		)
	}
}

@Composable
private fun SettingsSinglePaneShell(
	destination: SettingsDestination?,
	destinationKey: (SettingsDestination) -> String,
	modifier: Modifier = Modifier,
	destinationContent: @Composable (SettingsDestination) -> Unit,
) {
	val saveableStateHolder = rememberSaveableStateHolder()
	AnimatedContent(
		targetState = destination,
		modifier = modifier,
		label = "settings_page",
	) { targetDestination ->
		if (targetDestination != null) {
			saveableStateHolder.SaveableStateProvider(destinationKey(targetDestination)) {
				destinationContent(targetDestination)
			}
		}
	}
}

@Composable
private fun SettingsTwoPaneShell(
	destination: SettingsDestination,
	modifier: Modifier = Modifier,
	rootContent: @Composable (Modifier) -> Unit,
	destinationContent: @Composable (SettingsDestination) -> Unit,
) {
	Row(modifier = modifier.fillMaxSize()) {
		rootContent(
			Modifier
				.fillMaxHeight()
				.width(SettingsListPaneWidth),
		)
		Box(
			modifier = Modifier
				.fillMaxHeight()
				.width(1.dp)
				.background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f)),
		)
		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxHeight(),
		) {
			if (destination == SettingsDestination.Root) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(MaterialTheme.colorScheme.background),
				)
			} else {
				destinationContent(destination)
			}
		}
	}
}
