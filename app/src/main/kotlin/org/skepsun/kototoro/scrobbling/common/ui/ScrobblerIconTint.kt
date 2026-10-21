package org.skepsun.kototoro.scrobbling.common.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

@Composable
fun scrobblerIconTint(
    @DrawableRes iconResId: Int,
    defaultTint: Color = LocalContentColor.current,
): Color = if (ScrobblerService.entries.any { it.iconResId == iconResId }) Color.Unspecified else defaultTint
