package org.skepsun.kototoro.main.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.google.android.material.navigation.NavigationBarView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.domain.BackupStartupCoordinator
import org.skepsun.kototoro.browser.AdListUpdateService
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.os.VoiceInputContract
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentLinkResolver
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.widgets.BottomNavState
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.core.util.ext.animatorDurationScale
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.details.service.ContentPrefetchService
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.local.ui.LocalIndexUpdateService
import org.skepsun.kototoro.local.ui.LocalStorageCleanupWorker
import org.skepsun.kototoro.main.ui.compose.ComposeAppNavBarDelegator
import org.skepsun.kototoro.main.ui.compose.KototoroApp
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.domain.sourceTypesFromTags
import org.skepsun.kototoro.search.ui.compose.SearchNavigationRequest
import org.skepsun.kototoro.search.ui.suggestion.SearchSuggestionViewModel
import org.skepsun.kototoro.space.ui.SpaceViewModel
import org.skepsun.kototoro.space.ui.SpaceNavigationSessionUiState
import org.skepsun.kototoro.space.ui.SpaceNavigationSessionViewModel
import org.skepsun.kototoro.space.ui.ImmersiveSpaceSessionRegistry
import org.skepsun.kototoro.space.ui.SpaceAction
import org.skepsun.kototoro.space.ui.SpaceResumeUiState
import org.skepsun.kototoro.space.ui.SpaceResumeViewModel
import org.skepsun.kototoro.space.ui.SpaceUiState
import org.skepsun.kototoro.space.ui.SpaceTransitionCurtainController
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.data.SpaceRoutePreferencesController
import org.skepsun.kototoro.space.data.SpaceSourcePresetController
import org.skepsun.kototoro.tracker.work.TrackWorker
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseComposeActivity() {

    companion object {
        const val EXTRA_RESUME_SPACE_ID = "main_activity.resume_space_id"
        const val EXTRA_RESTORE_IMMERSIVE_SPACE_ID = "main_activity.restore_immersive_space_id"
    }

    @Inject
    lateinit var spaceRoutePreferencesController: SpaceRoutePreferencesController

    @Inject
    lateinit var spaceSourcePresetController: SpaceSourcePresetController

    @Inject
    lateinit var spaceRepository: SpaceRepository

    @Inject
    lateinit var immersiveSpaceSessionRegistry: ImmersiveSpaceSessionRegistry

    @Inject
    lateinit var spaceFeatureFlagsRepository: SpaceFeatureFlagsRepository

    @Inject
    lateinit var spaceTransitionCurtainController: SpaceTransitionCurtainController

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var backupStartupCoordinator: BackupStartupCoordinator

    @Inject
    lateinit var sourcePresetsRepository: SourcePresetsRepository

    @Inject
    lateinit var contentDataRepository: ContentDataRepository

    @Inject
    lateinit var entityGraphRepository: EntityGraphRepository

    @Inject
    lateinit var workResolver: WorkResolver

    private val spaceViewModel by viewModels<SpaceViewModel>()
    private val spaceNavigationSessionViewModel by viewModels<SpaceNavigationSessionViewModel>()
    private val spaceResumeViewModel by viewModels<SpaceResumeViewModel>()

    @Inject
    lateinit var pageSaveHelperFactory: org.skepsun.kototoro.reader.ui.PageSaveHelper.Factory

    @Inject
    lateinit var trackWorkerScheduler: TrackWorker.Scheduler

    private val viewModel by viewModels<MainViewModel>()
    private val searchSuggestionViewModel by viewModels<SearchSuggestionViewModel>()
    private lateinit var pageSaveHelper: org.skepsun.kototoro.reader.ui.PageSaveHelper
    private val voiceInputLauncher = registerForActivityResult(VoiceInputContract()) { result ->
        val query = result?.trim().orEmpty()
        if (query.isNotEmpty()) {
            updateSearchQuery(query)
        }
    }

    private var isFoldUnfolded = false
    private var spaceResumeObserverInstalled = false
    private val navStateFlow = MutableStateFlow(BottomNavState())
    private lateinit var composeNavBarDelegator: ComposeAppNavBarDelegator

    private var topBarHeightPx = 0
    private var bottomNavHeightPx = 0
    private var containerTopInsetPx = 0
    private var containerBottomInsetPx = 0
    private var searchNavigationRequest by mutableStateOf<SearchNavigationRequest?>(null)
    private var nextSearchRequestId = 0L
    private var searchQuery by mutableStateOf("")
    private val activeFilterCallbacks = LinkedHashSet<SearchBarFilterViewController.Callback>()
    private var currentFilterCallback: SearchBarFilterViewController.Callback? = null
    private var activeFilterContentType by mutableStateOf<ContentType?>(null)
    private var activeFilterSourceTags by mutableStateOf<Set<SourceTag>>(emptySet())
    private var isLanguagePresetFilterVisible by mutableStateOf(false)
    private var isContentTypeFilterVisible by mutableStateOf(true)
    private var isSourceTagFilterVisible by mutableStateOf(true)
    private var availableSourceTags by mutableStateOf(SourceTag.quickFilterEntries)
    private var enabledSourceTags by mutableStateOf(SourceTag.quickFilterEntries.toSet())
    private var enabledContentTypes by mutableStateOf(allTopBarContentTypes())


    fun setActiveFilterCallback(callback: SearchBarFilterViewController.Callback) {
        activeFilterCallbacks.remove(callback)
        activeFilterCallbacks.add(callback)
        currentFilterCallback = callback
        refreshFilters()
    }

    fun clearActiveFilterCallback(callback: SearchBarFilterViewController.Callback) {
        activeFilterCallbacks.remove(callback)
        currentFilterCallback = activeFilterCallbacks.lastOrNull()
        if (currentFilterCallback != null) {
            refreshFilters()
        } else {
            clearActiveFilters()
        }
    }

    fun refreshFilters() {
        val callback = currentFilterCallback ?: return
        val sourceTagEntries = callback.getSourceTagEntries()
        availableSourceTags = sourceTagEntries
        isLanguagePresetFilterVisible = callback.isLanguagePresetFilterVisible() && settings.isShowLanguagePresetFilter
        isContentTypeFilterVisible = callback.isContentTypeFilterVisible() && settings.isShowContentTypeFilter
        isSourceTagFilterVisible = callback.isSourceTagFilterVisible() &&
            settings.isShowSourceTagFilter &&
            sourceTagEntries.isNotEmpty()
        applyConfiguredLanguagePreset()

        val selectedTab = if (isContentTypeFilterVisible) {
            callback.getSelectedContentType()
        } else {
            settings.hiddenContentType.toBrowseGroupTab()
        }
        if (!isContentTypeFilterVisible) {
            callback.applyContentTypeSelection(selectedTab)
        }
        activeFilterContentType = selectedTab.toContentTypeOrNull()

        val selectedSourceTags = if (isSourceTagFilterVisible) {
            callback.getSelectedSourceTags()
        } else {
            settings.hiddenSourceTag.toSourceTagSelection()
        }
        if (!isSourceTagFilterVisible) {
            callback.applySourceTagSelection(selectedSourceTags)
        }
        activeFilterSourceTags = selectedSourceTags

        enabledSourceTags = sourceTagEntries.filterTo(linkedSetOf()) { tag ->
            callback.isSourceTagEnabled(tag)
        }
        enabledContentTypes = buildSet {
            if (callback.isContentTypeEnabled(BrowseGroupTab.Content)) {
                add(ContentType.MANGA)
            }
            if (callback.isContentTypeEnabled(BrowseGroupTab.Novel)) {
                add(ContentType.NOVEL)
            }
            if (callback.isContentTypeEnabled(BrowseGroupTab.Video)) {
                add(ContentType.VIDEO)
            }
        }
        syncSearchSuggestionFilters()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncSpaceRuntime()
        pageSaveHelper = pageSaveHelperFactory.create(this)
        searchQuery = savedInstanceState?.getString(STATE_TOP_BAR_QUERY).orEmpty()
        applyConfiguredLanguagePreset()

        composeNavBarDelegator = ComposeAppNavBarDelegator(this, navStateFlow)

        lifecycleScope.launch {
            settings.observeAsFlow(AppSettings.KEY_NAV_MAIN) { mainNavItems }
                .collect { items ->
                    composeNavBarDelegator.setupMenu(items)
                }
        }
        lifecycleScope.launch {
            settings.observeAsFlow(AppSettings.KEY_NAV_LABELS) { isNavLabelsVisible }
                .collect { isVisible ->
                    composeNavBarDelegator.labelVisibilityMode = if (isVisible) {
                        NavigationBarView.LABEL_VISIBILITY_SELECTED
                    } else {
                        NavigationBarView.LABEL_VISIBILITY_UNLABELED
                    }
                }
        }

        viewModel.feedCounter.observe(this) { count ->
            if (count > 0) {
                composeNavBarDelegator.setBadgeNumber(R.id.nav_feed, count)
            } else {
                composeNavBarDelegator.clearBadge(R.id.nav_feed)
            }
        }

        setComposeContent {
            val suggestions by searchSuggestionViewModel.suggestion.collectAsState(initial = emptyList())
            val appUpdate by viewModel.appUpdate.collectAsState(initial = null)
            val isIncognitoModeEnabled by viewModel.isIncognitoModeEnabled.collectAsState()
            val isResumeEnabled by viewModel.isResumeEnabled.collectAsStateWithLifecycle()
            val sourcePresets by sourcePresetsRepository.observeAll().collectAsState(initial = emptyList())
            val lastReadContent by viewModel.lastReadContent.collectAsState()
            val spaceFlags by spaceFeatureFlagsRepository.flags.collectAsStateWithLifecycle()
            val spaceEnabled = spaceFlags.entitySpaceEnabled
            val spaceUiState by if (spaceEnabled) {
                spaceViewModel.uiState.collectAsStateWithLifecycle()
            } else {
                remember { mutableStateOf(SpaceUiState()) }
            }
            val spaceNavigationSessionUiState by if (spaceEnabled) {
                spaceNavigationSessionViewModel.uiState.collectAsStateWithLifecycle()
            } else {
                remember {
                    mutableStateOf(SpaceNavigationSessionUiState())
                }
            }
            val spaceResumeUiState by if (spaceEnabled) {
                spaceResumeViewModel.uiState.collectAsStateWithLifecycle()
            } else {
                remember { mutableStateOf(SpaceResumeUiState()) }
            }
            // This state bridges tasks and must remain current while MainActivity is stopped;
            // lifecycle-gated collection would expose a stale IDLE frame when the task returns.
            val spaceTransitionState by spaceTransitionCurtainController.state.collectAsState()
            val mainTransitionSuppressionTarget by immersiveSpaceSessionRegistry
                .mainTransitionSuppressionTarget
                .collectAsStateWithLifecycle()
            KototoroApp(
                appSettings = settings,
                navStateFlow = navStateFlow,
                pageSaveHelper = pageSaveHelper,
                lastReadContent = lastReadContent,
                suggestions = suggestions,
                onQueryChanged = ::updateSearchQuery,
                onSearch = { query -> submitSearch(query) },
                initialSearchKind = SearchKind.SIMPLE,
                initialSearchSourceTypes = searchSuggestionViewModel.getSourceTypes(),
                initialSearchContentKinds = searchSuggestionViewModel.getContentKinds(),
                onSearchWithOptions = ::submitSearchWithOptions,
                onSearchOverlaySourceTypesChange = searchSuggestionViewModel::setSourceTypes,
                onSearchOverlayContentKindsChange = searchSuggestionViewModel::setContentKinds,
                onSearchOverlayDismiss = ::syncSearchSuggestionFilters,
                query = searchQuery,
                onFeedRefresh = trackWorkerScheduler::startNow,
                isResumeEnabled = isResumeEnabled,
                onResumeClick = viewModel::openLastReader,
                spaceUiState = spaceUiState,
                spaceTransitionState = spaceTransitionState,
                onSpaceTransitionCovered = spaceTransitionCurtainController::reveal,
                onSpaceCurtainCoverFinished = spaceTransitionCurtainController::markCovered,
                onSpaceCurtainRevealFinished = spaceTransitionCurtainController::markRevealFinished,
                onSpaceAction = if (spaceEnabled) {
                    { action ->
                        when (action) {
                            SpaceAction.OpenSwitcher,
                            SpaceAction.DismissSwitcher -> spaceViewModel.onAction(action)
                            is SpaceAction.SelectSpace -> {
                                selectSpaceAndRestoreImmersiveSession(action.spaceId)
                            }
                        }
                    }
                } else {
                    {}
                },
                spaceNavigationSessionUiState = spaceNavigationSessionUiState,
                onSpaceSessionChanged = if (spaceEnabled) {
                    { snapshot: SpaceSessionSnapshot -> spaceNavigationSessionViewModel.save(snapshot) }
                } else {
                    { _: SpaceSessionSnapshot -> }
                },
                spaceTransitionSuppressionTarget = mainTransitionSuppressionTarget,
                onSpaceTransitionSuppressionConsumed =
                    immersiveSpaceSessionRegistry::completeMainTransitionSuppression,
                spaceResumeUiState = spaceResumeUiState,
                onSpaceResume = if (spaceEnabled) {
                    { spaceId ->
                        spaceViewModel.onAction(SpaceAction.DismissSwitcher)
                        if (immersiveSpaceSessionRegistry.hasActiveSession(spaceId)) {
                            selectSpaceAndRestoreImmersiveSession(spaceId)
                        } else {
                            spaceResumeViewModel.resume(spaceId)
                        }
                    }
                } else {
                    {}
                },
                onContentSuggestionClick = { content ->
                    resolveDetailsOriginForContent(content) { origin ->
                        when (origin) {
                            is DetailsOrigin.EntityGraph -> {
                                router.openEntityDetails(
                                    entityId = origin.entityId,
                                    initialProjectionLocalMangaId = origin.initialProjectionLocalMangaId,
                                )
                            }
                            else -> router.openResolvedDetails(content)
                        }
                    }
                },
                onLocalEntitySuggestionClick = { suggestion ->
                    suggestion.entityId?.let { entityId ->
                        openEntityDetailsWithPreferredProjection(
                            entityId = entityId,
                            fallbackLocalMangaId = suggestion.representative.id,
                        )
                    } ?: resolveDetailsOriginForContent(suggestion.representative) { origin ->
                        when (origin) {
                            is DetailsOrigin.EntityGraph -> {
                                router.openEntityDetails(
                                    entityId = origin.entityId,
                                    initialProjectionLocalMangaId = origin.initialProjectionLocalMangaId,
                                )
                            }
                            else -> router.openResolvedDetails(suggestion.representative)
                        }
                    }
                },
                onTrackingEntitySuggestionClick = { entity ->
                    when (entity.entityType) {
                        EntityType.WORK -> router.openTrackingSiteDetails(
                            service = entity.service,
                            remoteId = entity.remoteId,
                            url = entity.url,
                        )
                        EntityType.PERSON,
                        EntityType.CHARACTER,
                        EntityType.ORGANIZATION,
                        -> router.openTrackingEntityDetails(
                            service = entity.service,
                            entityType = entity.entityType,
                            remoteId = entity.remoteId,
                            name = entity.name,
                            altName = entity.altName,
                            coverUrl = entity.coverUrl,
                            url = entity.url,
                        )
                    }
                },
                onTagSuggestionClick = { tag ->
                    submitSearch(tag.title, SearchKind.TAG)
                },
                onSourceSuggestionClick = { source ->
                    this.router.openList(source, null, null)
                },
                onAuthorSuggestionClick = { author ->
                    submitSearch(author, SearchKind.AUTHOR)
                },
                onDeleteQuery = searchSuggestionViewModel::deleteQuery,
                onVoiceInput = {
                    try {
                        voiceInputLauncher.launch(null)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this@MainActivity, R.string.voice_search, android.widget.Toast.LENGTH_SHORT).show()
                        e.printStackTrace()
                    }
                },
                onOpenListOptions = {
                    this.router.showListConfigSheet(org.skepsun.kototoro.list.ui.config.ListConfigSection.General)
                },
                onSettingsClick = {
                    this.router.openSettings()
                },
                onSourceSettingsClick = {
                    this.router.openSourcesSettings()
                },
                onManageSourcesClick = {
                    this.router.openManageSources()
                },
                onGlobalTagBlacklistClick = {
                    this.router.openGlobalTagBlacklist()
                },
                onTrackingAccountsClick = {
                    this.router.openTrackingAccountsSettings()
                },
                isAppUpdateAvailable = appUpdate != null,
                onAppUpdateClick = {
                    this.router.openAppUpdate()
                },
                isIncognitoModeEnabled = isIncognitoModeEnabled,
                onIncognitoToggle = {
                    viewModel.setIncognitoMode(!isIncognitoModeEnabled)
                },
                onTopBarHeightChanged = { height ->
                    if (topBarHeightPx != height) {
                        topBarHeightPx = height
                        viewModel.setTopBarHeightPx(height)
                    }
                },
                onBottomNavHeightChanged = { height ->
                    if (bottomNavHeightPx != height) {
                        bottomNavHeightPx = height
                        viewModel.setBottomNavHeightPx(height)
                    }
                },
                onContentInsetsChanged = { topInset, bottomInset ->
                    if (containerTopInsetPx != topInset || containerBottomInsetPx != bottomInset) {
                        containerTopInsetPx = topInset
                        containerBottomInsetPx = bottomInset
                        viewModel.setContentInsetsPx(topInset, bottomInset)
                    }
                },
                onNavDestinationChanged = { itemId ->
                    composeNavBarDelegator.syncSelectedItem(itemId)
                },
                pendingSearchNavigation = searchNavigationRequest,
                onSearchNavigationHandled = {
                    clearSearchQuery()
                    searchNavigationRequest = null
                },
                isLanguagePresetFilterVisible = isLanguagePresetFilterVisible,
                languagePresetEntries = sourcePresets,
                onLanguagePresetSelected = { presetId ->
                    settings.activeSourcePresetId = presetId
                },
                onManageLanguagePresets = router::openSourcePresets,
                selectedContentType = activeFilterContentType,
                enabledContentTypes = enabledContentTypes,
                isContentTypeFilterVisible = isContentTypeFilterVisible,
                onContentTypeSelected = { type ->
                    if (type == null || type in enabledContentTypes) {
                        val tab = when (type) {
                            ContentType.NOVEL -> BrowseGroupTab.Novel
                            ContentType.VIDEO -> BrowseGroupTab.Video
                            ContentType.MANGA -> BrowseGroupTab.Content
                            else -> BrowseGroupTab.All
                        }
                        currentFilterCallback?.onContentTypeSelected(tab)
                        refreshFilters()
                    }
                },
                selectedSourceTags = activeFilterSourceTags,
                sourceTagEntries = availableSourceTags,
                enabledSourceTags = enabledSourceTags,
                isSourceTagFilterVisible = isSourceTagFilterVisible,
                onSourceTagFilterClick = ::onSourceTagFilterClick,
                onSourceTagSelected = { tag ->
                    if (tag == null || tag in enabledSourceTags) {
                        currentFilterCallback?.onSourceTagSelected(tag)
                        refreshFilters()
                    }
                },
            )
        }

        installSpaceResumeObserverIfEnabled()
        viewModel.onOpenReader.observeEvent(this) { content ->
            router.openReader(content)
        }
        viewModel.onFirstStart.observeEvent(this) { this.router.showWelcomeSheet() }
        viewModel.isBottomNavPinned.observe(this, ::setNavbarPinned)

        if (savedInstanceState == null) {
            onFirstStart()
        }

        consumeResumeSpaceIntent(intent)
        observeFoldableState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeResumeSpaceIntent(intent)
    }

    private fun consumeResumeSpaceIntent(intent: Intent) {
        if (!settings.isEntitySpaceEnabled) {
            intent.removeExtra(EXTRA_RESTORE_IMMERSIVE_SPACE_ID)
            intent.removeExtra(EXTRA_RESUME_SPACE_ID)
            return
        }
        intent.getStringExtra(EXTRA_RESTORE_IMMERSIVE_SPACE_ID)?.let { rawSpaceId ->
            intent.removeExtra(EXTRA_RESTORE_IMMERSIVE_SPACE_ID)
            val spaceId = SpaceId(rawSpaceId)
            if (immersiveSpaceSessionRegistry.hasActiveSession(spaceId)) {
                restoreActiveImmersiveSession(spaceId)
                intent.removeExtra(EXTRA_RESUME_SPACE_ID)
                return
            }
        }
        val rawSpaceId = intent.getStringExtra(EXTRA_RESUME_SPACE_ID) ?: return
        intent.removeExtra(EXTRA_RESUME_SPACE_ID)
        spaceResumeViewModel.resume(SpaceId(rawSpaceId))
    }

    private fun restoreActiveImmersiveSession(spaceId: SpaceId) {
        if (!immersiveSpaceSessionRegistry.hasActiveSession(spaceId)) return
        lifecycleScope.launch {
            spaceRepository.activeSpace.first { it == spaceId }
            immersiveSpaceSessionRegistry.restore(spaceId, this@MainActivity)
        }
    }

    private fun selectSpaceAndRestoreImmersiveSession(spaceId: SpaceId) {
        if (immersiveSpaceSessionRegistry.hasActiveSession(spaceId)) {
            immersiveSpaceSessionRegistry.suppressMainTransitionTo(spaceId)
            lifecycleScope.launch {
                try {
                    if (!coverSpaceTransition(spaceId, showOnTarget = false)) {
                        immersiveSpaceSessionRegistry.completeMainTransitionSuppression(spaceId)
                        return@launch
                    }
                    if (immersiveSpaceSessionRegistry.restore(
                        spaceId,
                        this@MainActivity,
                        suppressAnimation = true,
                    )) {
                        lifecycle.currentStateFlow.first { state ->
                            !state.isAtLeast(Lifecycle.State.RESUMED)
                        }
                        if (!spaceViewModel.selectSpaceAndAwait(spaceId)) {
                            immersiveSpaceSessionRegistry.completeMainTransitionSuppression(spaceId)
                            spaceTransitionCurtainController.reveal(spaceId)
                        }
                    } else {
                        immersiveSpaceSessionRegistry.completeMainTransitionSuppression(spaceId)
                        if (!spaceViewModel.selectSpaceAndAwait(spaceId)) {
                            spaceTransitionCurtainController.reveal(spaceId)
                        }
                    }
                } catch (error: CancellationException) {
                    immersiveSpaceSessionRegistry.completeMainTransitionSuppression(spaceId)
                    spaceTransitionCurtainController.cancel(spaceId)
                    throw error
                }
            }
            return
        }
        lifecycleScope.launch {
            try {
                if (!coverSpaceTransition(spaceId)) return@launch
                if (spaceViewModel.selectSpaceAndAwait(spaceId)) {
                    immersiveSpaceSessionRegistry.restore(
                        spaceId,
                        this@MainActivity,
                        suppressAnimation = true,
                    )
                } else {
                    spaceTransitionCurtainController.reveal(spaceId)
                }
            } catch (error: CancellationException) {
                spaceTransitionCurtainController.cancel(spaceId)
                throw error
            }
        }
    }

    private suspend fun coverSpaceTransition(
        spaceId: SpaceId,
        showOnTarget: Boolean = true,
    ): Boolean {
        val activeSpaceId = spaceRepository.activeSpace.value
        if (activeSpaceId == spaceId) return true
        return spaceTransitionCurtainController.cover(
            from = activeSpaceId,
            target = spaceId,
            animated = !settings.isReducedVisualEffectsEnabled && animatorDurationScale > 0f,
            showOnTarget = showOnTarget,
        )
    }

    private fun openEntityDetailsWithPreferredProjection(entityId: Long, fallbackLocalMangaId: Long) {
        lifecycleScope.launch {
            val preferredLocalMangaId = withContext(Dispatchers.IO) {
                workResolver.selectPreferredProjection(entityId)
            }
            router.openEntityDetails(
                entityId = entityId,
                preferredLocalMangaId = preferredLocalMangaId ?: fallbackLocalMangaId,
            )
        }
    }

	fun resolveDetailsOriginForContent(
		content: Content,
		onResolved: (DetailsOrigin) -> Unit,
	) {
		lifecycleScope.launch {
			val origin = withContext(Dispatchers.IO) {
				val entityId = workResolver.resolveByMangaId(content.id).entityId
				val cachedProjection = entityId?.let {
					contentDataRepository.findContentById(content.id, withChapters = false)
				}
				val canResolveProjection = entityId != null && cachedProjection != null
				android.util.Log.i(
					"DetailsTrace",
					"origin.resolve inputId=${content.id} inputSource=${content.source.name} " +
						"inputLocale=${content.source.locale} entityId=$entityId " +
						"cached=${cachedProjection != null} cachedSource=${cachedProjection?.source?.name} " +
						"cachedLocale=${cachedProjection?.source?.locale}",
				)
				if (entityId != null && canResolveProjection) {
					android.util.Log.i(
						"DetailsTrace",
						"origin.entityGraph entityId=$entityId initialProjectionId=${content.id}",
					)
					DetailsOrigin.EntityGraph(
						entityId = entityId,
						initialProjectionLocalMangaId = content.id,
					)
				} else {
					android.util.Log.i("DetailsTrace", "origin.localContent id=${content.id}")
					DetailsOrigin.LocalMangaContent(ParcelableContent(content))
				}
            }
            onResolved(origin)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TOP_BAR_QUERY, searchQuery)
    }

    override fun onResume() {
        super.onResume()
        syncSpaceRuntime()
        if (currentFilterCallback != null) {
            refreshFilters()
        } else {
            clearActiveFilters()
        }
    }

    private fun syncSpaceRuntime() {
        if (settings.isEntitySpaceEnabled) {
            spaceRoutePreferencesController.start()
            spaceSourcePresetController.start()
            installSpaceResumeObserverIfEnabled()
        } else {
            spaceRoutePreferencesController.stop()
            spaceSourcePresetController.stop()
        }
    }

    private fun installSpaceResumeObserverIfEnabled() {
        if (spaceResumeObserverInstalled || !settings.isEntitySpaceEnabled) return
        spaceResumeObserverInstalled = true
        spaceResumeViewModel.onOpenReader.observeEvent(this) { request ->
            router.openReader(request.content, contentTypeOverride = request.contentType)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        val activeSpaceId = spaceRepository.activeSpace.value
        val shouldRestore = shouldRestoreImmersiveSessionFromMain(
            immersiveSwitchEnabled = spaceFeatureFlagsRepository.flags.value.effectiveImmersiveSwitchEnabled,
            hasActiveSession = immersiveSpaceSessionRegistry.hasActiveSession(activeSpaceId),
            transitionSuppressionTarget = immersiveSpaceSessionRegistry.mainTransitionSuppressionTarget.value,
        )
        if (shouldRestore) {
            immersiveSpaceSessionRegistry.restore(activeSpaceId, this)
        }
    }

    private fun submitSearch(query: String, kind: SearchKind = SearchKind.SIMPLE) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return
        }
        if (kind == SearchKind.SIMPLE && ContentLinkResolver.isValidLink(trimmedQuery)) {
            clearSearchQuery()
            this.router.openDetails(trimmedQuery.toUri())
            return
        }
        openSearchInMain(
            query = trimmedQuery,
            kind = kind,
            sourceTypes = searchSuggestionViewModel.getSourceTypes(),
            contentKinds = searchSuggestionViewModel.getContentKinds(),
        )
        if (kind != SearchKind.TAG) {
            searchSuggestionViewModel.saveQuery(trimmedQuery)
        }
    }

    private fun submitSearchWithOptions(
        query: String,
        kind: SearchKind,
        sourceTypes: Set<org.skepsun.kototoro.core.jsonsource.SourceType>,
        contentKinds: Set<SearchContentKind>,
        advancedQuery: AdvancedSearchParams?,
        pinnedOnly: Boolean,
        hideEmpty: Boolean,
    ) {
        val resolvedKind = if (
            !advancedQuery?.title.isNullOrBlank() ||
            !advancedQuery?.tags.isNullOrBlank() ||
            !advancedQuery?.author.isNullOrBlank()
        ) {
            SearchKind.ADVANCED
        } else {
            kind
        }
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty() && advancedQuery == null) {
            return
        }
        openSearchInMain(
            query = trimmedQuery,
            kind = resolvedKind,
            sourceTypes = sourceTypes,
            contentKinds = contentKinds,
            advancedTitle = advancedQuery?.title?.takeIf { it.isNotBlank() },
            advancedTags = advancedQuery?.tags?.takeIf { it.isNotBlank() },
            advancedAuthor = advancedQuery?.author?.takeIf { it.isNotBlank() },
            pinnedOnly = pinnedOnly,
            hideEmpty = hideEmpty,
        )
        if (resolvedKind != SearchKind.TAG && trimmedQuery.isNotBlank()) {
            searchSuggestionViewModel.saveQuery(trimmedQuery)
        }
    }

    private fun openSearchInMain(
        query: String,
        kind: SearchKind,
        sourceTypes: Set<org.skepsun.kototoro.core.jsonsource.SourceType>,
        contentKinds: Set<SearchContentKind>,
        advancedTitle: String? = null,
        advancedTags: String? = null,
        advancedAuthor: String? = null,
        pinnedOnly: Boolean = false,
        hideEmpty: Boolean = false,
    ) {
        nextSearchRequestId += 1
        searchNavigationRequest = SearchNavigationRequest(
            query = query,
            kind = kind,
            sourceTypes = sourceTypes,
            contentKinds = contentKinds,
            advancedQuery = AdvancedSearchParams(
                query = query,
                title = advancedTitle.orEmpty(),
                tags = advancedTags.orEmpty(),
                author = advancedAuthor.orEmpty(),
            ).takeIf {
                it.title.isNotBlank() || it.tags.isNotBlank() || it.author.isNotBlank()
            },
            pinnedOnly = pinnedOnly,
            hideEmpty = hideEmpty,
            requestId = nextSearchRequestId,
        )
    }

    private fun syncSearchSuggestionFilters() {
        searchSuggestionViewModel.setSourceTypes(sourceTypesFromTags(activeFilterSourceTags))
        searchSuggestionViewModel.setContentKinds(activeFilterContentType.toSearchContentKinds())
    }

    private fun updateSearchQuery(query: String) {
        if (searchQuery != query) {
            searchQuery = query
        }
        searchSuggestionViewModel.onQueryChanged(query)
    }

    private fun clearSearchQuery() {
        updateSearchQuery("")
    }

    private fun clearActiveFilters() {
        activeFilterCallbacks.clear()
        currentFilterCallback = null
        activeFilterContentType = if (settings.isShowContentTypeFilter) {
            null
        } else {
            settings.hiddenContentType.toBrowseGroupTab().toContentTypeOrNull()
        }
        activeFilterSourceTags = if (settings.isShowSourceTagFilter) {
            emptySet()
        } else {
            settings.hiddenSourceTag.toSourceTagSelection()
        }
        isLanguagePresetFilterVisible = settings.isShowLanguagePresetFilter
        isContentTypeFilterVisible = settings.isShowContentTypeFilter
        isSourceTagFilterVisible = settings.isShowSourceTagFilter
        availableSourceTags = SourceTag.quickFilterEntries
        enabledSourceTags = SourceTag.quickFilterEntries.toSet()
        enabledContentTypes = allTopBarContentTypes()
        applyConfiguredLanguagePreset()
        syncSearchSuggestionFilters()
    }

    private fun applyConfiguredLanguagePreset() {
        if (!settings.isShowLanguagePresetFilter) {
            val presetId = settings.hiddenLanguagePreset.toPresetId()
            if (settings.activeSourcePresetId != presetId) {
                settings.activeSourcePresetId = presetId
            }
        }
    }

    private fun onFirstStart() = try {
        lifecycleScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.Default) {
                LocalStorageCleanupWorker.enqueue(applicationContext)
            }
            lifecycle.withResumed {
                ContentPrefetchService.prefetchLast(this@MainActivity)
                requestNotificationsPermission()
                startService(Intent(this@MainActivity, LocalIndexUpdateService::class.java))
                backupStartupCoordinator.startOnFirstLaunch(lifecycleScope)
                if (settings.isAdBlockEnabled) {
                    startService(Intent(this@MainActivity, AdListUpdateService::class.java))
                }
            }
        }
    } catch (e: IllegalStateException) {
        e.printStackTrace()
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1,
            )
        }
    }

    private fun setNavbarPinned(isPinned: Boolean) = Unit

    private fun onSourceTagFilterClick(anchorView: View?): Boolean {
        val anchor = anchorView ?: window.decorView
        return currentFilterCallback?.onFilterIconClicked(anchor) == true
    }

    private fun observeFoldableState() {
        val foldableState = FoldableUtils.observeFoldableState(this, this)

        lifecycleScope.launch {
            foldableState.collect { unfolded ->
                if (unfolded != isFoldUnfolded) {
                    isFoldUnfolded = unfolded
                    adjustLayoutForFoldableState()
                }
            }
        }
    }

    private fun adjustLayoutForFoldableState() { }
}

internal fun shouldRestoreImmersiveSessionFromMain(
    immersiveSwitchEnabled: Boolean,
    hasActiveSession: Boolean,
    transitionSuppressionTarget: SpaceId?,
): Boolean = immersiveSwitchEnabled && hasActiveSession && transitionSuppressionTarget == null

private fun ContentType?.toSearchContentKinds(): Set<SearchContentKind> = when (this) {
    ContentType.MANGA -> setOf(SearchContentKind.MANGA)
    ContentType.NOVEL, ContentType.HENTAI_NOVEL -> setOf(SearchContentKind.NOVEL)
    ContentType.VIDEO, ContentType.HENTAI_VIDEO -> setOf(SearchContentKind.VIDEO)
    else -> ALL_SEARCH_CONTENT_KINDS
}

private fun BrowseGroupTab.toContentTypeOrNull(): ContentType? = when (this) {
    BrowseGroupTab.Content -> ContentType.MANGA
    BrowseGroupTab.Novel -> ContentType.NOVEL
    BrowseGroupTab.Video -> ContentType.VIDEO
    BrowseGroupTab.All -> null
}

private fun String?.toBrowseGroupTab(): BrowseGroupTab = BrowseGroupTab.fromId(this ?: BrowseGroupTab.All.id)

private fun String?.toSourceTagSelection(): Set<SourceTag> {
    if (this.isNullOrBlank() || this == "all") {
        return emptySet()
    }
    return SourceTag.sanitizeQuickFilterSelection(
        split(',')
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "all" }
            .mapNotNull { raw ->
                runCatching { SourceTag.valueOf(raw) }.getOrNull()
                    ?: SourceTag.entries.firstOrNull { it.id == raw }
            }
            .toSet(),
    )
}

private fun String?.toPresetId(): Long = this?.toLongOrNull()?.takeIf { it > 0L } ?: -1L

private fun allTopBarContentTypes(): Set<ContentType> = setOf(
    ContentType.MANGA,
    ContentType.NOVEL,
    ContentType.VIDEO,
)

private const val STATE_TOP_BAR_QUERY = "main_activity.top_bar_query"
