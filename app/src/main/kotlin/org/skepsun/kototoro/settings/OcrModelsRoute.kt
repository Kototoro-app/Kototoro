package org.skepsun.kototoro.settings

import android.widget.Toast
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.R
import org.skepsun.kototoro.reader.translate.data.OnnxModelCategory
import org.skepsun.kototoro.reader.translate.data.OnnxModelDownloadWorker
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog
import org.skepsun.kototoro.reader.translate.domain.DefaultDbNetTextDetector
import org.skepsun.kototoro.reader.translate.domain.ComicTextDetectorOnnx
import org.skepsun.kototoro.settings.compose.OcrModelItemUiState
import org.skepsun.kototoro.settings.compose.OcrModelSectionUiState
import org.skepsun.kototoro.settings.compose.OcrModelsSettingsScreen
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.prefs.ReaderOcrMode
import org.skepsun.kototoro.reader.translate.data.AdvancedOcrModelPackWorker
import androidx.core.content.edit

internal val READER_TRANSLATION_VISIBLE_RECOGNIZER_MODEL_IDS = linkedSetOf(
    "mangaocr_2025_onnx",
    "manga_48px_ctc_onnx",
    "ppocrv6_medium_rec_onnx",
    "latin_ppocrv5_mobile_rec_onnx",
    "korean_ppocrv5_mobile_rec_onnx",
    "thai_ppocrv5_mobile_rec_onnx",
)

@Keep
@Composable
fun OcrModelsRoute(
    onnxModelManager: OnnxModelManager,
	settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val transientStateByModelId = remember { mutableStateMapOf<String, ModelTransientState>() }
    var refreshKey by remember { mutableStateOf(0) }
    var modelPendingDeletion by remember { mutableStateOf<OnnxOfficialModel?>(null) }

    fun updateTransientState(modelId: String, state: ModelTransientState?) {
        if (state == null) {
            transientStateByModelId.remove(modelId)
        } else {
            transientStateByModelId[modelId] = state
        }
        refreshKey += 1
    }

    val sections = remember(refreshKey, context, onnxModelManager) {
        fun buildItemState(model: OnnxOfficialModel): OcrModelItemUiState {
            val transient = transientStateByModelId[model.id]
            val downloaded = onnxModelManager.isModelDownloaded(model.id)
            val statusText = transient?.progressText
                ?: transient?.errorText
                ?: context.getString(
                    if (downloaded) R.string.reader_translation_ocr_model_status_downloaded
                    else R.string.reader_translation_ocr_model_status_not_downloaded,
                ) + " (${model.version})"

            return OcrModelItemUiState(
                id = model.id,
                title = model.title,
                summary = "${model.description}\n$statusText",
                enabled = transient?.isBusy != true,
            )
        }

        fun buildSection(
            title: String,
            category: OnnxModelCategory,
            visibleModelIds: Set<String>? = null,
        ): OcrModelSectionUiState {
            return OcrModelSectionUiState(
                title = title,
                items = OnnxOfficialModelCatalog.models
                    .filter { model ->
                        model.category == category &&
                            (visibleModelIds == null || model.id in visibleModelIds)
                    }
                    .map(::buildItemState),
            )
        }

        listOf(
            buildSection(
                title = context.getString(R.string.reader_translation_ocr_detector_models_title),
                category = OnnxModelCategory.OCR_DETECTOR,
				visibleModelIds = setOf(
					ComicTextDetectorOnnx.MODEL_ID,
					DefaultDbNetTextDetector.MODEL_ID,
				),
            ),
            buildSection(
                title = context.getString(R.string.reader_translation_ocr_recognizer_models_title),
                category = OnnxModelCategory.OCR_RECOGNIZER,
                visibleModelIds = READER_TRANSLATION_VISIBLE_RECOGNIZER_MODEL_IDS,
            ),
            buildSection(
                title = context.getString(R.string.reader_translation_onnx_super_resolution_models_title),
                category = OnnxModelCategory.IMAGE_SUPER_RESOLUTION,
            ),
        )
    }
	val detectorOptions = listOf(
		ComicTextDetectorOnnx.MODEL_ID,
		DefaultDbNetTextDetector.MODEL_ID,
	).mapNotNull(OnnxOfficialModelCatalog::findById).map { model ->
		SettingsChoiceOption(model.id, model.title)
	}
	val recognizerOptions = buildList {
		add(SettingsChoiceOption("AUTO", context.getString(R.string.reader_translation_ocr_rec_model_auto)))
		READER_TRANSLATION_VISIBLE_RECOGNIZER_MODEL_IDS
			.mapNotNull(OnnxOfficialModelCatalog::findById)
			.forEach { model -> add(SettingsChoiceOption(model.id, model.title)) }
	}
	val selectedDetector = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_PADDLE_DET_MODEL_ID) {
		settings.readerTranslationAdvancedDetModelId
	}.value
	val selectedRecognizer = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_PADDLE_OFFICIAL_MODEL_ID) {
		settings.readerTranslationAdvancedRecModelId
	}.value

    fun handleModelClick(modelId: String) {
        val model = OnnxOfficialModelCatalog.findById(modelId) ?: return
        if (onnxModelManager.isModelDownloaded(model.id)) {
            modelPendingDeletion = model
        } else {
            OnnxModelDownloadWorker.enqueue(context, model.id)
            Toast.makeText(context, R.string.reader_translation_model_download_started_background, Toast.LENGTH_LONG).show()
        }
    }

    OcrModelsSettingsScreen(
        sections = sections,
		detectorOptions = detectorOptions,
		recognizerOptions = recognizerOptions,
		selectedDetector = selectedDetector,
		selectedRecognizer = selectedRecognizer,
		onDetectorChange = { modelId ->
			settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_PADDLE_DET_MODEL_ID, modelId) }
		},
		onRecognizerChange = { modelId ->
			settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_PADDLE_OFFICIAL_MODEL_ID, modelId) }
		},
        onModelClick = ::handleModelClick,
        modifier = modifier,
    )

    modelPendingDeletion?.let { model ->
        SettingsAlertDialog(
            title = stringResource(R.string.delete),
            onDismissRequest = { modelPendingDeletion = null },
            text = {
                Text(stringResource(R.string.reader_translation_model_delete_confirm, model.title))
            },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        modelPendingDeletion = null
                        OnnxModelDownloadWorker.cancel(context, model.id)
                        if (onnxModelManager.deleteModel(model.id)) {
                            if (model.id in AdvancedOcrModelPackWorker.REQUIRED_MODEL_IDS) {
                                settings.readerTranslationOcrMode = ReaderOcrMode.BASIC
                            }
                            updateTransientState(model.id, null)
                            Toast.makeText(
                                context,
                                context.getString(R.string.reader_translation_model_deleted, model.title),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { modelPendingDeletion = null },
                )
            },
        )
    }
}

private data class ModelTransientState(
    val isBusy: Boolean = false,
    val progressText: String? = null,
    val errorText: String? = null,
)
