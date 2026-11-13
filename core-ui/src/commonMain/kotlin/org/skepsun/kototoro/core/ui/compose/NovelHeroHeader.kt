package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/**
 * Wraps a novel's 2:3 book cover with a subtle left spine crease and page-edge shadow so it reads
 * distinctly as a bound book rather than a manga gallery card.
 */
@Composable
fun NovelBookCoverFrame(
    modifier: Modifier = Modifier,
    width: Dp = 112.dp,
    coverContent: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp)
    val widthModifier = if (width != Dp.Unspecified && width > 0.dp) Modifier.width(width) else Modifier
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        shadowElevation = 12.dp,
        modifier = modifier
            .then(widthModifier)
            .aspectRatio(2f / 3f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape),
        ) {
            coverContent()
            // Subtle left book-spine crease + highlight.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(8.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.28f),
                                Color.White.copy(alpha = 0.16f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            // Subtle right page-edge shadow.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.10f),
                            ),
                        ),
                    ),
            )
        }
    }
}

/**
 * Editorial book header shared between Android and desktop novel details:
 * puts the author attribution directly beneath the title and presents a calm, book-first hierarchy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NovelHeroHeaderContent(
    title: String,
    authorText: String,
    alternateTitlesText: String,
    bookMetadataLine: String,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onAuthorClick: (() -> Unit)? = null,
    coverSlot: @Composable () -> Unit,
    titleExtraSlot: (@Composable () -> Unit)? = null,
    sourceChipsSlot: @Composable () -> Unit = {},
    quickActionsSlot: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("novel-hero-header"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        coverSlot()

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (titleExtraSlot != null) {
                titleExtraSlot()
            } else if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 28.sp,
                    ),
                    color = titleColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("novel-hero-title"),
                )
            }

            if (authorText.isNotBlank()) {
                Text(
                    text = authorText,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .tvFocusable(shape = RoundedCornerShape(6.dp), enabled = onAuthorClick != null, addFocusTarget = false)
                        .then(if (onAuthorClick != null) Modifier.clickable(role = Role.Button, onClick = onAuthorClick) else Modifier)
                        .padding(vertical = 1.dp)
                        .testTag("novel-hero-author"),
                )
            }

            if (alternateTitlesText.isNotBlank()) {
                Text(
                    text = alternateTitlesText,
                    style = MaterialTheme.typography.labelMedium,
                    color = secondaryTextColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (bookMetadataLine.isNotBlank()) {
                Text(
                    text = bookMetadataLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sourceChipsSlot()
            }

            Spacer(Modifier.height(2.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quickActionsSlot()
            }
        }
    }
}

/**
 * Editorial synopsis body with a subtle left accent bar and relaxed line height for novels.
 */
@Composable
fun NovelEditorialSynopsisBody(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 4,
    lineHeightScale: Float = 1.48f,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        )
        ExpandableDescriptionText(
            text = text,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            collapsedMaxLines = collapsedMaxLines,
            lineHeight = MaterialTheme.typography.bodyMedium.fontSize * lineHeightScale,
            modifier = Modifier.weight(1f),
        )
    }
}

@Immutable
data class NovelMetricItem(
    val label: String,
    val value: String,
    val subtitle: String? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * WeRead-inspired horizontal metrics strip showing key book facts (chapters, progress, reading time, rating/source)
 * separated by subtle vertical hairlines.
 */
@Composable
fun NovelMetricsStrip(
    items: List<NovelMetricItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("novel-metrics-strip"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .height(32.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    )
                }
                val onClick = item.onClick
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .tvFocusable(shape = RoundedCornerShape(10.dp), enabled = onClick != null, addFocusTarget = false)
                        .then(
                            if (onClick != null) {
                                Modifier.clickable(role = Role.Button, onClick = onClick)
                            } else Modifier,
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.value,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!item.subtitle.isNullOrBlank()) {
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Duokan-inspired "View Catalog" action row placed beneath the book synopsis so readers can jump
 * directly into the table of contents with chapter count / latest chapter context.
 */
@Composable
fun NovelCatalogEntryRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable () -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(16.dp))
            .tvFocusable(shape = RoundedCornerShape(16.dp), addFocusTarget = false)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("novel-catalog-entry"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = ReaderPanelIcons.BookPage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailingIcon()
        }
    }
}
