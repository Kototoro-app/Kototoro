package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/** One tag of [DetailsTagChips]; [highlighted] is the host's sensitive-tag decision. */
@Immutable
data class DetailsTagChip<K>(val key: K, val title: String, val highlighted: Boolean = false)

/** Android's amber for a sensitive tag on the details page. */
val DetailsSensitiveTagColor = Color(0xFFE3B341)

private val TagShape = RoundedCornerShape(8.dp)

/**
 * The details page's tag chips (Android's `DetailsHeader` tag flow): small 8dp-cornered chips, a highlighted tag on
 * an amber wash with an amber rim. With [onTagClick] null the chips are labels only, for a host with nothing to open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <K> DetailsTagChips(
    tags: List<DetailsTagChip<K>>,
    modifier: Modifier = Modifier,
    onTagClick: ((K) -> Unit)? = null,
    chipModifier: (DetailsTagChip<K>) -> Modifier = { Modifier },
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            tags.forEach { tag ->
                val container = if (tag.highlighted) {
                    DetailsSensitiveTagColor.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
                }
                val rim = if (tag.highlighted) {
                    DetailsSensitiveTagColor.copy(alpha = 0.68f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                }
                val label: @Composable () -> Unit = {
                    Text(
                        text = tag.title,
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onTagClick != null) {
                    SuggestionChip(
                        onClick = { onTagClick(tag.key) },
                        modifier = chipModifier(tag)
                            .heightIn(min = 24.dp)
                            .tvFocusable(shape = TagShape, addFocusTarget = false),
                        shape = TagShape,
                        label = label,
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = container,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = rim),
                    )
                } else {
                    // The same chip without a click: M3's SuggestionChip always takes one.
                    Surface(
                        modifier = chipModifier(tag).heightIn(min = 24.dp),
                        shape = TagShape,
                        color = container,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, rim),
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        ) { label() }
                    }
                }
            }
        }
    }
}
