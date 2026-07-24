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
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import org.skepsun.kototoro.reader.translate.domain.TranslationApiProviderCatalog
import javax.inject.Inject

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
