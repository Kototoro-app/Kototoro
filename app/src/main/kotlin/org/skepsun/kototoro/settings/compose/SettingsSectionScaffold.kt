package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSectionScaffold(
	title: String,
	onNavigateUp: () -> Unit,
	modifier: Modifier = Modifier,
	showTopBar: Boolean = true,
	actions: (@Composable BoxScope.() -> Unit)? = null,
	content: @Composable () -> Unit,
) {
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	val topBarContainerColor = if (expressive) {
		MaterialTheme.colorScheme.surfaceContainerLow
	} else {
		MaterialTheme.colorScheme.background
	}
	Column(
		modifier = modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
			.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
			.windowInsetsPadding(WindowInsets.navigationBars),
	) {
		if (showTopBar) {
			TopAppBar(
				title = {
					Text(
						text = title,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				},
				navigationIcon = {
					if (expressive) {
						FilledTonalIconButton(onClick = onNavigateUp) {
							Icon(
								imageVector = Icons.AutoMirrored.Filled.ArrowBack,
								contentDescription = null,
							)
						}
					} else {
						IconButton(onClick = onNavigateUp) {
							Icon(
								imageVector = Icons.AutoMirrored.Filled.ArrowBack,
								contentDescription = null,
							)
						}
					}
				},
				actions = {
					if (actions != null) {
						Box(
							modifier = Modifier.fillMaxHeight(),
							contentAlignment = Alignment.CenterEnd,
							content = actions,
						)
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = topBarContainerColor,
					titleContentColor = MaterialTheme.colorScheme.onSurface,
					navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
					actionIconContentColor = MaterialTheme.colorScheme.onSurface,
				),
				windowInsets = WindowInsets.statusBars,
			)
		}
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.weight(1f),
			content = { content() },
		)
	}
}
