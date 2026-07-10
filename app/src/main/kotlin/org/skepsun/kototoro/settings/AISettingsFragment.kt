package org.skepsun.kototoro.settings


import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.settings.compose.AISettingsScreen
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

@AndroidEntryPoint
class AISettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
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
                AISettingsRoute(
                    onOpenOcrModels = {
                        (activity as? SettingsActivity)?.openDestination(
                            SettingsDestination.OcrModelsSettings,
                            null,
                            false,
                        )
                    },
                    onOpenApiSettings = {
                        (activity as? SettingsActivity)?.openDestination(
                            SettingsDestination.TranslationApiSettings,
                            null,
                            false,
                        )
                    },
                    onOpenTranslationSettings = {
                        (activity as? SettingsActivity)?.openDestination(
                            SettingsDestination.TranslationSettings,
                            null,
                            false,
                        )
                    },
                    onOpenImageEnhancementSettings = {
                        (activity as? SettingsActivity)?.openDestination(
                            SettingsDestination.AiImageEnhancementSettings,
                            null,
                            false,
                        )
                    },
                    onOpenTtsSettings = {
                        (activity as? SettingsActivity)?.openDestination(
                            SettingsDestination.TtsSettings,
                            null,
                            false,
                        )
                    },
                    onOpenVideoEnhancementSettings = {
                        (activity as? SettingsActivity)?.openDestination(
                            SettingsDestination.AiVideoEnhancementSettings,
                            null,
                            false,
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.ai_settings))
    }
}

@Composable
fun AISettingsRoute(
    onOpenOcrModels: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenTranslationSettings: () -> Unit,
    onOpenImageEnhancementSettings: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onOpenVideoEnhancementSettings: () -> Unit,
) {
    AISettingsScreen(
        onOpenOcrModels = onOpenOcrModels,
        onOpenApiSettings = onOpenApiSettings,
        onOpenTranslationSettings = onOpenTranslationSettings,
        onOpenImageEnhancementSettings = onOpenImageEnhancementSettings,
        onOpenTtsSettings = onOpenTtsSettings,
        onOpenVideoEnhancementSettings = onOpenVideoEnhancementSettings,
    )
}
