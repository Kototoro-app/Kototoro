package org.skepsun.kototoro.video.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig

/** Frames spent claiming the lock overlay focus, which is the only focus target while locked. */
private const val LOCK_FOCUS_FRAME_ATTEMPTS = 6

@Composable
internal fun VideoScreenLockOverlay(
    locked: Boolean,
    unlockButtonVisible: Boolean,
    onLockedAreaClick: () -> Unit,
    onUnlockClick: () -> Unit,
) {
    if (!locked) return
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    Box(
        contentAlignment = Alignment.Center,
        modifier = if (isTvPresentation) {
            // A remote can neither tap the screen to reveal the button nor move focus by itself,
            // so the TV shows the unlock target from the start instead of a silent blank frame.
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().clickable(onClick = onLockedAreaClick)
        },
    ) {
        AnimatedVisibility(
            visible = unlockButtonVisible || isTvPresentation,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (isTvPresentation) {
                TvUnlockButton(onUnlockClick = onUnlockClick)
            } else {
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
}

/**
 * Remote-friendly unlock target: visible, labelled and focused as soon as the screen locks so the
 * first OK press unlocks instead of doing nothing.
 */
@Composable
private fun TvUnlockButton(onUnlockClick: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    LaunchedEffect(Unit) {
        repeat(LOCK_FOCUS_FRAME_ATTEMPTS) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(56.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.72f))
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onUnlockClick)
            .padding(horizontal = 28.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.LockOpen,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = stringResource(R.string.video_screen_unlock),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
