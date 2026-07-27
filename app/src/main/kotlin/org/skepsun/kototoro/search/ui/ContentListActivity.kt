package org.skepsun.kototoro.search.ui

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.model.parcelable.ParcelableContentListFilter
import org.skepsun.kototoro.core.nav.PendingDetailsNavigation
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.os.AppShortcutManager
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.compose.DetailsScreen
import org.skepsun.kototoro.details.ui.compose.handleDetailsAction
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.details.ui.pager.pages.PagesViewModel
import org.skepsun.kototoro.filter.ui.FilterCoordinator
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.reader.ui.PageSaveHelper
import org.skepsun.kototoro.remotelist.ui.RemoteListViewModel
import org.skepsun.kototoro.search.ui.compose.AppSearchContentListRoute
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.util.ext.getSerializableExtraCompat
import org.skepsun.kototoro.work.domain.WorkResolver
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
        val navController = rememberNavController()
        val appRouter = router
        val settings = remember(applicationContext) { AppSettings(applicationContext) }
        val isSharedElementTransitionsEnabled =
            settings.observeAsState(AppSettings.KEY_SHARED_ELEMENT_TRANSITIONS) {
                isSharedElementTransitionsEnabled
            }.value
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
                        NavHost(
                            navController = navController,
                            startDestination = ContentListRoute,
                        ) {
                            composable<ContentListRoute> {
                                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                                    AppSearchContentListRoute(
                                        appRouter = appRouter,
                                        onBackClick = { finishAfterTransition() },
                                        viewModel = viewModel,
                                        onOpenDetails = { content, sharedElementKey ->
                                            openContentDetails(navController, content, sharedElementKey)
                                        },
                                    )
                                }
                            }

                            composable<ContentListDetailsRoute>(
                                enterTransition = {
                                    slideIntoContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                                        animationSpec = tween(320, easing = LinearEasing),
                                    ) + fadeIn(tween(220, easing = LinearEasing))
                                },
                                exitTransition = {
                                    slideOutOfContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                                        animationSpec = tween(320, easing = LinearEasing),
                                    ) + fadeOut(tween(180, easing = LinearEasing))
                                },
                                popEnterTransition = {
                                    slideIntoContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                                        animationSpec = tween(320, easing = LinearEasing),
                                    ) + fadeIn(tween(180, easing = LinearEasing))
                                },
                                popExitTransition = {
                                    slideOutOfContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                                        animationSpec = tween(320, easing = LinearEasing),
                                    ) + fadeOut(tween(160, easing = LinearEasing))
                                },
                            ) {
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

                                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                                    DetailsScreen(
                                        viewModel = detailsViewModel,
                                        pagesViewModel = pagesViewModel,
                                        bookmarksViewModel = bookmarksViewModel,
                                        settings = kototoroAppSettings,
                                        appRouter = appRouter,
                                        pageSaveHelper = pageSaveHelper,
                                        onBackClick = { navController.popBackStack() },
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
                                                onFinish = { navController.popBackStack() },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun openContentDetails(
        navController: androidx.navigation.NavHostController,
        content: Content,
        sharedElementKey: String?,
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
            navController.navigate(ContentListDetailsRoute)
        }
    }
}

@Serializable
private data object ContentListRoute

@Serializable
private data object ContentListDetailsRoute
