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

/** The shared webtoon group; the host decides which reading modes expose it. */
@Composable
fun ReaderWebtoonSettingsContent(
    gaps: Boolean,
    onGapsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gapsIcon: Painter? = null,
    platformControls: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().testTag("reader-webtoon-settings-content")) {
        SettingsSectionHeader(uiString("reader_settings_group_webtoon"))
        SettingsGroup {
            if (platformControls != null) {
                platformControls()
                SettingsRowDivider()
            }
            SettingsSwitchRow(uiString("webtoon_gaps"), uiString("webtoon_gaps_summary"), gaps, enabled,
                onGapsChange, testTag = "reader-setting-webtoon-gaps", leadingIcon = gapsIcon,
                modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
        }
    }
}

/** Same preference meaning in Android's performance section and desktop's reader settings. */
@Composable
fun ReaderPreloadSettingsRow(
    reduced: Boolean,
    onReducedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
) {
    SettingsSwitchRow(uiString("reader_reduce_page_preloading"), uiString("reader_reduce_page_preloading_summary"),
        reduced, enabled, onReducedChange, testTag = "reader-setting-reduce-preloading", leadingIcon = icon,
        modifier = modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
}
