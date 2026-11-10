package org.skepsun.kototoro.core.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens

/** Shared hero frame; artwork loading and platform focus/transition modifiers remain host adapters. */
@Composable
fun HomeHeroCardSurface(
    onClick: () -> Unit,
    background: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    cardHeight: Dp = HomeHeroCardHeight,
    artworkScrim: Boolean = true,
    interactionModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.fillMaxWidth().height(cardHeight * LocalDensity.current.fontScale.coerceAtLeast(1f))
            .clip(RoundedCornerShape(LocalInterfaceStyleTokens.current.sectionCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(interactionModifier).clickable(onClick = onClick),
    ) {
        background()
        if (artworkScrim) Box(Modifier.fillMaxSize().drawBehind { drawRect(HomeHeroArtworkScrim) })
        content()
    }
}

/** Android's standard/details hero body, including the split-artwork alignment. */
@Composable
fun HomeHeroStandardContent(
    poster: @Composable (width: Dp, height: Dp) -> Unit,
    text: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    splitArtwork: Boolean = false,
    posterWidth: Dp = 72.dp,
    posterHeight: Dp = 112.dp,
) {
    Row(modifier.fillMaxSize()) {
        if (splitArtwork) Spacer(Modifier.weight(0.46f))
        Row(
            Modifier.weight(if (splitArtwork) 0.54f else 1f).fillMaxHeight().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!splitArtwork) poster(posterWidth, posterHeight)
            text(Modifier.weight(1f))
        }
    }
}
