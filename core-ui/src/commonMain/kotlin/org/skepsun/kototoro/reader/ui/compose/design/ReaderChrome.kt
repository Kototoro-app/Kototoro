package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import kotlin.math.roundToInt

/**
 * The surfaces of the reader's floating chrome (top capsules, title chip, floating buttons, progress dock). Android
 * draws them in liquid glass; a host without one gets translucent Material surfaces.
 */
interface ReaderChromeSurfaces {
    @Composable
    fun Pill(shape: Shape, modifier: Modifier, content: @Composable () -> Unit)

    @Composable
    fun Dock(shape: Shape, modifier: Modifier, content: @Composable () -> Unit)
}

val LocalReaderChromeSurfaces = staticCompositionLocalOf<ReaderChromeSurfaces?> { null }

/** White on dark themes, the scheme's on-surface otherwise. */
@Composable
fun readerControlContentColor(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.onBackground.luminance() > 0.5f) Color.White else colors.onSurface
}

/** A floating reader control: a glass pill on Android, a translucent surface elsewhere. */
@Composable
fun ReaderTopControlSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val body: @Composable () -> Unit = {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().then(contentModifier),
        ) {
            content()
        }
    }
    val surfaces = LocalReaderChromeSurfaces.current
    if (surfaces != null) {
        surfaces.Pill(shape, modifier, body)
    } else {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.84f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = ReaderControlTokens.ChromeShadowElevation,
            modifier = modifier,
            content = body,
        )
    }
}

/** Android's reader top bar: back, the title chip (unless it sits at the bottom) and the options button. */
@Composable
fun ReaderChromeTopBar(
    title: String,
    subtitle: String,
    chapterTitleAtBottom: Boolean,
    backDescription: String,
    optionsDescription: String,
    onNavigateBack: () -> Unit,
    onChapters: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
    titleFocusRequester: FocusRequester? = null,
    optionsFocusRequester: FocusRequester? = null,
    contentColor: Color = readerChromeTextColor(),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        ReaderTopControlSurface(
            shape = Capsule(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp),
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .tvFocusable(shape = Capsule(), addFocusTarget = false)
                    .testTag("reader-back"),
            ) {
                Icon(
                    imageVector = ReaderChromeIcons.ArrowBack,
                    contentDescription = backDescription,
                    tint = contentColor,
                )
            }
        }
        if (!chapterTitleAtBottom) {
            ReaderChapterTitleChip(
                title = title,
                subtitle = subtitle,
                onClick = onChapters,
                focusRequester = titleFocusRequester,
                contentColor = contentColor,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        ReaderTopControlSurface(
            shape = Capsule(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(48.dp),
        ) {
            IconButton(
                onClick = onOptions,
                modifier = Modifier
                    .then(optionsFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .tvFocusable(shape = Capsule(), addFocusTarget = false)
                    .testTag("reader-options"),
            ) {
                Icon(
                    imageVector = ReaderPanelIcons.MoreVert,
                    contentDescription = optionsDescription,
                    tint = contentColor,
                )
            }
        }
    }
}

/**
 * The work title + current chapter, opening the chapter list on tap. Shown in the top bar, or above the progress dock
 * when the chapter title is at the bottom — one-handed readers reach the bottom of the screen, not the top.
 */
@Composable
fun ReaderChapterTitleChip(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    height: Dp = 48.dp,
    contentColor: Color = readerChromeTextColor(),
) {
    val chapterControlShape = RoundedRectangle(24.dp)
    ReaderTopControlSurface(
        shape = chapterControlShape,
        modifier = modifier
            .widthIn(min = 148.dp, max = 176.dp)
            .height(height),
        contentModifier = Modifier
            .clip(chapterControlShape)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .tvFocusable(shape = chapterControlShape, borderWidth = 2.dp, addFocusTarget = false)
            .clickable(onClick = onClick)
            .testTag("reader-chapters"),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
        ) {
            Text(
                text = title,
                color = contentColor,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = contentColor.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** One of Android's floating reader controls (bookmark, auto scroll, save page...), with an optional label. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderFloatingControlButton(
    icon: Painter,
    contentDescription: String,
    label: String,
    active: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    contentColor: Color? = null,
) {
    val shape = if (showLabel) RoundedRectangle(22.dp) else Capsule()
    val sizeModifier = if (showLabel) {
        Modifier.fillMaxWidth().height(44.dp)
    } else {
        Modifier.size(44.dp)
    }
    val controlColor = contentColor ?: if (active) MaterialTheme.colorScheme.primary else readerControlContentColor()
    ReaderTopControlSurface(
        shape = shape,
        modifier = modifier.then(sizeModifier),
        contentModifier = Modifier
            .clip(shape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = contentDescription,
                onLongClickLabel = if (onLongClick != null) contentDescription else null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = if (showLabel) 8.dp else 0.dp),
        ) {
            Icon(
                painter = icon,
                contentDescription = contentDescription,
                tint = controlColor,
                modifier = Modifier.size(24.dp),
            )
            if (showLabel) {
                Text(
                    text = label,
                    color = controlColor,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ReaderProgressDock(
    isIosStyle: Boolean,
    modifier: Modifier = Modifier,
    contentColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val dockModifier = modifier
        .widthIn(max = ReaderControlTokens.DockMaxWidth)
        .fillMaxWidth()
    val body: @Composable () -> Unit = {
        CompositionLocalProvider(LocalContentColor provides (contentColor ?: readerControlContentColor())) {
            content()
        }
    }
    val surfaces = LocalReaderChromeSurfaces.current
    if (isIosStyle && surfaces != null) {
        surfaces.Dock(RoundedRectangle(22.dp), dockModifier, body)
    } else {
        Surface(
            modifier = dockModifier,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 2.dp,
            shadowElevation = ReaderControlTokens.ChromeShadowElevation,
            content = body,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ReaderProgressBar(
    value: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    previousDescription: String,
    nextDescription: String,
    previousEnabled: Boolean = true,
    nextEnabled: Boolean = true,
    isIosStyle: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var containerPosition by remember { mutableStateOf(IntOffset.Zero) }
    var trackPosition by remember { mutableStateOf(IntOffset.Zero) }
    var trackWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val popupOffsetPx = with(density) { 48.dp.roundToPx() }
    val popupHalfWidthPx = with(density) { 28.dp.roundToPx() }
    val effectiveMax = max.coerceAtLeast(1f)
    val displayedValue = (dragValue ?: value).coerceIn(0f, effectiveMax)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                containerPosition = IntOffset(position.x.roundToInt(), position.y.roundToInt())
            },
    ) {
        if (dragValue != null) {
            val fraction = displayedValue / effectiveMax
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    trackPosition.x - containerPosition.x +
                        (trackWidthPx * fraction).roundToInt() - popupHalfWidthPx,
                    -popupOffsetPx,
                ),
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(
                        text = "${displayedValue.toInt() + 1}/${max.toInt() + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onPreviousChapter,
                        enabled = previousEnabled,
                        modifier = Modifier.size(40.dp).testTag("reader-previous-chapter"),
                    ) {
                        Icon(
                            ReaderPanelIcons.Prev,
                            previousDescription,
                            tint = LocalContentColor.current,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coordinates ->
                                val position = coordinates.positionInWindow()
                                trackPosition = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                                trackWidthPx = coordinates.size.width
                            },
                    ) {
                        Slider(
                            value = displayedValue,
                            onValueChange = {
                                dragValue = it
                                onValueChange(it)
                            },
                            onValueChangeFinished = {
                                dragValue = null
                                onValueChangeFinished()
                            },
                            valueRange = 0f..effectiveMax,
                            thumb = {
                                Box(
                                    modifier = Modifier
                                        .size(if (isIosStyle) 14.dp else 18.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                )
                            },
                            track = { sliderState ->
                                SliderDefaults.Track(
                                    sliderState = sliderState,
                                    modifier = Modifier.height(if (isIosStyle) 4.dp else 10.dp),
                                    thumbTrackGapSize = 0.dp,
                                )
                            },
                            modifier = Modifier.testTag("reader-page-slider"),
                        )
                    }
                    IconButton(
                        onClick = onNextChapter,
                        enabled = nextEnabled,
                        modifier = Modifier.size(40.dp).testTag("reader-next-chapter"),
                    ) {
                        Icon(
                            ReaderPanelIcons.Next,
                            nextDescription,
                            tint = LocalContentColor.current,
                        )
                    }
                }
            }
        }
    }
}

/** Android's zoom buttons at the end of the reader; [middle] may sit between them (e.g. the current zoom). */
@Composable
fun ReaderZoomControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    zoomInDescription: String,
    zoomOutDescription: String,
    modifier: Modifier = Modifier,
    zoomInEnabled: Boolean = true,
    zoomOutEnabled: Boolean = true,
    middle: (@Composable () -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(12.dp),
    ) {
        IconButton(
            onClick = onZoomIn,
            enabled = zoomInEnabled,
            modifier = Modifier.size(48.dp).testTag("reader-zoom-in"),
        ) {
            Icon(ReaderPanelIcons.ZoomIn, zoomInDescription)
        }
        middle?.invoke()
        IconButton(
            onClick = onZoomOut,
            enabled = zoomOutEnabled,
            modifier = Modifier.size(48.dp).testTag("reader-zoom-out"),
        ) {
            Icon(ReaderPanelIcons.ZoomOut, zoomOutDescription)
        }
    }
}

/** Text on the top chrome, as Android picks it: white when the system is dark, black otherwise. */
@Composable
fun readerChromeTextColor(): Color = if (isSystemInDarkTheme()) Color.White else Color.Black

/** Chrome icons that come from AppCompat on Android rather than the app's drawables. */
object ReaderChromeIcons {
    /** AppCompat's `abc_ic_ab_back_material` (the Material "arrow back"), mirrored right to left. */
    val ArrowBack: ImageVector by lazy {
        readerPanelIcon(
            name = "ArrowBack",
            width = 24f,
            height = 24f,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
            paths = listOf(IconPath("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", false)),
        )
    }
}
