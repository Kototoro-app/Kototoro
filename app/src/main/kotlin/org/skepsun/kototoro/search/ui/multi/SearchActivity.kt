package org.skepsun.kototoro.search.ui.multi

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.core.util.ext.getSerializableExtraCompat
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.ui.compose.SearchResultsRoute

@AndroidEntryPoint
class SearchActivity : BaseComposeActivity() {

    private val viewModel by viewModels<SearchViewModel>(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<SearchViewModel.Factory> { factory ->
                factory.create(
                    query = intent.getStringExtra(AppRouter.KEY_QUERY).orEmpty(),
                    kind = intent.getSerializableExtraCompat<SearchKind>(AppRouter.KEY_KIND) ?: SearchKind.SIMPLE,
                    advancedTitle = intent.getStringExtra(AppRouter.KEY_ADVANCED_TITLE).orEmpty(),
                    advancedTags = intent.getStringExtra(AppRouter.KEY_ADVANCED_TAGS).orEmpty(),
                    advancedAuthor = intent.getStringExtra(AppRouter.KEY_ADVANCED_AUTHOR).orEmpty(),
                    pinnedOnly = intent.getBooleanExtra(AppRouter.KEY_PINNED_ONLY, false),
                    hideEmpty = intent.getBooleanExtra(AppRouter.KEY_HIDE_EMPTY, false),
                    sourceTypeNames = intent.getStringArrayListExtra(AppRouter.KEY_SOURCE_TYPES),
                    contentKindNames = intent.getStringArrayListExtra(AppRouter.KEY_CONTENT_KINDS),
                )
            }
        },
    )
    private val isPickMode by lazy { intent.getBooleanExtra(AppRouter.KEY_PICK_MODE, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
                SearchResultsRoute(
                    viewModel = viewModel,
                    onBackClick = ::finishAfterTransition,
                    onOpenContent = { content, _ ->
                        router.openResolvedDetails(content)
                    },
                    onPickContent = { content ->
                        setResult(RESULT_OK, Intent().putExtra(AppRouter.KEY_MANGA, ParcelableContent(content)))
                        finishAfterTransition()
                    },
                    onOpenSourceResults = { item ->
                        if (item.listFilter == null) {
                            router.openSearch(item.source, viewModel.query)
                        } else {
                            router.openList(item.source, item.listFilter, item.sortOrder)
                        }
                    },
                    onManageLanguagePresets = router::openSourcePresets,
                    onOpenGlobalTagBlacklist = router::openGlobalTagBlacklist,
                    onSubmitSearch = { query, kind, sourceTypes, contentKinds, advancedQuery, pinnedOnly, hideEmpty ->
                        router.openSearch(
                            query = query,
                            kind = kind,
                            sourceTypes = sourceTypes,
                            contentKinds = contentKinds,
                            advancedTitle = advancedQuery?.title?.takeIf { it.isNotBlank() },
                            advancedTags = advancedQuery?.tags?.takeIf { it.isNotBlank() },
                            advancedAuthor = advancedQuery?.author?.takeIf { it.isNotBlank() },
                            pinnedOnly = pinnedOnly,
                            hideEmpty = hideEmpty,
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out, 0)
                        } else {
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                        finishAfterTransition()
                    },
                    onShareSelection = { items ->
                        ShareHelper(this).shareContentLinks(items)
                    },
                    onSaveSelection = { items ->
                        router.showDownloadDialog(items)
                    },
                    onFavouriteSelection = { items ->
                        router.showFavoriteDialog(items)
                    },
                    isPickMode = isPickMode,
                )
        }
    }
}
