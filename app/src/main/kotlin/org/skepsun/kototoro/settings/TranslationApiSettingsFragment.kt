package org.skepsun.kototoro.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.settings.compose.TranslationApiSettingsScreen
import org.skepsun.kototoro.settings.support.TranslationApiSettingsSupport
import javax.inject.Inject

@AndroidEntryPoint
class TranslationApiSettingsFragment : Fragment() {

    @Inject
    @ContentHttpClient
    lateinit var okHttpClient: OkHttpClient

    private val settings: AppSettings by lazy { AppSettings(requireContext()) }
    private var fetchModelsJob: Job? = null

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
                TranslationApiSettingsRoute(
                    settings = settings,
                    onFetchModelsClick = ::fetchAndPickApiModel,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.ai_api_settings))
    }

    private fun fetchAndPickApiModel() {
        fetchModelsJob?.cancel()
        fetchModelsJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val endpoint = settings.readerTranslationApiEndpoint.trim()
                if (endpoint.isBlank()) {
                    Toast.makeText(requireContext(), R.string.reader_translation_api_endpoint_missing, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val modelsUrl = TranslationApiSettingsSupport.buildModelsUrl(endpoint)
                val key = settings.readerTranslationApiKey.trim()
                val models = withContext(Dispatchers.IO) {
                    val requestBuilder = Request.Builder().get().url(modelsUrl)
                    if (key.isNotBlank()) {
                        requestBuilder.header("Authorization", "Bearer $key")
                        requestBuilder.header("X-API-Key", key)
                    }
                    okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) return@withContext emptyList<String>()
                        val body = response.body?.string().orEmpty()
                        TranslationApiSettingsSupport.parseModelIds(body)
                    }
                }
                if (models.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.reader_translation_api_models_fetch_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showModelPickerDialog(models)
            } catch (e: Throwable) {
                e.printStackTrace()
                Toast.makeText(requireContext(), R.string.reader_translation_api_models_fetch_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showModelPickerDialog(models: List<String>) {
        val current = settings.readerTranslationApiModel.trim()
        val selected = models.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reader_translation_api_models_pick_title)
            .setSingleChoiceItems(models.toTypedArray(), selected) { dialog, which ->
                val chosen = models.getOrNull(which).orEmpty()
                if (chosen.isNotBlank()) {
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit {
                        putString(AppSettings.KEY_READER_TRANSLATION_API_MODEL, chosen)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

@Composable
fun TranslationApiSettingsRoute(
    settings: AppSettings,
    onFetchModelsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == AppSettings.KEY_READER_TRANSLATION_API_PROVIDER_PRESET) {
                TranslationApiSettingsSupport.applyApiProviderPreset(
                    sharedPreferences = sharedPreferences ?: settings.prefs,
                    presetInput = settings.readerTranslationApiProviderPreset,
                    forceOverride = true,
                )
            }
        }
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settings.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    TranslationApiSettingsScreen(
        settings = settings,
        onFetchModelsClick = onFetchModelsClick,
        modifier = modifier,
    )
}
