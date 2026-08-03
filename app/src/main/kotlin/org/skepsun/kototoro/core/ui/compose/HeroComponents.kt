package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
fun HeroBackdropCard(
    modifier: Modifier = Modifier,
    minHeight: Dp = 420.dp,
    shape: RoundedCornerShape = RoundedCornerShape(30.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    elevation: Dp = 6.dp,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight),
        ) {
            // Fallback gradient shown when AsyncImage has no cover or is still loading
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ),
                    ),
            )
            background()
            content()
        }
    }
}

@Composable
fun HeroBackdropScrim(
    modifier: Modifier = Modifier,
    verticalColors: List<Color> = listOf(
        MaterialTheme.colorScheme.surface.copy(alpha = 0.20f),
        MaterialTheme.colorScheme.surface.copy(alpha = 0.44f),
        MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ),
    horizontalColors: List<Color> = listOf(
        MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
        Color.Transparent,
        MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
    ),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = verticalColors)),
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(colors = horizontalColors)),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroAutoAdvanceEffect(
    pagerState: PagerState,
    pageCount: Int,
    intervalMillis: Long = 4500L,
    enabled: Boolean = true,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, pagerState, pageCount, intervalMillis, enabled) {
        if (!enabled || pageCount <= 1) {
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(intervalMillis)
                if (!pagerState.isScrollInProgress) {
                    pagerState.animateScrollToPage((pagerState.currentPage + 1) % pageCount)
                }
            }
        }
    }
}

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
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }
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
        if (trailingIcon != null) {
            trailingIcon()
        }
    }
}

private const val HeroPagerMaxVisibleDots = 7
