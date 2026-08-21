package org.skepsun.kototoro.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.settings.compose.SuggestionSourceOption
import org.skepsun.kototoro.settings.compose.SuggestionsSettingsScreen
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import org.skepsun.kototoro.suggestions.ui.SuggestionsWorker
import javax.inject.Inject

@Composable
fun SuggestionsSettingsRoute(
    settings: AppSettings,
    suggestionsScheduler: SuggestionsWorker.Scheduler,
    contentSourcesRepository: ContentSourcesRepository,
    excludeTagsFlow: MutableStateFlow<String>,
    preferredTagsFlow: MutableStateFlow<String>,
) {
    val context = LocalContext.current
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
                        key == AppSettings.KEY_SUGGESTIONS_PREFERRED_SOURCES ||
                        key == AppSettings.KEY_SUGGESTIONS_EXCLUDED_SOURCES ||
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
    val excludeTags by excludeTagsFlow.collectAsStateWithLifecycle()
    val preferredTags by preferredTagsFlow.collectAsStateWithLifecycle()
    val sourceTypeIdentifier = remember { SourceTypeIdentifier() }
    val sourceOptions by produceState<List<SuggestionSourceOption>>(emptyList(), contentSourcesRepository, context) {
        value = kotlinx.coroutines.withContext(Dispatchers.IO) {
            contentSourcesRepository.getAllAvailableSourcesUnfiltered()
                .distinctBy { it.name }
                .map {
                    SuggestionSourceOption(
                        id = it.name,
                        title = it.getTitle(context),
                        contentType = it.getContentType(),
                        sourceType = sourceTypeIdentifier.getSourceType(it.name),
                    )
                }
                .sortedBy { it.title.lowercase() }
        }
    }
    val preferredSources by settings.observeAsState(AppSettings.KEY_SUGGESTIONS_PREFERRED_SOURCES) {
        suggestionsPreferredSources
    }
    val excludedSources by settings.observeAsState(AppSettings.KEY_SUGGESTIONS_EXCLUDED_SOURCES) {
        suggestionsExcludedSources
    }
    SuggestionsSettingsScreen(
        settings = settings,
        excludeTags = excludeTags,
        preferredTags = preferredTags,
        sourceOptions = sourceOptions,
        preferredSources = preferredSources,
        excludedSources = excludedSources,
        onExcludeTagsChanged = { value ->
            settings.prefs.edit().putString(AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS, value).apply()
        },
        onPreferredTagsChanged = { value ->
            settings.prefs.edit().putString(AppSettings.KEY_SUGGESTIONS_PREFERRED_TAGS, value).apply()
        },
        onPreferredSourcesChanged = { sourceIds ->
            settings.prefs.edit().putStringSet(AppSettings.KEY_SUGGESTIONS_PREFERRED_SOURCES, sourceIds).apply()
        },
        onExcludedSourcesChanged = { sourceIds ->
            settings.prefs.edit().putStringSet(AppSettings.KEY_SUGGESTIONS_EXCLUDED_SOURCES, sourceIds).apply()
        },
    )
}
