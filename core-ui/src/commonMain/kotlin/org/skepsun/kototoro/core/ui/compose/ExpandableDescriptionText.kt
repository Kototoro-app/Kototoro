package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/**
 * The details description (Android's `DetailsHeader`): collapsed to [collapsedMaxLines] with the last lines fading
 * out, and a click on the text toggles the whole of it. Only text that actually overflows becomes clickable, so a
 * short description stays plain text.
 */
@Composable
fun ExpandableDescriptionText(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 3,
) {
    var canExpand by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(8.dp), enabled = canExpand, addFocusTarget = false)
            // Only text that overflows is a button; a short description must not read as a disabled one.
            .then(
                if (canExpand) Modifier.clickable(role = Role.Button) { onExpandedChange(!expanded) } else Modifier,
            )
            .animateContentSize()
            .then(
                if (canExpand && !expanded) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Black, Color.Transparent),
                                    startY = size.height * 0.62f,
                                    endY = size.height,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier
                },
            ),
        maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            val overflows = layout.hasVisualOverflow || layout.lineCount > collapsedMaxLines
            if (canExpand != overflows) canExpand = overflows
        },
    )
}
