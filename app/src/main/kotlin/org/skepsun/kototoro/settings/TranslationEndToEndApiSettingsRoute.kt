package org.skepsun.kototoro.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.settings.compose.TranslationE2ESettingsScreen
import org.skepsun.kototoro.settings.support.TranslationApiSettingsSupport
import javax.inject.Inject

@Composable
fun TranslationE2EApiSettingsRoute(
    settings: AppSettings,
    onFetchModelsClick: () -> Unit,
) {
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == AppSettings.KEY_READER_E2E_API_PROVIDER_PRESET) {
                TranslationApiSettingsSupport.applyApiProviderPreset(
                    sharedPreferences = sharedPreferences
                        ?: settings.prefs,
                    presetInput = settings.readerE2eApiProviderPreset,
                    forceOverride = true,
                    endpointKey = AppSettings.KEY_READER_E2E_API_ENDPOINT,
                    modelKey = AppSettings.KEY_READER_E2E_API_MODEL,
                )
            }
        }
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settings.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    TranslationE2ESettingsScreen(
        settings = settings,
        onFetchModels = onFetchModelsClick,
    )
}
