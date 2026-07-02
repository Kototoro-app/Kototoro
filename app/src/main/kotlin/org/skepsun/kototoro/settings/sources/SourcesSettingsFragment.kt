package org.skepsun.kototoro.settings.sources

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.explore.data.SourcesSortOrder
import org.skepsun.kototoro.settings.SettingsActivity
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.SourcesSettingsScreen
import org.skepsun.kototoro.settings.compose.SourcesSettingsUiState
import javax.inject.Inject

@AndroidEntryPoint
class SourcesSettingsFragment : Fragment() {

    @Inject
    lateinit var settings: AppSettings

    private val viewModel by viewModels<SourcesSettingsViewModel>()

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
                SourcesSettingsRoute(
                    settings = settings,
                    viewModel = viewModel,
                    onSetupWizardClick = { router.showWelcomeSheet() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.remote_sources))
        viewModel.refreshLinksEnabled()
    }
}

@Composable
fun SourcesSettingsRoute(
    settings: AppSettings,
    viewModel: SourcesSettingsViewModel,
    onSetupWizardClick: () -> Unit,
) {
    val isLinksEnabled = viewModel.isLinksEnabled.collectAsStateWithLifecycle().value
    val installedJarNames by viewModel.installedJarNames.collectAsStateWithLifecycle()
    val sourcesSortOrder = settings.observeAsState(AppSettings.KEY_SOURCES_ORDER) { sourcesSortOrder }.value
    val isSourcesGridMode = settings.observeAsState(AppSettings.KEY_SOURCES_GRID) { isSourcesGridMode }.value
    val isSourcesGroupedByLanguage =
        settings.observeAsState(AppSettings.KEY_SOURCES_GROUPED_BY_LANGUAGE) { isSourcesGroupedByLanguage }.value
    val jarPriorityOrder = settings.observeAsState(AppSettings.KEY_JAR_PRIORITY_ORDER) { jarPriorityOrder }.value
    val resolvedJarPriorityOrder = remember(jarPriorityOrder, installedJarNames) {
        viewModel.resolveJarPriorityOrder(jarPriorityOrder)
    }
    val isShowBrokenSources =
        settings.observeAsState(AppSettings.KEY_SHOW_BROKEN_SOURCES) { isShowBrokenSources }.value
    val isNsfwContentDisabled =
        settings.observeAsState(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled }.value
    val isHistoryExcludeNsfw =
        settings.observeAsState(AppSettings.KEY_HISTORY_EXCLUDE_NSFW) { isHistoryExcludeNsfw }.value
    val isFavouritesExcludeNsfw =
        settings.observeAsState(AppSettings.KEY_FAVOURITES_EXCLUDE_NSFW) { isFavouritesExcludeNsfw }.value
    val isFeedExcludeNsfw =
        settings.observeAsState(AppSettings.KEY_FEED_EXCLUDE_NSFW) { isFeedExcludeNsfw }.value
    val isTrackerNsfwDisabled =
        settings.observeAsState(AppSettings.KEY_TRACKER_NO_NSFW) { isTrackerNsfwDisabled }.value
    val isSuggestionsExcludeNsfw =
        settings.observeAsState(AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW) { isSuggestionsExcludeNsfw }.value
    val incognitoModeForNsfw =
        settings.observeAsState(AppSettings.KEY_INCOGNITO_NSFW) { incognitoModeForNsfw }.value
    val isTagsWarningsEnabled =
        settings.observeAsState(AppSettings.KEY_TAGS_WARNINGS) { isTagsWarningsEnabled }.value
    val isMirrorSwitchingEnabled =
        settings.observeAsState(AppSettings.KEY_MIRROR_SWITCHING) { isMirrorSwitchingEnabled }.value
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val sortOrderOptions = SourcesSortOrder.entries.map {
        SettingsChoiceOption(it, stringResource(it.titleResId))
    }
    val incognitoOptions = listOf(
        SettingsChoiceOption(TriStateOption.ENABLED, stringResource(R.string.enable)),
        SettingsChoiceOption(TriStateOption.ASK, stringResource(R.string.ask_every_time)),
        SettingsChoiceOption(TriStateOption.DISABLED, stringResource(R.string.disable)),
    )
    val operationNotSupported = stringResource(R.string.operation_not_supported)

    val state = SourcesSettingsUiState(
        sourcesSortOrder = sourcesSortOrder,
        isSourcesGridMode = isSourcesGridMode,
        isSourcesGroupedByLanguage = isSourcesGroupedByLanguage,
        jarPriorityOrder = resolvedJarPriorityOrder,
        isShowBrokenSources = isShowBrokenSources,
        isNsfwContentDisabled = isNsfwContentDisabled,
        isHistoryExcludeNsfw = isHistoryExcludeNsfw,
        isFavouritesExcludeNsfw = isFavouritesExcludeNsfw,
        isFeedExcludeNsfw = isFeedExcludeNsfw,
        isTrackerNsfwDisabled = isTrackerNsfwDisabled,
        isSuggestionsExcludeNsfw = isSuggestionsExcludeNsfw,
        incognitoModeForNsfw = incognitoModeForNsfw,
        isTagsWarningsEnabled = isTagsWarningsEnabled,
        isMirrorSwitchingEnabled = isMirrorSwitchingEnabled,
        isHandleLinksEnabled = isLinksEnabled,
    )

    SourcesSettingsScreen(
        overviewTitle = stringResource(R.string.remote_sources),
        remoteSourcesTitle = stringResource(R.string.remote_sources),
        adultFilteringTitle = stringResource(R.string.adult_content_filtering),
        moreTitle = stringResource(R.string.more),
        state = state,
        snackbarHostState = snackbarHostState,
        sortOrderOptions = sortOrderOptions,
        incognitoOptions = incognitoOptions,
        onSourcesSortOrderChange = { settings.sourcesSortOrder = it },
        onSourcesGridModeChange = { settings.isSourcesGridMode = it },
        onSourcesGroupedByLanguageChange = { settings.isSourcesGroupedByLanguage = it },
        onSetupWizardClick = onSetupWizardClick,
        onJarPriorityOrderChange = viewModel::persistJarPriorityOrder,
        onShowBrokenSourcesChange = { settings.isShowBrokenSources = it },
        onNsfwContentDisabledChange = { settings.isNsfwContentDisabled = it },
        onHistoryExcludeNsfwChange = { settings.isHistoryExcludeNsfw = it },
        onFavouritesExcludeNsfwChange = { settings.isFavouritesExcludeNsfw = it },
        onFeedExcludeNsfwChange = { settings.isFeedExcludeNsfw = it },
        onTrackerNsfwDisabledChange = { settings.isTrackerNsfwDisabled = it },
        onSuggestionsExcludeNsfwChange = { settings.isSuggestionsExcludeNsfw = it },
        onIncognitoModeForNsfwChange = { settings.incognitoModeForNsfw = it },
        onTagsWarningsEnabledChange = { settings.isTagsWarningsEnabled = it },
        onMirrorSwitchingChange = { settings.isMirrorSwitchingEnabled = it },
        onHandleLinksEnabledChange = { enabled ->
            runCatching {
                viewModel.setLinksEnabled(enabled)
            }.onFailure {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(message = operationNotSupported)
                }
            }
        },
    )
}
