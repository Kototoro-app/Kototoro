package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun VerticalRailAnimatedVisibility(
    animationKey: Any,
    index: Int,
    listState: LazyListState,
    isAnimationEnabled: Boolean = true,
    enableScrollLinkedAnimation: Boolean = true,
    animationFactor: Float = 1f,
    scaleFactor: Float = 1f,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    if (!isAnimationEnabled || animationFactor <= 0f || !enableScrollLinkedAnimation) {
        content(modifier)
        return
    }
    val entryProgress = remember(animationKey) { Animatable(0f) }
    val density = LocalDensity.current
    val initialOffsetPx = with(density) { 28.dp.toPx() } * animationFactor

    LaunchedEffect(animationKey, listState, index) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { item ->
                item.index == index || item.key == animationKey
            }
        }
            .distinctUntilChanged()
            .filter { it }
            .collectLatest {
                entryProgress.snapTo(0f)
                delay((index.coerceAtMost(8) * 24L))
                entryProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.84f,
                        stiffness = 420f,
                    ),
                )
            }
    }

    val clampedFactor = animationFactor.coerceIn(0f, 1f)
    val clampedScaleFactor = scaleFactor.coerceIn(0f, 1f)
    val animatedModifier = modifier.graphicsLayer {
        val entry = entryProgress.value
        alpha = 1f - ((1f - entry) * 0.54f * clampedFactor)
        val entryScale = 1f - ((1f - entry) * 0.03f * clampedScaleFactor * clampedFactor)
        scaleX = entryScale
        scaleY = entryScale
        translationY = (1f - entry) * initialOffsetPx
        translationX = 0f
    }
    content(animatedModifier)
}
