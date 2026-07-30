package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposeNovelReaderOptionsSheet(
	settings: NovelReaderSettings,
	onDismiss: () -> Unit,
	onSettingsChanged: (NovelReaderSettings) -> Unit,
	onToggleTranslation: () -> Unit,
	onBookmark: () -> Unit,
	onTts: () -> Unit,
	onClearTranslationCache: () -> Unit,
) {
	var sliderEditor by remember { mutableStateOf<SliderEditor?>(null) }
	fun update(transform: NovelReaderSettings.() -> NovelReaderSettings) {
		onSettingsChanged(settings.transform().normalized())
	}
	val pages = listOf(
		NovelOptionsPage(R.drawable.ic_book_page, R.string.novel_reading_mode),
		NovelOptionsPage(R.drawable.ic_appearance, R.string.appearance),
		NovelOptionsPage(R.drawable.ic_translate, R.string.novel_translation_display_mode),
		NovelOptionsPage(R.drawable.ic_more_vert, R.string.reader_actions),
	)
	val pagerState = rememberPagerState(pageCount = pages::size)
	val scope = rememberCoroutineScope()
	ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight()) {
		Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
			Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
				pages.forEachIndexed { index, page ->
					NovelOptionsTab(
						page = page,
						selected = pagerState.currentPage == index,
						onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
					)
				}
			}
			HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
				when (page) {
					0 -> NovelReadingOptionsPage(settings, ::update)
					1 -> NovelAppearanceOptionsPage(settings, ::update, onEditSlider = { sliderEditor = it })
					2 -> NovelTranslationOptionsPage(
						settings = settings,
						update = ::update,
						onToggleTranslation = { onToggleTranslation(); onDismiss() },
						onClearTranslationCache = onClearTranslationCache,
					)
					else -> NovelMiscOptionsPage(
						onBookmark = { onBookmark(); onDismiss() },
						onTts = { onTts(); onDismiss() },
						onReset = { onSettingsChanged(NovelReaderSettings()) },
					)
				}
			}
		}
		SliderEditorDialog(sliderEditor) { sliderEditor = null }
	}
}

@Immutable
private data class NovelOptionsPage(val icon: Int, val label: Int)

@Composable
private fun RowScope.NovelOptionsTab(page: NovelOptionsPage, selected: Boolean, onClick: () -> Unit) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
	) {
		IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
			Icon(
				painter = painterResource(page.icon),
				contentDescription = stringResource(page.label),
				tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun NovelReadingOptionsPage(
	settings: NovelReaderSettings,
	update: (NovelReaderSettings.() -> NovelReaderSettings) -> Unit,
) = NovelOptionsPageList {
	item {
		CompactGrid {
			SelectRow(
				stringResource(R.string.novel_reading_mode),
				stringResource(if (settings.readingMode == ReadingMode.PAGED) R.string.novel_mode_paged else R.string.novel_mode_scroll),
				listOf(stringResource(R.string.novel_mode_paged), stringResource(R.string.novel_mode_scroll)),
				Modifier.weight(1f),
			) { update { copy(readingMode = if (it == 0) ReadingMode.PAGED else ReadingMode.SCROLL) } }
			if (settings.readingMode == ReadingMode.PAGED) {
				SelectRow(
					stringResource(R.string.novel_page_turn_animation),
					stringResource(settings.pageTurnAnimation.label),
					NovelPageTurnAnimation.entries.map { stringResource(it.label) },
					Modifier.weight(1f),
				) { update { copy(pageTurnAnimation = NovelPageTurnAnimation.entries[it]) } }
			}
		}
	}
	item {
		FlowRow(maxItemsInEachRow = 2, modifier = Modifier.fillMaxWidth()) {
			OptionSwitch(R.string.novel_dual_page_mode, settings.enableDualPage, Modifier.weight(1f)) { update { copy(enableDualPage = it) } }
			OptionSwitch(R.string.novel_fullscreen_mode, settings.enableFullscreen, Modifier.weight(1f)) { update { copy(enableFullscreen = it) } }
			OptionSwitch(R.string.novel_show_reading_status, settings.showReadingStatus, Modifier.weight(1f)) { update { copy(showReadingStatus = it) } }
			OptionSwitch(R.string.novel_transparent_status_bar, settings.isReadingStatusTransparent, Modifier.weight(1f)) { update { copy(isReadingStatusTransparent = it) } }
			OptionSwitch(R.string.novel_first_line_indent, settings.enableParagraphIndent, Modifier.weight(1f)) { update { copy(enableParagraphIndent = it) } }
		}
	}
}

@Composable
private fun NovelAppearanceOptionsPage(
	settings: NovelReaderSettings,
	update: (NovelReaderSettings.() -> NovelReaderSettings) -> Unit,
	onEditSlider: (SliderEditor) -> Unit,
) = NovelOptionsPageList {
	item { NovelPreview(settings) }
	item {
		SelectRow(
			stringResource(R.string.novel_theme_preset),
			stringResource(settings.themePreset.label),
			NovelReaderThemePreset.entries.map { stringResource(it.label) },
		) { update { copy(themePreset = NovelReaderThemePreset.entries[it]) } }
	}
	item {
		CompactGrid {
			SliderRow(R.string.novel_font_size, "%.1fsp".format(settings.fontSizeSp), Modifier.weight(1f)) { onEditSlider(SliderEditor(R.string.novel_font_size, settings.fontSizeSp, NovelReaderSettings.FONT_SIZE_RANGE) { update { copy(fontSizeSp = it) } }) }
			SliderRow(R.string.novel_line_spacing, "%.1f".format(settings.lineSpacing), Modifier.weight(1f)) { onEditSlider(SliderEditor(R.string.novel_line_spacing, settings.lineSpacing, NovelReaderSettings.LINE_SPACING_RANGE) { update { copy(lineSpacing = it) } }) }
			SliderRow(R.string.novel_paragraph_spacing, "%.0fdp".format(settings.paragraphSpacing), Modifier.weight(1f)) { onEditSlider(SliderEditor(R.string.novel_paragraph_spacing, settings.paragraphSpacing, NovelReaderSettings.PARAGRAPH_SPACING_RANGE) { update { copy(paragraphSpacing = it) } }) }
			SliderRow(R.string.novel_margin_horizontal, "${settings.marginHorizontal}dp", Modifier.weight(1f)) { onEditSlider(SliderEditor(R.string.novel_margin_horizontal, settings.marginHorizontal.toFloat(), NovelReaderSettings.MARGIN_RANGE.asFloatRange()) { update { copy(marginHorizontal = it.toInt()) } }) }
			SliderRow(R.string.novel_margin_vertical, "${settings.marginVertical}dp", Modifier.weight(1f)) { onEditSlider(SliderEditor(R.string.novel_margin_vertical, settings.marginVertical.toFloat(), NovelReaderSettings.MARGIN_RANGE.asFloatRange()) { update { copy(marginVertical = it.toInt()) } }) }
		}
	}
}

@Composable
private fun NovelTranslationOptionsPage(
	settings: NovelReaderSettings,
	update: (NovelReaderSettings.() -> NovelReaderSettings) -> Unit,
	onToggleTranslation: () -> Unit,
	onClearTranslationCache: () -> Unit,
) = NovelOptionsPageList {
	item {
		SelectRow(
			stringResource(R.string.novel_translation_display_mode),
			stringResource(if (settings.translationDisplayMode == NovelTranslationDisplayMode.BILINGUAL) R.string.novel_translation_bilingual else R.string.novel_translation_only),
			listOf(stringResource(R.string.novel_translation_only), stringResource(R.string.novel_translation_bilingual)),
		) { update { copy(translationDisplayMode = if (it == 0) NovelTranslationDisplayMode.TRANSLATION_ONLY else NovelTranslationDisplayMode.BILINGUAL) } }
	}
	item {
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Action(R.drawable.ic_translate, R.string.reader_translation_action, onToggleTranslation)
			Action(R.drawable.ic_delete, R.string.clear_translation_cache, onClearTranslationCache)
		}
	}
}

@Composable
private fun NovelMiscOptionsPage(onBookmark: () -> Unit, onTts: () -> Unit, onReset: () -> Unit) =
	NovelOptionsPageList {
		item {
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Action(R.drawable.ic_bookmark, R.string.bookmark_add, onBookmark)
				Action(R.drawable.ic_voice_input, R.string.tts_settings_title, onTts)
				Action(R.drawable.ic_backup_restore, R.string.novel_reset, onReset)
			}
		}
	}

@Composable
private fun NovelOptionsPageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
	LazyColumn(
		verticalArrangement = Arrangement.spacedBy(10.dp),
		contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
		modifier = Modifier.fillMaxSize(),
		content = content,
	)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComposeNovelReaderOptionsPanel(
	settings: NovelReaderSettings,
	onSettingsChanged: (NovelReaderSettings) -> Unit,
	modifier: Modifier = Modifier,
) {
	var sliderEditor by remember { mutableStateOf<SliderEditor?>(null) }
	fun update(transform: NovelReaderSettings.() -> NovelReaderSettings) {
		onSettingsChanged(settings.transform().normalized())
	}
	LazyColumn(
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = modifier
			.fillMaxWidth()
			.heightIn(max = 420.dp)
			.padding(horizontal = 12.dp, vertical = 10.dp),
	) {
		item { NovelPreview(settings) }
		item {
			CompactGrid {
				SelectRow(
					stringResource(R.string.novel_reading_mode),
					if (settings.readingMode == ReadingMode.PAGED) {
						stringResource(R.string.novel_mode_paged)
					} else {
						stringResource(R.string.novel_mode_scroll)
					},
					listOf(stringResource(R.string.novel_mode_paged), stringResource(R.string.novel_mode_scroll)),
					Modifier.weight(1f),
				) { index ->
					update { copy(readingMode = if (index == 0) ReadingMode.PAGED else ReadingMode.SCROLL) }
				}
				SelectRow(
					stringResource(R.string.novel_theme_preset),
					stringResource(settings.themePreset.label),
					NovelReaderThemePreset.entries.map { stringResource(it.label) },
					Modifier.weight(1f),
				) { index -> update { copy(themePreset = NovelReaderThemePreset.entries[index]) } }
			}
		}
		item {
			CompactGrid {
				SliderRow(R.string.novel_font_size, "%.1fsp".format(settings.fontSizeSp), Modifier.weight(1f)) {
					sliderEditor = SliderEditor(
						R.string.novel_font_size,
						settings.fontSizeSp,
						NovelReaderSettings.FONT_SIZE_RANGE,
					) { update { copy(fontSizeSp = it) } }
				}
				SliderRow(R.string.novel_line_spacing, "%.1f".format(settings.lineSpacing), Modifier.weight(1f)) {
					sliderEditor = SliderEditor(
						R.string.novel_line_spacing,
						settings.lineSpacing,
						NovelReaderSettings.LINE_SPACING_RANGE,
					) { update { copy(lineSpacing = it) } }
				}
				SliderRow(
					R.string.novel_paragraph_spacing,
					"%.0fdp".format(settings.paragraphSpacing),
					Modifier.weight(1f),
				) {
					sliderEditor = SliderEditor(
						R.string.novel_paragraph_spacing,
						settings.paragraphSpacing,
						NovelReaderSettings.PARAGRAPH_SPACING_RANGE,
					) { update { copy(paragraphSpacing = it) } }
				}
				SliderRow(
					R.string.novel_margin_horizontal,
					"${settings.marginHorizontal}dp",
					Modifier.weight(1f),
				) {
					sliderEditor = SliderEditor(
						R.string.novel_margin_horizontal,
						settings.marginHorizontal.toFloat(),
						NovelReaderSettings.MARGIN_RANGE.asFloatRange(),
					) { update { copy(marginHorizontal = it.toInt()) } }
				}
				SliderRow(
					R.string.novel_margin_vertical,
					"${settings.marginVertical}dp",
					Modifier.weight(1f),
				) {
					sliderEditor = SliderEditor(
						R.string.novel_margin_vertical,
						settings.marginVertical.toFloat(),
						NovelReaderSettings.MARGIN_RANGE.asFloatRange(),
					) { update { copy(marginVertical = it.toInt()) } }
				}
			}
		}
		item {
			FlowRow(
				maxItemsInEachRow = 2,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
			) {
				OptionSwitch(R.string.novel_dual_page_mode, settings.enableDualPage, Modifier.weight(1f)) {
					update { copy(enableDualPage = it) }
				}
				OptionSwitch(R.string.novel_fullscreen_mode, settings.enableFullscreen, Modifier.weight(1f)) {
					update { copy(enableFullscreen = it) }
				}
				OptionSwitch(R.string.novel_show_reading_status, settings.showReadingStatus, Modifier.weight(1f)) {
					update { copy(showReadingStatus = it) }
				}
				OptionSwitch(R.string.novel_first_line_indent, settings.enableParagraphIndent, Modifier.weight(1f)) {
					update { copy(enableParagraphIndent = it) }
				}
			}
		}
		if (settings.readingMode == ReadingMode.PAGED) {
			item {
				CompactGrid {
					SelectRow(
						stringResource(R.string.novel_page_turn_animation),
						stringResource(settings.pageTurnAnimation.label),
						NovelPageTurnAnimation.entries.map { stringResource(it.label) },
						Modifier.weight(1f),
					) { index -> update { copy(pageTurnAnimation = NovelPageTurnAnimation.entries[index]) } }
				}
			}
		}
		item {
			Action(R.drawable.ic_backup_restore, R.string.novel_reset) {
				onSettingsChanged(NovelReaderSettings())
			}
		}
	}
	SliderEditorDialog(sliderEditor) { sliderEditor = null }
}

@Composable private fun OptionSwitch(label: Int, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
	) {
		Text(
			stringResource(label),
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.weight(1f),
		)
		Switch(checked = checked, onCheckedChange = onChange)
	}
}

@Composable private fun NovelPreview(settings: NovelReaderSettings) {
	val palette = novelReaderPalette(settings.themePreset, isSystemInDarkTheme())
	val shape = RoundedCornerShape(18.dp)
	val backgroundColor = Color(palette.backgroundColor)
	val textColor = Color(palette.textColor)
	val secondaryTextColor = Color(palette.secondaryTextColor)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(backgroundColor, shape)
			.border(1.dp, secondaryTextColor.copy(alpha = 0.22f), shape)
			.padding(horizontal = 16.dp, vertical = 14.dp),
	) {
		Text(
			stringResource(R.string.novel_preview_caption),
			style = MaterialTheme.typography.labelMedium,
			color = secondaryTextColor,
		)
		Text(
			stringResource(R.string.novel_preview_title),
			style = MaterialTheme.typography.titleMedium,
			color = textColor,
			modifier = Modifier.padding(top = 4.dp),
		)
		Text(
			stringResource(R.string.novel_preview_body),
			fontSize = settings.fontSizeSp.sp,
			lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
			color = textColor,
			maxLines = 3,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = settings.paragraphSpacing.dp.coerceAtLeast(8.dp)),
		)
		if (settings.translationDisplayMode == NovelTranslationDisplayMode.BILINGUAL) {
			Text(
				stringResource(R.string.novel_preview_body_secondary),
				style = MaterialTheme.typography.bodyMedium,
				color = secondaryTextColor,
				modifier = Modifier.padding(top = 6.dp),
			)
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
	FlowRow(
		maxItemsInEachRow = 2,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
		content = content,
	)
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
