package org.skepsun.kototoro.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.reader.translate.data.OnnxModelCategory
import org.skepsun.kototoro.reader.translate.data.OnnxModelDownloadWorker
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog
import org.skepsun.kototoro.settings.compose.OcrModelItemUiState
import org.skepsun.kototoro.settings.compose.OcrModelSectionUiState
import org.skepsun.kototoro.settings.compose.OcrModelsSettingsScreen
import javax.inject.Inject

@Keep
@AndroidEntryPoint
class OcrModelsFragment : Fragment() {

    @Inject
    lateinit var onnxModelManager: OnnxModelManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as ComposeView).setContent {
            KototoroTheme {
                OcrModelsRoute(
                    onnxModelManager = onnxModelManager,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.reader_translation_ocr_models_title))
    }
}

@Composable
fun OcrModelsRoute(
    onnxModelManager: OnnxModelManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val transientStateByModelId = remember { mutableStateMapOf<String, ModelTransientState>() }
    var refreshKey by remember { mutableStateOf(0) }

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
        ): OcrModelSectionUiState {
            return OcrModelSectionUiState(
                title = title,
                items = OnnxOfficialModelCatalog.models
                    .filter { it.category == category }
                    .map(::buildItemState),
            )
        }

        listOf(
            buildSection(
                title = context.getString(R.string.reader_translation_onnx_models_title),
                category = OnnxModelCategory.CLASSIC_TRANSLATION,
            ),
            buildSection(
                title = context.getString(R.string.reader_translation_ocr_detector_models_title),
                category = OnnxModelCategory.OCR_DETECTOR,
            ),
            buildSection(
                title = context.getString(R.string.reader_translation_ocr_recognizer_models_title),
                category = OnnxModelCategory.OCR_RECOGNIZER,
            ),
            buildSection(
                title = context.getString(R.string.reader_translation_onnx_bubble_detector_models_title),
                category = OnnxModelCategory.BUBBLE_DETECTION,
            ),
            buildSection(
                title = context.getString(R.string.reader_translation_onnx_super_resolution_models_title),
                category = OnnxModelCategory.IMAGE_SUPER_RESOLUTION,
            ),
        )
    }

    fun handleModelClick(modelId: String) {
        val model = OnnxOfficialModelCatalog.findById(modelId) ?: return
        if (onnxModelManager.isModelDownloaded(model.id)) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.delete)
                .setMessage(context.getString(R.string.reader_translation_model_delete_confirm, model.title))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    OnnxModelDownloadWorker.cancel(context, model.id)
                    if (onnxModelManager.deleteModel(model.id)) {
                        updateTransientState(model.id, null)
                        Toast.makeText(
                            context,
                            context.getString(R.string.reader_translation_model_deleted, model.title),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            OnnxModelDownloadWorker.enqueue(context, model.id)
            Toast.makeText(context, R.string.reader_translation_model_download_started_background, Toast.LENGTH_LONG).show()
        }
    }

    OcrModelsSettingsScreen(
        sections = sections,
        onModelClick = ::handleModelClick,
        modifier = modifier,
    )
}

private data class ModelTransientState(
    val isBusy: Boolean = false,
    val progressText: String? = null,
    val errorText: String? = null,
)
