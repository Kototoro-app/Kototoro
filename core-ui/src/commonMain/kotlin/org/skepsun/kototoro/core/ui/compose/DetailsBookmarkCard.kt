package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/** A novel position's card text: the chapter line and the saved excerpt. */
class DetailsBookmarkText(val title: String, val excerpt: String)

private val CardShape = RoundedCornerShape(8.dp)

/**
 * One saved position on the details bookmarks tab (Android's `BookmarksScreen` card): a 0.7 card holding either the
 * page image with its "P.n" corner label, or the chapter line and excerpt of a novel position; a reading ring in the
 * corner, and the primary wash with [selectedIcon] while selected. Click and long press mean what the host makes them.
 */
@Composable
fun DetailsBookmarkCard(
    selected: Boolean,
    percent: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selectedIcon: Painter,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pageLabel: String? = null,
    text: DetailsBookmarkText? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    elevation: Dp = 1.dp,
    image: @Composable BoxScope.() -> Unit = {},
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .tvFocusable(shape = CardShape, addFocusTarget = false)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = if (selected) BorderStroke(4.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (text != null) {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    Text(
                        text = text.title,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = text.excerpt,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                image()
            }
            if (percent > 0f) {
                CircularProgressIndicator(
                    progress = { percent },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Black.copy(alpha = 0.5f),
                )
            }
            if (text == null && pageLabel != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                ) {
                    Text(
                        text = pageLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            if (selected) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = selectedIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
            }
        }
    }
}
