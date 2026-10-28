package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsContent
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsRowIcons
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsUiState

/**
 * Desktop only projects the service switches and forwards the edits; the page itself comes from the shared
 * [ServicesSettingsContent].
 *
 * The rows follow Android's order: the recommendations entry, then 相关漫画 — which decides whether the details page
 * asks the source for related works — then 阅读统计 as a split row (the title opens the statistics screen while the
 * switch alone starts or stops recording), then 阅读时间估算, whose estimate the details page shows. Android's
 * browse-tracking pair, Discord RPC and their summaries have no Windows behaviour yet, so they are not drawn: a switch
 * that gates nothing would be worse than a missing row.
 */
@Composable
internal fun DesktopServicesPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.services
    val active = !state.busy
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        ServicesSettingsContent(
            state = ServicesSettingsUiState(
                suggestionsEnabled = state.suggestionSettings.enabled,
                relatedContent = settings.relatedContent,
                statsEnabled = settings.statsEnabled,
                readingTimeEstimation = settings.readingTimeEstimation,
            ),
            onSuggestionsClick = { controller.showRecommendations() },
            onRelatedContentChange = { controller.servicesSettings(settings.copy(relatedContent = it)) },
            onStatsClick = { controller.showReadingStats() },
            onStatsEnabledChange = { controller.servicesSettings(settings.copy(statsEnabled = it)) },
            onReadingTimeChange = { controller.servicesSettings(settings.copy(readingTimeEstimation = it)) },
            enabled = active,
            // Windows had Android's suggestion artwork on this row before the page was shared; keep it.
            icons = ServicesSettingsRowIcons(suggestions = painterResource("icons/ic_suggestion.svg")),
        )
    }
}
