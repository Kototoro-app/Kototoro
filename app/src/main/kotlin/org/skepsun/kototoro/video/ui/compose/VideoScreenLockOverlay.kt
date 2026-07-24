package org.skepsun.kototoro.video.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

@Composable
internal fun VideoScreenLockOverlay(
	locked: Boolean,
	unlockButtonVisible: Boolean,
	onLockedAreaClick: () -> Unit,
	onUnlockClick: () -> Unit,
) {
	if (!locked) return
	Box(
		contentAlignment = Alignment.Center,
		modifier = Modifier.fillMaxSize().clickable(onClick = onLockedAreaClick),
	) {
		AnimatedVisibility(visible = unlockButtonVisible, enter = fadeIn(), exit = fadeOut()) {
			IconButton(
				onClick = onUnlockClick,
				modifier = Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.58f), CircleShape),
			) {
				Icon(
					imageVector = Icons.Filled.LockOpen,
					contentDescription = stringResource(R.string.video_screen_unlock),
					tint = Color.White,
				)
			}
		}
	}
}
