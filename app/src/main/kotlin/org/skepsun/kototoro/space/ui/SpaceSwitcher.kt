package org.skepsun.kototoro.space.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.text.BreakIterator
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.SpaceSwitcherPosition
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.LocalRootGlassMenuHost
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.core.util.ext.mangaExtra

private const val SPACE_SWITCHER_FAB_MIN_ALPHA = 0.60f

internal class SpacePanelDragDirection {

    enum class Value {
        UNDECIDED,
        HORIZONTAL,
        VERTICAL,
    }

    private var totalX = 0f
    private var totalY = 0f

    var value: Value = Value.UNDECIDED
        private set

    fun update(deltaX: Float, deltaY: Float, touchSlop: Float): Value {
        if (value != Value.UNDECIDED) return value

        totalX += deltaX
        totalY += deltaY
        if (kotlin.math.abs(totalX) < touchSlop && kotlin.math.abs(totalY) < touchSlop) {
            return value
        }

        value = if (kotlin.math.abs(totalX) >= kotlin.math.abs(totalY)) {
            Value.HORIZONTAL
        } else {
            Value.VERTICAL
        }
        return value
    }
}

@Composable
fun BoxScope.SpaceSidekick(
    state: SpaceUiState,
    onAction: (SpaceAction) -> Unit,
    resumeItems: Map<SpaceId, SpaceResumeItem> = emptyMap(),
    onResume: (SpaceId) -> Unit = {},
    visible: Boolean,
    position: SpaceSwitcherPosition = SpaceSwitcherPosition.TOP_RIGHT,
    modifier: Modifier = Modifier,
) {
    if (!state.switcherEnabled) return
    val isLeft = position == SpaceSwitcherPosition.TOP_LEFT ||
        position == SpaceSwitcherPosition.CENTER_LEFT
    val isCentered = position == SpaceSwitcherPosition.CENTER_LEFT ||
        position == SpaceSwitcherPosition.CENTER_RIGHT

    Box(modifier = modifier.zIndex(20f)) {
        AnimatedVisibility(
            visible = state.switcherVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAction(SpaceAction.DismissSwitcher) },
                    ),
            )
        }

        AnimatedVisibility(
            visible = state.switcherVisible,
            modifier = Modifier.align(if (isLeft) Alignment.CenterStart else Alignment.CenterEnd),
            enter = slideInHorizontally(initialOffsetX = { if (isLeft) -it else it }),
            exit = slideOutHorizontally(targetOffsetX = { if (isLeft) -it else it }),
        ) {
            SpaceSidekickPanel(
                state = state,
                onAction = onAction,
                resumeItems = resumeItems,
                onResume = onResume,
                onDismiss = { onAction(SpaceAction.DismissSwitcher) },
                isLeft = isLeft,
            )
        }

        AnimatedVisibility(
            visible = visible && !state.switcherVisible,
            modifier = Modifier
                .align(
                    when {
                        isCentered && isLeft -> Alignment.CenterStart
                        isCentered -> Alignment.CenterEnd
                        isLeft -> Alignment.TopStart
                        else -> Alignment.TopEnd
                    },
                )
                .then(if (isCentered) Modifier else Modifier.statusBarsPadding().padding(top = 72.dp)),
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { if (isLeft) -it else it }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { if (isLeft) -it else it }),
        ) {
            SpaceSidekickHandle(
                state = state,
                onOpen = { onAction(SpaceAction.OpenSwitcher) },
                position = position,
            )
        }
    }

    BackHandler(enabled = state.switcherVisible) {
        onAction(SpaceAction.DismissSwitcher)
    }
}

@Composable
internal fun BoxScope.SpaceSidekickHandle(
    state: SpaceUiState,
    onOpen: () -> Unit,
    position: SpaceSwitcherPosition,
    modifier: Modifier = Modifier,
) {
    val isLeft = position == SpaceSwitcherPosition.TOP_LEFT ||
        position == SpaceSwitcherPosition.CENTER_LEFT
    val activeSpace = state.spaces.firstOrNull { it.id == state.activeSpaceId }
    val presentation = activeSpace?.presentation() ?: state.activeSpaceId.presentation()
    val label = activeSpace?.title ?: stringResource(presentation.labelRes)
    val description = stringResource(R.string.space_switcher_content_description, label)
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val openDragThreshold = with(LocalDensity.current) { 36.dp.toPx() }
    val shape = if (isLeft) {
        RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
    }

    Box(
        modifier = modifier
            .size(width = 32.dp, height = 64.dp)
            .pointerInput(onOpen) {
                detectHorizontalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onHorizontalDrag = { change, amount ->
                        if ((isLeft && amount > 0f) || (!isLeft && amount < 0f)) {
                            change.consume()
                            dragDistance += kotlin.math.abs(amount)
                        }
                    },
                    onDragEnd = {
                        if (dragDistance >= openDragThreshold) onOpen()
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            }
            .tvFocusable(shape = shape, enabled = state.switcherEnabled, addFocusTarget = false)
            .clickable(
                enabled = state.switcherEnabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onOpen,
            )
            .semantics { contentDescription = description },
        contentAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        GlassSurface(
            modifier = Modifier
                .offset(x = if (isLeft) (-10).dp else 10.dp)
                .size(width = 32.dp, height = 64.dp)
                .graphicsLayer {
                    translationX = dragDistance.coerceAtMost(16f) * if (isLeft) 1f else -1f
                },
            style = GlassDefaults.topBarChromeStyle().copy(
                containerAlpha = 0.68f,
                borderAlpha = 0.28f,
            ),
            shape = shape,
            // Floating dock handle — pill chrome, not a bar panel.
            componentRole = GlassComponentRole.PillControl,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape),
                contentAlignment = if (isLeft) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                SpaceGlyph(
                    presentation = presentation,
                    monogram = activeSpace?.customMonogram(),
                    modifier = Modifier
                        .padding(
                            start = if (isLeft) 0.dp else 7.dp,
                            end = if (isLeft) 7.dp else 0.dp,
                        )
                        .size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SpaceSidekickPanel(
    state: SpaceUiState,
    onAction: (SpaceAction) -> Unit,
    resumeItems: Map<SpaceId, SpaceResumeItem>,
    onResume: (SpaceId) -> Unit,
    onDismiss: () -> Unit,
    isLeft: Boolean,
) {
    var horizontalDragOffset by remember(isLeft) { mutableFloatStateOf(0f) }
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val defaultFocusRequester = remember { FocusRequester() }
    val defaultFocusIndex = remember(state.spaces, state.activeSpaceId) {
        state.spaces.indexOfFirst { it.id == state.activeSpaceId }.takeIf { it >= 0 } ?: 0
    }
    LaunchedEffect(isTvPresentation, state.switcherVisible, defaultFocusIndex) {
        if (isTvPresentation && state.switcherVisible) {
            withFrameNanos { }
            runCatching { defaultFocusRequester.requestFocus() }
        }
    }
    val dismissThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val shape = if (isLeft) {
        RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
    } else {
        RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
    }
    GlassSurface(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.68f)
            .widthIn(max = 280.dp)
            .graphicsLayer { translationX = horizontalDragOffset }
            .pointerInput(isLeft, onDismiss) {
                var dismissed = false
                try {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val dragDirection = SpacePanelDragDirection()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: break
                            val delta = change.positionChange()
                            if (
                                dragDirection.update(
                                    deltaX = delta.x,
                                    deltaY = delta.y,
                                    touchSlop = viewConfiguration.touchSlop,
                                ) == SpacePanelDragDirection.Value.HORIZONTAL
                            ) {
                                change.consume()
                                val isOutwardDrag = (isLeft && delta.x < 0f) || (!isLeft && delta.x > 0f)
                                if (isOutwardDrag || horizontalDragOffset != 0f) {
                                    horizontalDragOffset = if (isLeft) {
                                        (horizontalDragOffset + delta.x).coerceAtMost(0f)
                                    } else {
                                        (horizontalDragOffset + delta.x).coerceAtLeast(0f)
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (dragDirection.value != SpacePanelDragDirection.Value.HORIZONTAL) {
                            return@awaitEachGesture
                        }
                        if (kotlin.math.abs(horizontalDragOffset) >= dismissThreshold) {
                            dismissed = true
                            onDismiss()
                            // Keep the dragged offset: the drawer is still composed
                            // during its slide-out exit, and this box's graphicsLayer
                            // translation is what the exit animates from. Resetting to 0
                            // here snaps the drawer back to its origin for one frame
                            // before it exits ("flash back then slide away").
                        } else {
                            horizontalDragOffset = 0f
                        }
                    }
                } finally {
                    if (!dismissed) horizontalDragOffset = 0f
                }
            },
        style = GlassDefaults.topBarChromeStyle().copy(
            containerAlpha = 0.94f,
            borderAlpha = 0.22f,
        ),
        shape = shape,
        pressFeedbackEnabled = false,
        // Floating workbench panel — pill chrome, not an edge-to-edge bar.
        componentRole = GlassComponentRole.PillControl,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.56f), shape)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.space_workbench_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
            )
            Text(
                text = stringResource(R.string.space_workbench_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 14.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isTvPresentation) Modifier.focusGroup() else Modifier)
                    .selectableGroup(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            ) {
                state.spaces.forEachIndexed { index, context ->
                    item(key = context.id.value) {
                        val selected = context.id == state.activeSpaceId
                        val resumeItem = resumeItems[context.id]
                        val coverRequest = rememberSidekickCoverRequest(resumeItem)
                        val cardShape = RoundedCornerShape(20.dp)
                        Surface(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            shape = cardShape,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f)
                            },
                            border = BorderStroke(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                                },
                            ),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 132.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (coverRequest != null) {
                                    AsyncImage(
                                        model = coverRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.horizontalGradient(
                                                0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                                0.62f to MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                                1f to MaterialTheme.colorScheme.surface.copy(alpha = 0.46f),
                                            ),
                                        ),
                                )
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                    )
                                }
                                SpaceSidekickCardContent(
                                    context = context,
                                    selected = selected,
                                    enabled = !state.switchInProgress,
                                    resumeItem = resumeItem,
                                    onResume = { onResume(context.id) },
                                    onClick = { onAction(SpaceAction.SelectSpace(context.id)) },
                                    focusRequester = defaultFocusRequester.takeIf { index == defaultFocusIndex },
                                )
                            }
                        }
                    }
                }
                if (state.switchInProgress) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaceSidekickCardContent(
    context: SpaceContext,
    selected: Boolean,
    enabled: Boolean,
    resumeItem: SpaceResumeItem?,
    onResume: () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val presentation = context.presentation()
    val hapticFeedback = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .tvFocusable(shape = RoundedCornerShape(20.dp), enabled = enabled, addFocusTarget = false)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onClick()
                },
            )
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(end = 42.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpaceGlyph(
                presentation = presentation,
                monogram = context.customMonogram(),
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = context.title ?: stringResource(presentation.labelRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (resumeItem?.canResume == true) {
            Text(
                text = resumeItem.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(top = 30.dp, end = 56.dp, bottom = 24.dp),
            )
        }
        if (selected) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }
        if (resumeItem?.canResume == true) {
            IconButton(
                onClick = onResume,
                enabled = enabled,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(48.dp),
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(presentation.resumeIconRes),
                            contentDescription = stringResource(
                                R.string.space_continue_content_description,
                                resumeItem.title,
                            ),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberSidekickCoverRequest(resumeItem: SpaceResumeItem?): ImageRequest? {
    val localContext = LocalContext.current
    val content = resumeItem?.content
    val coverUrl = content?.coverUrl?.takeIf { it.isNotBlank() }
    val activeRequest = remember(localContext, content?.id, coverUrl) {
        content?.takeIf { coverUrl != null }?.let {
            val cacheKey = contentCoverCacheKey(it, coverUrl)
            ImageRequest.Builder(localContext)
                .data(coverUrl)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .apply { mangaExtra(it) }
                .diskCachePolicy(CachePolicy.READ_ONLY)
                .networkCachePolicy(CachePolicy.DISABLED)
                .crossfade(true)
                .build()
        }
    }
    // Keep the last rendered cover while the resume item transiently reloads
    // (e.g. during a space switch the resume flows restart and briefly emit
    // null), so cards never blank out and re-fetch visibly.
    val lastNonBlankRequest = remember(localContext) { mutableStateOf<ImageRequest?>(null) }
    if (activeRequest != null) {
        lastNonBlankRequest.value = activeRequest
    }
    return activeRequest ?: lastNonBlankRequest.value
}

@Composable
fun SpaceSwitcherFab(
    activeSpaceId: SpaceId,
    activeSpace: SpaceContext? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = activeSpace?.presentation() ?: activeSpaceId.presentation()
    val label = activeSpace?.title ?: stringResource(presentation.labelRes)
    val iconState = SpaceIconState(presentation, activeSpace?.customMonogram())
    val description = stringResource(
        R.string.space_switcher_content_description,
        label,
    )
    val colorScheme = MaterialTheme.colorScheme
    val fabAccentColor = colorScheme.primaryContainer
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val fabShape = CircleShape
    val fabModifier = modifier
        .tvFocusable(shape = fabShape, addFocusTarget = false)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
        .semantics { contentDescription = description }
    val content: @Composable BoxScope.() -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = if (isIosStyle) {
                        colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        fabAccentColor.copy(alpha = SPACE_SWITCHER_FAB_MIN_ALPHA)
                    },
                    shape = fabShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides if (isIosStyle) {
                    colorScheme.onSurface
                } else {
                    colorScheme.onPrimaryContainer
                },
            ) {
                SpaceGlyph(
                    presentation = iconState.presentation,
                    monogram = iconState.monogram,
                    modifier = if (isIosStyle) Modifier.size(25.dp) else Modifier,
                )
            }
        }
    }
    GlassSurface(
        modifier = fabModifier,
        style = GlassDefaults.topBarChromeStyle().copy(
            containerAlpha = SPACE_SWITCHER_FAB_MIN_ALPHA,
            borderAlpha = 0.24f,
        ),
        shape = fabShape,
        // Floating FAB — pill chrome, not a bar panel.
        componentRole = GlassComponentRole.PillControl,
        content = content,
    )
}

@Composable
fun SpaceSwitcherRailButton(
    activeSpaceId: SpaceId,
    activeSpace: SpaceContext? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .tvFocusable(shape = CircleShape, addFocusTarget = false),
    ) {
        SpaceSwitcherIcon(activeSpaceId = activeSpaceId, activeSpace = activeSpace)
    }
}

@Composable
fun SpaceSwitcherIcon(
    activeSpaceId: SpaceId,
    activeSpace: SpaceContext? = null,
    modifier: Modifier = Modifier,
) {
    val presentation = activeSpace?.presentation() ?: activeSpaceId.presentation()
    val label = activeSpace?.title ?: stringResource(presentation.labelRes)
    Box(
        modifier = modifier.semantics {
            contentDescription = label
        },
        contentAlignment = Alignment.Center,
    ) {
        SpaceGlyph(presentation, activeSpace?.customMonogram())
    }
}

@Composable
internal fun spaceDisplayLabel(spaceId: SpaceId, space: SpaceContext?): String =
    space?.title ?: stringResource((space?.presentation() ?: spaceId.presentation()).labelRes)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceSwitcherSheet(
    state: SpaceUiState,
    onAction: (SpaceAction) -> Unit,
    resumeItems: Map<SpaceId, SpaceResumeItem> = emptyMap(),
    onResume: (SpaceId) -> Unit = {},
    anchorBounds: Rect? = null,
    useGlobalRootMenu: Boolean = false,
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    val rootMenuHost = LocalRootGlassMenuHost.current
    if (!state.switcherVisible) return
    if (useGlobalRootMenu && (anchorBounds == null || backdrop == null || rootMenuHost == null)) return
    val compactMenu = useGlobalRootMenu
    val menuContent: @Composable ColumnScope.() -> Unit = {
        if (compactMenu) {
            CompactDropdownMenuText(
                text = stringResource(R.string.space_switcher_title),
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.space_switcher_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        state.spaces.forEach { context ->
            SpaceRow(
                context = context,
                selected = context.id == state.activeSpaceId,
                enabled = !state.switchInProgress,
                resumeItem = resumeItems[context.id],
                onResume = { onResume(context.id) },
                onClick = { onAction(SpaceAction.SelectSpace(context.id)) },
                compactMenu = compactMenu,
            )
        }
        if (state.switchInProgress) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
    }
    if (useGlobalRootMenu) {
        GlassDropdownMenu(
            expanded = true,
            onDismissRequest = { onAction(SpaceAction.DismissSwitcher) },
            anchorBounds = anchorBounds,
            useRootOverlay = true,
            openAboveAnchor = true,
        ) {
            menuContent()
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = { onAction(SpaceAction.DismissSwitcher) },
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(0.dp),
            dragHandle = null,
        ) {
            KototoroSheetSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SheetDragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                    ) {
                        item { menuContent() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaceRow(
    context: SpaceContext,
    selected: Boolean,
    enabled: Boolean,
    resumeItem: SpaceResumeItem?,
    onResume: () -> Unit,
    onClick: () -> Unit,
    compactMenu: Boolean = false,
) {
    val presentation = context.presentation()
    val hapticFeedback = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .then(
                if (compactMenu) {
                    Modifier.wrapContentWidth()
                } else {
                    Modifier.fillMaxWidth()
                },
            )
            .tvFocusable(shape = RoundedCornerShape(12.dp), enabled = enabled, addFocusTarget = false)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = {
                    if (!selected) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onClick()
                },
                role = Role.RadioButton,
            )
            .padding(
                horizontal = if (compactMenu) 12.dp else 24.dp,
                vertical = if (compactMenu) 6.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SpaceGlyph(
            presentation = presentation,
            monogram = context.customMonogram(),
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = context.title ?: stringResource(presentation.labelRes),
                style = if (compactMenu) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            )
            resumeItem?.let { item ->
                Text(
                    text = stringResource(R.string.space_recent_context, item.title),
                    style = if (compactMenu) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (resumeItem?.canResume == true) {
            IconButton(onClick = onResume, enabled = enabled) {
                Icon(
                    painter = painterResource(presentation.resumeIconRes),
                    contentDescription = stringResource(
                        R.string.space_continue_content_description,
                        resumeItem.title,
                    ),
                )
            }
        }
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
                modifier = if (compactMenu) Modifier.size(28.dp) else Modifier,
            )
    }
}

private data class SpacePresentation(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    @DrawableRes val resumeIconRes: Int,
)

private data class SpaceIconState(
    val presentation: SpacePresentation,
    val monogram: String?,
)

@Composable
private fun SpaceGlyph(
    presentation: SpacePresentation,
    monogram: String?,
    modifier: Modifier = Modifier,
) {
    if (monogram == null) {
        Icon(
            painter = painterResource(presentation.iconRes),
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Text(text = monogram, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

internal fun SpaceContext.customMonogram(): String? {
    if (isBuiltIn) return null
    val value = title?.trim().orEmpty()
    if (value.isEmpty()) return null
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(value)
    val start = iterator.first()
    val end = iterator.next()
    return value.substring(start, end.takeUnless { it == BreakIterator.DONE } ?: value.offsetByCodePoints(0, 1))
}

private fun SpaceId.presentation(): SpacePresentation = when (this) {
    BuiltInSpaces.Novel -> SpacePresentation(R.string.space_novel, R.drawable.ic_content_novel, R.drawable.ic_read)
    BuiltInSpaces.Anime -> SpacePresentation(R.string.space_anime, R.drawable.ic_content_video, R.drawable.ic_play)
    BuiltInSpaces.Manga -> SpacePresentation(R.string.space_manga, R.drawable.ic_content_manga, R.drawable.ic_read)
    else -> SpacePresentation(R.string.all, R.drawable.ic_filter_content_type, R.drawable.ic_read)
}

private fun SpaceContext.presentation(): SpacePresentation = when (kind) {
    org.skepsun.kototoro.space.domain.SpaceKind.NOVEL ->
        SpacePresentation(R.string.space_novel, R.drawable.ic_content_novel, R.drawable.ic_read)
    org.skepsun.kototoro.space.domain.SpaceKind.ANIME ->
        SpacePresentation(R.string.space_anime, R.drawable.ic_content_video, R.drawable.ic_play)
    org.skepsun.kototoro.space.domain.SpaceKind.MANGA ->
        SpacePresentation(R.string.space_manga, R.drawable.ic_content_manga, R.drawable.ic_read)
    org.skepsun.kototoro.space.domain.SpaceKind.ALL ->
        SpacePresentation(R.string.all, R.drawable.ic_filter_content_type, R.drawable.ic_read)
}
