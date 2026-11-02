package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

@Immutable
data class CategoryChoice(
    val id: Long,
    val title: String,
    val state: ToggleableState,
    val tracking: Boolean = false,
    val hidden: Boolean = false,
)

/** Android's tri-state category list. Hosts choose immediate writes or a private draft with explicit confirmation. */
@Composable
fun CategoryChoicesContent(
    choices: List<CategoryChoice>,
    onToggle: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    emptyMessage: String = uiString("empty_favourite_categories"),
    hasHiddenCategories: Boolean = false,
    onShowAll: () -> Unit = {},
    tagPrefix: String = "category-choices",
) {
    Column(modifier.testTag("$tagPrefix-dialog")) {
        when {
            loading -> CircularProgressIndicator(Modifier.padding(vertical = 16.dp).testTag("$tagPrefix-loading"))
            choices.isEmpty() -> Text(emptyMessage, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp))
            else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(choices, key = { it.id }, contentType = { "category_row" }) { choice ->
                    Row(Modifier.fillMaxWidth()
                        .tvFocusable(enabled = enabled, shape = RoundedCornerShape(10.dp), addFocusTarget = false)
                        .triStateToggleable(choice.state, enabled = enabled, role = Role.Checkbox,
                            onClick = { onToggle(choice.id, choice.state != ToggleableState.On) })
                        .padding(vertical = 4.dp).testTag("$tagPrefix:${choice.id}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        TriStateCheckbox(choice.state, onClick = null, enabled = enabled)
                        Spacer(Modifier.width(8.dp))
                        Text(choice.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (choice.tracking) Icon(ReaderPanelIcons.Notification, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                        if (choice.hidden) Icon(ReaderPanelIcons.EyeOff, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
        }
        if (hasHiddenCategories) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TvTextButton(onShowAll, enabled = enabled, modifier = Modifier.testTag("$tagPrefix-show-all")) {
                Text(uiString("show_all_categories"))
            }
        }
    }
}
