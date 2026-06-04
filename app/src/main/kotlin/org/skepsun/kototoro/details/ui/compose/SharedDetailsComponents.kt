package org.skepsun.kototoro.details.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.memory.MemoryCache
import org.skepsun.kototoro.R
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.rememberDrawablePainter
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import androidx.compose.animation.ExperimentalSharedTransitionApi
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionPhase
import org.skepsun.kototoro.core.ui.compose.HeroTransitionPhase
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionInProgress
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DetailsCoverFrame(
    coverModel: Any?,
    contentDescription: String,
    showNsfwBadge: Boolean,
    sourceName: String? = null,
    ownerKey: String? = null,
    coverUrl: String? = null,
    sharedElementKey: String? = null,
    topBadgeText: String? = null,
    @DrawableRes topBadgeIconRes: Int? = null,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onState: ((coil3.compose.AsyncImagePainter.State) -> Unit)? = null,
) {
    val context = LocalContext.current
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val heroTransitionInProgress = LocalHeroTransitionInProgress.current
    val heroTransitionPhase = LocalHeroTransitionPhase.current
    val imageLoader = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BaseApp.BaseAppEntryPoint::class.java,
        ).imageLoader()
    }
    val cachedPlaceholder = remember(imageLoader, sourceName, ownerKey, coverUrl) {
        val cacheKey = sharedCoverMemoryCacheKey(
            sourceName = sourceName,
            ownerKey = ownerKey,
            url = coverUrl,
        ) ?: return@remember null
        imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey))?.image
    }
    val snapshotPlaceholder = remember(sharedElementKey) {
        sharedElementKey?.let(HeroCoverSnapshotStore::get)
    }
    val sharedPlaceholder = cachedPlaceholder ?: snapshotPlaceholder
    val cachedPainter = rememberDrawablePainter(sharedPlaceholder?.asDrawable(context.resources))
    val enableSharedElement = sharedElementKey != null &&
        sharedPlaceholder != null &&
        sharedTransitionScope != null &&
        animatedVisibilityScope != null
    var hasResolvedCover by remember(coverModel) { mutableStateOf(false) }
    val shouldFreezeCoverDuringEnter = enableSharedElement &&
        heroTransitionInProgress &&
        heroTransitionPhase == HeroTransitionPhase.EnteringDetails
    val shouldFreezeCoverDuringReturn = enableSharedElement &&
        heroTransitionInProgress &&
        heroTransitionPhase == HeroTransitionPhase.ReturningFromDetails
    val shouldHideResolvedCoverDuringTransition = shouldFreezeCoverDuringReturn
    val shouldShowStableForeground = sharedPlaceholder != null && (
        shouldFreezeCoverDuringEnter ||
            shouldFreezeCoverDuringReturn ||
            !hasResolvedCover
        )

    Box(
        modifier = modifier
            .width(132.dp)
            .shadow(
                elevation = 18.dp,
                shape = MaterialTheme.shapes.large,
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.28f),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.54f),
                shape = MaterialTheme.shapes.large,
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.large,
            )
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(13f / 18f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                        shape = MaterialTheme.shapes.medium,
                    ),
            )
            if (coverModel == null) {
                if (sharedPlaceholder != null) {
                    androidx.compose.foundation.Image(
                        painter = cachedPainter,
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(13f / 18f)
                            .then(
                                if (enableSharedElement) {
                                    with(sharedTransitionScope) {
                                        Modifier.sharedElement(
                                            rememberSharedContentState(key = sharedElementKey),
                                            animatedVisibilityScope = animatedVisibilityScope,
                                        )
                                    }
                                } else Modifier
                            )
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                                shape = MaterialTheme.shapes.medium,
                            ),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(13f / 18f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                                shape = MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_placeholder),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            } else {
                AsyncImage(
                    model = coverModel,
                    contentDescription = contentDescription,
                    imageLoader = imageLoader,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(13f / 18f)
                        .then(
                            if (enableSharedElement && !shouldShowStableForeground) {
                                with(sharedTransitionScope) {
                                    Modifier.sharedElement(
                                        rememberSharedContentState(key = sharedElementKey),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                    )
                                }
                            } else Modifier
                        )
                        .alpha(if (shouldShowStableForeground) 0f else 1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                            shape = MaterialTheme.shapes.medium,
                        ),
                    contentScale = ContentScale.Crop,
                    placeholder = cachedPainter,
                    error = cachedPainter,
                    fallback = cachedPainter,
                    onLoading = { state ->
                        hasResolvedCover = false
                        onState?.invoke(state)
                    },
                    onSuccess = { state ->
                        hasResolvedCover = true
                        onState?.invoke(state)
                    },
                    onError = { state ->
                        hasResolvedCover = false
                        onState?.invoke(state)
                    },
                )
                if (shouldShowStableForeground) {
                    androidx.compose.foundation.Image(
                        painter = cachedPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(13f / 18f)
                            .then(
                                if (enableSharedElement) {
                                    with(sharedTransitionScope) {
                                        Modifier.sharedElement(
                                            rememberSharedContentState(key = sharedElementKey),
                                            animatedVisibilityScope = animatedVisibilityScope,
                                        )
                                    }
                                } else Modifier
                            )
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                                shape = MaterialTheme.shapes.medium,
                            ),
                    )
                }
            }
            if (!topBadgeText.isNullOrBlank()) {
                val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val badgeContainerColor = if (isDarkTheme) {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.scrim.copy(alpha = 0.34f)
                }
                val badgeContentColor = if (isDarkTheme) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    Color.White
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = badgeContainerColor,
                    contentColor = badgeContentColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isDarkTheme) {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
                        } else {
                            Color.White.copy(alpha = 0.10f)
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        topBadgeIconRes?.let { iconRes ->
                            Icon(
                                painter = rememberSafePainter(iconRes),
                                contentDescription = null,
                                tint = badgeContentColor,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        Text(
                            text = topBadgeText,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = badgeContentColor,
                        )
                    }
                }
            }
            if (showNsfwBadge) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFD4322C),
                    contentColor = Color.White,
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.nsfw),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun DetailsHeaderActionButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.medium
    val containerColor = if (filled) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.94f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
    }
    val contentColor = if (filled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = if (filled) 2.dp else 0.dp,
        shadowElevation = if (filled) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor.copy(alpha = if (enabled) 1f else 0.6f),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor.copy(alpha = if (enabled) 1f else 0.6f),
                maxLines = 1,
            )
        }
    }
}

@Composable
fun DetailsHeaderIconButton(
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    if (filled) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            Box(
                modifier = Modifier
                    .combinedClickable(
                        enabled = enabled,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = rememberSafePainter(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun MetadataItem(
    label: String,
    value: String,
    @DrawableRes iconRes: Int? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(min = 56.dp),
        ) {
            iconRes?.let {
                Icon(
                    painter = rememberSafePainter(it),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun DetailsHeroBadge(
    text: String,
    @DrawableRes iconRes: Int? = null,
) {
    GlassSurface(
        style = GlassDefaults.subtleStyle(),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            iconRes?.let {
                Icon(
                    painter = rememberSafePainter(it),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

data class DetailsInfoItem(
    val label: String,
    val value: String,
    @DrawableRes val iconRes: Int? = null,
    val onClick: (() -> Unit)? = null,
)

data class DetailsHeroBadgeSpec(
    val text: String,
    @DrawableRes val iconRes: Int? = null,
)
