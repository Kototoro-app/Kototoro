package org.skepsun.kototoro.settings


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.viewLifecycleScope
import org.skepsun.kototoro.reader.translate.data.OnnxModelCategory
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog
import org.skepsun.kototoro.settings.compose.AIImageEnhancementSettingsScreen
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import javax.inject.Inject

@Composable
fun AIImageEnhancementSettingsRoute(
    settings: AppSettings,
    onnxModelManager: OnnxModelManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ncnnModels = buildList {
        addAll(
            OnnxOfficialModelCatalog.models
                .filter { it.category == OnnxModelCategory.IMAGE_SUPER_RESOLUTION }
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

    AIImageEnhancementSettingsScreen(
        settings = settings,
        ncnnModels = ncnnModels,
        modifier = modifier,
    )
}
