package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
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

/** Fraction of the glow's height after which it fades out, so its clipped bottom edge is clear. */
private const val TOP_GLOW_FADE_START = 0.45f

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
    // Static on purpose: an infinite drift here re-rendered the whole window (glass chrome
    // samples this layer) at the panel refresh rate on every idle top-level page.
    Box(
        modifier = modifier
            .height(height)
            .clipToBounds()
            // The glow radii follow the window width, so on a landscape tablet they reach far past
            // this box and clipping left a hard colour edge across the screen. Fading the whole
            // layer to nothing over its lower part keeps the edge invisible at any width.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        TOP_GLOW_FADE_START to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.16f
                    scaleY = 1.08f
                    alpha = 0.94f
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
                    alpha = 0.94f
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
