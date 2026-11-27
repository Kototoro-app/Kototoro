package org.skepsun.kototoro.reader.translate.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.translate.domain.RemoteTranslationConsentRequest
import org.skepsun.kototoro.reader.translate.domain.RemoteTranslationPayloadKind
import org.skepsun.kototoro.reader.translate.domain.RemoteTranslationUsageMode

@Composable
fun RemoteTranslationConsentDialog(
    request: RemoteTranslationConsentRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val usageSummary = when (request.usageMode) {
        RemoteTranslationUsageMode.API_ONLY -> uiString("ai_api_usage_summary_api_only")
        RemoteTranslationUsageMode.LOCAL_FIRST -> uiString("ai_api_usage_summary_local_first")
        RemoteTranslationUsageMode.END_TO_END_API -> uiString("reader_translation_e2e_api_intro_summary")
    }
    val dataSummary = when (request.payloadKind) {
        RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY -> uiString("remote_translation_consent_data_ocr_text")
        RemoteTranslationPayloadKind.NOVEL_TEXT_AND_GLOSSARY -> uiString("remote_translation_consent_data_novel_text")
        RemoteTranslationPayloadKind.FULL_PAGE_IMAGE_AND_GLOSSARY -> uiString("remote_translation_consent_data_e2e_image")
    }
    val hostDisplay = request.normalizedHost.ifBlank { uiString("remote_translation_consent_host_invalid") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = uiString("remote_translation_consent_title"))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = usageSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ConsentDetailRow(
                            label = uiString("remote_translation_consent_provider_label"),
                            value = request.providerDisplayName,
                        )
                        ConsentDetailRow(
                            label = uiString("remote_translation_consent_host_label"),
                            value = hostDisplay,
                        )
                        ConsentDetailRow(
                            label = uiString("remote_translation_consent_data_label"),
                            value = dataSummary,
                        )
                    }
                }
                Text(
                    text = uiString("remote_translation_consent_remember_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = uiString("remote_translation_consent_confirm"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = uiString("cancel"))
            }
        },
    )
}

@Composable
private fun ConsentDetailRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
