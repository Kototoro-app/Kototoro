package org.skepsun.kototoro.community.ui

import org.skepsun.kototoro.core.ui.compose.DetailsSectionHeader
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.settings.compose.SettingsMaterialIcons
import kotlin.math.abs
import kotlin.math.roundToLong

/** One community work in a rail: the index knows its title and how it relates, not where to read it. */
data class CommunityWorkCard(
    val key: String,
    val title: String,
    val supportingText: String?,
    /** The community score out of 10, drawn as a badge when known. */
    val score: Double? = null,
)

/**
 * A titled rail of community works. The index names works by title only, so opening one searches every source for it,
 * which lands on the reader's own sources rather than on a link that may not work for them. [ranked] numbers the cards
 * for charts; [note] explains the list under the title, and [emptyText] stands in for an empty rail.
 */
@Composable
fun CommunityWorksRail(
    title: String,
    cards: List<CommunityWorkCard>,
    onOpen: (CommunityWorkCard) -> Unit,
    /** A long press: every source of the reader's where the work can be looked for ([CommunitySourcePicker]). */
    onLongOpen: ((CommunityWorkCard) -> Unit)? = null,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    titleTrailing: (@Composable () -> Unit)? = null,
    ranked: Boolean = false,
    note: String? = null,
    emptyText: String? = null,
    testTag: String = "community-rail",
) {
    Column(modifier = modifier.fillMaxWidth().testTag(testTag)) {
        DetailsSectionHeader(
            title = title,
            modifier = Modifier.padding(horizontal = horizontalPadding),
            trailing = titleTrailing?.let { trailing -> { trailing() } },
        )
        note?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding, bottom = 8.dp),
            )
        }
        if (cards.isEmpty()) {
            emptyText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 12.dp),
                )
            }
            return@Column
        }
        LazyRow(
            modifier = if (LocalUiPresentationConfig.current.isTv) Modifier.focusGroup() else Modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = horizontalPadding),
        ) {
            itemsIndexed(cards, key = { _, card -> card.key }) { index, card ->
                CommunityWorkTile(card, rank = if (ranked) index + 1 else null, onClick = { onOpen(card) },
                    onLongClick = onLongOpen?.let { { it(card) } }, testTag = "$testTag-item")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommunityWorkTile(
    card: CommunityWorkCard,
    rank: Int?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    testTag: String,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.width(156.dp).height(108.dp).clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag(testTag),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rank != null) {
                    // The top three stand out, as on a podium.
                    Surface(
                        shape = CircleShape,
                        color = if (rank <= 3) colors.tertiaryContainer else colors.surfaceContainerHighest,
                        modifier = Modifier.size(22.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = rank.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (rank <= 3) colors.onTertiaryContainer else colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                card.score?.let { score -> CommunityScoreBadge(score) }
            }
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            card.supportingText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A star and a score out of 10, the way every community surface shows a rating. */
@Composable
fun CommunityScoreBadge(score: Double, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = SettingsMaterialIcons.Star,
            contentDescription = null,
            tint = communityStarColor(),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = formatCommunityScore(score),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
fun communityStarColor() = MaterialTheme.colorScheme.tertiary

/** One decimal, the same in every locale (`7.5`), as Android's `String.format(Locale.ROOT, "%.1f")` wrote it. */
fun formatCommunityScore(score: Double): String {
    val tenths = (abs(score) * 10).roundToLong()
    return (if (score < 0 && tenths != 0L) "-" else "") + "${tenths / 10}.${tenths % 10}"
}
