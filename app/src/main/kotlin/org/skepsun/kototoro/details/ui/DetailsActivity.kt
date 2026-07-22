package org.skepsun.kototoro.details.ui

import android.app.Activity
import android.app.assist.AssistContent
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.os.AppShortcutManager
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseActivity
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.details.ui.compose.DetailsAction
import org.skepsun.kototoro.details.ui.compose.DetailsScreen
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.details.ui.pager.pages.PagesViewModel
import org.skepsun.kototoro.main.ui.owners.BottomSheetOwner
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.search.domain.SearchKind
import javax.inject.Inject

@AndroidEntryPoint
class DetailsActivity :
    BaseActivity<DetailsComposeBinding>(),
    BottomSheetOwner {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var pageSaveHelperFactory: org.skepsun.kototoro.reader.ui.PageSaveHelper.Factory

    @Inject
    lateinit var appShortcutManager: AppShortcutManager

    private val viewModel: DetailsViewModel by viewModels()
    private val pagesViewModel: PagesViewModel by viewModels()
    private val bookmarksViewModel: BookmarksViewModel by viewModels()

    private lateinit var pageSaveHelper: org.skepsun.kototoro.reader.ui.PageSaveHelper

    private val overrideEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.reload()
            }
        }

    override val bottomSheet: View?
        get() = null

    override fun onApplyWindowInsets(
        v: View,
        insets: androidx.core.view.WindowInsetsCompat,
    ): androidx.core.view.WindowInsetsCompat {
        return insets
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pageSaveHelper = pageSaveHelperFactory.create(this)

        setContentView(DetailsComposeBinding.inflate(this))

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    isEnabled = false
                    finishAfterTransition()
                }
            },
        )

        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        viewBinding.root.setContent {
            KototoroTheme {
                val backdropBackground = MaterialTheme.colorScheme.background
                val backdrop = rememberLayerBackdrop {
                    drawRect(backdropBackground)
                    drawContent()
                }
                CompositionLocalProvider(
                    LocalLiquidGlassBackdrop provides backdrop,
                    LocalLiquidGlassLayerBackdrop provides backdrop,
                ) {
                    DetailsScreen(
                        viewModel = viewModel,
                        pagesViewModel = pagesViewModel,
                        bookmarksViewModel = bookmarksViewModel,
                        settings = settings,
                        appRouter = router,
                        pageSaveHelper = pageSaveHelper,
                        onBackClick = { onBackPressedDispatcher.onBackPressed() },
                        onActionClick = ::handleActionClick,
                        isTemporaryReadOnly = intent.getBooleanExtra(AppRouter.KEY_TEMPORARY_DETAILS, false),
                    )
                }
            }
        }

        viewModel.onContentRemoved.observeEvent(this, ::onContentRemoved)
    }

    override fun dispatchNavigateUp() {
        super.dispatchNavigateUp()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        viewModel.getContentOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
    }

    override fun isNsfwContent(): Flow<Boolean> = viewModel.manga.map { it?.contentRating == ContentRating.ADULT }

    private fun openReader(isIncognitoMode: Boolean = false) {
        openDetailsReader(
            context = this,
            viewModel = viewModel,
            router = router,
            isIncognitoMode = isIncognitoMode,
            snackbarHost = viewBinding.root,
        )
    }

    private fun handleActionClick(action: DetailsAction) {
        when (action) {
            DetailsAction.OpenCover -> {
                viewModel.getContentOrNull()?.let { content ->
                    content.coverUrl?.let { url ->
                        router.openImage(
                            url = url,
                            source = content.source,
                            anchor = viewBinding.root,
                        )
                    }
                }
            }

            DetailsAction.Resume -> openReader()
            DetailsAction.ResumeIncognito -> openReader(isIncognitoMode = true)
            DetailsAction.ManageDownloads -> router.openDownloads()
            is DetailsAction.OpenContent -> router.openDetails(action.content)
            DetailsAction.Favorite -> viewModel.getContentOrNull()?.let(this.router::showFavoriteDialog)
            DetailsAction.Share -> viewModel.getContentOrNull()?.let(this.router::showShareDialog)
            DetailsAction.ForgetHistory -> viewModel.removeFromHistory()
            DetailsAction.ManageCategories -> this.router.openFavoriteCategories()
            is DetailsAction.OpenSource -> this.router.openList(action.source, null, null)
            is DetailsAction.OpenTrackingDiscover -> this.router.openTrackingDiscover(action.service, action.forceLoad)
            is DetailsAction.SearchAuthorOnSource -> this.router.openSearch(action.source, action.author)
            is DetailsAction.SearchAuthorEverywhere -> this.router.openSearch(action.author, SearchKind.AUTHOR)
            is DetailsAction.SearchTagOnSource -> this.router.openSearch(action.tag.source, action.tag.title)
            is DetailsAction.SearchTagEverywhere -> this.router.openSearch(action.tagTitle, SearchKind.TAG)
            is DetailsAction.OpenWebUrl -> router.openBrowser(action.url, null, null)
            is DetailsAction.SelectBranch -> viewModel.setSelectedBranch(action.branch)
            is DetailsAction.ShareLink -> router.shareLink(action.link, action.title)

            DetailsAction.Translate -> {
                val hasCache = viewModel.hasTranslationCache.value
                viewModel.translateTitleAndDescription(forceRefresh = hasCache)
                com.google.android.material.snackbar.Snackbar.make(
                    viewBinding.root,
                    if (hasCache) R.string.reader_translation_retranslate_started else R.string.translating,
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT,
                ).show()
            }

            DetailsAction.ToggleTranslation -> viewModel.toggleTranslationDisplay()
            DetailsAction.FindSimilar -> viewModel.getContentOrNull()?.let { this.router.openSearch(it.title) }
            DetailsAction.OpenAlternatives -> viewModel.getContentOrNull()?.let(this.router::openAlternatives)
            DetailsAction.OpenOnlineVariant -> viewModel.remoteContent.value?.let(this.router::openDetails)
            is DetailsAction.OpenBrowserPage -> router.openBrowser(action.url, action.source, action.title)
            DetailsAction.OpenMetadataInBrowser, DetailsAction.OpenLocalSourceInBrowser -> Unit
            is DetailsAction.OpenTrackingDetails -> router.openTrackingSiteDetails(action.service, action.remoteId, action.url)

            is DetailsAction.ManageTrackingBinding -> {
                router.openScrobblerBinding(
                    scrobbler = action.service,
                    remoteId = action.remoteId,
                    title = action.title,
                    url = action.url,
                )
            }

            is DetailsAction.BindTrackingMatch -> viewModel.bindTrackingMatch(action.match)
            is DetailsAction.IgnoreTrackingSuggestion -> viewModel.ignoreTrackingSuggestion(action.match)
            is DetailsAction.RemoveTrackingMatch -> viewModel.removeTrackingMatch(action.match)
            DetailsAction.Download -> Unit
            DetailsAction.OpenStatistics -> Unit
            DetailsAction.OpenReadingRecord -> Unit

            DetailsAction.ToggleList,
            DetailsAction.ToggleGrid,
            DetailsAction.ToggleBookmarkView -> Unit
            DetailsAction.ToggleSafe -> viewModel.toggleMarkSafe()
            DetailsAction.DeleteLocal -> viewModel.deleteLocal()

            DetailsAction.EditOverride -> {
                viewModel.getContentOrNull()?.let {
                    overrideEditLauncher.launch(AppRouter.overrideEditIntent(this, it))
                }
            }

            DetailsAction.CreateShortcut -> {
                viewModel.getContentOrNull()?.let { manga ->
                    lifecycleScope.launch {
                        if (!appShortcutManager.requestPinShortcut(manga)) {
                            com.google.android.material.snackbar.Snackbar.make(
                                viewBinding.root,
                                R.string.operation_not_supported,
                                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun onContentRemoved(manga: Content) {
        Toast.makeText(
            this,
            getString(R.string._s_deleted_from_local_storage, manga.title),
            Toast.LENGTH_SHORT,
        ).show()
        finishAfterTransition()
    }
}
