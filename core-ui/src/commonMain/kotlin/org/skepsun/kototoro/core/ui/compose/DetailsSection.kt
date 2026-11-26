package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/**
 * The one heading every details section uses (synopsis, episodes, rating, related works, relations): the title, an
 * optional muted count beside it, then whatever actions the section has at the end. Sections are not wrapped in
 * cards; this heading and [DetailsSectionDivider] are what separate them.
 */
@Composable
fun DetailsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = count?.takeIf { it > 0 }?.toString().orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/** The hairline between two details sections, inset by the page's horizontal padding. */
@Composable
fun DetailsSectionDivider(
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = horizontalPadding),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

/** One column of [DetailsMetaStrip]: the value large, what it is small beneath; [onClick] makes it a link. */
@Immutable
data class DetailsMetaItem(
    val value: String,
    val label: String,
    val onClick: (() -> Unit)? = null,
    val valueMuted: Boolean = false,
)

/** Narrowest a [DetailsMetaStrip] column may get before the strip scrolls instead of squeezing it further. */
private val MetaColumnMinWidth = 80.dp

/**
 * The work's facts as one borderless row of columns split by hairlines (rating, status, chapters, author…), in place
 * of a boxed metadata grid. A linked column's label ends in a chevron. [contentColor] lets an immersive header keep
 * the strip on its own text colour. Columns share the width evenly; when that would leave them under
 * [MetaColumnMinWidth] they keep that width and the strip scrolls sideways.
 */
@Composable
fun DetailsMetaStrip(
    items: List<DetailsMetaItem>,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val scrolls = maxWidth < MetaColumnMinWidth * items.size
        DetailsMetaStripRow(
            items = items,
            columnModifier = { if (scrolls) Modifier.width(MetaColumnMinWidth) else Modifier.weight(1f) },
            contentColor = contentColor,
            secondaryColor = secondaryColor,
            modifier = if (scrolls) {
                Modifier.horizontalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            },
        )
    }
}

@Composable
private fun DetailsMetaStripRow(
    items: List<DetailsMetaItem>,
    columnModifier: RowScope.() -> Modifier,
    contentColor: Color,
    secondaryColor: Color,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                )
            }
            val shape = RoundedCornerShape(8.dp)
            Column(
                modifier = columnModifier()
                    .clip(shape)
                    .then(
                        if (item.onClick != null) {
                            Modifier
                                .tvFocusable(shape = shape, addFocusTarget = false)
                                .clickable(role = Role.Button, onClick = item.onClick)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (item.valueMuted) secondaryColor else contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (item.onClick != null) "${item.label} \u203A" else item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
