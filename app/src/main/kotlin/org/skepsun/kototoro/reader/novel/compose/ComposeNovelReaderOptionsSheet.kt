package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.reader.ui.compose.ReaderAnimationIcon
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionValueRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderSegmentedChoice
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
		ReaderSegmentedChoice(
			title = stringResource(R.string.novel_reading_mode),
			options = listOf(stringResource(R.string.novel_mode_paged), stringResource(R.string.novel_mode_scroll)),
			selectedIndex = if (settings.readingMode == ReadingMode.PAGED) 0 else 1,
			onSelected = { update { copy(readingMode = if (it == 0) ReadingMode.PAGED else ReadingMode.SCROLL) } },
			iconOnly = true,
			icon = { NovelReadingModeIcon(it) },
		)
	}
	if (settings.readingMode == ReadingMode.PAGED) {
		item {
			ReaderSegmentedChoice(
				title = stringResource(R.string.novel_page_turn_animation),
				options = NovelPageTurnAnimation.entries.map { stringResource(it.label) },
				selectedIndex = NovelPageTurnAnimation.entries.indexOf(settings.pageTurnAnimation),
				onSelected = { update { copy(pageTurnAnimation = NovelPageTurnAnimation.entries[it]) } },
				iconOnly = true,
				icon = { NovelPageAnimationIcon(NovelPageTurnAnimation.entries[it]) },
			)
		}
	}
	item {
		ReaderOptionGroup {
			NovelSwitchRows(settings, update)
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
		ReaderSegmentedChoice(
			title = stringResource(R.string.novel_theme_preset),
			options = NovelReaderThemePreset.entries.map { stringResource(it.label) },
			selectedIndex = NovelReaderThemePreset.entries.indexOf(settings.themePreset),
			onSelected = { update { copy(themePreset = NovelReaderThemePreset.entries[it]) } },
			iconOnly = true,
			icon = { NovelThemeSwatch(NovelReaderThemePreset.entries[it]) },
		)
	}
	item {
		ReaderOptionGroup {
			NovelTypographyRows(settings, update, onEditSlider)
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
		ReaderSegmentedChoice(
			title = stringResource(R.string.novel_translation_display_mode),
			options = listOf(
				stringResource(R.string.novel_translation_only),
				stringResource(R.string.novel_translation_bilingual),
			),
			selectedIndex = if (settings.translationDisplayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY) 0 else 1,
			onSelected = {
				update {
					copy(
						translationDisplayMode = if (it == 0) {
							NovelTranslationDisplayMode.TRANSLATION_ONLY
						} else {
							NovelTranslationDisplayMode.BILINGUAL
						},
					)
				}
			},
		)
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
			ReaderSegmentedChoice(
				title = stringResource(R.string.novel_reading_mode),
				options = listOf(stringResource(R.string.novel_mode_paged), stringResource(R.string.novel_mode_scroll)),
				selectedIndex = if (settings.readingMode == ReadingMode.PAGED) 0 else 1,
				onSelected = { update { copy(readingMode = if (it == 0) ReadingMode.PAGED else ReadingMode.SCROLL) } },
				iconOnly = true,
				icon = { NovelReadingModeIcon(it) },
			)
		}
		item {
			ReaderSegmentedChoice(
				title = stringResource(R.string.novel_theme_preset),
				options = NovelReaderThemePreset.entries.map { stringResource(it.label) },
				selectedIndex = NovelReaderThemePreset.entries.indexOf(settings.themePreset),
				onSelected = { update { copy(themePreset = NovelReaderThemePreset.entries[it]) } },
				iconOnly = true,
				icon = { NovelThemeSwatch(NovelReaderThemePreset.entries[it]) },
			)
		}
		item {
			ReaderOptionGroup {
				NovelTypographyRows(settings, ::update) { sliderEditor = it }
			}
		}
		item {
			ReaderOptionGroup {
				NovelSwitchRows(settings, ::update, includeTransparentStatusBar = false)
			}
		}
		if (settings.readingMode == ReadingMode.PAGED) {
			item {
				ReaderSegmentedChoice(
					title = stringResource(R.string.novel_page_turn_animation),
					options = NovelPageTurnAnimation.entries.map { stringResource(it.label) },
					selectedIndex = NovelPageTurnAnimation.entries.indexOf(settings.pageTurnAnimation),
					onSelected = { update { copy(pageTurnAnimation = NovelPageTurnAnimation.entries[it]) } },
					iconOnly = true,
					icon = { NovelPageAnimationIcon(NovelPageTurnAnimation.entries[it]) },
				)
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

@Composable
private fun NovelSwitchRows(
	settings: NovelReaderSettings,
	update: (NovelReaderSettings.() -> NovelReaderSettings) -> Unit,
	includeTransparentStatusBar: Boolean = true,
) {
	ReaderOptionSwitchRow(
		label = stringResource(R.string.novel_dual_page_mode),
		checked = settings.enableDualPage,
		onCheckedChange = { update { copy(enableDualPage = it) } },
	)
	ReaderOptionDivider()
	ReaderOptionSwitchRow(
		label = stringResource(R.string.novel_fullscreen_mode),
		checked = settings.enableFullscreen,
		onCheckedChange = { update { copy(enableFullscreen = it) } },
	)
	ReaderOptionDivider()
	ReaderOptionSwitchRow(
		label = stringResource(R.string.novel_show_reading_status),
		checked = settings.showReadingStatus,
		onCheckedChange = { update { copy(showReadingStatus = it) } },
	)
	if (includeTransparentStatusBar) {
		ReaderOptionDivider()
		ReaderOptionSwitchRow(
			label = stringResource(R.string.novel_transparent_status_bar),
			checked = settings.isReadingStatusTransparent,
			onCheckedChange = { update { copy(isReadingStatusTransparent = it) } },
		)
	}
	ReaderOptionDivider()
	ReaderOptionSwitchRow(
		label = stringResource(R.string.novel_first_line_indent),
		checked = settings.enableParagraphIndent,
		onCheckedChange = { update { copy(enableParagraphIndent = it) } },
	)
}

@Composable
private fun NovelTypographyRows(
	settings: NovelReaderSettings,
	update: (NovelReaderSettings.() -> NovelReaderSettings) -> Unit,
	onEditSlider: (SliderEditor) -> Unit,
) {
	ReaderOptionValueRow(
		label = stringResource(R.string.novel_font_size),
		value = "%.1fsp".format(settings.fontSizeSp),
		onClick = {
			onEditSlider(
				SliderEditor(R.string.novel_font_size, settings.fontSizeSp, NovelReaderSettings.FONT_SIZE_RANGE) {
					update { copy(fontSizeSp = it) }
				},
			)
		},
	)
	ReaderOptionDivider()
	ReaderOptionValueRow(
		label = stringResource(R.string.novel_line_spacing),
		value = "%.1f".format(settings.lineSpacing),
		onClick = {
			onEditSlider(
				SliderEditor(R.string.novel_line_spacing, settings.lineSpacing, NovelReaderSettings.LINE_SPACING_RANGE) {
					update { copy(lineSpacing = it) }
				},
			)
		},
	)
	ReaderOptionDivider()
	ReaderOptionValueRow(
		label = stringResource(R.string.novel_paragraph_spacing),
		value = "%.0fdp".format(settings.paragraphSpacing),
		onClick = {
			onEditSlider(
				SliderEditor(
					R.string.novel_paragraph_spacing,
					settings.paragraphSpacing,
					NovelReaderSettings.PARAGRAPH_SPACING_RANGE,
				) { update { copy(paragraphSpacing = it) } },
			)
		},
	)
	ReaderOptionDivider()
	ReaderOptionValueRow(
		label = stringResource(R.string.novel_margin_horizontal),
		value = "${settings.marginHorizontal}dp",
		onClick = {
			onEditSlider(
				SliderEditor(
					R.string.novel_margin_horizontal,
					settings.marginHorizontal.toFloat(),
					NovelReaderSettings.MARGIN_RANGE.asFloatRange(),
				) { update { copy(marginHorizontal = it.toInt()) } },
			)
		},
	)
	ReaderOptionDivider()
	ReaderOptionValueRow(
		label = stringResource(R.string.novel_margin_vertical),
		value = "${settings.marginVertical}dp",
		onClick = {
			onEditSlider(
				SliderEditor(
					R.string.novel_margin_vertical,
					settings.marginVertical.toFloat(),
					NovelReaderSettings.MARGIN_RANGE.asFloatRange(),
				) { update { copy(marginVertical = it.toInt()) } },
			)
		},
	)
}

@Composable
private fun NovelReadingModeIcon(index: Int) {
	Icon(
		painter = painterResource(if (index == 0) R.drawable.ic_book_page else R.drawable.ic_gesture_vertical),
		contentDescription = null,
		modifier = Modifier.size(20.dp),
	)
}

@Composable
private fun NovelPageAnimationIcon(animation: NovelPageTurnAnimation) {
	ReaderAnimationIcon(
		if (animation == NovelPageTurnAnimation.SLIDE) ReaderAnimation.DEFAULT else ReaderAnimation.SIMULATION,
	)
}

@Composable
private fun NovelThemeSwatch(preset: NovelReaderThemePreset) {
	val palette = novelReaderPalette(preset, isSystemInDarkTheme())
	Box(
		modifier = Modifier
			.size(20.dp)
			.background(Color(palette.backgroundColor), RoundedCornerShape(5.dp))
			.border(1.dp, Color(palette.secondaryTextColor).copy(alpha = 0.7f), RoundedCornerShape(5.dp)),
	)
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

private fun IntRange.asFloatRange() = first.toFloat()..last.toFloat()
private data class SliderEditor(val title: Int, val value: Float, val range: ClosedFloatingPointRange<Float>, val onChange: (Float) -> Unit)

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
