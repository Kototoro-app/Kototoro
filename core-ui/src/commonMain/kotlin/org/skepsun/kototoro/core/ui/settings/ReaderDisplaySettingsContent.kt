package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString

/** The display preferences both hosts already persist; device-specific controls stay in the host. */
data class ReaderDisplaySettingsUiState(
    val chapterTitleAtBottom: Boolean,
    val pageNumbers: Boolean,
)

data class ReaderDisplaySettingsIcons(
    val chapterTitleAtBottom: Painter? = null,
    val pageNumbers: Painter? = null,
)

/** Shared display group, including labels, summaries, switch semantics and TV focus. Hosts own scrolling. */
@Composable
fun ReaderDisplaySettingsContent(
    state: ReaderDisplaySettingsUiState,
    onChapterTitleAtBottomChange: (Boolean) -> Unit,
    onPageNumbersChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icons: ReaderDisplaySettingsIcons = ReaderDisplaySettingsIcons(),
    pageNumbersSummary: String? = uiString("show_pages_numbers_summary"),
    platformControlsBefore: (@Composable ColumnScope.() -> Unit)? = null,
    platformControlsAfter: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().testTag("reader-display-settings-content")) {
        SettingsSectionHeader(uiString("reader_settings_group_display"))
        SettingsGroup {
            if (platformControlsBefore != null) {
                platformControlsBefore()
                SettingsRowDivider()
            }
            SettingsSwitchRow(uiString("reader_chapter_title_at_bottom"),
                uiString("reader_chapter_title_at_bottom_summary"), state.chapterTitleAtBottom,
                enabled, onChapterTitleAtBottomChange, testTag = "reader-setting-chapter-title-bottom",
                leadingIcon = icons.chapterTitleAtBottom,
                modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
            SettingsRowDivider()
            SettingsSwitchRow(uiString("show_pages_numbers"), pageNumbersSummary,
                state.pageNumbers, enabled, onPageNumbersChange, testTag = "reader-setting-page-numbers",
                leadingIcon = icons.pageNumbers,
                modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
            if (platformControlsAfter != null) {
                SettingsRowDivider()
                platformControlsAfter()
            }
        }
    }
}
