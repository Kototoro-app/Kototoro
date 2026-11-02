package org.skepsun.kototoro.core.ui.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Android's repository presentation; hosts provide icons, metadata and platform actions, not another card layout. */
@Composable
fun ExtensionRepositoryCard(
    title: String,
    location: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    iconShape: Shape = RoundedCornerShape(8.dp),
    elevation: Dp = 0.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    error: String? = null,
    leading: @Composable () -> Unit,
    badges: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, iconShape),
                    contentAlignment = Alignment.Center) { leading() }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                actions()
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp), content = badges)
            Text(location, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            error?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
