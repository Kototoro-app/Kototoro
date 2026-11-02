package org.skepsun.kototoro.core.ui.backups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.theme.LocalIsIosInterfaceStyle

/**
 * One section of a backup archive as the restore preview shows it.
 *
 * [title] and [summary] are host-formatted: Android names the section from its own resources and passes no summary,
 * Windows writes the entry count next to it. [enabled] is false for a section the archive does not carry, and for a
 * host that can only restore the whole archive (the rows then describe what will be restored instead of offering a
 * choice it cannot honour).
 */
data class BackupRestoreSection(
    val id: String,
    val title: String,
    val summary: String? = null,
    val checked: Boolean,
    val enabled: Boolean = true,
)

/** Android's two restore modes (replace the library with the snapshot, or merge into it) as a host-neutral option. */
data class BackupRestoreMode(
    val id: String,
    val title: String,
    val summary: String? = null,
)

/**
 * The restore preview's section list: one checkable row per archive section, with the section name and, when the host
 * knows it, how much the archive carries. Android shows it inside the restore dialog and Windows inside the library
 * backup page, so both hosts describe an archive the same way.
 *
 * [interactive] is false for a host that can only restore the whole archive: the rows then describe what the restore
 * will do instead of offering a choice it cannot honour, and [onToggle] is never called.
 */
@Composable
fun BackupRestoreSectionList(
    sections: List<BackupRestoreSection>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    tagPrefix: String = "backup-restore-section",
) {
    val isIosStyle = LocalIsIosInterfaceStyle.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        sections.forEach { section ->
            val rowEnabled = if (interactive) enabled && section.enabled else true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (isIosStyle) 48.dp else 0.dp)
                    .then(
                        if (interactive) {
                            Modifier
                                .tvFocusable(enabled = rowEnabled, shape = RoundedCornerShape(10.dp), addFocusTarget = false)
                                .toggleable(value = section.checked, enabled = rowEnabled, role = Role.Checkbox) { onToggle(section.id) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 6.dp)
                    .testTag("$tagPrefix:${section.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = section.checked, onCheckedChange = null, enabled = rowEnabled)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (rowEnabled) 1f else 0.38f),
                    )
                    if (!section.summary.isNullOrBlank()) {
                        Text(
                            text = section.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The restore-mode radio rows: title plus the one-line explanation under it, exactly as Android lays them out. */
@Composable
fun BackupRestoreModeList(
    modes: List<BackupRestoreMode>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "backup-restore-mode",
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        modes.forEach { mode ->
            val selected = mode.id == selectedId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onSelect(mode.id) }
                    .padding(vertical = 2.dp)
                    .testTag("$tagPrefix:${mode.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = mode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
                    )
                    if (!mode.summary.isNullOrBlank()) {
                        Text(
                            text = mode.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
