package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

/** Bounds of the home rail rows-per-page setting (shared by rail and its config UIs). */
internal const val HOME_LIST_RAIL_ROWS_MIN = 1
internal const val HOME_LIST_RAIL_ROWS_MAX = 3
internal const val HOME_LIST_RAIL_ROWS_DEFAULT = 2

/**
 * Rows-per-page selector for the home list-mode rails (1..3). Shown by the
 * display options surfaces when the active display style is a list mode.
 */
@Composable
internal fun RailRowsSelector(
    railRows: Int,
    onRailRowsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.home_rail_rows_per_page),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            (HOME_LIST_RAIL_ROWS_MIN..HOME_LIST_RAIL_ROWS_MAX).forEach { rows ->
                val selected = rows == railRows
                AssistChip(
                    onClick = { onRailRowsChange(rows) },
                    label = {
                        Text(
                            text = rows.toString(),
                            maxLines = 1,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                        } else {
                            Color.Transparent
                        },
                    ),
                )
            }
        }
    }
}
