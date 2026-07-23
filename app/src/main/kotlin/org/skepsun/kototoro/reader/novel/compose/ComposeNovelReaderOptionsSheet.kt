package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.reader.novel.NovelPageTurnAnimation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelReaderThemePreset
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode
import org.skepsun.kototoro.reader.novel.ReadingMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposeNovelReaderOptionsSheet(
	settings: NovelReaderSettings,
	onDismiss: () -> Unit,
	onSettingsChanged: (NovelReaderSettings) -> Unit,
	onBookmark: () -> Unit,
	onTts: () -> Unit,
	onClearTranslationCache: () -> Unit,
) {
	var sliderEditor by remember { mutableStateOf<SliderEditor?>(null) }
	fun update(transform: NovelReaderSettings.() -> NovelReaderSettings) {
		onSettingsChanged(settings.transform().normalized())
	}
	ModalBottomSheet(onDismissRequest = onDismiss) {
		BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
			val contentWidth = if (maxWidth >= 800.dp) 760.dp else maxWidth
			LazyColumn(
				verticalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier
					.widthIn(max = contentWidth)
					.align(Alignment.TopCenter)
					.padding(horizontal = 16.dp),
			) {
			item { NovelPreview(settings) }
			item { SectionTitle(stringResource(R.string.novel_reading_mode)) }
			item {
				CompactGrid {
					SelectRow(stringResource(R.string.novel_reading_mode), if (settings.readingMode == ReadingMode.PAGED) stringResource(R.string.novel_mode_paged) else stringResource(R.string.novel_mode_scroll), listOf(stringResource(R.string.novel_mode_paged), stringResource(R.string.novel_mode_scroll)), modifier = Modifier.weight(1f)) { index -> update { copy(readingMode = if (index == 0) ReadingMode.PAGED else ReadingMode.SCROLL) } }
					SelectRow(stringResource(R.string.novel_theme_preset), stringResource(settings.themePreset.label), NovelReaderThemePreset.entries.map { stringResource(it.label) }, modifier = Modifier.weight(1f)) { index -> update { copy(themePreset = NovelReaderThemePreset.entries[index]) } }
				}
			}
			item { SectionTitle(stringResource(R.string.novel_margins)) }
			item {
				CompactGrid {
					SliderRow(R.string.novel_font_size, "%.1fsp".format(settings.fontSizeSp), Modifier.weight(1f)) { sliderEditor = SliderEditor(R.string.novel_font_size, settings.fontSizeSp, NovelReaderSettings.FONT_SIZE_RANGE) { update { copy(fontSizeSp = it) } } }
					SliderRow(R.string.novel_line_spacing, "%.1f".format(settings.lineSpacing), Modifier.weight(1f)) { sliderEditor = SliderEditor(R.string.novel_line_spacing, settings.lineSpacing, NovelReaderSettings.LINE_SPACING_RANGE) { update { copy(lineSpacing = it) } } }
					SliderRow(R.string.novel_paragraph_spacing, "%.0fdp".format(settings.paragraphSpacing), Modifier.weight(1f)) { sliderEditor = SliderEditor(R.string.novel_paragraph_spacing, settings.paragraphSpacing, NovelReaderSettings.PARAGRAPH_SPACING_RANGE) { update { copy(paragraphSpacing = it) } } }
					SliderRow(R.string.novel_margin_horizontal, "${settings.marginHorizontal}dp", Modifier.weight(1f)) { sliderEditor = SliderEditor(R.string.novel_margin_horizontal, settings.marginHorizontal.toFloat(), NovelReaderSettings.MARGIN_RANGE.asFloatRange()) { update { copy(marginHorizontal = it.toInt()) } } }
					SliderRow(R.string.novel_margin_vertical, "${settings.marginVertical}dp", Modifier.weight(1f)) { sliderEditor = SliderEditor(R.string.novel_margin_vertical, settings.marginVertical.toFloat(), NovelReaderSettings.MARGIN_RANGE.asFloatRange()) { update { copy(marginVertical = it.toInt()) } } }
				}
			}
			item { HorizontalDivider() }
			item {
				Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
				FlowRow(maxItemsInEachRow = 2, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
					OptionSwitch(R.string.novel_dual_page_mode, settings.enableDualPage, Modifier.weight(1f)) { update { copy(enableDualPage = it) } }
					OptionSwitch(R.string.novel_fullscreen_mode, settings.enableFullscreen, Modifier.weight(1f)) { update { copy(enableFullscreen = it) } }
					OptionSwitch(R.string.novel_show_reading_status, settings.showReadingStatus, Modifier.weight(1f)) { update { copy(showReadingStatus = it) } }
					OptionSwitch(R.string.novel_transparent_status_bar, settings.isReadingStatusTransparent, Modifier.weight(1f)) { update { copy(isReadingStatusTransparent = it) } }
					OptionSwitch(R.string.novel_first_line_indent, settings.enableParagraphIndent, Modifier.weight(1f)) { update { copy(enableParagraphIndent = it) } }
				}
				}
			}
			if (settings.readingMode == ReadingMode.PAGED) {
				item { SectionTitle(stringResource(R.string.novel_page_turn_animation)) }
				item {
					CompactGrid {
						SelectRow(stringResource(R.string.novel_page_turn_animation), stringResource(settings.pageTurnAnimation.label), NovelPageTurnAnimation.entries.map { stringResource(it.label) }, modifier = Modifier.weight(1f)) { index -> update { copy(pageTurnAnimation = NovelPageTurnAnimation.entries[index]) } }
					}
				}
			}
			item { SectionTitle(stringResource(R.string.novel_translation_display_mode)) }
			item {
				CompactGrid {
					SelectRow(stringResource(R.string.novel_translation_display_mode), stringResource(if (settings.translationDisplayMode == NovelTranslationDisplayMode.BILINGUAL) R.string.novel_translation_bilingual else R.string.novel_translation_only), listOf(stringResource(R.string.novel_translation_only), stringResource(R.string.novel_translation_bilingual)), modifier = Modifier.weight(1f)) { index -> update { copy(translationDisplayMode = if (index == 0) NovelTranslationDisplayMode.TRANSLATION_ONLY else NovelTranslationDisplayMode.BILINGUAL) } }
				}
			}
			item {
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
					modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
				) {
					Action(R.drawable.ic_bookmark, R.string.bookmark_add) { onBookmark(); onDismiss() }
					Action(R.drawable.ic_voice_input, R.string.tts_settings_title) { onTts(); onDismiss() }
					Action(R.drawable.ic_delete, R.string.clear_translation_cache, onClearTranslationCache)
					Action(R.drawable.ic_backup_restore, R.string.novel_reset) { onSettingsChanged(NovelReaderSettings()) }
				}
			}
			}
		}
		SliderEditorDialog(sliderEditor) { sliderEditor = null }
	}
}

@Composable private fun OptionSwitch(label: Int, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = modifier) {
	Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
		Text(stringResource(label), modifier = Modifier.weight(1f))
		Switch(checked = checked, onCheckedChange = onChange)
	}
	}
}

@Composable private fun NovelPreview(settings: NovelReaderSettings) {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(16.dp)) {
			Text(stringResource(R.string.novel_preview_caption), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
			Text(stringResource(R.string.novel_preview_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
			Text(stringResource(R.string.novel_preview_body), fontSize = settings.fontSizeSp.sp, lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
			if (settings.translationDisplayMode == NovelTranslationDisplayMode.BILINGUAL) {
				Text(stringResource(R.string.novel_preview_body_secondary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
			}
		}
	}
}

@Composable private fun Action(icon: Int, label: Int, onClick: () -> Unit) {
	FilledTonalButton(onClick = onClick) {
		Icon(painterResource(icon), contentDescription = null)
		Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
	}
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

private fun IntRange.asFloatRange() = first.toFloat()..last.toFloat()
private data class SliderEditor(val title: Int, val value: Float, val range: ClosedFloatingPointRange<Float>, val onChange: (Float) -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CompactGrid(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
		FlowRow(maxItemsInEachRow = 2, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(10.dp), content = content)
	}
}

@Composable private fun SliderRow(title: Int, value: String, modifier: Modifier, onClick: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp)) {
		Column(modifier = Modifier.weight(1f)) {
			Text(stringResource(title), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
		}
		Text(">", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable private fun SelectRow(title: String, selected: String, options: List<String>, modifier: Modifier = Modifier, onSelected: (Int) -> Unit) {
	var expanded by remember { mutableStateOf(false) }
	Box(modifier = modifier) {
		Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 12.dp)) {
			Column(modifier = Modifier.weight(1f)) {
				Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(selected, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
			Text(">", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			options.forEachIndexed { index, option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelected(index) }) }
		}
	}
}

@Composable private fun SliderEditorDialog(editor: SliderEditor?, onDismiss: () -> Unit) {
	if (editor == null) return
	var value by remember(editor) { mutableStateOf(editor.value) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(editor.title)) },
		text = { Slider(value = value, onValueChange = { value = it; editor.onChange(it) }, valueRange = editor.range) },
		confirmButton = { FilledTonalButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
	)
}
private val NovelReaderThemePreset.label: Int get() = when (this) {
	NovelReaderThemePreset.PAPER -> R.string.novel_theme_paper
	NovelReaderThemePreset.SEPIA -> R.string.novel_theme_sepia
	NovelReaderThemePreset.MOSS -> R.string.novel_theme_moss
	NovelReaderThemePreset.SLATE -> R.string.novel_theme_slate
}
private val NovelPageTurnAnimation.label: Int get() = when (this) {
	NovelPageTurnAnimation.SLIDE -> R.string.novel_page_turn_slide
	NovelPageTurnAnimation.SIMULATION -> R.string.novel_page_turn_simulation
}
