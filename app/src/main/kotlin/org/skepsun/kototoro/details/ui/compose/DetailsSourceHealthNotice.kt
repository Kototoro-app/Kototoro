package org.skepsun.kototoro.details.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.buttons.TextButton

/**
 * The "this source may be broken (Not installed)" warning, inline at the top of the details page.
 *
 * It used to be a long-lived snackbar pinned to the bottom of the screen: it covered the primary
 * action, and while it was up the page still posted a second snackbar for the same cause. An inline
 * notice sits where the user reads it, scrolls with the page, and never hides a button.
 */
@Composable
internal fun DetailsSourceHealthNotice(
    message: String,
    onMigrate: () -> Unit,
    modifier: Modifier = Modifier,
    /** "Don't show again"; null for a warning that states a fact rather than a guess. */
    onDisable: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ReportProblem,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onDisable != null) {
                    TextButton(onClick = onDisable) {
                        Text(
                            text = stringResource(R.string.migration_health_banner_disable),
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.72f),
                        )
                    }
                }
                TextButton(onClick = onMigrate) {
                    Text(stringResource(R.string.migrate))
                }
            }
        }
    }
}
