package org.skepsun.kototoro.history.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvTakeInitialFocus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class ClearHistoryOption(val textRes: Int) {
    LAST_2_HOURS(R.string.last_2_hours),
    TODAY(R.string.today),
    NOT_IN_FAVORITES(R.string.not_in_favorites),
    CLEAR_ALL(R.string.clear_all_history)
}

private val ClearHistoryRowShape = RoundedCornerShape(8.dp)

@Composable
fun ClearHistoryDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (ClearHistoryOption) -> Unit
) {
    var selectedOption by remember { mutableIntStateOf(0) }
    val options = ClearHistoryOption.entries

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_delete_all),
                contentDescription = null
            )
        },
        title = {
            Text(stringResource(R.string.clear_history))
        },
        text = {
            // selectableGroup + tvTakeInitialFocus: a dialog opens its own window, so without the
            // request the remote lands nowhere and the first option has no visible ring.
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .tvTakeInitialFocus(),
            ) {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(shape = ClearHistoryRowShape, addFocusTarget = false)
                            .selectable(
                                selected = (index == selectedOption),
                                onClick = { selectedOption = index },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = (index == selectedOption),
                            onClick = null
                        )
                        Text(
                            text = stringResource(option.textRes),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(options[selectedOption])
                    onDismissRequest()
                },
                modifier = Modifier.tvFocusable(shape = ClearHistoryRowShape, addFocusTarget = false),
            ) {
                Text(stringResource(R.string.clear))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.tvFocusable(shape = ClearHistoryRowShape, addFocusTarget = false),
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
