package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Android's empty collection presentation, with host-resolved text, optional icon and recovery action. */
@Composable
fun ContentCollectionEmpty(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    actionModifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.invoke()
        Text(title, modifier = Modifier.semantics { heading() }, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) Button(onAction, actionModifier.heightIn(min = 48.dp), enabled = enabled) {
            Text(actionLabel, textAlign = TextAlign.Center)
        }
    }
}
