package org.skepsun.kototoro.settings


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.settings.compose.TranslationSettingsScreen
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.AdvancedOcrModelPackWorker
import org.skepsun.kototoro.core.prefs.ReaderOcrMode
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import javax.inject.Inject

@Composable
fun TranslationSettingsRoute(
    settings: AppSettings,
    onnxModelManager: OnnxModelManager,
    onOpenOcrModels: () -> Unit,
    onOpenApiSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

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
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.reader_translation_ocr_pack_title)
			.setMessage(R.string.reader_translation_ocr_pack_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.reader_translation_ocr_pack_download) { _, _ ->
				AdvancedOcrModelPackWorker.enqueue(context)
				Toast.makeText(context, R.string.reader_translation_ocr_pack_started, Toast.LENGTH_LONG).show()
			}
			.show()
	}

    TranslationSettingsScreen(
        settings = settings,
		onOcrModeChange = ::selectOcrMode,
        onOpenOcrModels = onOpenOcrModels,
        onOpenApiSettings = onOpenApiSettings,
    )
}
