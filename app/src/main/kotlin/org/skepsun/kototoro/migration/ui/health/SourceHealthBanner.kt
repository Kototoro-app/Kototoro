package org.skepsun.kototoro.migration.ui.health

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R

/** Floating, dismissible card telling the user some favourite sources look broken. */
@Composable
fun SourceHealthBanner(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourceHealthBannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = state.sourceCount > 0, modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.migration_health_hint, state.sourceCount, state.sourceCount),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpen) { Text(stringResource(R.string.migration_title)) }
                IconButton(onClick = viewModel::dismiss) { Icon(Icons.Default.Close, contentDescription = null) }
            }
        }
    }
}
