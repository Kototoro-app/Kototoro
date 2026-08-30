package org.skepsun.kototoro.main.ui.compose

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.widgets.BottomNavState
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.domain.LocalEntitySuggestion
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.ui.compose.SearchNavigationRequest
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem
import org.skepsun.kototoro.search.ui.suggestion.model.TrackingEntity
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot
import org.skepsun.kototoro.space.ui.SpaceAction
import org.skepsun.kototoro.space.ui.SpaceNavigationSessionUiState
import org.skepsun.kototoro.space.ui.SpaceResumeUiState
import org.skepsun.kototoro.space.ui.SpaceTransitionState
import org.skepsun.kototoro.space.ui.SpaceUiState

/**
 * Aggregated input state for the app shell composable [KototoroApp].
 * Collapses the previous ~70-parameter signature into a single holder so the
 * shell's public API stays manageable as features are added.
 */
data class MainAppState(
    val appSettings: AppSettings,
    val navStateFlow: StateFlow<BottomNavState>,
    val pageSaveHelper: org.skepsun.kototoro.reader.ui.PageSaveHelper,
    val lastReadContent: Content? = null,
    val query: String = "",
    val suggestions: List<SearchSuggestionItem> = emptyList(),
    val onQueryChanged: (String) -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val initialSearchKind: SearchKind = SearchKind.SIMPLE,
    val initialSearchSourceTypes: Set<SourceType> = emptySet(),
    val initialSearchContentKinds: Set<SearchContentKind> = emptySet(),
    val onSearchWithOptions: (
        query: String,
        kind: SearchKind,
        sourceTypes: Set<SourceType>,
        contentKinds: Set<SearchContentKind>,
        advancedQuery: AdvancedSearchParams?,
        pinnedOnly: Boolean,
        hideEmpty: Boolean,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    val onSearchOverlaySourceTypesChange: (Set<SourceType>) -> Unit = {},
    val onSearchOverlayContentKindsChange: (Set<SearchContentKind>) -> Unit = {},
    val onSearchOverlayDismiss: () -> Unit = {},
    val onContentSuggestionClick: (Content) -> Unit = {},
    val onLocalEntitySuggestionClick: (LocalEntitySuggestion) -> Unit = {},
    val onTrackingEntitySuggestionClick: (TrackingEntity) -> Unit = {},
    val onTagSuggestionClick: (ContentTag) -> Unit = {},
    val onSourceSuggestionClick: (ContentSource) -> Unit = {},
    val onAuthorSuggestionClick: (String) -> Unit = {},
    val onDeleteQuery: (String) -> Unit = {},
    val onVoiceInput: () -> Unit = {},
    val onOpenListOptions: () -> Unit = {},
    /** Opens the home three-section paged display options sheet (home ⋮ menu). */
    val onHomeDisplayOptionsClick: () -> Unit = {},
    val onSettingsClick: () -> Unit = {},
    val onHelpClick: () -> Unit = {},
    val onSourceSettingsClick: () -> Unit = {},
    val onManageSourcesClick: () -> Unit = onSourceSettingsClick,
    val onGlobalTagBlacklistClick: () -> Unit = {},
    val onTrackingAccountsClick: () -> Unit = {},
    val isAppUpdateAvailable: Boolean = false,
    val onAppUpdateClick: () -> Unit = {},
    val isIncognitoModeEnabled: Boolean = false,
    val onIncognitoToggle: () -> Unit = {},
    val isLanguagePresetFilterVisible: Boolean = false,
    val languagePresetEntries: List<SourcePreset> = emptyList(),
    val onLanguagePresetSelected: (Long) -> Unit = {},
    val onManageLanguagePresets: () -> Unit = {},
    val selectedContentType: ContentType? = null,
    val enabledContentTypes: Set<ContentType> = setOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO),
    val isContentTypeFilterVisible: Boolean = true,
    val onContentTypeSelected: (ContentType?) -> Unit = {},
    val selectedSourceTags: Set<SourceTag> = emptySet(),
    val sourceTagEntries: List<SourceTag> = SourceTag.quickFilterEntries,
    val enabledSourceTags: Set<SourceTag> = sourceTagEntries.toSet(),
    val isSourceTagFilterVisible: Boolean = true,
    val onSourceTagFilterClick: (android.view.View?) -> Boolean = { false },
    val onSourceTagSelected: (SourceTag?) -> Unit = {},
    /**
     * Optional page-provided content for the source-tag filter popup (the "content source
     * type" filter button). When non-null it replaces the default single-tag menu.
     */
    val sourceTagCustomMenuContent: (@Composable ((close: () -> Unit) -> Unit))? = null,
    val onTopBarHeightChanged: (Int) -> Unit = {},
    val onBottomNavHeightChanged: (Int) -> Unit = {},
    val onContentInsetsChanged: (Int, Int) -> Unit = { _, _ -> },
    val onNavDestinationChanged: (Int) -> Unit = {},
    val pendingSearchNavigation: SearchNavigationRequest? = null,
    val onSearchNavigationHandled: () -> Unit = {},
    val onFeedRefresh: () -> Unit = {},
    val isResumeEnabled: Boolean = false,
    val onResumeClick: () -> Unit = {},
    val spaceUiState: SpaceUiState = SpaceUiState(),
    val spaceTransitionState: SpaceTransitionState = SpaceTransitionState(),
    val onSpaceTransitionCovered: suspend (SpaceId) -> Unit = {},
    val onSpaceCurtainCoverFinished: (SpaceId) -> Unit = {},
    val onSpaceCurtainRevealFinished: (SpaceId) -> Unit = {},
    val onSpaceAction: (SpaceAction) -> Unit = {},
    val spaceNavigationSessionUiState: SpaceNavigationSessionUiState = SpaceNavigationSessionUiState(),
    val onSpaceSessionChanged: (SpaceSessionSnapshot) -> Unit = {},
    val spaceTransitionSuppressionTarget: SpaceId? = null,
    val onSpaceTransitionSuppressionConsumed: (SpaceId) -> Unit = {},
    val spaceResumeUiState: SpaceResumeUiState = SpaceResumeUiState(),
    val onSpaceResume: (SpaceId) -> Unit = {},
)