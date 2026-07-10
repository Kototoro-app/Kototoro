package org.skepsun.kototoro.settings


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.settings.compose.TranslationSettingsScreen
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.OnnxModelCategory
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import javax.inject.Inject

@AndroidEntryPoint
class TranslationSettingsFragment : Fragment() {

    @Inject
    lateinit var onnxModelManager: OnnxModelManager

    private val settings: AppSettings by lazy { AppSettings(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as ComposeView).setContent {
            KototoroTheme {
                TranslationSettingsRoute(
                    settings = settings,
                    onnxModelManager = onnxModelManager,
                    onOpenOcrModels = {
                        (activity as? SettingsActivity)?.openDestination(SettingsDestination.OcrModelsSettings, null, false)
                    },
                    onOpenApiSettings = {
                        (activity as? SettingsActivity)?.openDestination(SettingsDestination.TranslationApiSettings, null, false)
                    },
                    onOpenE2eApiSettings = {
                        (activity as? SettingsActivity)?.openDestination(SettingsDestination.TranslationE2EApiSettings, null, false)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.translation_settings))
    }
}

@Composable
fun TranslationSettingsRoute(
    settings: AppSettings,
    onnxModelManager: OnnxModelManager,
    onOpenOcrModels: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenE2eApiSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val onnxModels = buildList {
        add(SettingsChoiceOption("", context.getString(R.string.reader_translation_local_model_mlkit)))
        addAll(
            OnnxOfficialModelCatalog.models
                .filter { it.category == OnnxModelCategory.CLASSIC_TRANSLATION }
                .map {
                    val suffix = if (onnxModelManager.isModelDownloaded(it.id)) {
                        ""
                    } else {
                        context.getString(R.string.reader_translation_ocr_model_selection_not_downloaded_suffix)
                    }
                    SettingsChoiceOption(it.id, it.title + suffix)
                },
        )
    }
    val paddleDetModels = buildList {
        add(SettingsChoiceOption("MLKIT", context.getString(R.string.reader_translation_ocr_det_mlkit)))
        addAll(
            OnnxOfficialModelCatalog.models
                .filter {
                    it.category == OnnxModelCategory.OCR_DETECTOR ||
                        it.category == OnnxModelCategory.BUBBLE_DETECTION
                }
                .map {
                    val suffix = if (onnxModelManager.isModelDownloaded(it.id)) {
                        ""
                    } else {
                        context.getString(R.string.reader_translation_ocr_model_selection_not_downloaded_suffix)
                    }
                    SettingsChoiceOption(it.id, it.title + suffix)
                },
        )
    }
    val paddleOfficialModels = buildList {
        add(SettingsChoiceOption("AUTO", context.getString(R.string.reader_translation_ocr_rec_model_auto)))
        add(SettingsChoiceOption("MLKIT", context.getString(R.string.reader_translation_ocr_det_mlkit)))
        add(SettingsChoiceOption("mangaocr_2025_onnx", "MangaOCR 2025"))
        addAll(
            OnnxOfficialModelCatalog.models
                .filter { it.category == OnnxModelCategory.OCR_RECOGNIZER && !it.id.startsWith("mangaocr") }
                .map {
                    val suffix = if (onnxModelManager.isModelDownloaded(it.id)) {
                        ""
                    } else {
                        context.getString(R.string.reader_translation_ocr_model_selection_not_downloaded_suffix)
                    }
                    SettingsChoiceOption(it.id, it.title + suffix)
                },
        )
    }
    val onnxBubbleModels = buildList {
        add(SettingsChoiceOption("AUTO", context.getString(R.string.reader_translation_ocr_model_onnx_automatic)))
        addAll(
            OnnxOfficialModelCatalog.models
                .filter { it.category == OnnxModelCategory.BUBBLE_DETECTION }
                .map {
                    val suffix = if (onnxModelManager.isModelDownloaded(it.id)) {
                        ""
                    } else {
                        context.getString(R.string.reader_translation_ocr_model_selection_not_downloaded_suffix)
                    }
                    SettingsChoiceOption(it.id, it.title + suffix)
                },
        )
    }

    TranslationSettingsScreen(
        settings = settings,
        onnxModels = onnxModels,
        paddleDetModels = paddleDetModels,
        paddleOfficialModels = paddleOfficialModels,
        onnxBubbleModels = onnxBubbleModels,
        onOpenOcrModels = onOpenOcrModels,
        onOpenApiSettings = onOpenApiSettings,
        onOpenE2eApiSettings = onOpenE2eApiSettings,
    )
}
