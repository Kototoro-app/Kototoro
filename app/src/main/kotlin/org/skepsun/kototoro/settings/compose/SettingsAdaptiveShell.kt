package org.skepsun.kototoro.settings.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
			Box(
				modifier = Modifier
					.fillMaxSize()
					.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
			) {
				saveableStateHolder.SaveableStateProvider(destinationKey(targetDestination)) {
					destinationContent(targetDestination)
				}
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
		Box(
			modifier = Modifier
				.fillMaxHeight()
				.width(SettingsListPaneWidth)
				.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
		) {
			rootContent(Modifier.fillMaxSize())
		}
		Box(
			modifier = Modifier
				.fillMaxHeight()
				.width(1.dp)
				.background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f)),
		)
		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxHeight()
				.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)),
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
