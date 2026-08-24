package org.skepsun.kototoro.search.ui

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.model.parcelable.ParcelableContentListFilter
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.PendingDetailsNavigation
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.os.AppShortcutManager
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.util.ext.getSerializableExtraCompat
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.compose.DetailsScreen
import org.skepsun.kototoro.details.ui.compose.handleDetailsAction
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.details.ui.pager.pages.PagesViewModel
import org.skepsun.kototoro.filter.ui.FilterCoordinator
import org.skepsun.kototoro.main.ui.navigation3.KototoroMotionCatalog
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.reader.ui.PageSaveHelper
import org.skepsun.kototoro.remotelist.ui.RemoteListViewModel
import org.skepsun.kototoro.search.ui.compose.AppSearchContentListRoute
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject

@AndroidEntryPoint
class ContentListActivity : BaseComposeActivity(), FilterCoordinator.Owner {

    private val viewModel: RemoteListViewModel by viewModels()

    @Inject
    lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

    @Inject
    lateinit var appShortcutManager: AppShortcutManager

    @Inject
    lateinit var contentDataRepository: ContentDataRepository

    @Inject
    lateinit var workResolver: WorkResolver

    private lateinit var pageSaveHelper: PageSaveHelper

    override val filterCoordinator: FilterCoordinator
        get() = viewModel.filterCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pageSaveHelper = pageSaveHelperFactory.create(this)

        val filter = intent.getParcelableExtraCompat<ParcelableContentListFilter>(AppRouter.KEY_FILTER)?.filter
        val sortOrder = intent.getSerializableExtraCompat<SortOrder>(AppRouter.KEY_SORT_ORDER)

        if (filter != null) filterCoordinator.setAdjusted(filter)
        if (sortOrder != null) filterCoordinator.setSortOrder(sortOrder)

        setComposeContent {
            ContentListNavHost()
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    private fun ContentListNavHost() {
        val appRouter = router
        val settings = remember(applicationContext) { AppSettings(applicationContext) }
        val isSharedElementTransitionsEnabled =
            settings.observeAsState(AppSettings.KEY_SHARED_ELEMENT_TRANSITIONS) {
                isSharedElementTransitionsEnabled
            }.value
        // Navigation 3 host: the work list is the root entry and every opened work pushes its
        // own [WorkDetailsRoute] back-stack entry. Navigation 3 gives each entry a dedicated
        // ViewModelStore (created on push, retained across configuration changes, cleared after
        // the pop transition), which is the root fix for the details ViewModels being reused
        // across open/back cycles and always showing the first work's details.
        //
        // However the origin payload is handed off through the process-wide
        // PendingDetailsNavigation static, which does not survive process death. So we also
        // keep the full (Parcelable) DetailsOrigin in saveable state: a restored details
        // session re-seeds the hand-off before its fresh ViewModel reads it, otherwise the
        // details page reopens blank after the process was killed while backgrounded.
        var lastDetailsOrigin by rememberSaveable { mutableStateOf<DetailsOrigin?>(null) }
        @Suppress("UNCHECKED_CAST")
        val navBackStack = rememberNavBackStack(WorkListRoute) as NavBackStack<ContentListRouteKey>
        val saveableStateHolder = rememberSaveableStateHolder()
        val activity = LocalContext.current as ContentListActivity
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            SharedTransitionLayout {
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides if (isSharedElementTransitionsEnabled) {
                        this@SharedTransitionLayout
                    } else {
                        null
                    },
                ) {
                    val entries = rememberDecoratedNavEntries(
                        backStack = navBackStack.toList(),
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator<ContentListRouteKey>(saveableStateHolder),
                            rememberViewModelStoreNavEntryDecorator<ContentListRouteKey>(activity),
                        ),
                    ) { entryKey ->
                        contentListNavEntry(entryKey) { key ->
                            when (key) {
                                is WorkListRoute -> AppSearchContentListRoute(
                                    appRouter = appRouter,
                                    onBackClick = { finishAfterTransition() },
                                    viewModel = viewModel,
                                    onOpenDetails = { content, sharedElementKey ->
                                        openContentDetails(content, sharedElementKey) { origin ->
                                            lastDetailsOrigin = origin
                                            // Replace any already-open details session instead of stacking one:
                                            // two rapid taps can both finish origin resolution before a frame
                                            // renders, and a buried entry would later find its
                                            // PendingDetailsNavigation payload already consumed (blank details).
                                            if (navBackStack.lastOrNull() is WorkDetailsRoute) {
                                                navBackStack.removeAt(navBackStack.lastIndex)
                                            }
                                            navBackStack.add(
                                                WorkDetailsRoute(sessionId = System.nanoTime()),
                                            )
                                        }
                                    },
                                )

                                is WorkDetailsRoute -> {
                                    // Re-seed the static hand-off from durable state when it is missing:
                                    // after process death PendingDetailsNavigation is empty (the activity
                                    // was recreated with a fresh back-stack entry and a fresh DetailsViewModel),
                                    // so without this the restored details page would open blank.
                                    val detailsOrigin = lastDetailsOrigin
                                    remember(detailsOrigin) {
                                        if (detailsOrigin != null && PendingDetailsNavigation.peek() == null) {
                                            PendingDetailsNavigation.set(detailsOrigin)
                                        }
                                    }
                                    ContentListDetailsContent(
                                        onBack = { popContentListDetails(navBackStack) },
                                        onFinish = { popContentListDetails(navBackStack) },
                                        animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                                    )
                                }
                            }
                        }
                    }
                    NavDisplay(
                        entries = entries,
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopStart,
                        sceneStrategies = listOf(remember { SinglePaneSceneStrategy<ContentListRouteKey>() }),
                        sceneDecoratorStrategies = emptyList<SceneDecoratorStrategy<ContentListRouteKey>>(),
                        sharedTransitionScope = LocalSharedTransitionScope.current,
                        transitionSpec = { KototoroMotionCatalog.legacySlide.enter(this) },
                        popTransitionSpec = { KototoroMotionCatalog.legacySlide.pop(this) },
                        predictivePopTransitionSpec = { progress -> KototoroMotionCatalog.legacySlide.predictivePop(this, progress) },
                        onBack = { popContentListDetails(navBackStack) },
                    )
                }
            }
        }
    }

    @Composable
    private fun ContentListDetailsContent(
        onBack: () -> Unit,
        onFinish: () -> Unit,
        animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    ) {
        val appRouter = router
        // These ViewModels are scoped to the current back-stack entry's ViewModelStore
        // (provided by rememberViewModelStoreNavEntryDecorator), so each details session gets
        // a fresh instance that consumes its own PendingDetailsNavigation payload and is
        // disposed when the entry is popped.
        val detailsViewModel = hiltViewModel<DetailsViewModel>()
        val pagesViewModel = hiltViewModel<PagesViewModel>()
        val bookmarksViewModel = hiltViewModel<BookmarksViewModel>()
        val detailsCoroutineScope = rememberCoroutineScope()
        val overrideEditLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                detailsViewModel.reload()
            }
        }
        val pendingContent = remember { PendingDetailsNavigation.lastContent() }
        val pendingSharedKey = remember { PendingDetailsNavigation.lastSharedElementKey() }
        val mangaDetails by detailsViewModel.mangaDetails.collectAsStateWithLifecycle()
        val sharedKey = remember(pendingSharedKey, mangaDetails, pendingContent) {
            pendingSharedKey ?: run {
                val content: Content? = mangaDetails?.toContent() ?: pendingContent
                content?.let { c ->
                    contentCoverSharedKey(c, c.coverUrl)
                }
            }
        }

        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides animatedVisibilityScope) {
            DetailsScreen(
                viewModel = detailsViewModel,
                pagesViewModel = pagesViewModel,
                bookmarksViewModel = bookmarksViewModel,
                settings = kototoroAppSettings,
                appRouter = appRouter,
                pageSaveHelper = pageSaveHelper,
                onBackClick = onBack,
                sharedElementKey = sharedKey,
                onActionClick = { action ->
                    handleDetailsAction(
                        action = action,
                        appRouter = appRouter,
                        viewModel = detailsViewModel,
                        appShortcutManager = appShortcutManager,
                        coroutineScope = detailsCoroutineScope,
                        snackbarHost = window.decorView.rootView,
                        overrideEditLauncher = overrideEditLauncher,
                        onFinish = onFinish,
                    )
                },
            )
        }
    }

    private fun openContentDetails(
        content: Content,
        sharedElementKey: String?,
        onOpened: (DetailsOrigin) -> Unit,
    ) {
        lifecycleScope.launch {
            val origin = withContext(Dispatchers.IO) {
                val entityId = workResolver.resolveByMangaId(content.id).entityId
                val canResolveProjection = entityId != null &&
                    contentDataRepository.findContentById(content.id, withChapters = false) != null
                if (entityId != null && canResolveProjection) {
                    DetailsOrigin.EntityGraph(
                        entityId = entityId,
                        initialProjectionLocalMangaId = content.id,
                    )
                } else {
                    DetailsOrigin.LocalMangaContent(
                        org.skepsun.kototoro.core.model.parcelable.ParcelableContent(content),
                    )
                }
            }
            PendingDetailsNavigation.set(origin, sharedElementKey)
            onOpened(origin)
        }
    }
}

/**
 * Pops the top details entry off the local back stack. The root [WorkListRoute] entry is never
 * removed; there the system back falls through to the Activity's default dispatch (finishing the
 * list screen, matching the pre-Navigation-3 behavior).
 */
private fun popContentListDetails(stack: NavBackStack<ContentListRouteKey>) {
    if (stack.size > 1) {
        stack.removeAt(stack.lastIndex)
    }
}

/**
 * Builds a [NavEntry] for the local host, mirroring the main shell's entry wrapper: the
 * Navigation 3 scene scope is published as [LocalNavAnimatedVisibilityScope] so shared-element
 * bounds register against the NavDisplay's animated content scope.
 */
private fun contentListNavEntry(
    key: ContentListRouteKey,
    renderEntry: @Composable (ContentListRouteKey) -> Unit,
): NavEntry<ContentListRouteKey> {
    return NavEntry(key = key, metadata = emptyMap()) { entryKey ->
        CompositionLocalProvider(
            LocalNavAnimatedVisibilityScope provides LocalNavAnimatedContentScope.current,
        ) {
            renderEntry(entryKey)
        }
    }
}
