package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.parsers.model.ContentType

private val CompactFilterChipSize = 32.dp

/** A fixed-center, three-slot swipe filter: Video | Manga | Novel. */
@Composable
fun SwipeableFilterChip(
    selectedType: ContentType?,
    enabledTypes: Set<ContentType>,
    onTypeSelected: (ContentType?) -> Unit,
    controlSize: Dp = CompactFilterChipSize,
    iconSize: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnTypeSelected by rememberUpdatedState(onTypeSelected)
    var displayedSelectedType by remember { mutableStateOf(selectedType) }
    LaunchedEffect(selectedType) {
        displayedSelectedType = selectedType
    }
    val swipeThresholdPx = with(density) { 28.dp.toPx() }

    val expansion = remember { Animatable(0f) }
    var isPressed by remember { mutableStateOf(false) }
    var highlightIndex by remember { mutableIntStateOf(1) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    val types = listOf(ContentType.VIDEO, ContentType.MANGA, ContentType.NOVEL)

    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val icons = listOf(
        painterResource(R.drawable.ic_content_video),
        painterResource(R.drawable.ic_content_manga),
        painterResource(R.drawable.ic_content_novel),
    )
    val iconAll = painterResource(R.drawable.ic_filter_content_type)
    val filterDescription = stringResource(R.string.content_type_filter)
    val exp = expansion.value
    val slotWidth = controlSize * (1f + exp)
    val panelWidth = controlSize * swipeableFilterChipWidthMultiplier(exp)
    val panelShape = RoundedCornerShape(999.dp)
    val backdrop = LocalLiquidGlassBackdrop.current
    val useBackdrop = exp > 0.01f && LocalInterfaceStyle.current == InterfaceStyle.IOS && backdrop != null
    val exportedBackdrop = if (useBackdrop) rememberLayerBackdrop() else null

    fun selectCenterType(): Boolean {
        if (ContentType.MANGA !in enabledTypes) return false
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val nextType = resolveSwipeableFilterTapSelection(displayedSelectedType)
        displayedSelectedType = nextType
        currentOnTypeSelected(nextType)
        return true
    }

    Box(
        modifier = modifier
            .width(slotWidth)
            .height(controlSize)
            .semantics {
                role = Role.Button
                contentDescription = filterDescription
                onClick { selectCenterType() }
            }
            .pointerInput(enabledTypes, displayedSelectedType) {
                if (enabledTypes.isEmpty()) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()

                    isPressed = true
                    highlightIndex = displayedSelectedType
                        ?.toSwipeableIndex()
                        ?.takeIf { types[it] in enabledTypes }
                        ?: ContentType.MANGA.toSwipeableIndex()!!.takeIf { types[it] in enabledTypes }
                        ?: types.indexOfFirst { it in enabledTypes }.takeIf { it >= 0 }
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
                            val change = event.changes.firstOrNull() ?: break
                            val moveX = change.positionChange().x
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
                    } catch (_: CancellationException) {
                        dragCanceled = true
                    }

                    isPressed = false
                    if (!dragCanceled) {
                        val newType = types[highlightIndex]
                        if (newType in enabledTypes) {
                            val finalType = if (newType.toSwipeableIndex() == displayedSelectedType?.toSwipeableIndex()) {
                                null
                            } else {
                                newType
                            }
                            displayedSelectedType = finalType
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
    ) {
        Box(
            modifier = Modifier
                .requiredWidth(panelWidth)
                .height(controlSize)
                .offset(x = controlSize * 0.5f * exp)
                .zIndex(1f)
                .then(
                    if (exp > 0.01f) {
                        if (useBackdrop) {
                            Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.22f), panelShape)
                                .drawBackdrop(
                                    backdrop = backdrop!!,
                                    exportedBackdrop = exportedBackdrop!!,
                                    shape = { panelShape },
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
                            Modifier.background(surfaceVariant.copy(alpha = 0.92f), panelShape)
                        }
                    } else {
                        Modifier
                    },
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
            val baseW = with(density) { controlSize.toPx() }
            val h = size.height
            val totalW = baseW * (1f + 2f * exp)
            val radius = h / 2f
            val cornerRadius = CornerRadius(radius, radius)
            val filterIconSize = with(density) { iconSize.toPx() }

            if (exp > 0.01f && isPressed) {
                val highlightX = when (highlightIndex) {
                    0 -> 0f
                    2 -> baseW * 2f * exp
                    else -> baseW * exp
                }
                drawRoundRect(
                    color = primaryContainer.copy(alpha = exp),
                    topLeft = Offset(highlightX, 0f),
                    size = Size(baseW, h),
                    cornerRadius = cornerRadius,
                )
            }

            if (exp > 0.01f) {
                for (i in 0..2) {
                    val isEnabled = types[i] in enabledTypes
                    val centerX = when (i) {
                        0 -> baseW / 2f
                        2 -> baseW / 2f + baseW * 2f * exp
                        else -> baseW / 2f + baseW * exp
                    }
                    val isHighlighted = i == highlightIndex
                    val alpha = if (isEnabled) exp * if (isHighlighted) 1f else 0.5f else exp * 0.24f
                    val tint = if (isEnabled && isHighlighted) onPrimaryContainer else onSurfaceVariant
                    translate(left = centerX - filterIconSize / 2f, top = (h - filterIconSize) / 2f) {
                        with(icons[i]) {
                            draw(
                                size = Size(filterIconSize, filterIconSize),
                                alpha = alpha,
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                            )
                        }
                    }
                }
            }
            }
        }
        if (exp < 0.99f && !isPressed) {
            Canvas(
                modifier = Modifier
                    .width(controlSize)
                    .height(controlSize)
                    .zIndex(2f),
            ) {
                val filterIconSize = with(density) { iconSize.toPx() }
                val iconPadding = (size.width - filterIconSize) / 2f
                val collapsedIcon = when (displayedSelectedType) {
                    ContentType.VIDEO, ContentType.HENTAI_VIDEO -> icons[0]
                    ContentType.MANGA, ContentType.HENTAI_MANGA -> icons[1]
                    ContentType.NOVEL, ContentType.HENTAI_NOVEL -> icons[2]
                    else -> iconAll
                }
                val tint = if (displayedSelectedType != null) onPrimaryContainer else onSurfaceVariant
                translate(left = iconPadding, top = (size.height - filterIconSize) / 2f) {
                    with(collapsedIcon) {
                        draw(
                            size = Size(filterIconSize, filterIconSize),
                            alpha = 1f - exp,
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                        )
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

internal fun swipeableFilterChipWidthMultiplier(expansion: Float): Float =
    1f + 2f * expansion.coerceIn(0f, 1f)

internal fun resolveSwipeableFilterIndex(dragOffset: Float, threshold: Float): Int {
    val safeThreshold = threshold.coerceAtLeast(0f)
    return when {
        dragOffset < -safeThreshold -> 0
        dragOffset > safeThreshold -> 2
        else -> 1
    }
}

internal fun resolveSwipeableFilterTapSelection(selectedType: ContentType?): ContentType? =
    if (selectedType == ContentType.MANGA || selectedType == ContentType.HENTAI_MANGA) null else ContentType.MANGA
