package org.skepsun.kototoro.space.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId

@Composable
internal fun ViewSpaceSwitcherFab(
	activeSpaceId: SpaceId,
	activeSpace: SpaceContext?,
) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color.White.copy(alpha = 0.08f), CircleShape)
			.border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape),
	) {
		CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
			SpaceSwitcherIcon(
				activeSpaceId = activeSpaceId,
				activeSpace = activeSpace,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}
