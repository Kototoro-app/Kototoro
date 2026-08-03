package org.skepsun.kototoro.search.ui.multi

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.search.ui.compose.SearchResultsRoute

@AndroidEntryPoint
class SearchActivity : BaseComposeActivity() {

	private val viewModel by viewModels<SearchViewModel>()
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
