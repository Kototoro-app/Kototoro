package org.skepsun.kototoro.settings.userdata.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.dialog.buildAlertDialog
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.local.data.CacheDir
import org.skepsun.kototoro.settings.SettingsActivity
import org.skepsun.kototoro.settings.SettingsDestination
import org.skepsun.kototoro.settings.compose.DataCleanupSettingsScreen
import javax.inject.Inject

@AndroidEntryPoint
class DataCleanupSettingsFragment : Fragment() {

    private val viewModel by viewModels<DataCleanupSettingsViewModel>()

    @Inject
    lateinit var appSettings: AppSettings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KototoroTheme {
                    DataCleanupSettingsRoute(
                        settings = appSettings,
                        viewModel = viewModel,
                        onClearSearchHistory = ::clearSearchHistory,
                        onClearCookies = ::clearCookies,
                        onDeleteReadChapters = ::cleanupChapters,
                        onOpenEntityOrganize = ::openEntityOrganize,
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.data_removal))
        
        viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(view, this))
        viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(view))
        viewModel.onChaptersCleanedUp.observeEvent(viewLifecycleOwner, ::onChaptersCleanedUp)
    }

    private fun onChaptersCleanedUp(result: Pair<Int, Long>) {
        val c = context ?: return
        val text = if (result.first == 0 && result.second == 0L) {
            c.getString(R.string.no_chapters_deleted)
        } else {
            c.getString(
                R.string.chapters_deleted_pattern,
                c.resources.getQuantityStringSafe(R.plurals.chapters, result.first, result.first),
                FileSize.BYTES.format(c, result.second),
            )
        }
        view?.let { Snackbar.make(it, text, Snackbar.LENGTH_SHORT).show() }
    }

    private fun clearSearchHistory() {
        buildAlertDialog(context ?: return) {
            setTitle(R.string.clear_search_history)
            setMessage(R.string.text_clear_search_history_prompt)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(R.string.clear) { _, _ ->
                viewModel.clearSearchHistory()
            }
        }.show()
    }

    private fun clearCookies() {
        buildAlertDialog(context ?: return) {
            setTitle(R.string.clear_cookies)
            setMessage(R.string.text_clear_cookies_prompt)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(R.string.clear) { _, _ ->
                viewModel.clearCookies()
            }
        }.show()
    }

    private fun cleanupChapters() {
        buildAlertDialog(context ?: return) {
            setTitle(R.string.delete_read_chapters)
            setMessage(R.string.delete_read_chapters_prompt)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(R.string.delete) { _, _ ->
                viewModel.cleanupChapters()
            }
        }.show()
    }
}

@Composable
fun DataCleanupSettingsRoute(
    settings: AppSettings,
    viewModel: DataCleanupSettingsViewModel,
    onClearSearchHistory: () -> Unit,
    onClearCookies: () -> Unit,
    onDeleteReadChapters: () -> Unit,
    onOpenEntityOrganize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = context.resources

    val searchHistoryCount by viewModel.searchHistoryCount.collectAsState(initial = -1)
    val searchHistorySummary = if (searchHistoryCount < 0) {
        context.getString(R.string.loading_)
    } else {
        resources.getQuantityStringSafe(R.plurals.items, searchHistoryCount, searchHistoryCount)
    }

    val feedItemsCount by viewModel.feedItemsCount.collectAsState(initial = -1)
    val updatesFeedSummary = if (feedItemsCount < 0) {
        context.getString(R.string.loading_)
    } else {
        resources.getQuantityStringSafe(R.plurals.items, feedItemsCount, feedItemsCount)
    }

    val thumbsCacheSize by viewModel.cacheSizes[CacheDir.THUMBS]!!.collectAsState(initial = -1L)
    val thumbsCacheSummary = if (thumbsCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, thumbsCacheSize)
    }

    val pagesCacheSize by viewModel.cacheSizes[CacheDir.PAGES]!!.collectAsState(initial = -1L)
    val pagesCacheSummary = if (pagesCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, pagesCacheSize)
    }

    val videoCacheSize by viewModel.cacheSizes[CacheDir.VIDEO]!!.collectAsState(initial = -1L)
    val videoCacheSummary = if (videoCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, videoCacheSize)
    }

    val httpCacheSize by viewModel.httpCacheSize.collectAsState(initial = -1L)
    val networkCacheSummary = if (httpCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, httpCacheSize)
    }

    val loadingKeys by viewModel.loadingKeys.collectAsState(initial = emptySet())

    DataCleanupSettingsScreen(
        settings = settings,
        searchHistorySummary = searchHistorySummary,
        updatesFeedSummary = updatesFeedSummary,
        thumbsCacheSummary = thumbsCacheSummary,
        pagesCacheSummary = pagesCacheSummary,
        videoCacheSummary = videoCacheSummary,
        networkCacheSummary = networkCacheSummary,
        isBrowserVisible = viewModel.isBrowserDataCleanupEnabled,
        isSearchHistoryEnabled = AppSettings.KEY_SEARCH_HISTORY_CLEAR !in loadingKeys,
        isUpdatesFeedEnabled = AppSettings.KEY_UPDATES_FEED_CLEAR !in loadingKeys,
        isThumbsCacheEnabled = AppSettings.KEY_THUMBS_CACHE_CLEAR !in loadingKeys,
        isPagesCacheEnabled = AppSettings.KEY_PAGES_CACHE_CLEAR !in loadingKeys,
        isVideoCacheEnabled = AppSettings.KEY_VIDEO_CACHE_CLEAR !in loadingKeys,
        isNetworkCacheEnabled = AppSettings.KEY_HTTP_CACHE_CLEAR !in loadingKeys,
        isChaptersClearEnabled = AppSettings.KEY_CHAPTERS_CLEAR !in loadingKeys,
        isWebviewClearEnabled = AppSettings.KEY_WEBVIEW_CLEAR !in loadingKeys,
        isMangaDataEnabled = AppSettings.KEY_CLEAR_MANGA_DATA !in loadingKeys,
        onClearSearchHistory = onClearSearchHistory,
        onClearUpdatesFeed = viewModel::clearUpdatesFeed,
        onClearThumbsCache = {
            viewModel.clearCache(
                AppSettings.KEY_THUMBS_CACHE_CLEAR,
                CacheDir.THUMBS,
                CacheDir.FAVICONS,
            )
        },
        onClearPagesCache = {
            viewModel.clearCache(AppSettings.KEY_PAGES_CACHE_CLEAR, CacheDir.PAGES)
        },
        onClearVideoCache = {
            viewModel.clearCache(AppSettings.KEY_VIDEO_CACHE_CLEAR, CacheDir.VIDEO)
        },
        onClearNetworkCache = viewModel::clearHttpCache,
        onClearDatabase = viewModel::clearContentData,
        onClearCookies = onClearCookies,
        onClearBrowserData = viewModel::clearBrowserData,
        onDeleteReadChapters = onDeleteReadChapters,
        onOpenEntityOrganize = onOpenEntityOrganize,
        modifier = modifier,
    )
}

private fun DataCleanupSettingsFragment.openEntityOrganize() {
    (activity as? SettingsActivity)?.openDestination(SettingsDestination.EntityOrganizeSettings, null, false)
}
