package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class OcrModelItemUiState(
    val id: String,
    val title: String,
    val summary: String,
    val enabled: Boolean,
)

data class OcrModelSectionUiState(
    val title: String,
    val items: List<OcrModelItemUiState>,
)

@Composable
fun OcrModelsSettingsScreen(
    sections: List<OcrModelSectionUiState>,
    onModelClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
        ) {
            sections.forEach { section ->
                SettingsPreferenceSection(title = section.title) {
                    section.items.forEachIndexed { index, item ->
                        SettingsActionPreference(
                            title = item.title,
                            summary = item.summary,
                            enabled = item.enabled,
                            showChevron = false,
                            onClick = { onModelClick(item.id) },
                        )
                        if (index < section.items.lastIndex) {
                            SettingsSectionDivider()
                        }
                    }
                }
            }
        }
    }
}
