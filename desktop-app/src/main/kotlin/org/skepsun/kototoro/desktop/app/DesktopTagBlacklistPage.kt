package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.model.TagTaxonomy
import org.skepsun.kototoro.core.model.TagTitleBlacklist
import org.skepsun.kototoro.core.model.TaxonomyCategory
import org.skepsun.kototoro.core.model.TaxonomyTag
import org.skepsun.kototoro.core.ui.settings.BlacklistTagUiModel
import org.skepsun.kototoro.core.ui.settings.BlacklistedTagsContent
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow

/**
 * Android's `GlobalTagBlacklistScreen`. The catalogue, the ids and the `raw:` keys are the shared [TagTaxonomy] and
 * [TagTitleBlacklist], so a selection made here hides exactly the works Android hides: the stored strings are what
 * the shelf derivers, the feed and the browse list feed into the shared matcher.
 *
 * The grid, the chips and the "add a pattern" action are the shared [BlacklistedTagsContent] Android draws, passed in
 * as the window's own rows: the back button, the count and CLEAR make up the header, the note the footer. Android shows
 * a chip's aliases on long press; Windows shows the same dialog with the key and the English label as well, which keeps
 * a single click free for toggling.
 */
@Composable
internal fun DesktopTagBlacklistPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.sourceSettings
    val active = !state.busy
    var query by remember { mutableStateOf("") }
    var details by remember { mutableStateOf<DesktopBlacklistChip?>(null) }
    val languageTag by DesktopStrings.tag
    val chips = remember(languageTag) { desktopBlacklistChips(languageTag) }
    val rawCategory = remember(languageTag) { rawCategoryLabel(languageTag) }
    val rows = remember(chips, rawCategory) { chips.map { it.toUiModel(rawCategory) } }
    val selected = settings.tagBlacklist
    val matches = remember(rows, query) {
        val text = query.trim()
        if (text.isEmpty()) rows
        else rows.filter { row ->
            val taxonomy = chips.firstOrNull { it.key == row.key }?.taxonomy
            taxonomy?.let { TagTaxonomy.search(it, text) } ?: row.label.contains(text, ignoreCase = true)
        }
    }
    val rawKey = TagTitleBlacklist.rawTagKey(query)
    // Android offers the raw pattern only when the text names something the catalogue cannot resolve.
    val offerRaw = query.isNotBlank() && rawKey !in selected && TagTaxonomy.resolve(query).isEmpty()
    BlacklistedTagsContent(
        available = matches,
        selected = selected.map { it.toChip(chips, languageTag).toUiModel(rawCategory) },
        query = query,
        onQueryChange = { query = it },
        onToggle = controller::toggleTagBlacklist,
        onShowDetails = { details = it.key.toChip(chips, languageTag) },
        modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth(),
        enabled = active,
        rawPatternLabel = if (offerRaw) desktopText("sources.tagBlacklistAddPattern", query.trim()) else null,
        onAddRawPattern = { controller.toggleTagBlacklist(rawKey) },
        searchModifier = Modifier.testTag("tag-blacklist-search"),
        tagPrefix = "tag-blacklist",
        header = {
            // The window's own title bar already names the screen, so only the count and Android's "clear" stay here.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ controller.showSourceSettings() }, enabled = active,
                    modifier = Modifier.testTag("tag-blacklist-back")) { Text(desktopText("common.back")) }
                // The shared grid already prints Android's selected count, so this row keeps only Android's CLEAR.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ controller.clearTagBlacklist() }, enabled = active && selected.isNotEmpty(),
                        modifier = Modifier.testTag("tag-blacklist-clear")) {
                        Text(desktopText("sources.tagBlacklistClear"))
                    }
                }
            }
        },
        footer = {
            SettingsInfoRow(desktopText("sources.tagBlacklistNote"), null)
        },
    )
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

private fun DesktopBlacklistChip.toUiModel(rawCategory: String): BlacklistTagUiModel = BlacklistTagUiModel(
    key = key,
    label = title,
    aliases = taxonomy?.aliases?.sorted().orEmpty(),
    categoryId = taxonomy?.category?.name ?: TaxonomyCategory.RAW.name,
    categoryName = categoryLabel ?: rawCategory,
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
