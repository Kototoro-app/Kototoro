package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One row of a "data removal" page: the operations are the same shape on every platform, only the set of them differs,
 * so the hosts build the rows (their titles, their counts, the ones this platform can really perform) and the shared
 * page draws them the way Android groups them.
 *
 * A row with [checked] and [onCheckedChange] is a switch, everything else is an action row — Android's chapters section
 * pairs the two: the button sweeps read chapters once, the switch arms the same sweep at every start.
 */
data class DataCleanupRow(
    val key: String,
    val title: String,
    val summary: String? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val onClick: () -> Unit = {},
    val onCheckedChange: ((Boolean) -> Unit)? = null,
    val leadingIcon: Painter? = null,
)

/** A titled group of [rows]; an empty group is skipped, so a host that cannot perform a whole section omits it. */
data class DataCleanupSection(
    val key: String,
    val title: String,
    val rows: List<DataCleanupRow>,
)

/**
 * Android's `DataCleanupSettingsScreen` body: local storage, caches, privacy and chapters, each a titled group of action
 * rows plus the switch that arms the read-chapter sweep. Both pages scroll as one column, so a host that needs its own
 * rows (a back button) puts them in [header] or [footer] instead of wrapping the page in another scroller.
 *
 * Rows carry `"$tagPrefix-${row.key}"`, which is how the desktop probe reaches them; the confirmations and the work
 * itself stay with the host, which owns the stores each row acts on.
 */
@Composable
fun DataCleanupContent(
    sections: List<DataCleanupSection>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    tagPrefix: String = "cleanup",
    sectionSpacing: Dp = 16.dp,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        header?.invoke()
        sections.forEach { section ->
            if (section.rows.isEmpty()) return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsSectionHeader(section.title)
                SettingsGroup {
                    section.rows.forEachIndexed { index, row ->
                        if (index > 0) SettingsRowDivider()
                        val onToggle = row.onCheckedChange
                        val checked = row.checked
                        if (checked != null && onToggle != null) {
                            SettingsSwitchRow(
                                title = row.title,
                                summary = row.summary,
                                checked = checked,
                                enabled = row.enabled,
                                onCheckedChange = onToggle,
                                testTag = "$tagPrefix-${row.key}",
                                leadingIcon = row.leadingIcon,
                            )
                        } else {
                            SettingsValueRow(
                                title = row.title,
                                summary = row.summary,
                                value = null,
                                enabled = row.enabled,
                                onClick = row.onClick,
                                testTag = "$tagPrefix-${row.key}",
                                leadingIcon = row.leadingIcon,
                            )
                        }
                    }
                }
            }
        }
        footer?.invoke()
    }
}
