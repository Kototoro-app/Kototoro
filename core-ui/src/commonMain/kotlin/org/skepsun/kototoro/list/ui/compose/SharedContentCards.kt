package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/** Only presentation values cross this seam; parser objects, image requests and progress formatting stay with hosts. */
@Immutable
data class ContentCardPresentation(
    val id: Long,
    val title: String,
    val subtitle: String? = null,
    val supportingText: String? = null,
)

/** Android's poster card, including selection, title layout and optional metadata below the cover. */
@Composable
fun ContentPosterCard(
    model: ContentCardPresentation,
    style: CompactPosterCardStyle,
    rimBorderBrush: Brush,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    cover: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSelected: Boolean = false,
    isHighlighted: Boolean = false,
    compactOverlay: Boolean = false,
    gridScale: Float = 1f,
    cellContentPadding: PaddingValues = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    focusModifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    overlays: @Composable BoxScope.() -> Unit = {},
    progress: @Composable BoxScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()
        .cardInteraction(RoundedCornerShape(style.cornerRadius), isSelected, isHighlighted, enabled,
            focusModifier, onClick, onLongClick)
        .padding(cellContentPadding), horizontalAlignment = Alignment.CenterHorizontally) {
        TabletPosterCover(model.title, style, rimBorderBrush, cover,
            modifier = Modifier.fillMaxWidth().aspectRatio(style.itemWidth.value / style.posterHeight.value)
                .then(coverModifier),
            compactOverlay = compactOverlay, gridScale = gridScale,
            overlays = {
                if (isSelected) {
                    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .4f)))
                    SelectionIndicator(model.id, Modifier.align(Alignment.Center).size(32.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)).padding(4.dp),
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
                overlays()
            }, progress = progress)
        if (!compactOverlay) {
            val fontSize = resolveGridTitleFontSize(gridScale)
            Text(model.title, style = MaterialTheme.typography.labelLarge.copy(fontSize = fontSize,
                lineHeight = (fontSize.value + 4f).sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface, minLines = 2, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (!model.supportingText.isNullOrBlank()) {
                Text(model.supportingText, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp,
                    lineHeight = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
            }
        }
    }
}

/** Android's compact row; hosts supply cover/transition and feature-specific status content. */
@Composable
fun ContentCompactCard(
    model: ContentCardPresentation,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    cover: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSelected: Boolean = false,
    isHighlighted: Boolean = false,
    focusModifier: Modifier = Modifier,
    titleLeading: @Composable () -> Unit = {},
    status: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().cardInteraction(RoundedCornerShape(16.dp), isSelected, isHighlighted, enabled,
        focusModifier, onClick, onLongClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            cover()
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isSelected) SelectionIndicator(model.id, Modifier.size(18.dp))
                    titleLeading()
                    Text(model.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp, lineHeight = 20.sp), color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (!model.subtitle.isNullOrBlank()) Text(model.subtitle,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp))
                if (!model.supportingText.isNullOrBlank()) Text(model.supportingText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp))
                status()
            }
        }
        HorizontalDivider(Modifier.padding(start = 78.dp), thickness = .5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f))
    }
}

@Composable
private fun Modifier.cardInteraction(
    shape: Shape,
    isSelected: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
    focusModifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = this
    .semantics { selected = isSelected }
    .background(when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = .3f)
        isHighlighted -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .24f)
        else -> Color.Transparent
    }, shape)
    .then(if (isHighlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
    .then(focusModifier)
    .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)

@Composable
private fun SelectionIndicator(id: Long, modifier: Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Icon(ReaderPanelIcons.Check, contentDescription = null, tint = tint,
        modifier = modifier.testTag("content-selected:$id"))
}
