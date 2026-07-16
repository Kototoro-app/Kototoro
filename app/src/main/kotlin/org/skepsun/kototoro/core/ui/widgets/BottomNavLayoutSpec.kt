package org.skepsun.kototoro.core.ui.widgets

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class BottomNavDensity {
	REGULAR,
	COMPACT,
	MINIMUM,
}

internal data class BottomNavLayoutSpec(
	val density: BottomNavDensity,
	val itemSpacing: Dp,
	val horizontalPadding: Dp,
	val outerHorizontalPadding: Dp,
	val fabGap: Dp,
	val showLabels: Boolean,
	val labelScale: Float,
	val labelMaxWidth: Dp?,
)

private val BottomNavItemTouchTarget = 48.dp
private val RegularItemSpacing = 5.8.dp
private val RegularHorizontalPadding = 6.2.dp
private val CompactItemSpacing = 4.dp
private val CompactHorizontalPadding = 4.dp
private val MinimumItemSpacing = 2.dp
private val MinimumHorizontalPadding = 0.dp
private val RegularOuterPadding = 12.dp
private val CompactOuterPadding = 8.dp
private val MinimumOuterPadding = 0.dp
private val RegularFabGap = 8.dp
private val CompactFabGap = 6.dp
private val MinimumFabGap = 4.dp
private val RegularExpressiveLabelWidth = 56.dp
private val CompactExpressiveLabelWidth = 40.dp
private val MinimumExpressiveLabelWidth = 32.dp
private const val CompactExpressiveLabelScale = 0.84f
private const val MinimumExpressiveLabelScale = 0.76f

internal fun resolveBottomNavLayout(
	availableWidth: Dp,
	itemCount: Int,
	fabWidth: Dp?,
	showLabels: Boolean,
	isExpressivePill: Boolean = false,
): BottomNavLayoutSpec {
	val normalizedItemCount = itemCount.coerceAtLeast(1)
	val fabWidth = fabWidth ?: 0.dp

	fun fits(
		itemSpacing: Dp,
		horizontalPadding: Dp,
		outerPadding: Dp,
		fabGap: Dp,
		expressiveLabelWidth: Dp = 0.dp,
	): Boolean {
		val navWidth = BottomNavItemTouchTarget * normalizedItemCount +
			itemSpacing * (normalizedItemCount - 1) +
			horizontalPadding * 2 + expressiveLabelWidth
		return navWidth + fabWidth + fabGap + outerPadding * 2 <= availableWidth
	}

	val shouldShowExpressiveLabels = showLabels && isExpressivePill

	return when {
		fits(
			itemSpacing = RegularItemSpacing,
			horizontalPadding = RegularHorizontalPadding,
			outerPadding = RegularOuterPadding,
			fabGap = RegularFabGap,
			expressiveLabelWidth = RegularExpressiveLabelWidth.takeIf { shouldShowExpressiveLabels } ?: 0.dp,
		) -> BottomNavLayoutSpec(
			density = BottomNavDensity.REGULAR,
			itemSpacing = RegularItemSpacing,
			horizontalPadding = RegularHorizontalPadding,
			outerHorizontalPadding = RegularOuterPadding,
			fabGap = RegularFabGap,
			showLabels = showLabels,
			labelScale = 1f,
			labelMaxWidth = RegularExpressiveLabelWidth.takeIf { shouldShowExpressiveLabels },
		)
		fits(
			itemSpacing = CompactItemSpacing,
			horizontalPadding = CompactHorizontalPadding,
			outerPadding = CompactOuterPadding,
			fabGap = CompactFabGap,
			expressiveLabelWidth = CompactExpressiveLabelWidth.takeIf { shouldShowExpressiveLabels } ?: 0.dp,
		) -> BottomNavLayoutSpec(
			density = BottomNavDensity.COMPACT,
			itemSpacing = CompactItemSpacing,
			horizontalPadding = CompactHorizontalPadding,
			outerHorizontalPadding = CompactOuterPadding,
			fabGap = CompactFabGap,
			showLabels = showLabels && isExpressivePill,
			labelScale = if (shouldShowExpressiveLabels) CompactExpressiveLabelScale else 1f,
			labelMaxWidth = CompactExpressiveLabelWidth.takeIf { shouldShowExpressiveLabels },
		)
		else -> BottomNavLayoutSpec(
			density = BottomNavDensity.MINIMUM,
			itemSpacing = MinimumItemSpacing,
			horizontalPadding = MinimumHorizontalPadding,
			outerHorizontalPadding = MinimumOuterPadding,
			fabGap = MinimumFabGap,
			showLabels = shouldShowExpressiveLabels && fits(
				itemSpacing = MinimumItemSpacing,
				horizontalPadding = MinimumHorizontalPadding,
				outerPadding = MinimumOuterPadding,
				fabGap = MinimumFabGap,
				expressiveLabelWidth = MinimumExpressiveLabelWidth,
			),
			labelScale = if (shouldShowExpressiveLabels) MinimumExpressiveLabelScale else 1f,
			labelMaxWidth = MinimumExpressiveLabelWidth.takeIf { shouldShowExpressiveLabels },
		)
	}
}
