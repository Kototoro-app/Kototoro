package org.skepsun.kototoro.community.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.list.ui.compose.ContentRailSection

/** One community work in a rail: the index knows its title and how it relates, not where to read it. */
data class CommunityWorkCard(val key: String, val title: String, val supportingText: String?)

/**
 * A titled rail of community works. The index names works by title only, so opening one searches every source for it,
 * which lands on the reader's own sources rather than on a link that may not work for them.
 */
@Composable
fun CommunityWorksRail(
    title: String,
    cards: List<CommunityWorkCard>,
    onOpen: (CommunityWorkCard) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    titleTrailing: (@Composable () -> Unit)? = null,
    testTag: String = "community-rail",
) {
    ContentRailSection(
        title = title,
        count = cards.size,
        key = { cards[it].key },
        modifier = modifier.testTag(testTag),
        horizontalPadding = horizontalPadding,
        titleTrailing = titleTrailing,
    ) { index ->
        val card = cards[index]
        Surface(
            onClick = { onOpen(card) },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.width(148.dp).heightIn(min = 64.dp).testTag("$testTag-item"),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                card.supportingText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
