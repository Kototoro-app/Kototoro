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
import org.skepsun.kototoro.reader.translate.data.AdvancedOcrModelPackWorker
import org.skepsun.kototoro.core.prefs.ReaderOcrMode
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
