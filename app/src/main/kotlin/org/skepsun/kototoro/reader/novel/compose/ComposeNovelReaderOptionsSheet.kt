package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.reader.novel.NovelPageTurnAnimation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelReaderThemePreset
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode
import org.skepsun.kototoro.reader.novel.ReadingMode
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import org.skepsun.kototoro.reader.ui.compose.ReaderAnimationIcon
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsDetailTabs
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsPageList
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsTab
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChoiceChips
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSection
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelToggleChip
import org.skepsun.kototoro.reader.ui.compose.design.ReaderQuickActionGrid
import org.skepsun.kototoro.reader.ui.compose.design.ReaderSliderRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderStepperRow
import org.skepsun.kototoro.reader.ui.compose.design.currentReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.forEInk
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderOptionsPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.rememberReaderPanelSurfaceMode
import kotlin.math.roundToInt

private const val DEFAULT_READER_BRIGHTNESS = 0.8f

private typealias NovelSettingsUpdate = (NovelReaderSettings.() -> NovelReaderSettings) -> Unit

@Composable
internal fun ComposeNovelReaderOptionsSheet(
    settings: NovelReaderSettings,
    onDismiss: () -> Unit,
    onSettingsChanged: (NovelReaderSettings) -> Unit,
    onToggleTranslation: () -> Unit,
    replaceRulesEnabled: Boolean = true,
    onToggleReplaceRules: () -> Unit = {},
    onShowReplaceRules: () -> Unit = {},
    onShowMarkings: () -> Unit = {},
    onBookmark: () -> Unit = {},
    onTts: () -> Unit,
    onClearTranslationCache: () -> Unit,
    eInkMode: Boolean = false,
) {
    fun update(transform: NovelReaderSettings.() -> NovelReaderSettings) {
        onSettingsChanged(settings.transform().normalized())
    }
    val colors = novelReaderPanelColors(novelReaderPalette(settings.themePreset, isSystemInDarkTheme()))
    ReaderOptionsPanelHost(
        colors = if (eInkMode) colors.forEInk() else colors,
        surfaceMode = rememberReaderPanelSurfaceMode(eInkMode),
        onDismissRequest = onDismiss,
        quickLayer = {
            NovelQuickLayer(
                settings = settings,
                update = ::update,
                onQuickAction = { id ->
                    when (id) {
                        NovelQuickActionId.TTS -> { onTts(); onDismiss() }
                        NovelQuickActionId.BOOKMARK -> { onBookmark(); onDismiss() }
                        NovelQuickActionId.MARKINGS -> { onShowMarkings(); onDismiss() }
                        NovelQuickActionId.TRANSLATE -> onToggleTranslation()
                    }
                },
            )
        },
        details = { dragModifier ->
            NovelDetailTabs(
                settings = settings,
                update = ::update,
                dragModifier = dragModifier,
                onToggleTranslation = onToggleTranslation,
                onClearTranslationCache = onClearTranslationCache,
                replaceRulesEnabled = replaceRulesEnabled,
                onToggleReplaceRules = onToggleReplaceRules,
                onShowReplaceRules = { onShowReplaceRules(); onDismiss() },
                onReset = { onSettingsChanged(NovelReaderSettings()) },
            )
        },
    )
}

@Composable
private fun NovelQuickLayer(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
    onQuickAction: (NovelQuickActionId) -> Unit,
) {
    NovelReaderQuickLayer(
        fontSize = novelFontSizeOption(settings, update),
        themes = novelThemeSwatches(),
        selectedTheme = settings.themePreset.name,
        onThemeSelected = { name -> update { copy(themePreset = NovelReaderThemePreset.valueOf(name)) } },
        brightness = {
            ReaderSliderRow(
                label = stringResource(R.string.brightness),
                // While following the system the chip beside it already says so.
                valueLabel = settings.screenBrightness?.let { "${(it * 100f).roundToInt()}%" }.orEmpty(),
                value = settings.screenBrightness ?: DEFAULT_READER_BRIGHTNESS,
                valueRange = NovelReaderSettings.SCREEN_BRIGHTNESS_RANGE,
                // Dragging sets a brightness of the reader's own, which ends "follow system".
                onValueChange = { value -> update { copy(screenBrightness = value) } },
                leadingIcon = painterResource(R.drawable.ic_lightbulb),
                trailing = {
                    ReaderPanelToggleChip(
                        label = stringResource(R.string.follow_system),
                        checked = settings.screenBrightness == null,
                        onCheckedChange = { followSystem ->
                            update {
                                copy(screenBrightness = if (followSystem) null else screenBrightness ?: DEFAULT_READER_BRIGHTNESS)
                            }
                        },
                    )
                },
                contentPadding = PaddingValues(0.dp),
            )
        },
        actions = {
            ReaderQuickActionGrid(
                actions = novelQuickActions(settings.isTranslationEnabled),
                onClick = { onQuickAction(NovelQuickActionId.valueOf(it.id)) },
                icon = { painterResource(NovelQuickActionId.valueOf(it.id).iconResId) },
                label = { stringResource(NovelQuickActionId.valueOf(it.id).labelResId) },
            )
        },
    )
}

@Composable
private fun novelFontSizeOption(settings: NovelReaderSettings, update: NovelSettingsUpdate) = NovelReaderValueOption(
    label = stringResource(R.string.novel_font_size),
    valueLabel = "%.1fsp".format(settings.fontSizeSp),
    value = settings.fontSizeSp,
    range = NovelReaderSettings.FONT_SIZE_RANGE,
    step = 1f,
    onValueChange = { value -> update { copy(fontSizeSp = value) } },
)

@Composable
private fun novelThemeSwatches(): List<NovelReaderThemeSwatch> {
    val isDark = isSystemInDarkTheme()
    return NovelReaderThemePreset.entries.map { preset ->
        val palette = novelReaderPalette(preset, isDark)
        NovelReaderThemeSwatch(
            id = preset.name,
            label = stringResource(preset.label),
            background = Color(palette.backgroundColor),
            text = Color(palette.textColor),
        )
    }
}

@Composable
private fun NovelDetailTabs(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
    dragModifier: Modifier,
    onToggleTranslation: () -> Unit,
    onClearTranslationCache: () -> Unit,
    replaceRulesEnabled: Boolean,
    onToggleReplaceRules: () -> Unit,
    onShowReplaceRules: () -> Unit,
    onReset: () -> Unit,
) {
    ReaderOptionsDetailTabs(
        tabs = listOf(
            ReaderOptionsTab(stringResource(R.string.novel_reader_tab_typography)) {
                NovelTypographyPage(settings, update)
            },
            ReaderOptionsTab(stringResource(R.string.novel_reader_tab_reading)) {
                NovelReadingPage(settings, update)
            },
            ReaderOptionsTab(stringResource(R.string.novel_reader_tab_translation_tools)) {
                NovelTranslationToolsPage(
                    settings = settings,
                    update = update,
                    onToggleTranslation = onToggleTranslation,
                    onClearTranslationCache = onClearTranslationCache,
                    replaceRulesEnabled = replaceRulesEnabled,
                    onToggleReplaceRules = onToggleReplaceRules,
                    onShowReplaceRules = onShowReplaceRules,
                    onReset = onReset,
                )
            },
        ),
        settingsDescription = "",
        onOpenSettings = null,
        dragModifier = dragModifier,
    )
}

@Composable
private fun NovelTypographyPage(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
) = NovelTypographyOptionsPage(
    fontSize = novelFontSizeOption(settings, update),
    lineSpacing = NovelReaderValueOption(
        label = stringResource(R.string.novel_line_spacing),
        valueLabel = "%.1f".format(settings.lineSpacing),
        value = settings.lineSpacing,
        range = NovelReaderSettings.LINE_SPACING_RANGE,
        step = NovelReaderSettings.LINE_SPACING_STEP,
        onValueChange = { value -> update { copy(lineSpacing = value) } },
    ),
    fontOption = {
        NovelReaderFontOptionRow(
            selected = settings.font,
            onSelected = { update { copy(font = it) } },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    },
    additionalOptions = {
        ReaderOptionDivider()
        ReaderStepperRow(
            label = stringResource(R.string.novel_paragraph_spacing),
            valueLabel = stringResource(R.string.novel_paragraph_spacing_value, settings.paragraphSpacingLines),
            value = settings.paragraphSpacing,
            valueRange = NovelReaderSettings.PARAGRAPH_SPACING_RANGE,
            step = NovelReaderSettings.PARAGRAPH_SPACING_STEP,
            onValueChange = { value -> update { copy(paragraphSpacing = value) } },
        )
        ReaderOptionDivider()
        NovelMarginStepper(
            label = stringResource(R.string.novel_margin_horizontal),
            value = settings.marginHorizontal,
            onValueChange = { value -> update { copy(marginHorizontal = value) } },
        )
        ReaderOptionDivider()
        NovelMarginStepper(
            label = stringResource(R.string.novel_margin_vertical),
            value = settings.marginVertical,
            onValueChange = { value -> update { copy(marginVertical = value) } },
        )
        ReaderOptionDivider()
        ReaderOptionSwitchRow(
            label = stringResource(R.string.novel_first_line_indent),
            checked = settings.enableParagraphIndent,
            onCheckedChange = { update { copy(enableParagraphIndent = it) } },
        )
    },
)

@Composable
private fun NovelMarginStepper(label: String, value: Int, onValueChange: (Int) -> Unit) {
    ReaderStepperRow(
        label = label,
        valueLabel = "${value}dp",
        value = value.toFloat(),
        valueRange = NovelReaderSettings.MARGIN_RANGE.first.toFloat()..NovelReaderSettings.MARGIN_RANGE.last.toFloat(),
        step = NovelReaderSettings.MARGIN_STEP.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
    )
}

@Composable
private fun NovelReadingPage(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
) = ReaderOptionsPageList {
    item {
        ReaderOptionSection(stringResource(R.string.novel_reading_mode)) {
            ReaderChoiceChips(
                options = listOf(stringResource(R.string.novel_mode_paged), stringResource(R.string.novel_mode_scroll)),
                selectedIndex = if (settings.readingMode == ReadingMode.PAGED) 0 else 1,
                onSelected = { update { copy(readingMode = if (it == 0) ReadingMode.PAGED else ReadingMode.SCROLL) } },
                icon = { NovelReadingModeIcon(it) },
            )
        }
    }
    if (settings.readingMode == ReadingMode.PAGED) {
        item {
            ReaderOptionSection(stringResource(R.string.novel_page_turn_animation)) {
                ReaderChoiceChips(
                    options = NovelPageTurnAnimation.entries.map { stringResource(it.label) },
                    selectedIndex = NovelPageTurnAnimation.entries.indexOf(settings.pageTurnAnimation),
                    onSelected = { update { copy(pageTurnAnimation = NovelPageTurnAnimation.entries[it]) } },
                    icon = { NovelPageAnimationIcon(NovelPageTurnAnimation.entries[it]) },
                )
            }
        }
    }
    item {
        ReaderOptionGroup {
            NovelSwitchRows(settings, update)
        }
    }
}

@Composable
private fun NovelTranslationToolsPage(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
    onToggleTranslation: () -> Unit,
    onClearTranslationCache: () -> Unit,
    replaceRulesEnabled: Boolean,
    onToggleReplaceRules: () -> Unit,
    onShowReplaceRules: () -> Unit,
    onReset: () -> Unit,
) = ReaderOptionsPageList {
    item {
        ReaderOptionGroup(title = stringResource(R.string.novel_reader_translation_section)) {
            ReaderOptionSwitchRow(
                label = stringResource(R.string.novel_reader_translation_enabled),
                checked = settings.isTranslationEnabled,
                onCheckedChange = { onToggleTranslation() },
            )
            ReaderOptionDivider()
            ReaderOptionSection(
                title = stringResource(R.string.novel_translation_display_mode),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                ReaderChoiceChips(
                    options = listOf(
                        stringResource(R.string.novel_translation_only),
                        stringResource(R.string.novel_translation_bilingual),
                    ),
                    selectedIndex = if (settings.translationDisplayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY) 0 else 1,
                    onSelected = { index ->
                        update {
                            copy(
                                translationDisplayMode = if (index == 0) {
                                    NovelTranslationDisplayMode.TRANSLATION_ONLY
                                } else {
                                    NovelTranslationDisplayMode.BILINGUAL
                                },
                            )
                        }
                    },
                    height = 44.dp,
                    // Inside a card: unselected chips take the panel colour so they stay visible.
                    unselectedColor = currentReaderPanelColors().container,
                )
            }
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onToggleTranslation, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_translate), contentDescription = null)
                Text(stringResource(R.string.novel_reader_translation_start), modifier = Modifier.padding(start = 8.dp))
            }
            FilledTonalButton(onClick = onClearTranslationCache, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                Text(stringResource(R.string.clear_translation_cache), modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                text = stringResource(R.string.novel_reader_translation_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = currentReaderPanelColors().contentSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    item {
        ReaderOptionGroup {
            NovelToolActionRow(
                icon = R.drawable.ic_replace,
                title = stringResource(R.string.replace_rule_effective_title),
                supporting = stringResource(R.string.novel_reader_replace_summary),
                onClick = onShowReplaceRules,
            )
            ReaderOptionDivider()
            ReaderOptionSwitchRow(
                label = stringResource(R.string.replace_rule_book_toggle),
                checked = replaceRulesEnabled,
                onCheckedChange = { onToggleReplaceRules() },
            )
        }
    }
    item { NovelResetRow(onReset) }
}

/** Reset asks first, in place: a destructive action should not fire on a single tap. */
@Composable
private fun NovelResetRow(onReset: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    ReaderOptionGroup {
        if (!confirming) {
            NovelToolActionRow(
                icon = R.drawable.ic_backup_restore,
                title = stringResource(R.string.novel_reset),
                supporting = null,
                onClick = { confirming = true },
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.novel_reader_reset_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = { confirming = false; onReset() }) {
                    Text(stringResource(R.string.reset))
                }
            }
        }
    }
}

@Composable
private fun NovelToolActionRow(
    icon: Int,
    title: String,
    supporting: String?,
    onClick: () -> Unit,
) {
    val colors = currentReaderPanelColors()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.selectedContainer),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = colors.content,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, color = colors.content)
                supporting?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.contentSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_forward),
                contentDescription = null,
                tint = colors.contentSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NovelSwitchRows(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
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
    ReaderOptionDivider()
    ReaderOptionSwitchRow(
        label = stringResource(R.string.reader_chapter_title_at_bottom),
        checked = settings.chapterTitleAtBottom,
        onCheckedChange = { update { copy(chapterTitleAtBottom = it) } },
    )
    ReaderOptionDivider()
    ReaderOptionSwitchRow(
        label = stringResource(R.string.novel_transparent_status_bar),
        checked = settings.isReadingStatusTransparent,
        onCheckedChange = { update { copy(isReadingStatusTransparent = it) } },
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
