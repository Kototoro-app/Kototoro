package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

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
    detectorOptions: List<SettingsChoiceOption<String>>,
    recognizerOptions: List<SettingsChoiceOption<String>>,
    selectedDetector: String,
    selectedRecognizer: String,
    onDetectorChange: (String) -> Unit,
    onRecognizerChange: (String) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsPreferenceGroup(title = stringResource(R.string.reader_translation_ocr_advanced_settings)) {
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_ocr_det_model_selection),
                        iconRes = R.drawable.ic_eye_check,
                        options = detectorOptions,
                        value = selectedDetector,
                        onValueChange = onDetectorChange,
                    )
                }
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_ocr_recognizer_model_selection),
                        iconRes = R.drawable.ic_script,
                        options = recognizerOptions,
                        value = selectedRecognizer,
                        onValueChange = onRecognizerChange,
                    )
                }
            }
            sections.forEach { section ->
                SettingsPreferenceGroup(title = section.title) {
                    section.items.forEach { model ->
                        item {
                            SettingsActionPreference(
                                title = model.title,
                                summary = model.summary,
                                iconRes = R.drawable.ic_script,
                                enabled = model.enabled,
                                showChevron = false,
                                onClick = { onModelClick(model.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
