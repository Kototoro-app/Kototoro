package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.parsers.model.ContentType
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

private val CompactFilterChipSize = 36.dp

@Composable
fun SwipeableFilterChip(
    selectedType: ContentType?,
    enabledTypes: Set<ContentType>,
    onTypeSelected: (ContentType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val currentSelectedType by rememberUpdatedState(selectedType)
    val currentOnTypeSelected by rememberUpdatedState(onTypeSelected)

    val swipeThresholdPx = with(density) { 24.dp.toPx() }

    // 0 = collapsed, 1 = fully expanded
    val expansion = remember { Animatable(0f) }
    var isPressed by remember { mutableStateOf(false) }
    var highlightIndex by remember { mutableIntStateOf(1) } // 0=left, 1=center, 2=right
    var dragOffsetX by remember { mutableStateOf(0f) }

    val types = listOf(ContentType.VIDEO, ContentType.MANGA, ContentType.NOVEL)

    // Colors
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    // Icons
    val iconVideo = painterResource(R.drawable.ic_content_video)
    val iconManga = painterResource(R.drawable.ic_content_manga)
    val iconNovel = painterResource(R.drawable.ic_content_novel)
    val iconAll = painterResource(R.drawable.ic_filter_content_type)

    val exp = expansion.value
    val animatedWidth = CompactFilterChipSize * swipeableFilterChipWidthMultiplier(exp)
    val backdrop = LocalLiquidGlassBackdrop.current
    val exportedBackdrop = rememberLayerBackdrop()
    val useBackdrop = LocalInterfaceStyle.current == InterfaceStyle.IOS && backdrop != null

    Box(
        modifier = modifier
            .width(animatedWidth)
            .height(CompactFilterChipSize)
            .then(
                if (useBackdrop) {
                    Modifier
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                        .drawBackdrop(
                            backdrop = backdrop,
                            exportedBackdrop = exportedBackdrop,
                            shape = { RoundedCornerShape(999.dp) },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx())
                                lens(
                                    refractionHeight = 8.dp.toPx(),
                                    refractionAmount = 8.dp.toPx(),
                                    chromaticAberration = false,
                                )
                            },
                        )
                } else {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(999.dp),
                    )
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .pointerInput(enabledTypes, currentSelectedType) {
                if (enabledTypes.isEmpty()) {
                    return@pointerInput
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume() // Consume immediately so DockedSearchBar doesn't trigger!

                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) {
                        return@awaitEachGesture
                    }
                    longPress.consume()

                    isPressed = true
                    highlightIndex = currentSelectedType
                        ?.toSwipeableIndex()
                        ?.takeIf { index -> types[index] in enabledTypes }
                        ?: types.indexOf(ContentType.MANGA).takeIf { index -> index >= 0 && types[index] in enabledTypes }
                        ?: types.indexOfFirst { type -> type in enabledTypes }.takeIf { it >= 0 }
                        ?: 1
                    dragOffsetX = 0f
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        expansion.animateTo(
                            1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                    }

                    var dragCanceled = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull()

                            if (change == null) break

                            // Read position change BEFORE consuming, otherwise it returns zero!
                            val moveX = change.positionChange().x
                            
                            // Consume the position change immediately to stop ANY parent scrolling/interactions
                            change.consume()
                            
                            if (moveX != 0f) {
                                dragOffsetX += moveX
                                val newIndex = resolveSwipeableFilterIndex(dragOffsetX, swipeThresholdPx)
                                if (newIndex != highlightIndex && types[newIndex] in enabledTypes) {
                                    highlightIndex = newIndex
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    } catch (e: CancellationException) {
                        dragCanceled = true
                    }

                    isPressed = false

                    if (!dragCanceled) {
                        val newType = types[highlightIndex]
                        if (newType in enabledTypes) {
                            val finalType = if (newType == currentSelectedType) null else newType
                            currentOnTypeSelected(finalType)
                        }
                    }

                    scope.launch {
                        expansion.animateTo(
                            0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val baseW = size.height
            val totalW = baseW * (1f + 2f * exp)
            val h = size.height
            val radius = h / 2f
            val cr = CornerRadius(radius, radius)
            val iconPadding = baseW * 0.25f
            val iconSize = baseW - iconPadding * 2f
            val centerSlotLeft = (totalW - baseW) / 2f

            if (exp > 0.01f) {
                drawRoundRect(
                    color = surfaceVariant.copy(alpha = exp),
                    topLeft = Offset.Zero,
                    size = Size(totalW, h),
                    cornerRadius = cr,
                )

                val highlightX = when (highlightIndex) {
                    0 -> centerSlotLeft - baseW
                    2 -> centerSlotLeft + baseW
                    else -> centerSlotLeft
                }
                drawRoundRect(
                    color = primaryContainer.copy(alpha = exp),
                    topLeft = Offset(highlightX, 0f),
                    size = Size(baseW, h),
                    cornerRadius = cr,
                )
            }

            if (exp < 0.99f && !isPressed) {
                // Collapsed: draw single icon
                val collapsedIcon = when (selectedType) {
                    ContentType.VIDEO, ContentType.HENTAI_VIDEO -> iconVideo
                    ContentType.MANGA, ContentType.HENTAI_MANGA -> iconManga
                    ContentType.NOVEL, ContentType.HENTAI_NOVEL -> iconNovel
                    else -> iconAll
                }
                val tint = if (selectedType != null) onPrimaryContainer else onSurfaceVariant
                val iconOffset = centerSlotLeft + (baseW - iconSize) / 2f
                translate(left = iconOffset, top = (h - iconSize) / 2f) {
                    with(collapsedIcon) {
                        draw(
                            size = Size(iconSize, iconSize),
                            alpha = 1f - exp,
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                        )
                    }
                }
            }

            if (exp > 0.01f) {
                // Expanded: draw 3 icons
                val icons = listOf(iconVideo, iconManga, iconNovel)
                for (i in 0..2) {
                    val isEnabled = types[i] in enabledTypes
                    val centerX = when (i) {
                        0 -> centerSlotLeft + baseW / 2f - baseW
                        2 -> centerSlotLeft + baseW / 2f + baseW
                        else -> centerSlotLeft + baseW / 2f
                    }
                    val isHighlighted = i == highlightIndex
                    val alpha = if (isEnabled) {
                        exp * if (isHighlighted) 1f else 0.5f
                    } else {
                        exp * 0.24f
                    }
                    val tint = if (isEnabled && isHighlighted) onPrimaryContainer else onSurfaceVariant

                    translate(
                        left = centerX - iconSize / 2f,
                        top = (h - iconSize) / 2f,
                    ) {
                        with(icons[i]) {
                            draw(
                                size = Size(iconSize, iconSize),
                                alpha = alpha,
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ContentType.toSwipeableIndex(): Int? = when (this) {
    ContentType.VIDEO, ContentType.HENTAI_VIDEO -> 0
    ContentType.MANGA, ContentType.HENTAI_MANGA -> 1
    ContentType.NOVEL, ContentType.HENTAI_NOVEL -> 2
    else -> null
}

internal fun swipeableFilterChipWidthMultiplier(expansion: Float): Float {
    return 1f + 2f * expansion.coerceIn(0f, 1f)
}

internal fun resolveSwipeableFilterIndex(dragOffset: Float, threshold: Float): Int {
    val safeThreshold = threshold.coerceAtLeast(0f)
    return when {
        dragOffset < -safeThreshold -> 0
        dragOffset > safeThreshold -> 2
        else -> 1
    }
}
