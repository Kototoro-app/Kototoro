package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.isDarkTheme
import org.skepsun.kototoro.main.ui.navigation3.DiscoverNavKey
import org.skepsun.kototoro.main.ui.navigation3.ExploreNavKey
import org.skepsun.kototoro.main.ui.navigation3.FavoritesNavKey
import org.skepsun.kototoro.main.ui.navigation3.FeedNavKey
import org.skepsun.kototoro.main.ui.navigation3.HistoryNavKey
import org.skepsun.kototoro.main.ui.navigation3.LocalNavKey
import org.skepsun.kototoro.main.ui.navigation3.SuggestionsNavKey
import org.skepsun.kototoro.main.ui.navigation3.TopLevelNavKey
import org.skepsun.kototoro.main.ui.navigation3.UpdatedNavKey
import kotlin.math.max

@Composable
internal fun BoxScope.TopChromeGlow(
    route: TopLevelNavKey?,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accents = when (route) {
        FavoritesNavKey, DiscoverNavKey, SuggestionsNavKey -> scheme.tertiary to scheme.primary
        HistoryNavKey, UpdatedNavKey -> scheme.secondary to scheme.tertiary
        ExploreNavKey, FeedNavKey -> scheme.primary to scheme.secondary
        LocalNavKey -> scheme.secondary to scheme.primary
        else -> scheme.primary to scheme.tertiary
    }
    val firstAccent = animateColorAsState(accents.first, tween(380), label = "top_glow_primary").value
    val secondAccent = animateColorAsState(accents.second, tween(380), label = "top_glow_secondary").value
    val strength = when {
        LocalBackgroundStyle.current.usesArtworkBackdrop -> 0.13f
        scheme.isDarkTheme() -> 0.32f
        else -> 0.23f
    }
    val motion = rememberInfiniteTransition(label = "top_glow_motion")
    val drift = motion.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "top_glow_drift",
    )

    Box(
        modifier = modifier
            .height(height)
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.16f
                    scaleY = 1.08f
                    translationX = size.width * 0.045f * drift.value
                    translationY = size.height * 0.018f * drift.value
                    alpha = 0.94f + 0.06f * drift.value
                }
                .drawWithCache {
                    val brush = Brush.radialGradient(
                        colors = listOf(firstAccent.copy(alpha = strength), Color.Transparent),
                        center = Offset(size.width * 0.16f, -size.height * 0.18f),
                        radius = max(size.width * 0.72f, size.height * 1.14f),
                    )
                    onDrawBehind { drawRect(brush) }
                },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.16f
                    scaleY = 1.08f
                    translationX = -size.width * 0.05f * drift.value
                    translationY = -size.height * 0.012f * drift.value
                    alpha = 0.94f - 0.06f * drift.value
                }
                .drawWithCache {
                    val brush = Brush.radialGradient(
                        colors = listOf(secondAccent.copy(alpha = strength * 0.78f), Color.Transparent),
                        center = Offset(size.width * 0.88f, size.height * 0.12f),
                        radius = max(size.width * 0.58f, size.height * 0.84f),
                    )
                    onDrawBehind { drawRect(brush) }
                },
        )
    }
}
