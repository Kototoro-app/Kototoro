package org.skepsun.kototoro.list.ui.compose

import org.skepsun.kototoro.core.ui.compose.DetailsSectionHeader
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig

/**
 * A titled horizontal rail of content cards (Android's details "related works"): the section title, then the cards
 * side by side with the page's horizontal padding as the rail's content padding, so the first card lines up with the
 * title and the rest scroll under the edge. On TV the rail is one focus group.
 */
@Composable
fun ContentRailSection(
    title: String,
    count: Int,
    key: (Int) -> Any,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    titleTrailing: (@Composable () -> Unit)? = null,
    railModifier: Modifier = Modifier,
    item: @Composable LazyItemScope.(index: Int) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DetailsSectionHeader(
            title = title,
            count = count,
            modifier = Modifier.padding(horizontal = horizontalPadding),
            trailing = titleTrailing?.let { trailing -> { trailing() } },
        )
        LazyRow(
            modifier = railModifier.then(
                if (LocalUiPresentationConfig.current.isTv) Modifier.focusGroup() else Modifier,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = horizontalPadding),
        ) {
            items(count = count, key = key) { index -> item(index) }
        }
    }
}
