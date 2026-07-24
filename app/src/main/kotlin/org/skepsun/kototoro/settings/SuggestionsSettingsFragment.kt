package org.skepsun.kototoro.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.settings.compose.SuggestionsSettingsScreen
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import org.skepsun.kototoro.suggestions.ui.SuggestionsWorker
import javax.inject.Inject

@Composable
fun SuggestionsSettingsRoute(
    settings: AppSettings,
    suggestionsScheduler: SuggestionsWorker.Scheduler,
    excludeTagsFlow: MutableStateFlow<String>,
    preferredTagsFlow: MutableStateFlow<String>,
) {
    val coroutineScope = rememberCoroutineScope()
    val listener = remember(settings, suggestionsScheduler, coroutineScope) {
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS || key == AppSettings.KEY_SUGGESTIONS_PREFERRED_TAGS) {
                excludeTagsFlow.value = settings.prefs.getString(AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS, "") ?: ""
                preferredTagsFlow.value = settings.prefs.getString(AppSettings.KEY_SUGGESTIONS_PREFERRED_TAGS, "") ?: ""
            }
            if (settings.isSuggestionsEnabled && (
                    key == AppSettings.KEY_SUGGESTIONS ||
                        key == AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS ||
                        key == AppSettings.KEY_SUGGESTIONS_PREFERRED_TAGS ||
                        key == AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW
                    )
            ) {
                coroutineScope.launch(Dispatchers.Default) {
                    suggestionsScheduler.startNow()
                }
            }
        }
    }
    DisposableEffect(settings, listener) {
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settings.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    val excludeTags by excludeTagsFlow.collectAsState()
    val preferredTags by preferredTagsFlow.collectAsState()
    SuggestionsSettingsScreen(
        settings = settings,
        excludeTags = excludeTags,
        preferredTags = preferredTags,
        onExcludeTagsChanged = { value ->
            settings.prefs.edit().putString(AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS, value).apply()
        },
        onPreferredTagsChanged = { value ->
            settings.prefs.edit().putString(AppSettings.KEY_SUGGESTIONS_PREFERRED_TAGS, value).apply()
        },
    )
}
