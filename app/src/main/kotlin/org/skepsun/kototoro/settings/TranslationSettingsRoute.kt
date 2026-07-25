package org.skepsun.kototoro.settings


import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.settings.compose.TranslationSettingsScreen
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.AdvancedOcrModelPackWorker
import org.skepsun.kototoro.core.prefs.ReaderOcrMode
import android.widget.Toast

@Composable
fun TranslationSettingsRoute(
    settings: AppSettings,
    onnxModelManager: OnnxModelManager,
    onOpenOcrModels: () -> Unit,
    onOpenApiSettings: () -> Unit,
) {
	val context = androidx.compose.ui.platform.LocalContext.current
	var showAdvancedOcrDownloadDialog by remember { mutableStateOf(false) }

	fun selectOcrMode(mode: ReaderOcrMode) {
		if (mode == ReaderOcrMode.BASIC) {
			AdvancedOcrModelPackWorker.cancel(context)
			settings.readerTranslationOcrMode = ReaderOcrMode.BASIC
			return
		}
		if (AdvancedOcrModelPackWorker.areAllModelsReady(onnxModelManager)) {
			settings.readerTranslationOcrMode = ReaderOcrMode.ADVANCED
			Toast.makeText(context, R.string.reader_translation_ocr_pack_ready, Toast.LENGTH_SHORT).show()
			return
		}
		showAdvancedOcrDownloadDialog = true
	}

    TranslationSettingsScreen(
        settings = settings,
		onOcrModeChange = ::selectOcrMode,
        onOpenOcrModels = onOpenOcrModels,
        onOpenApiSettings = onOpenApiSettings,
    )

	if (showAdvancedOcrDownloadDialog) {
		AlertDialog(
			onDismissRequest = { showAdvancedOcrDownloadDialog = false },
			title = { Text(stringResource(R.string.reader_translation_ocr_pack_title)) },
			text = { Text(stringResource(R.string.reader_translation_ocr_pack_message)) },
			confirmButton = {
				TextButton(
					onClick = {
						showAdvancedOcrDownloadDialog = false
						AdvancedOcrModelPackWorker.enqueue(context)
						Toast.makeText(context, R.string.reader_translation_ocr_pack_started, Toast.LENGTH_LONG).show()
					},
				) {
					Text(stringResource(R.string.reader_translation_ocr_pack_download))
				}
			},
			dismissButton = {
				TextButton(onClick = { showAdvancedOcrDownloadDialog = false }) {
					Text(stringResource(android.R.string.cancel))
				}
			},
		)
	}
}
