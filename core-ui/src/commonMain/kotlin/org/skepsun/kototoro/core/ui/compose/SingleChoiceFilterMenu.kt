package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A single choice closes the menu. Hosts provide the platform toolbar anchor and labels. */
@Composable
fun <K> SingleChoiceFilterMenu(
    options: List<FilterFacetOption<K>>,
    onOptionSelected: (K) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionModifier: (K) -> Modifier = { Modifier },
    anchor: @Composable (openMenu: () -> Unit) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        anchor { expanded = true }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                TvDropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = {
                        RadioButton(selected = option.checked, onClick = null, enabled = option.enabled)
                    },
                    enabled = option.enabled,
                    modifier = optionModifier(option.key)
                        .heightIn(min = 48.dp)
                        .semantics { selected = option.checked },
                    onClick = {
                        expanded = false
                        onOptionSelected(option.key)
                    },
                )
            }
        }
    }
}
