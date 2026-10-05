package org.skepsun.kototoro.core.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import kotlin.math.ceil

/*
 * Android's home presentation shared with the Windows host: section header, quick access grid, list rail row,
 * hero text/badge and the hero entry and layout rules. Hosts supply images, transitions and string resources.
 */

// ---- layout rules

private val HomeHeroCompactCardWidth = 312.dp
private val HomeHeroMaxCardWidth = 640.dp
private val HomeHeroThreeCardViewport = 1400.dp

/** Hero card height on touch devices. */
val HomeHeroCardHeight = 184.dp

/** Home content stops growing here on wide windows and centres instead of stretching sections. */
val HomeContentMaxWidth = 1200.dp

/**
 * Columns for a grid of [itemCount] items with at most [maxColumns] per row, spread so rows are as even as
 * possible: 10 items on 9 columns become 5 + 5 rather than 9 + one stretched straggler.
 */
fun homeBalancedGridColumns(itemCount: Int, maxColumns: Int): Int {
    val limit = maxColumns.coerceAtLeast(1)
    if (itemCount <= 0) return 1
    val rows = ceil(itemCount / limit.toDouble()).toInt()
    return ceil(itemCount / rows.toDouble()).toInt().coerceIn(1, limit)
}

/**
 * Hero card width for a [viewport]. Phones keep the fixed card; medium windows show one and a half cards so the
 * next one peeks in; expanded windows fill the row with two (three from 1400dp) cards, each capped so a lone entry
 * does not become a banner.
 */
fun homeHeroCardWidth(viewport: Dp, edgePadding: Dp, spacing: Dp, layoutClass: TabletLayoutClass): Dp = when (layoutClass) {
    TabletLayoutClass.COMPACT -> minOf(HomeHeroCompactCardWidth, viewport * 0.78f)
        .coerceAtMost((viewport - edgePadding * 2).coerceAtLeast(0.dp))
    TabletLayoutClass.MEDIUM -> ((viewport - edgePadding - spacing) / 1.5f)
        .coerceIn(HomeHeroCompactCardWidth, HomeHeroMaxCardWidth)
    TabletLayoutClass.EXPANDED -> {
        val cards = if (viewport >= HomeHeroThreeCardViewport) 3 else 2
        ((viewport - edgePadding * 2 - spacing * (cards - 1)) / cards)
            .coerceIn(HomeHeroCompactCardWidth, HomeHeroMaxCardWidth)
    }
}

/** Section counts read "1k" from a thousand and "10k+" from ten thousand. */
fun homeCountLabel(count: Int): String = when {
    count >= 10_000 -> "${count / 1000}k+"
    count >= 1_000 -> "${count / 1000}k"
    else -> count.toString()
}

// ---- hero entries

enum class HomeHeroKind { RESUME, HISTORY, UPDATE, RECOMMENDATION }

data class HomeHeroEntry<C>(
    val kind: HomeHeroKind,
    val content: C,
    val groupKey: Long,
    val progressPercent: Int? = null,
    val newChapters: Int = 0,
)

/** A work offered to the hero from one home section. */
data class HomeHeroCandidate<C>(val content: C, val groupKey: Long, val newChapters: Int = 0)

data class HomeHeroLimits(val total: Int = 15, val history: Int = 6, val updates: Int = 5, val recommendations: Int = 4)

/** The hero pages: the resume entry, then history, updates and recommendations, each capped, without the resume work. */
fun <C> buildHomeHeroEntries(
    resume: C?,
    resumeGroupKey: Long?,
    resumeProgressPercent: Int?,
    resumeKeyOf: (C) -> Long,
    history: List<HomeHeroCandidate<C>>,
    updates: List<HomeHeroCandidate<C>>,
    recommendations: List<HomeHeroCandidate<C>>,
    limits: HomeHeroLimits = HomeHeroLimits(),
): List<HomeHeroEntry<C>> {
    val entries = ArrayList<HomeHeroEntry<C>>(limits.total)
    fun add(entry: HomeHeroEntry<C>) {
        if (entries.size < limits.total) entries += entry
    }
    resume?.let { add(HomeHeroEntry(HomeHeroKind.RESUME, it, resumeGroupKey ?: resumeKeyOf(it), resumeProgressPercent)) }
    fun section(items: List<HomeHeroCandidate<C>>, limit: Int, kind: HomeHeroKind) = items.asSequence()
        .filterNot { it.groupKey == resumeGroupKey }
        .take(limit)
        .forEach { add(HomeHeroEntry(kind, it.content, it.groupKey, newChapters = it.newChapters)) }
    section(history, limits.history, HomeHeroKind.HISTORY)
    section(updates, limits.updates, HomeHeroKind.UPDATE)
    section(recommendations, limits.recommendations, HomeHeroKind.RECOMMENDATION)
    return entries
}

// ---- composables

/** The small pill above a hero title ("推荐", "继续阅读"…). */
@Composable
fun HomeBadge(text: String, icon: Painter, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Badge, title, source and an optional supporting line of a hero card. */
@Composable
fun HomeHeroText(
    kindLabel: String,
    kindIcon: Painter,
    title: String,
    sourceTitle: String,
    supportingText: String?,
    compact: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp)) {
        HomeBadge(text = kindLabel, icon = kindIcon)
        Text(
            text = title,
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            color = textColor,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(sourceTitle, style = MaterialTheme.typography.bodyMedium, color = textColor.copy(alpha = 0.86f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        supportingText?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = textColor.copy(alpha = 0.92f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The darkening gradient over hero artwork, heavier at the bottom where the indicator and text sit. */
val HomeHeroArtworkScrim: Brush = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.20f), 1f to Color.Black.copy(alpha = 0.56f))

/** "History 1k →" plus the optional per-section settings button. [large] is Android's TV presentation. */
@Composable
fun HomeSectionHeader(
    title: String,
    count: Int,
    arrowIcon: Painter,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    titleModifier: Modifier = Modifier,
    configureIcon: Painter? = null,
    configureLabel: String? = null,
    configureModifier: Modifier = Modifier,
    onConfigureClick: (() -> Unit)? = null,
) {
    val controlSize = if (large) 48.dp else 32.dp
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides controlSize) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onMoreClick,
                modifier = Modifier.weight(1f, fill = false).then(titleModifier),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(homeCountLabel(count), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(arrowIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(if (large) 20.dp else 16.dp))
                }
            }
            if (onConfigureClick != null && configureIcon != null) {
                IconButton(onClick = onConfigureClick, modifier = Modifier.size(controlSize).then(configureModifier)) {
                    Icon(configureIcon, contentDescription = configureLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(controlSize / 2f))
                }
            }
        }
    }
}

/** One quick access entry. */
class HomeQuickAction(val label: String, val icon: Painter, val onClick: () -> Unit, val enabled: Boolean = true)

/** Draws a quick access tile container; Android swaps in glass for its iOS style. */
typealias HomeQuickActionContainer = @Composable (modifier: Modifier, shape: Shape, paletteIndex: Int,
    content: @Composable () -> Unit) -> Unit

/** Android's Material container: expressive tiles cycle through three tonal palettes. */
fun homeQuickActionSurface(expressive: Boolean): HomeQuickActionContainer = { modifier, shape, paletteIndex, content ->
    val color = when {
        !expressive -> MaterialTheme.colorScheme.surfaceContainerLow
        paletteIndex % 3 == 0 -> MaterialTheme.colorScheme.secondaryContainer
        paletteIndex % 3 == 1 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Surface(modifier = modifier, shape = shape, color = color, tonalElevation = if (expressive) 0.dp else 1.dp) { content() }
}

/** Colours of a quick access tile's icon and label. */
class HomeQuickActionColors(val icon: Color, val text: Color)

@Composable
fun homeQuickActionColors(enabled: Boolean, paletteIndex: Int, expressive: Boolean, isIosStyle: Boolean): HomeQuickActionColors {
    val scheme = MaterialTheme.colorScheme
    val expressiveContent = when (paletteIndex % 3) {
        0 -> scheme.onSecondaryContainer
        1 -> scheme.onTertiaryContainer
        else -> scheme.onSurface
    }
    val disabled = scheme.onSurfaceVariant.copy(alpha = 0.38f)
    return HomeQuickActionColors(
        icon = when {
            !enabled -> disabled
            isIosStyle -> scheme.primary
            !expressive -> scheme.onSurfaceVariant
            else -> expressiveContent
        },
        text = when {
            !enabled -> disabled
            isIosStyle || !expressive -> scheme.onSurface
            else -> expressiveContent
        },
    )
}

/**
 * Android's quick access grid: rows of equally weighted tiles with balanced columns, so a short last row keeps
 * the tile width instead of stretching.
 */
@Composable
fun HomeQuickActionsGrid(
    title: String,
    actions: List<HomeQuickAction>,
    modifier: Modifier = Modifier,
    preferredTileWidth: Dp = 68.dp,
    tileHeight: Dp = 64.dp,
    spacing: Dp = 6.dp,
    expressive: Boolean = true,
    isIosStyle: Boolean = false,
    large: Boolean = false,
    container: HomeQuickActionContainer = homeQuickActionSurface(expressive),
    /** Extra modifier on a tile's clickable content, e.g. Android's TV focus border. */
    tileModifier: @Composable (index: Int, shape: Shape) -> Modifier = { _, _ -> Modifier },
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxColumns = ((maxWidth + spacing) / (preferredTileWidth + spacing)).toInt().coerceAtLeast(2)
            val columns = homeBalancedGridColumns(actions.size, maxColumns)
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                actions.withIndex().chunked(columns).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        row.forEach { (index, action) ->
                            val shape = RoundedCornerShape(if (expressive) 20.dp else 16.dp)
                            val colors = homeQuickActionColors(action.enabled, index, expressive, isIosStyle)
                            container(Modifier.weight(1f).height(tileHeight), shape, index) {
                                Column(
                                    modifier = Modifier.fillMaxSize().then(tileModifier(index, shape))
                                        .clickable(enabled = action.enabled, onClick = action.onClick)
                                        .padding(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(action.icon, contentDescription = null, tint = colors.icon,
                                        modifier = Modifier.size(if (large) 28.dp else if (expressive) 20.dp else 18.dp))
                                    Text(
                                        text = action.label,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = colors.text,
                                    )
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * A list-mode rail row: framed cover (shadow, rim, clip) and title, accent supporting line or detail line, source.
 * [coverModifier] carries host transitions; [cover] draws the image, spine and badges.
 */
@Composable
fun HomeListRailRow(
    title: String,
    sourceTitle: String,
    coverWidth: Dp,
    coverShape: Shape,
    rimBorderBrush: Brush,
    onClick: () -> Unit,
    cover: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    detailed: Boolean = false,
    supportingText: String? = null,
    detailText: String? = null,
    coverModifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(coverWidth)
                .height(coverWidth * 1.5f)
                .then(coverModifier)
                .shadow(elevation = 2.dp, shape = coverShape, clip = false)
                .clip(coverShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(width = 0.5.dp, brush = rimBorderBrush, shape = coverShape),
            content = cover,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (detailed) 4.dp else 2.dp)) {
            Text(
                text = title,
                style = if (detailed) {
                    MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
                } else {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 18.sp)
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (detailed) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                supportingText != null -> Text(
                    text = supportingText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = if (detailed) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                detailed && detailText != null -> Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(sourceTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
