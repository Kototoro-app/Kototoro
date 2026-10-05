package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.novel.compose.NovelReaderQuickLayer
import org.skepsun.kototoro.reader.novel.compose.NovelReaderThemeSwatch
import org.skepsun.kototoro.reader.novel.compose.NovelReaderValueOption
import org.skepsun.kototoro.reader.novel.compose.NovelReaderValueStepper
import org.skepsun.kototoro.reader.novel.compose.NovelTypographyOptionsPage
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsDetailTabs
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsPageList
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsTab
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
import org.skepsun.kototoro.reader.ui.compose.design.readerPanelColors
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderOptionsPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelSurfaceMode
import kotlin.math.roundToInt

/** The shared novel controls, backed by the existing Windows preferences and scroll reader. */
@Composable
internal fun DesktopNovelOptionsPanel(
    controller: DesktopController,
    settings: DesktopNovelSettings,
    fullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val theme = settings.theme
    val fontSize = NovelReaderValueOption(
        label = AndroidStrings["novel_font_size"],
        valueLabel = "${settings.fontSize}sp",
        value = settings.fontSize.toFloat(),
        range = DesktopNovelSettings.FONT_SIZES.let { it.first.toFloat()..it.last.toFloat() },
        step = 1f,
        onValueChange = { controller.novelSettings(settings.copy(fontSize = it.roundToInt())) },
        tag = "novel-font-size",
    )
    ReaderOptionsPanelHost(
        colors = readerPanelColors(
            base = Color(theme.background),
            content = Color(theme.text),
            contentSecondary = Color(theme.muted),
            accent = Color(theme.text),
            isDark = theme == DesktopNovelTheme.DARK,
        ),
        surfaceMode = ReaderPanelSurfaceMode.Opaque,
        onDismissRequest = onDismiss,
        openExpanded = true,
        modifier = Modifier.testTag("novel-settings-panel"),
        quickLayer = {
            NovelReaderQuickLayer(
                fontSize = fontSize.copy(tag = "novel-quick-font-size"),
                themes = DesktopNovelTheme.entries.map {
                    NovelReaderThemeSwatch(it.name, it.title, Color(it.background), Color(it.text))
                },
                selectedTheme = theme.name,
                onThemeSelected = { controller.novelSettings(settings.copy(theme = DesktopNovelTheme.valueOf(it))) },
            )
        },
        details = { dragModifier ->
            ReaderOptionsDetailTabs(
                tabs = listOf(
                    ReaderOptionsTab(AndroidStrings["novel_reader_tab_typography"]) {
                        NovelTypographyOptionsPage(
                            fontSize = fontSize,
                            lineSpacing = NovelReaderValueOption(
                                label = AndroidStrings["novel_line_spacing"],
                                valueLabel = "%.2f".format(settings.lineSpacing),
                                value = settings.lineSpacing,
                                range = DesktopNovelSettings.LINE_SPACINGS,
                                step = .05f,
                                onValueChange = {
                                    controller.novelSettings(settings.copy(lineSpacing = (it * 20).roundToInt() / 20f))
                                },
                                tag = "novel-line-spacing",
                            ),
                            fontOption = {
                                ReaderOptionSwitchRow(
                                    label = "衬线字体",
                                    checked = settings.serif,
                                    onCheckedChange = { controller.novelSettings(settings.copy(serif = it)) },
                                    modifier = Modifier.testTag("novel-serif"),
                                )
                            },
                            additionalOptions = {
                                ReaderOptionDivider()
                                NovelReaderValueStepper(NovelReaderValueOption(
                                    label = "版心宽度",
                                    valueLabel = "${settings.width}dp",
                                    value = settings.width.toFloat(),
                                    range = DesktopNovelSettings.WIDTHS.let { it.first.toFloat()..it.last.toFloat() },
                                    step = 20f,
                                    onValueChange = {
                                        controller.novelSettings(settings.copy(width = (it / 20).roundToInt() * 20))
                                    },
                                    tag = "novel-width",
                                ))
                            },
                        )
                    },
                    ReaderOptionsTab(AndroidStrings["novel_reader_tab_reading"]) {
                        ReaderOptionsPageList {
                            if (onToggleFullscreen != null) item {
                                ReaderOptionGroup {
                                    ReaderOptionSwitchRow(
                                        label = AndroidStrings["novel_fullscreen_mode"],
                                        checked = fullscreen,
                                        onCheckedChange = { if (it != fullscreen) onToggleFullscreen() },
                                        modifier = Modifier.testTag("novel-fullscreen"),
                                    )
                                }
                            }
                            item {
                                ReaderOptionGroup(title = "快捷键") {
                                    Text(
                                        text = "PageUp / PageDown / 空格 翻页滚动\nHome / End 章首末\n" +
                                            "H 收起或显示工具栏\nF11 全屏\nEsc 关闭面板或返回详情",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    },
                ),
                settingsDescription = "",
                onOpenSettings = null,
                dragModifier = dragModifier,
            )
        },
    )
}
