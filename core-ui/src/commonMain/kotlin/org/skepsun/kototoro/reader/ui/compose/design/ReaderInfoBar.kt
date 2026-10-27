package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The reader's info bar while the chrome is hidden: the chapter on the left, the visible pages next to it and the
 * host's own status slot (Android draws the battery and the clock there; Windows has none).
 *
 * [ReaderInfoBarRow] is the shared arrangement — one row that centres its items or splits them to the edges, keeping
 * the reading status whole, pushing the progress out of a display cutout and never measuring a child twice. The
 * platform keeps what only it can know: Android reads the window insets, the cutout rectangles and the rounded-corner
 * radii and turns them into [ReaderInfoBarInsets]; Windows passes its constant padding. That split is why the
 * geometry lives here while the inset lookups stay in the host.
 */
enum class ReaderInfoBarAlignment {
    CENTERED,
    SPLIT,
}

@Immutable
data class ReaderInfoBarBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

@Immutable
data class ReaderInfoBarCutout(
    val left: Int,
    val right: Int,
)

@Immutable
data class ReaderInfoBarPlacement(
    val readingX: Int,
    val readingWidth: Int,
    val systemStatusX: Int,
)

@Immutable
data class ReaderInfoBarItemPlacement(
    val x: Int,
    val width: Int,
)

/** The resolved paddings of one measure pass; the host answers these from its own window facts. */
@Immutable
data class ReaderInfoBarInsets(
    val left: Int,
    val right: Int,
    val vertical: Int,
)

/**
 * Android's outlined info-bar text: without a scrim the words are stroked first, so the chapter stays readable over a
 * light page as well as a dark one. The outline is the same colour as the scrim, which is why both hosts pass one.
 */
@Composable
fun ReaderInfoBarText(
    text: String,
    style: TextStyle,
    outlineColor: Color,
    drawOutline: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val outlineWidth = with(LocalDensity.current) { 0.75.dp.toPx() }
    Box(modifier = modifier) {
        if (drawOutline) {
            Text(
                text = text,
                style = style.copy(
                    color = outlineColor,
                    drawStyle = Stroke(width = outlineWidth),
                ),
                maxLines = maxLines,
                overflow = overflow,
            )
        }
        Text(
            text = text,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/**
 * One info-bar measure, with the platform's insets and cutout resolved from the bar's own window bounds.
 *
 * [insets] and [cutout] take the bounds of the previous pass (null before the bar is first placed), which is exactly
 * what Android needs: the corner radii only matter once the bar knows where it sits in the window.
 */
@Composable
fun ReaderInfoBarRow(
    alignment: ReaderInfoBarAlignment,
    readingStatus: @Composable () -> Unit,
    insets: (ReaderInfoBarBounds?) -> ReaderInfoBarInsets,
    itemSpacing: Dp = 10.dp,
    obstructionSpacing: Dp = 4.dp,
    modifier: Modifier = Modifier,
    progressStatus: (@Composable () -> Unit)? = null,
    systemStatus: (@Composable () -> Unit)? = null,
    cutout: (ReaderInfoBarBounds?) -> ReaderInfoBarCutout? = { null },
) {
    var bounds by remember { mutableStateOf<ReaderInfoBarBounds?>(null) }
    val density = LocalDensity.current
    val resolvedInsets = insets(bounds)
    val resolvedCutout = cutout(bounds)
    Layout(
        content = {
            readingStatus()
            progressStatus?.invoke()
            systemStatus?.invoke()
        },
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                val left = position.x.roundToInt()
                val top = position.y.roundToInt()
                val updated = ReaderInfoBarBounds(
                    left = left,
                    top = top,
                    right = left + coordinates.size.width,
                    bottom = top + coordinates.size.height,
                )
                if (bounds != updated) bounds = updated
            },
        measurePolicy = readerInfoBarMeasurePolicy(
            alignment = alignment,
            leftPadding = resolvedInsets.left,
            rightPadding = resolvedInsets.right,
            verticalPadding = resolvedInsets.vertical,
            obstructionSpacing = with(density) { obstructionSpacing.roundToPx() },
            itemSpacing = with(density) { itemSpacing.roundToPx() },
            localCutout = resolvedCutout,
            hasProgressStatus = progressStatus != null,
            hasSystemStatus = systemStatus != null,
        ),
    )
}

/** The shared measure policy: reading status first, then the optional progress and the host's status slot. */
fun readerInfoBarMeasurePolicy(
    alignment: ReaderInfoBarAlignment,
    leftPadding: Int,
    rightPadding: Int,
    verticalPadding: Int,
    obstructionSpacing: Int,
    itemSpacing: Int,
    localCutout: ReaderInfoBarCutout?,
    hasProgressStatus: Boolean,
    hasSystemStatus: Boolean,
): MeasurePolicy = MeasurePolicy { measurables, constraints ->
    val width = constraints.maxWidth
    val contentHeightLimit = (constraints.maxHeight - verticalPadding * 2).coerceAtLeast(0)
    val childConstraints = Constraints(
        minWidth = 0,
        maxWidth = (width - leftPadding - rightPadding).coerceAtLeast(0),
        minHeight = 0,
        maxHeight = contentHeightLimit,
    )
    var measurableIndex = 1
    val progressMeasurable = if (hasProgressStatus) measurables.getOrNull(measurableIndex++) else null
    val systemPlaceable = if (hasSystemStatus) {
        measurables.getOrNull(measurableIndex)?.measure(childConstraints)
    } else {
        null
    }
    val systemWidth = systemPlaceable?.width ?: 0
    // Resolve the final cutout-constrained width before measuring progress; Compose only allows one measurement.
    val progressWidth = progressMeasurable?.maxIntrinsicWidth(contentHeightLimit)
        ?.coerceIn(0, childConstraints.maxWidth) ?: 0
    val trailingWidth = if (alignment == ReaderInfoBarAlignment.CENTERED && progressWidth > 0) {
        itemSpacing + progressWidth + systemWidth
    } else {
        systemWidth
    }
    val readingMeasurable = measurables.first()
    val desiredReadingWidth = readingMeasurable
        .maxIntrinsicWidth(contentHeightLimit)
        .coerceAtMost((childConstraints.maxWidth - trailingWidth).coerceAtLeast(0))
    val placement = resolveReaderInfoBarPlacement(
        width = width,
        desiredReadingWidth = desiredReadingWidth,
        systemStatusWidth = trailingWidth,
        alignment = alignment,
        leftPadding = leftPadding,
        rightPadding = rightPadding,
        cutout = localCutout,
        cutoutSpacing = obstructionSpacing,
    )
    val readingPlaceable = readingMeasurable.measure(
        childConstraints.copy(maxWidth = placement.readingWidth),
    )
    val progressPlacement = progressMeasurable?.let {
        if (alignment == ReaderInfoBarAlignment.CENTERED) {
            ReaderInfoBarItemPlacement(
                x = placement.systemStatusX + itemSpacing,
                width = progressWidth,
            )
        } else {
            resolveReaderInfoBarProgressPlacement(
                readingEnd = placement.readingX + readingPlaceable.width,
                systemStatusX = placement.systemStatusX,
                desiredProgressWidth = progressWidth,
                cutout = localCutout,
                cutoutSpacing = obstructionSpacing,
            )
        }
    }
    val progressPlaceable = progressPlacement?.let { progress ->
        progressMeasurable.measure(childConstraints.copy(maxWidth = progress.width))
    }
    val resolvedSystemStatusX = if (alignment == ReaderInfoBarAlignment.CENTERED && progressPlaceable != null) {
        progressPlacement!!.x + progressPlaceable.width
    } else {
        placement.systemStatusX
    }
    val contentHeight = maxOf(
        readingPlaceable.height,
        progressPlaceable?.height ?: 0,
        systemPlaceable?.height ?: 0,
    )
    val height = (contentHeight + verticalPadding * 2).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) {
        readingPlaceable.place(
            x = placement.readingX,
            y = (height - readingPlaceable.height) / 2,
        )
        systemPlaceable?.place(
            x = resolvedSystemStatusX,
            y = (height - systemPlaceable.height) / 2,
        )
        progressPlaceable?.place(
            x = progressPlacement!!.x,
            y = (height - progressPlaceable.height) / 2,
        )
    }
}

/** Where the progress text goes inside the gap the reading status and the host status leave for it. */
fun resolveReaderInfoBarProgressPlacement(
    readingEnd: Int,
    systemStatusX: Int,
    desiredProgressWidth: Int,
    cutout: ReaderInfoBarCutout?,
    cutoutSpacing: Int,
): ReaderInfoBarItemPlacement {
    val availableStart = readingEnd + cutoutSpacing
    val availableEnd = (systemStatusX - cutoutSpacing).coerceAtLeast(availableStart)
    val overlappingCutout = cutout?.takeIf {
        it.right > availableStart && it.left < availableEnd
    }
    if (overlappingCutout == null) {
        val width = desiredProgressWidth.coerceIn(0, availableEnd - availableStart)
        return ReaderInfoBarItemPlacement(
            x = availableStart + (availableEnd - availableStart - width) / 2,
            width = width,
        )
    }

    val leftEnd = (overlappingCutout.left - cutoutSpacing).coerceIn(availableStart, availableEnd)
    val rightStart = (overlappingCutout.right + cutoutSpacing).coerceIn(availableStart, availableEnd)
    val leftWidth = leftEnd - availableStart
    val rightWidth = availableEnd - rightStart
    return if (rightWidth >= leftWidth) {
        ReaderInfoBarItemPlacement(
            x = rightStart,
            width = desiredProgressWidth.coerceIn(0, rightWidth),
        )
    } else {
        val width = desiredProgressWidth.coerceIn(0, leftWidth)
        ReaderInfoBarItemPlacement(
            x = leftEnd - width,
            width = width,
        )
    }
}

/**
 * How far the info bar has to move in from a rounded display corner before its text stops being clipped by it.
 * Android supplies the corner centre, the radius and the bar's top edge; nothing here is platform specific.
 */
fun resolveReaderInfoBarRoundedCornerPadding(
    edgeDistanceToCenter: Int,
    cornerCenterY: Int,
    radius: Int,
    contentTopY: Int,
    spacing: Int,
): Int {
    if (radius <= 0 || edgeDistanceToCenter <= 0) return 0
    val verticalDistance = (cornerCenterY - contentTopY).coerceIn(0, radius)
    val visibleRadiusAtContentTop = sqrt(
        radius.toDouble() * radius - verticalDistance.toDouble() * verticalDistance,
    )
    val clippedInset = edgeDistanceToCenter - visibleRadiusAtContentTop
    return if (clippedInset > 0) ceil(clippedInset + spacing).toInt() else 0
}

/** The horizontal placement of the reading status and of the host's status slot, cutout aware. */
fun resolveReaderInfoBarPlacement(
    width: Int,
    desiredReadingWidth: Int,
    systemStatusWidth: Int,
    alignment: ReaderInfoBarAlignment,
    leftPadding: Int,
    rightPadding: Int,
    cutout: ReaderInfoBarCutout?,
    cutoutSpacing: Int,
): ReaderInfoBarPlacement {
    val contentLeft = leftPadding
    val contentRight = (width - rightPadding).coerceAtLeast(contentLeft)
    val availableWidth = contentRight - contentLeft
    val readingWidth = desiredReadingWidth.coerceIn(0, (availableWidth - systemStatusWidth).coerceAtLeast(0))
    val naturalSystemX = when (alignment) {
        ReaderInfoBarAlignment.CENTERED -> (width - readingWidth - systemStatusWidth) / 2 + readingWidth
        ReaderInfoBarAlignment.SPLIT -> contentRight - systemStatusWidth
    }
    val naturalReadingX = when (alignment) {
        ReaderInfoBarAlignment.CENTERED -> naturalSystemX - readingWidth
        ReaderInfoBarAlignment.SPLIT -> contentLeft
    }
    val protectedCutout = cutout?.let {
        ReaderInfoBarCutout(
            left = (it.left - cutoutSpacing).coerceIn(contentLeft, contentRight),
            right = (it.right + cutoutSpacing).coerceIn(contentLeft, contentRight),
        )
    }?.takeIf { it.left < it.right }
        ?: return ReaderInfoBarPlacement(naturalReadingX, readingWidth, naturalSystemX)

    val leftWidth = (protectedCutout.left - contentLeft).coerceAtLeast(0)
    val rightWidth = (contentRight - protectedCutout.right).coerceAtLeast(0)

    if (alignment == ReaderInfoBarAlignment.SPLIT) {
        val readingPlacement = resolveReaderEdgeAnchoredItem(
            itemWidth = readingWidth,
            contentLeft = contentLeft,
            contentRight = contentRight,
            cutout = protectedCutout,
            anchorAtStart = true,
        )
        val systemPlacement = resolveReaderEdgeAnchoredItem(
            itemWidth = systemStatusWidth,
            contentLeft = contentLeft,
            contentRight = contentRight,
            cutout = protectedCutout,
            anchorAtStart = false,
        )
        return ReaderInfoBarPlacement(
            readingX = readingPlacement.x,
            readingWidth = readingPlacement.width,
            systemStatusX = systemPlacement.x,
        )
    }

    fun overlaps(x: Int, itemWidth: Int): Boolean =
        itemWidth > 0 && x < protectedCutout.right && x + itemWidth > protectedCutout.left

    if (!overlaps(naturalReadingX, readingWidth) && !overlaps(naturalSystemX, systemStatusWidth)) {
        return ReaderInfoBarPlacement(naturalReadingX, readingWidth, naturalSystemX)
    }

    val totalWidth = readingWidth + systemStatusWidth
    if (readingWidth <= leftWidth && systemStatusWidth <= rightWidth) {
        return ReaderInfoBarPlacement(
            readingX = if (alignment == ReaderInfoBarAlignment.CENTERED) {
                protectedCutout.left - readingWidth
            } else {
                contentLeft
            },
            readingWidth = readingWidth,
            systemStatusX = if (alignment == ReaderInfoBarAlignment.CENTERED) {
                protectedCutout.right
            } else {
                contentRight - systemStatusWidth
            },
        )
    }
    if (totalWidth <= leftWidth) {
        val start = if (alignment == ReaderInfoBarAlignment.CENTERED) {
            contentLeft + (leftWidth - totalWidth) / 2
        } else {
            contentLeft
        }
        return ReaderInfoBarPlacement(start, readingWidth, start + readingWidth)
    }
    if (totalWidth <= rightWidth) {
        val start = if (alignment == ReaderInfoBarAlignment.CENTERED) {
            protectedCutout.right + (rightWidth - totalWidth) / 2
        } else {
            contentRight - totalWidth
        }
        return ReaderInfoBarPlacement(start, readingWidth, start + readingWidth)
    }

    val systemOnRight = systemStatusWidth <= rightWidth || systemStatusWidth > leftWidth
    val safeReadingWidth = if (systemOnRight) leftWidth else rightWidth
    val resolvedReadingWidth = readingWidth.coerceAtMost(safeReadingWidth)
    return if (systemOnRight) {
        ReaderInfoBarPlacement(
            readingX = contentLeft,
            readingWidth = resolvedReadingWidth,
            systemStatusX = protectedCutout.right,
        )
    } else {
        ReaderInfoBarPlacement(
            readingX = protectedCutout.right,
            readingWidth = resolvedReadingWidth,
            systemStatusX = protectedCutout.left - systemStatusWidth,
        )
    }
}

private fun resolveReaderEdgeAnchoredItem(
    itemWidth: Int,
    contentLeft: Int,
    contentRight: Int,
    cutout: ReaderInfoBarCutout,
    anchorAtStart: Boolean,
): ReaderInfoBarItemPlacement {
    val naturalX = if (anchorAtStart) contentLeft else contentRight - itemWidth
    val overlapsCutout = itemWidth > 0 && naturalX < cutout.right && naturalX + itemWidth > cutout.left
    if (!overlapsCutout) return ReaderInfoBarItemPlacement(naturalX, itemWidth)

    val beforeWidth = (cutout.left - contentLeft).coerceAtLeast(0)
    val afterWidth = (contentRight - cutout.right).coerceAtLeast(0)
    if (anchorAtStart) {
        if (itemWidth <= beforeWidth) return ReaderInfoBarItemPlacement(contentLeft, itemWidth)
        if (itemWidth <= afterWidth) return ReaderInfoBarItemPlacement(cutout.right, itemWidth)
    } else {
        if (itemWidth <= afterWidth) return ReaderInfoBarItemPlacement(contentRight - itemWidth, itemWidth)
        if (itemWidth <= beforeWidth) return ReaderInfoBarItemPlacement(cutout.left - itemWidth, itemWidth)
    }
    return if (beforeWidth >= afterWidth) {
        ReaderInfoBarItemPlacement(contentLeft, itemWidth.coerceAtMost(beforeWidth))
    } else {
        ReaderInfoBarItemPlacement(cutout.right, itemWidth.coerceAtMost(afterWidth))
    }
}
