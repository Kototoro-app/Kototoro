package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.model.TaxonomyCategory
import org.skepsun.kototoro.core.model.TaxonomyTag
import org.skepsun.kototoro.core.model.TagTaxonomy
import org.skepsun.kototoro.core.model.TagTitleBlacklist
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader

/**
 * Android's `GlobalTagBlacklistScreen`. The catalogue, the ids and the `raw:` keys are the shared [TagTaxonomy] and
 * [TagTitleBlacklist], so a selection made here hides exactly the works Android hides: the stored strings are what
 * the shelf derivers, the feed and the browse list feed into the shared matcher.
 *
 * Windows draws the same three parts: the current selection, the catalogue filtered by the search field, and the
 * "add a pattern" action for a tag no source reports yet. Android shows a chip's aliases on long press; a desktop
 * pointer gets the same dialog from a long press, which keeps a single click free for toggling.
 */
@Composable
internal fun DesktopTagBlacklistPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.sourceSettings
    val active = !state.busy
    var query by remember { mutableStateOf("") }
    var details by remember { mutableStateOf<DesktopBlacklistChip?>(null) }
    val languageTag by DesktopStrings.tag
    val chips = remember(languageTag) { desktopBlacklistChips(languageTag) }
    val selected = settings.tagBlacklist
    val matches = remember(chips, query) {
        val text = query.trim()
        if (text.isEmpty()) chips
        else chips.filter { chip ->
            chip.taxonomy?.let { TagTaxonomy.search(it, text) } ?: chip.title.contains(text, ignoreCase = true)
        }
    }
    val rawKey = TagTitleBlacklist.rawTagKey(query)
    // Android offers the raw pattern only when the text names something the catalogue cannot resolve.
    val offerRaw = query.isNotBlank() && rawKey !in selected && TagTaxonomy.resolve(query).isEmpty()
    Column(
        modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton({ controller.showSourceSettings() }, enabled = active,
            modifier = Modifier.testTag("tag-blacklist-back")) { Text(desktopText("common.back")) }
        // The window's own title bar already names the screen, so only the count and Android's "clear" stay here.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(desktopText("sources.tagBlacklistCount", selected.size), fontSize = 12.sp, color = Muted,
                modifier = Modifier.weight(1f))
            TextButton({ controller.clearTagBlacklist() }, enabled = active && selected.isNotEmpty(),
                modifier = Modifier.testTag("tag-blacklist-clear")) { Text(desktopText("sources.tagBlacklistClear")) }
        }
        DesktopSearchField(query, { query = it }, desktopText("sources.tagBlacklistSearch"), enabled = active,
            modifier = Modifier.fillMaxWidth(), tag = "tag-blacklist-search")
        if (offerRaw) {
            TextButton({ controller.toggleTagBlacklist(rawKey) }, enabled = active,
                modifier = Modifier.testTag("tag-blacklist-add")) {
                Text(desktopText("sources.tagBlacklistAddPattern", query.trim()))
            }
        }
        SettingsSectionHeader(desktopText("sources.tagBlacklistSelected"))
        if (selected.isEmpty()) {
            Text(desktopText("sources.tagBlacklistEmpty"), fontSize = 12.sp, color = Muted)
        } else {
            ChipFlow(selected.map { it.toChip(chips, languageTag) }, accent = true,
                tagPrefix = "tag-blacklist-selected", onToggle = controller::toggleTagBlacklist,
                onDetails = { details = it })
        }
        // Android groups the catalogue by category, in the taxonomy's own order, and lists source-only titles last.
        val groups = matches.mapNotNull { chip -> chip.taxonomy?.let { it.category to chip } }
            .groupBy({ it.first }, { it.second })
            .toList().sortedBy { it.first.ordinal }
        val rawChips = matches.filter { it.taxonomy == null }
        Text(desktopText("sources.tagBlacklistAvailable", matches.size), fontSize = 12.sp, color = Muted)
        if (matches.isEmpty()) {
            Text(desktopText("sources.tagBlacklistNotFound"), fontSize = 12.sp, color = Muted)
        }
        groups.forEach { (category, group) ->
            SettingsSectionHeader(category.localizedLabel(desktopLanguage(languageTag)))
            ChipFlow(group, accent = false, tagPrefix = "tag-blacklist",
                selected = selected, onToggle = controller::toggleTagBlacklist, onDetails = { details = it })
        }
        if (rawChips.isNotEmpty()) {
            SettingsSectionHeader(TaxonomyCategory.RAW.localizedLabel(desktopLanguage(languageTag)))
            ChipFlow(rawChips, accent = false, tagPrefix = "tag-blacklist",
                selected = selected, onToggle = controller::toggleTagBlacklist, onDetails = { details = it })
        }
        SettingsInfoRow(desktopText("sources.tagBlacklistNote"), null)
    }
    details?.let { chip ->
        AlertDialog(
            onDismissRequest = { details = null },
            modifier = Modifier.testTag("tag-blacklist-dialog"),
            title = { Text(chip.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(desktopText("sources.tagBlacklistKey", chip.key), fontSize = 12.sp, color = Muted)
                    chip.taxonomy?.let { tag ->
                        Text(desktopText("sources.tagBlacklistEnglish", tag.englishLabel), fontSize = 12.sp)
                        Text(desktopText("sources.tagBlacklistCategory", chip.categoryLabel.orEmpty()), fontSize = 12.sp)
                        if (tag.aliases.isNotEmpty()) {
                            Text(desktopText("sources.tagBlacklistAliases", tag.aliases.sorted().joinToString(", ")),
                                fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({ details = null }, enabled = active,
                    modifier = Modifier.testTag("tag-blacklist-dialog-close")) {
                    Text(desktopText("common.close"))
                }
            },
        )
    }
}

/** One selectable row of the catalogue; [taxonomy] is null for a source title the taxonomy does not know. */
private data class DesktopBlacklistChip(
    val key: String,
    val title: String,
    val categoryLabel: String?,
    val taxonomy: TaxonomyTag?,
)

/** The catalogue Android's view model shows: every taxonomy tag, then the source tags it knows by name. */
private fun desktopBlacklistChips(languageTag: String): List<DesktopBlacklistChip> {
    val language = desktopLanguage(languageTag)
    val tags = TagTaxonomy.tags.map { tag ->
        DesktopBlacklistChip(
            key = tag.id,
            title = tag.localizedLabel(language),
            categoryLabel = tag.category.localizedLabel(language),
            taxonomy = tag,
        )
    }
    val raw = TagTaxonomy.knownSourceTags
        .filter { TagTaxonomy.resolve(it).isEmpty() }
        .distinctBy { TagTitleBlacklist.rawTagKey(it) }
        .map { DesktopBlacklistChip(TagTitleBlacklist.rawTagKey(it), it, null, null) }
    return tags + raw
}

private fun desktopLanguage(languageTag: String): String =
    languageTag.substringBefore('-').lowercase()

/** A stored key renders from the catalogue when it is there, and from the key itself when it is not. */
private fun String.toChip(chips: List<DesktopBlacklistChip>, languageTag: String): DesktopBlacklistChip {
    val known = chips.firstOrNull { it.key == this }
    if (known != null) return known
    val title = removePrefix(TagTitleBlacklist.RAW_TAG_PREFIX).ifBlank { this }
    return DesktopBlacklistChip(this, title, rawCategoryLabel(languageTag), null)
}

private fun rawCategoryLabel(languageTag: String): String =
    TaxonomyCategory.RAW.localizedLabel(desktopLanguage(languageTag))

@Composable
private fun ChipFlow(chips: List<DesktopBlacklistChip>, accent: Boolean, tagPrefix: String,
    selected: Set<String> = emptySet(), onToggle: (String) -> Unit, onDetails: (DesktopBlacklistChip) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            val checked = if (accent) true else chip.key in selected
            Surface(
                shape = RoundedCornerShape(50),
                color = if (checked) Accent.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant,
                // Android toggles on click and shows the aliases on long press; a pointer gets both here.
                modifier = Modifier
                    .testTag("$tagPrefix:${chip.key}")
                    .pointerInput(chip.key, checked) {
                        detectTapGestures(
                            onTap = { onToggle(chip.key) },
                            onLongPress = { onDetails(chip) },
                        )
                    },
            ) {
                Text(
                    chip.title,
                    fontSize = 12.sp,
                    color = if (checked) Accent else Ink,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
