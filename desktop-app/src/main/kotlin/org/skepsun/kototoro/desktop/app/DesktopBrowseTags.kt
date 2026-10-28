package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.core.source.SourceTagGroup
import org.skepsun.kototoro.core.ui.chips.ChoiceChip
import org.skepsun.kototoro.core.ui.chips.ChoiceChipFlow

/** Android's filter header shows this many tag chips before its "more" entry. */
private const val HEADER_TAGS = 12

/**
 * The tags Android's filter header puts above a source's list: the picked ones first, then the source's own tags up to
 * [HEADER_TAGS]. Android adds tags from the search history; the desktop keeps no such history, so the catalogue order
 * of the source fills the row.
 */
internal fun browseHeaderTags(groups: List<SourceTagGroup>, picked: Set<SourceTag>): List<SourceTag> {
    val catalogue = groups.flatMap { it.tags }.distinct()
    return (picked.toList() + catalogue.filterNot { it in picked }).take(maxOf(HEADER_TAGS, picked.size))
}

/** The chip row above the results; the last chip opens the whole catalogue. */
@Composable
internal fun BrowseTagRow(groups: List<SourceTagGroup>, picked: Set<SourceTag>, enabled: Boolean,
    onToggle: (SourceTag) -> Unit, onShowAll: () -> Unit) {
    val shown = browseHeaderTags(groups, picked)
    if (shown.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("browse-tags"),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { tag ->
            ChoiceChip(tag in picked, { onToggle(tag) }, enabled = enabled,
                modifier = Modifier.testTag("browse-tag:${tag.key}")) {
                Text(tag.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (groups.sumOf { it.tags.size } > shown.size) {
            ChoiceChip(false, onShowAll, enabled = enabled, modifier = Modifier.testTag("browse-tags-all")) {
                Text(AndroidStrings["show_all"], maxLines = 1)
            }
        }
    }
}

/** Every tag of the source, in the groups it declares, as Android's filter sheet lists them. */
@Composable
internal fun BrowseTagCatalog(groups: List<SourceTagGroup>, picked: Set<SourceTag>, enabled: Boolean,
    onToggle: (SourceTag) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AndroidStrings["tags"]) },
        text = {
            Column(Modifier.widthIn(max = 720.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                .testTag("browse-tag-catalog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                groups.forEach { group ->
                    // A source with one unnamed group gets no heading, as Android draws it.
                    if (groups.size > 1) Text(group.title, style = MaterialTheme.typography.titleSmall)
                    ChoiceChipFlow {
                        group.tags.forEach { tag ->
                            ChoiceChip(tag in picked, { onToggle(tag) }, enabled = enabled,
                                modifier = Modifier.testTag("browse-catalog-tag:${tag.key}")) {
                                Text(tag.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(desktopText("common.close")) } },
        dismissButton = {
            TextButton(onClear, enabled = enabled && picked.isNotEmpty(),
                modifier = Modifier.testTag("browse-tags-clear")) { Text(AndroidStrings["clear"]) }
        },
    )
}
