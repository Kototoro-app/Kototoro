package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Hero pager dots (up to seven), switching to an "n / total" counter for longer pagers. Shared with Windows. */
@Composable
fun HeroPagerIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
    pageCounter: String? = null,
    counterColor: Color = activeColor,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val showPageDots = pageCount <= HeroPagerMaxVisibleDots
    val effectivePageCounter = if (showPageDots) {
        pageCounter
    } else {
        pageCounter ?: "${currentPage.coerceIn(0, pageCount - 1) + 1} / $pageCount"
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        leadingIcon?.invoke()
        if (showPageDots) {
            repeat(pageCount) { index ->
                val isSelected = index == currentPage
                Box(
                    modifier = Modifier
                        .size(height = 8.dp, width = if (isSelected) 22.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) activeColor else inactiveColor),
                )
            }
        }
        if (effectivePageCounter != null) {
            Text(
                text = effectivePageCounter,
                style = MaterialTheme.typography.labelSmall,
                color = counterColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = if (showPageDots) 4.dp else 0.dp),
            )
        }
        trailingIcon?.invoke()
    }
}

private const val HeroPagerMaxVisibleDots = 7
