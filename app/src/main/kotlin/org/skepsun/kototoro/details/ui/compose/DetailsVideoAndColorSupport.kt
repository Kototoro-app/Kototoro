package org.skepsun.kototoro.details.ui.compose

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.asDrawable
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.VideoHeroBackdropLayer
import org.skepsun.kototoro.core.ui.compose.rememberDrawablePainter
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.theme.CoverDominantColor
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.parsers.model.Content

@Composable
internal fun rememberCoverDominantColor(
    content: Content?,
    coverUrl: String?,
): Color? {
    val context = LocalContext.current
    val cacheKey = remember(content?.source?.name, content?.url, coverUrl) {
        if (coverUrl.isNullOrBlank()) null
        else "${content?.source?.name.orEmpty()}\n${content?.url.orEmpty()}\n$coverUrl"
    }
    var dominantColor by remember(cacheKey) {
        mutableStateOf(cacheKey?.let(CoverDominantColor::cached))
    }
    val imageLoader = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BaseApp.BaseAppEntryPoint::class.java,
        ).imageLoader()
    }
    LaunchedEffect(cacheKey, coverUrl) {
        val key = cacheKey ?: run {
            dominantColor = null
            return@LaunchedEffect
        }
        CoverDominantColor.cached(key)?.let { cached ->
            dominantColor = cached
            return@LaunchedEffect
        }
        val probeCacheKey = sharedCoverMemoryCacheKey(
            sourceName = content?.source?.name,
            ownerKey = content?.url,
            url = coverUrl,
        )
        val request = ImageRequest.Builder(context)
            .data(coverUrl)
            .size(192, 192)
            .allowHardware(false)
            .apply {
                if (probeCacheKey != null) {
                    diskCacheKey(probeCacheKey)
                }
                content?.let { mangaExtra(it) }
            }
            .build()
        val extracted = runCatching {
            val result = imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
            val imageBitmap = result.image.toBitmap().asImageBitmap()
            withContext(Dispatchers.Default) {
                CoverDominantColor.extract(imageBitmap)
            }
        }.getOrNull()
        if (extracted != null) {
            CoverDominantColor.putCached(key, extracted)
        }
        dominantColor = extracted
    }
    return dominantColor
}

@Composable
internal fun VideoDetailsBackdrop(
    model: ImageRequest?,
    placeholderMemoryCacheKey: String?,
    snapshotKey: String?,
    backgroundColor: Color,
    height: Dp,
    scrollOffsetPxProvider: () -> Float,
    fadeDistancePx: Float,
    onLoadError: () -> Unit,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    scrollLinked: Boolean = true,
    blurRadius: Dp = 18.dp,
    animateMotion: Boolean = false,
    animationSpeedPercent: Int = 100,
) {
    if (model == null && snapshotKey == null) return
    val context = LocalContext.current
    val imageLoader: ImageLoader = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BaseApp.BaseAppEntryPoint::class.java,
        ).imageLoader()
    }
    val placeholderImage = remember(imageLoader, model, placeholderMemoryCacheKey, snapshotKey) {
        val memoryCache = imageLoader.memoryCache
        val primary = model?.memoryCacheKey?.let { key -> memoryCache?.get(MemoryCache.Key(key))?.image }
        primary
            ?: placeholderMemoryCacheKey?.let { key -> memoryCache?.get(MemoryCache.Key(key))?.image }
            ?: snapshotKey?.let(HeroCoverSnapshotStore::get)
    }
    val placeholderPainter = rememberDrawablePainter(placeholderImage?.asDrawable(context.resources))
    val artworkPainter = if (model != null) {
        rememberAsyncImagePainter(
            model = model,
            imageLoader = imageLoader,
            placeholder = placeholderPainter,
            error = placeholderPainter,
            onError = { onLoadError() },
        )
    } else {
        placeholderPainter
    }

    val artworkSlot: @Composable (Modifier) -> Unit = { artworkModifier ->
        if (artworkPainter != null) {
            Image(
                painter = artworkPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = artworkModifier.fillMaxSize(),
            )
        }
    }

    VideoHeroBackdropLayer(
        height = height,
        backgroundColor = backgroundColor,
        fullScreen = fullScreen,
        blurRadius = blurRadius,
        useRealtimeBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        scrollOffsetPxProvider = scrollOffsetPxProvider,
        fadeDistancePx = fadeDistancePx,
        scrollLinked = scrollLinked,
        animateMotion = animateMotion,
        animationSpeedPercent = animationSpeedPercent,
        diffusedArtwork = { m -> artworkSlot(m) },
        sharpArtwork = { m -> artworkSlot(m) },
        modifier = modifier,
    )
}

@Composable
internal fun VideoDetailsPlayButtonRow(
    playLabel: String,
    episodeSubtitle: String?,
    enabled: Boolean,
    showPlayFromFirst: Boolean,
    showIncognito: Boolean,
    onPlayClick: () -> Unit,
    onPlayFromFirstClick: () -> Unit,
    onIncognitoClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var menuAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val hasSecondaryMenu = enabled && (showPlayFromFirst || showIncognito)

    Row(
        modifier = Modifier.widthIn(max = 420.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPlayClick,
            enabled = enabled,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            modifier = Modifier
                .weight(1f, fill = false)
                .height(48.dp)
                .tvFocusable(shape = CircleShape, enabled = enabled, addFocusTarget = false),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = playLabel,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!episodeSubtitle.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = episodeSubtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        if (hasSecondaryMenu) {
            Box(
                modifier = Modifier.onGloballyPositioned { menuAnchorBounds = it.boundsInRoot() },
            ) {
                FilledTonalIconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(48.dp)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.options),
                    )
                }
                GlassDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(24.dp),
                    style = GlassDefaults.subtleStyle(),
                    useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
                    anchorBounds = menuAnchorBounds,
                ) {
                    if (showPlayFromFirst) {
                        CompactDropdownMenuItem(
                            text = { Text(stringResource(R.string.details_play_from_first)) },
                            onClick = {
                                menuExpanded = false
                                onPlayFromFirstClick()
                            },
                        )
                    }
                    if (showIncognito) {
                        CompactDropdownMenuItem(
                            text = { Text(stringResource(R.string.incognito_mode)) },
                            onClick = {
                                menuExpanded = false
                                onIncognitoClick()
                            },
                        )
                    }
                }
            }
        }
    }
}
