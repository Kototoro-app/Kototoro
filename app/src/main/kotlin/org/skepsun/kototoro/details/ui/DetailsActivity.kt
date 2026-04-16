package org.skepsun.kototoro.details.ui

import android.app.assist.AssistContent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.SpannedString
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.graphics.Color
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.graphics.ColorUtils
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.transition.TransitionManager
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.lifecycle
import coil3.request.transformations
import coil3.size.Precision
import coil3.transform.RoundedCornersTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.constraintlayout.widget.Guideline
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationItem
import android.util.TypedValue
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.core.image.CoilMemoryCacheKey
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.model.getSummary
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.os.AppShortcutManager
import org.skepsun.kototoro.core.parser.favicon.faviconUri
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseActivity
import org.skepsun.kototoro.core.ui.BaseListAdapter
import org.skepsun.kototoro.core.ui.dialog.buildAlertDialog
import org.skepsun.kototoro.core.ui.image.FaviconDrawable
import org.skepsun.kototoro.core.ui.image.TextDrawable
import org.skepsun.kototoro.core.ui.image.TextViewTarget
import org.skepsun.kototoro.core.ui.list.OnListItemClickListener
import org.skepsun.kototoro.core.ui.sheet.BottomSheetCollapseCallback
import org.skepsun.kototoro.core.ui.util.MenuInvalidator
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.core.ui.widgets.ChipsView
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.core.util.LocaleUtils
import org.skepsun.kototoro.core.util.ext.consume
import org.skepsun.kototoro.core.util.ext.copyToClipboard
import org.skepsun.kototoro.core.util.ext.drawableStart
import org.skepsun.kototoro.core.util.ext.end
import org.skepsun.kototoro.core.util.ext.enqueueWith
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.core.util.ext.isAnimationsEnabled
import org.skepsun.kototoro.core.util.ext.isTextTruncated
import org.skepsun.kototoro.core.util.ext.joinToStringWithLimit
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.getThemeDimensionPixelSize
import org.skepsun.kototoro.core.util.ext.getThemeColor
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.parentView
import org.skepsun.kototoro.core.util.ext.setTooltipCompat
import org.skepsun.kototoro.core.util.ext.start
import org.skepsun.kototoro.core.util.ext.textAndVisible
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.databinding.ActivityDetailsBinding
import org.skepsun.kototoro.databinding.LayoutDetailsTableBinding
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.data.ReadingTime
import org.skepsun.kototoro.details.service.ContentPrefetchService
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.details.ui.model.HistoryInfo
import org.skepsun.kototoro.details.ui.scrobbling.ScrobblingItemDecoration
import org.skepsun.kototoro.details.ui.scrobbling.ScrollingInfoAdapter
import org.skepsun.kototoro.entitygraph.ui.details.EntityDetailsViewModel
import org.skepsun.kototoro.entitygraph.ui.details.EntityDetailsScreenState
import org.skepsun.kototoro.discover.bangumidata.domain.OfficialSiteDetails
import org.skepsun.kototoro.download.ui.worker.DownloadStartedObserver
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.adapter.ListItemType
import org.skepsun.kototoro.list.ui.adapter.mangaGridItemAD
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.size.StaticItemSizeResolver
import org.skepsun.kototoro.main.ui.owners.BottomSheetOwner
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import org.skepsun.kototoro.parsers.util.toTitleCase
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import javax.inject.Inject
import kotlin.math.roundToInt
import com.google.android.material.R as materialR

@AndroidEntryPoint
class DetailsActivity :
	BaseActivity<ActivityDetailsBinding>(),
	View.OnClickListener,
	View.OnLayoutChangeListener,
	ViewTreeObserver.OnDrawListener,
	ChipsView.OnChipClickListener,
	OnListItemClickListener<Bookmark>,
	SwipeRefreshLayout.OnRefreshListener,
	AuthorSpan.OnAuthorClickListener,
	BottomSheetOwner {

	@Inject
	lateinit var shortcutManager: AppShortcutManager

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var jsonSourceManager: JsonSourceManager

	private val viewModel: DetailsViewModel by viewModels()
	private val entityViewModel: EntityDetailsViewModel by viewModels()
	private lateinit var menuProvider: DetailsMenuProvider
	private lateinit var infoBinding: LayoutDetailsTableBinding
	private var isFoldUnfolded = false

	override val bottomSheet: View?
		get() = viewBinding.containerBottomSheet

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		
		if (settings.isSharedElementTransitionsEnabled) {
			window.requestFeature(android.view.Window.FEATURE_ACTIVITY_TRANSITIONS)
			
			val interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
			
			val slideEnter = android.transition.Slide(android.view.Gravity.END)
			slideEnter.duration = 350L
			slideEnter.interpolator = interpolator
			slideEnter.excludeTarget(android.R.id.statusBarBackground, true)
			slideEnter.excludeTarget(android.R.id.navigationBarBackground, true)
			window.enterTransition = slideEnter

			val slideReturn = android.transition.Slide(android.view.Gravity.END)
			slideReturn.duration = 275L
			slideReturn.interpolator = interpolator
			slideReturn.excludeTarget(android.R.id.statusBarBackground, true)
			slideReturn.excludeTarget(android.R.id.navigationBarBackground, true)
			window.returnTransition = slideReturn
			
			val sharedTransition = android.transition.TransitionInflater.from(this).inflateTransition(android.R.transition.move)
			sharedTransition.duration = 350L
			sharedTransition.interpolator = interpolator
			window.sharedElementEnterTransition = sharedTransition
			window.sharedElementReturnTransition = sharedTransition
			
			window.allowEnterTransitionOverlap = true
			window.allowReturnTransitionOverlap = true
		}
		
		setContentView(ActivityDetailsBinding.inflate(layoutInflater))
		infoBinding = LayoutDetailsTableBinding.bind(viewBinding.root)

		if (settings.isSharedElementTransitionsEnabled) {
			val manga = viewModel.getContentOrNull()
			if (manga != null) {
				androidx.core.view.ViewCompat.setTransitionName(viewBinding.imageViewCover, "cover_${manga.source.name}_${manga.url}")
				supportPostponeEnterTransition()
				// Fallback to prevent indefinite hang on broken content or failed image loads
				window.decorView.postDelayed({ supportStartPostponedEnterTransition() }, 350)
			}
		}

		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setDisplayShowTitleEnabled(false)
		// Make toolbar and appbar immersive/transparent
		val surfaceColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
		viewBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
		viewBinding.appbar.outlineProvider = null
		val toolbar = viewBinding.root.findViewById<View>(R.id.toolbar)
		toolbar?.setBackgroundColor(Color.TRANSPARENT)
		
		val titleCoordinator = TitleScrollCoordinator(viewBinding.textViewTitle)
		viewBinding.scrollView.setOnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
			titleCoordinator.onScrollChange(v as androidx.core.widget.NestedScrollView, scrollX, scrollY, oldScrollX, oldScrollY)
			val alpha = (scrollY.toFloat() / 200f).coerceIn(0f, 1f)
			viewBinding.appbar.setBackgroundColor(ColorUtils.setAlphaComponent(surfaceColor, (alpha * 255).toInt()))
		}
		viewBinding.chipFavorite.setOnClickListener(this)
		infoBinding.textViewLocal.setOnClickListener(this)
		infoBinding.textViewSource.setOnClickListener(this)
		viewBinding.imageViewCover.setOnClickListener(this)
		viewBinding.textViewTitle.setOnClickListener(this)
		viewBinding.buttonDescriptionMore.setOnClickListener(this)
		viewBinding.buttonScrobblingMore.setOnClickListener(this)
		viewBinding.buttonRelatedMore.setOnClickListener(this)
		viewBinding.textViewDescription.addOnLayoutChangeListener(this)
		viewBinding.swipeRefreshLayout.setOnRefreshListener(this)
		viewBinding.textViewDescription.viewTreeObserver.addOnDrawListener(this)
		infoBinding.textViewAuthor.movementMethod = LinkMovementMethodCompat.getInstance()
		viewBinding.textViewDescription.movementMethod = LinkMovementMethodCompat.getInstance()
		viewBinding.chipsTags.onChipClickListener = this
		// TitleScrollCoordinator is now handled by the custom scroll listener above
		// TitleScrollCoordinator(viewBinding.textViewTitle).attach(viewBinding.scrollView)
		if (settings.isDescriptionExpanded) {
			viewBinding.textViewDescription.maxLines = Int.MAX_VALUE - 1
		}
		viewBinding.containerBottomSheet?.let { sheet ->
			sheet.setOnClickListener(this)
			sheet.addOnLayoutChangeListener(this)
			onBackPressedDispatcher.addCallback(BottomSheetCollapseCallback(sheet))
			BottomSheetBehavior.from(sheet).addBottomSheetCallback(
				DetailsBottomSheetCallback(viewBinding.swipeRefreshLayout, checkNotNull(viewBinding.navbarDim)),
			)
		}

		val appRouter = router
		
		viewBinding.imageViewCover.addImageRequestListener(object : coil3.request.ImageRequest.Listener {
			override fun onSuccess(request: coil3.request.ImageRequest, result: coil3.request.SuccessResult) {
				supportStartPostponedEnterTransition()
			}
			override fun onError(request: coil3.request.ImageRequest, result: coil3.request.ErrorResult) {
				supportStartPostponedEnterTransition()
			}
		})

		viewModel.mangaDetails.filterNotNull().observe(this, ::onContentUpdated)
		viewModel.coverUrl.observe(this, ::loadCover)
		viewModel.onContentRemoved.observeEvent(this, ::onContentRemoved)
		viewModel.onError
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, DetailsErrorObserver(this, viewModel, exceptionResolver))
		viewModel.onActionDone
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, ReversibleActionObserver(viewBinding.scrollView))
		combine(viewModel.historyInfo, viewModel.isLoading, ::Pair).observe(this) {
			onHistoryChanged(it.first, it.second)
		}
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.scrobblingInfo.observe(this, ::onScrobblingInfoChanged)
		entityViewModel.screenState.observe(this, ::onEntityStateChanged)
		viewModel.trackingMatchSuggestion.observe(this) { suggestion ->
			renderTrackingMatchSuggestion(suggestion)
		}
		viewModel.localSize.observe(this, ::onLocalSizeChanged)
		viewModel.relatedContent.observe(this, ::onRelatedContentChanged)
		viewModel.favouriteCategories.observe(this, ::onFavoritesChanged)
		val menuInvalidator = MenuInvalidator(this)
		viewModel.isStatsAvailable.observe(this, menuInvalidator)
		viewModel.remoteContent.observe(this, menuInvalidator)
		viewModel.isMarkedSafe.observe(this, menuInvalidator)
		viewModel.tags.observe(this, ::onTagsChanged)
		viewModel.chapters.observe(this, PrefetchObserver(this))
		viewModel.onDownloadStarted
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, DownloadStartedObserver(viewBinding.scrollView))
		menuProvider = DetailsMenuProvider(
			activity = this,
			viewModel = viewModel,
			snackbarHost = viewBinding.scrollView,
			appShortcutManager = shortcutManager,
		)
		addMenuProvider(menuProvider)
		viewModel.translatedTitle.observe(this) { translated ->
			renderTitle()
			invalidateOptionsMenu()
		}
		viewModel.translatedDescription.observe(this) { translated ->
			val original = viewModel.mangaDetails.value?.description
				.ifNullOrEmpty { getString(R.string.no_description) }
			if (translated != null) {
				viewBinding.textViewDescription.text = "$original\n\n--- Translation ---\n$translated"
			} else {
				viewBinding.textViewDescription.text = original
			}
		}
		viewModel.isTranslating.observe(this) { isTranslating ->
			if (!isTranslating && !viewModel.hasTranslationCache.value) {
				// Translation finished with no results — might have failed silently
			}
			invalidateOptionsMenu()
		}
		viewModel.isShowingTranslation.observe(this) {
			invalidateOptionsMenu()
		}
		viewModel.hasTranslationCache.observe(this) {
			invalidateOptionsMenu()
		}
		
		// 观察折叠屏状态变化
		observeFoldableState()
	}

	override fun onProvideAssistContent(outContent: AssistContent) {
		super.onProvideAssistContent(outContent)
		viewModel.getContentOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
	}

	override fun isNsfwContent(): Flow<Boolean> = viewModel.manga.map { it?.contentRating == ContentRating.ADULT }

	override fun onClick(v: View) {
		when (v.id) {
			R.id.textView_source -> {
				val manga = viewModel.getContentOrNull() ?: return
				router.openList(manga.source, null, null)
			}

			R.id.textView_local -> {
				val manga = viewModel.getContentOrNull() ?: return
				router.showLocalInfoDialog(manga)
			}

			R.id.chip_favorite -> {
				val manga = viewModel.getContentOrNull() ?: return
				router.showFavoriteDialog(manga)
			}

			R.id.imageView_cover -> {
				val manga = viewModel.getContentOrNull() ?: return
				router.openImage(
					url = viewModel.coverUrl.value ?: return,
					source = manga.source,
					preview = CoilMemoryCacheKey.from(viewBinding.imageViewCover),
					anchor = v,
				)
			}

			R.id.button_description_more -> {
				val tv = viewBinding.textViewDescription
				if (tv.context.isAnimationsEnabled) {
					tv.parentView?.let {
						TransitionManager.beginDelayedTransition(it)
					}
				}
				if (tv.maxLines in 1 until Integer.MAX_VALUE) {
					tv.maxLines = Integer.MAX_VALUE
				} else {
					tv.maxLines = resources.getInteger(R.integer.details_description_lines)
				}
			}

			R.id.button_scrobbling_more -> {
				val manga = viewModel.getContentOrNull() ?: return
				router.showScrobblingSelectorSheet(
					manga = manga,
					scrobblerService = viewModel.scrobblingInfo.value.firstOrNull()?.scrobbler,
				)
			}

			R.id.button_related_more -> {
				val manga = viewModel.getContentOrNull() ?: return
				router.openRelated(manga)
			}

			R.id.textView_title -> {
				val title = viewModel.getContentOrNull()?.title?.nullIfEmpty() ?: return
				buildAlertDialog(this) {
					setMessage(title)
					setNegativeButton(R.string.close, null)
					setPositiveButton(androidx.preference.R.string.copy) { _, _ ->
						copyToClipboard(getString(R.string.content_type_manga), title)
					}
				}.show()
			}
		}
	}

	override fun onAuthorClick(author: String) {
		router.showAuthorDialog(author, viewModel.getContentOrNull()?.source ?: return)
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		val tag = data as? ContentTag ?: return
		router.showTagDialog(tag)
	}

	override fun onItemClick(item: Bookmark, view: View) {
		router.openReader(ReaderIntent.Builder(view.context).bookmark(item).incognito().build())
		Toast.makeText(view.context, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
	}

	override fun onRefresh() {
		viewModel.reload()
	}

	override fun onDraw() {
		viewBinding.run {
			buttonDescriptionMore.isVisible = textViewDescription.maxLines == Int.MAX_VALUE ||
				textViewDescription.isTextTruncated
		}
	}

	override fun onLayoutChange(
		v: View?,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int
	) {
		with(viewBinding) {
			containerBottomSheet?.let { sheet ->
				val peekHeight = BottomSheetBehavior.from(sheet).peekHeight
				if (scrollView.paddingBottom != peekHeight) {
					scrollView.updatePadding(bottom = peekHeight)
				}
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		if (viewBinding.cardChapters != null) {
			// landscape
			viewBinding.cardChapters?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				topMargin = barsInsets.top + resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer)
				marginEnd = barsInsets.end(v) + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
				bottomMargin = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
			}
			viewBinding.scrollView.updatePaddingRelative(
				bottom = barsInsets.bottom,
				start = barsInsets.start(v),
			)
			viewBinding.appbar.updatePaddingRelative(
				start = barsInsets.start(v),
			)
			viewBinding.appbar.updatePadding(top = barsInsets.top)
			val extraSpace = if (settings.isPanoramaCoverEnabled) (settings.panoramaCoverExtraHeight * v.resources.displayMetrics.density).toInt() else 0
			val totalTopOffset = barsInsets.top + v.context.getThemeDimensionPixelSize(androidx.appcompat.R.attr.actionBarSize) + extraSpace
			viewBinding.scrollView.findViewById<Guideline>(R.id.guideline_status_bar)?.setGuidelineBegin(totalTopOffset)
			return insets.consume(v, typeMask, bottom = true, end = true)
		} else {
			// portrait: immersive toolbar
			viewBinding.appbar.updatePadding(top = barsInsets.top)
			val extraSpace = if (settings.isPanoramaCoverEnabled) (settings.panoramaCoverExtraHeight * v.resources.displayMetrics.density).toInt() else 0
			val totalTopOffset = barsInsets.top + v.context.getThemeDimensionPixelSize(androidx.appcompat.R.attr.actionBarSize) + extraSpace
			viewBinding.scrollView.findViewById<Guideline>(R.id.guideline_status_bar)?.setGuidelineBegin(totalTopOffset)
			viewBinding.navbarDim?.updateLayoutParams {
				height = barsInsets.bottom
			}
			return insets
		}
	}

	private fun onFavoritesChanged(categories: Set<FavouriteCategory>) {
		val chip = viewBinding.chipFavorite
		chip.setChipIconResource(if (categories.isEmpty()) R.drawable.ic_heart_outline else R.drawable.ic_heart)
		chip.text = if (categories.isEmpty()) {
			getString(R.string.add_to_favourites)
		} else {
			categories.joinToStringWithLimit(this, FAV_LABEL_LIMIT) { it.title }
		}
	}

	private fun onLocalSizeChanged(size: Long) {
		if (size == 0L) {
			infoBinding.textViewLocal.isVisible = false
			infoBinding.textViewLocalLabel.isVisible = false
		} else {
			infoBinding.textViewLocal.text = FileSize.BYTES.format(this, size)
			infoBinding.textViewLocal.isVisible = true
			infoBinding.textViewLocalLabel.isVisible = true
		}
	}

	private fun onRelatedContentChanged(related: List<ContentListModel>) {
		if (related.isEmpty()) {
			viewBinding.groupRelated.isVisible = false
			return
		}
		val rv = viewBinding.recyclerViewRelated

		@Suppress("UNCHECKED_CAST")
		val adapter = (rv.adapter as? BaseListAdapter<ListModel>) ?: BaseListAdapter<ListModel>()
			.addDelegate(
				ListItemType.MANGA_GRID,
				mangaGridItemAD(
					sizeResolver = StaticItemSizeResolver(resources.getDimensionPixelSize(R.dimen.smaller_grid_width)),
				) { item, view ->
					val coverView = view.findViewById<View>(R.id.imageView_cover)
					router.openDetails(item.toContentWithOverride(), coverView)
				},
			).also { rv.adapter = it }
		adapter.items = related
		viewBinding.groupRelated.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.swipeRefreshLayout.isRefreshing = isLoading
	}

	private fun onScrobblingInfoChanged(scrobblings: List<ScrobblingInfo>) {
		val preferredScrobbler = settings.preferredTrackingSite
		val sortedScrobblings = scrobblings.sortedWith(
			compareByDescending<ScrobblingInfo> { it.scrobbler == preferredScrobbler }
				.thenBy { it.scrobbler.id },
		)
		val hasScrobblings = scrobblings.isNotEmpty()
		var adapter = viewBinding.recyclerViewScrobbling.adapter as? ScrollingInfoAdapter
		viewBinding.groupScrobbling.isVisible = true
		viewBinding.recyclerViewScrobbling.isVisible = hasScrobblings
		viewBinding.textViewScrobblingEmpty.isVisible = !hasScrobblings
		viewBinding.buttonScrobblingMore.setText(if (hasScrobblings) R.string.manage else R.string.add)
		if (adapter != null) {
			adapter.items = sortedScrobblings
		} else {
			adapter = ScrollingInfoAdapter(router) { settings.preferredTrackingSite }
			adapter.items = sortedScrobblings
			viewBinding.recyclerViewScrobbling.adapter = adapter
			viewBinding.recyclerViewScrobbling.addItemDecoration(ScrobblingItemDecoration())
		}

		// Sync top tracking identity into EntityDetailsViewModel to pull the Graph
		sortedScrobblings.firstOrNull()?.let { info ->
			entityViewModel.loadTrackingContext(info.scrobbler.id, info.targetId)
		}
	}

	private fun onEntityStateChanged(state: EntityDetailsScreenState) {
		val hasSites = state.officialSites.isNotEmpty()
		viewBinding.groupOfficialSites?.isVisible = hasSites
		if (hasSites) {
			viewBinding.chipsOfficialSites?.removeAllViews()
			state.officialSites.forEach { site ->
				val chip = Chip(this).apply {
					text = site.title
					setOnClickListener {
						router.openExternalBrowser(site.url)
					}
				}
				viewBinding.chipsOfficialSites?.addView(chip)
			}
		}

		// Group relations by Title Res
		val characters = state.relationSections.find { it.titleRes == R.string.entity_graph_section_characters }?.items ?: emptyList()
		val creators = state.relationSections.find { it.titleRes == R.string.entity_graph_section_creators }?.items ?: emptyList()

		viewBinding.groupCharacters?.isVisible = characters.isNotEmpty()
		if (characters.isNotEmpty()) {
			viewBinding.recyclerViewCharacters?.adapter = NativeRelationAdapter(characters)
		}

		viewBinding.groupCreators?.isVisible = creators.isNotEmpty()
		if (creators.isNotEmpty()) {
			viewBinding.recyclerViewCreators?.adapter = NativeRelationAdapter(creators)
		}
	}

	private inner class NativeRelationAdapter(private val items: List<EntityRelationItem>) : RecyclerView.Adapter<NativeRelationAdapter.VH>() {
		inner class VH(val textView: TextView) : RecyclerView.ViewHolder(textView) {
			init {
				textView.isClickable = true
				textView.isFocusable = true
				val outValue = TypedValue()
				theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
				textView.setBackgroundResource(outValue.resourceId)
				textView.setLines(1)
				textView.ellipsize = android.text.TextUtils.TruncateAt.END
				textView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
				val padH = resources.getDimensionPixelSize(R.dimen.margin_normal)
				val padV = resources.getDimensionPixelSize(R.dimen.grid_spacing)
				textView.setPadding(padH, padV, padH, padV)
				textView.setOnClickListener {
					val item = items[bindingAdapterPosition]
					router.openEntityDetails(item.entityId)
				}
			}
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
			val tv = TextView(parent.context).apply {
				layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
					it.marginEnd = resources.getDimensionPixelSize(R.dimen.margin_small)
				}
			}
			return VH(tv)
		}

		override fun getItemCount() = items.size
		override fun onBindViewHolder(holder: VH, position: Int) {
			holder.textView.text = items[position].name
		}
	}

	private fun renderTrackingMatchSuggestion(match: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult?) {
		val suggestionView = viewBinding.textViewScrobblingSuggestion ?: return
		suggestionView.isVisible = match != null
		suggestionView.text = match?.let {
			if (it.isLinked) {
				getString(
					R.string.tracking_linked_match_text,
					it.title,
				)
			} else {
				getString(
					R.string.tracking_auto_match_text,
					getString(it.service.titleResId),
					it.title,
					it.confidence * 100f,
				)
			}
		}
		suggestionView.setOnClickListener {
			if (match != null) {
				showTrackingMatchActions(match)
			}
		}
	}

	private fun showTrackingMatchActions(match: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult) {
		val isLinked = match.isLinked
		val actions = if (isLinked) {
			arrayOf(
				getString(R.string.tracking_open_linked_match_action),
				getString(R.string.tracking_manage_binding_action),
				getString(R.string.tracking_remove_binding_action),
			)
		} else {
			arrayOf(
				getString(R.string.tracking_auto_match_action),
				getString(R.string.tracking_bind_suggestion_action),
			)
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.tracking_match_actions_title)
			.setMessage(match.title)
			.setItems(actions) { _, which ->
				when {
					isLinked && which == 0 -> router.openTrackingSiteDetails(match.service, match.remoteId)
					isLinked && which == 1 -> router.openScrobblerBinding(match.service, match.remoteId, match.title, match.url)
					isLinked && which == 2 -> viewModel.removeTrackingMatch(match)
					!isLinked && which == 0 -> router.openTrackingSiteDetails(match.service, match.remoteId)
					!isLinked && which == 1 -> viewModel.bindTrackingMatch(match)
				}
			}
			.show()
	}

	private fun onContentUpdated(details: ContentDetails) {
		val manga = details.toContent()
		with(viewBinding) {
			textViewSubtitle.textAndVisible = manga.altTitles.joinToString("\n")
			textViewNsfw16.isVisible = manga.contentRating == ContentRating.SUGGESTIVE
			textViewNsfw18.isVisible = manga.contentRating == ContentRating.ADULT
			textViewDescription.text = details.description.ifNullOrEmpty { getString(R.string.no_description) }
		}
		renderTitle()
		with(infoBinding) {
			val translation = details.getLocale()
			infoBinding.textViewTranslation.textAndVisible = translation?.getDisplayLanguage(translation)
				?.toTitleCase(translation)
			infoBinding.textViewTranslation.drawableStart = translation?.let {
				LocaleUtils.getEmojiFlag(it)
			}?.let {
				TextDrawable.compound(infoBinding.textViewTranslation, it)
			}
			infoBinding.textViewTranslationLabel.isVisible = infoBinding.textViewTranslation.isVisible
			textViewAuthor.textAndVisible = manga.getAuthorsString()
			textViewAuthorLabel.isVisible = textViewAuthor.isVisible
			if (manga.hasRating) {
				ratingBarRating.rating = manga.rating * ratingBarRating.numStars
				ratingBarRating.isVisible = true
				textViewRatingLabel.isVisible = true
			} else {
				ratingBarRating.isVisible = false
				textViewRatingLabel.isVisible = false
			}
			manga.state?.let { state ->
				textViewState.textAndVisible = resources.getString(state.titleResId)
				textViewStateLabel.isVisible = textViewState.isVisible
			} ?: run {
				textViewState.isVisible = false
				textViewStateLabel.isVisible = false
			}

			if (manga.source == LocalMangaSource || manga.source == UnknownContentSource) {
				textViewSource.isVisible = false
				textViewSourceLabel.isVisible = false
			} else {
				val initialTitle = manga.source.getTitle(this@DetailsActivity)
				val contentType = getContentType(manga.source)
				textViewSource.textAndVisible = initialTitle
				textViewSource.setTooltipCompat(manga.source.getSummary(this@DetailsActivity, contentType))
				textViewSourceLabel.isVisible = textViewSource.isVisible == true
				if ((initialTitle == getString(R.string.unknown) || manga.source.name.startsWith("JSON_")) &&
					manga.source !is JsonContentSource
				) {
					// 某些场景 seed.source 仍是裸 ID，这里兜底同步显示数据库中的显示名
					lifecycleScope.launch {
						jsonSourceManager.getById(manga.source.name)?.name?.takeIf { it.isNotBlank() }?.let { displayName ->
							textViewSource.textAndVisible = displayName
							textViewSource.setTooltipCompat(displayName)
							textViewSourceLabel.isVisible = textViewSource.isVisible == true
						}
					}
				}
			}
			val faviconPlaceholderFactory = FaviconDrawable.Factory(R.style.FaviconDrawable_Chip)
			ImageRequest.Builder(this@DetailsActivity)
				.data(manga.source.faviconUri())
				.lifecycle(this@DetailsActivity)
				.crossfade(false)
				.precision(Precision.EXACT)
				.size(resources.getDimensionPixelSize(materialR.dimen.m3_chip_icon_size))
				.target(TextViewTarget(textViewSource, Gravity.START))
				.placeholder(faviconPlaceholderFactory)
				.error(faviconPlaceholderFactory)
				.fallback(faviconPlaceholderFactory)
				.mangaSourceExtra(manga.source)
				.transformations(RoundedCornersTransformation(resources.getDimension(R.dimen.chip_icon_corner)))
				.allowRgb565(true)
				.enqueueWith(coil)
		}
		invalidateOptionsMenu()
	}

	private fun renderTitle() {
		val manga = viewModel.getContentOrNull() ?: return
		val displayTitle = viewModel.translatedTitle.value ?: manga.title
		viewBinding.textViewTitle.text = displayTitle
		viewBinding.textViewTitle.maxLines = resources.getInteger(R.integer.details_title_lines)
		title = displayTitle
	}

	private fun onContentRemoved(manga: Content) {
		Toast.makeText(
			this,
			getString(R.string._s_deleted_from_local_storage, manga.title),
			Toast.LENGTH_SHORT,
		).show()
		finishAfterTransition()
	}

	private fun onHistoryChanged(info: HistoryInfo, isLoading: Boolean) = with(infoBinding) {
		textViewChapters.text = when {
			isLoading -> getString(R.string.loading_)
			info.currentChapter >= 0 -> getString(
				R.string.chapter_d_of_d,
				info.currentChapter + 1,
				info.totalChapters,
			).withEstimatedTime(info.estimatedTime)

			info.totalChapters == 0 -> getString(R.string.no_chapters)
			info.totalChapters == -1 -> getString(R.string.error_occurred)
			else -> resources.getQuantityStringSafe(R.plurals.chapters, info.totalChapters, info.totalChapters)
				.withEstimatedTime(info.estimatedTime)
		}
		textViewProgress.textAndVisible = if (info.percent <= 0f) {
			null
		} else {
			val displayPercent = if (ReadingProgress.isCompleted(info.percent)) 100 else (info.percent * 100f).toInt()
			getString(R.string.percent_string_pattern, displayPercent.toString())
		}

		progress.setProgressCompat(
			(progress.max * info.percent.coerceIn(0f, 1f)).roundToInt(),
			true,
		)
		textViewProgressLabel.isVisible = info.history != null
		textViewProgress.isVisible = info.history != null
		progress.isVisible = info.history != null
	}

	private fun onTagsChanged(tags: Collection<ChipsView.ChipModel>) {
		viewBinding.chipsTags.isVisible = tags.isNotEmpty()
		viewBinding.chipsTags.setChips(tags)
	}

	private fun loadCover(imageUrl: String?) {
		android.util.Log.d("DetailsActivity", "loadCover: $imageUrl")
		viewBinding.imageViewCover.setImageAsync(imageUrl, viewModel.getContentOrNull())
		loadPanoramaCover(imageUrl)
	}

	private fun loadPanoramaCover(imageUrl: String?) {
		val panoramaView = viewBinding.root.findViewById<android.widget.ImageView>(R.id.imageView_panorama)
			?: return
		val scrimView = viewBinding.root.findViewById<View>(R.id.view_panorama_scrim)
		val bottomGradientView = viewBinding.root.findViewById<View>(R.id.view_panorama_bottom_gradient)

		if (!settings.isPanoramaCoverEnabled || imageUrl.isNullOrEmpty()) {
			panoramaView.isVisible = false
			scrimView?.isVisible = false
			bottomGradientView?.isVisible = false
			return
		}

		val bottomGradientAlpha = settings.panoramaBottomGradientAlpha / 100f

		panoramaView.isVisible = true
		scrimView?.isVisible = true
		bottomGradientView?.isVisible = true
		bottomGradientView?.alpha = bottomGradientAlpha

		val request = ImageRequest.Builder(this)
			.data(imageUrl)
			.lifecycle(this)
			.crossfade(true)
			.allowRgb565(true)
			.mangaSourceExtra(viewModel.getContentOrNull()?.source)
			.target(
				onSuccess = { result ->
					panoramaView.setImageDrawable(result.asDrawable(resources))
					applyBlurEffect(panoramaView)
				},
				onError = {
					panoramaView.isVisible = false
					scrimView?.isVisible = false
					bottomGradientView?.isVisible = false
				},
			)
			.build()
		coil.enqueue(request)
	}

	private fun applyBlurEffect(imageView: android.widget.ImageView) {
		val blurLevel = settings.panoramaCoverBlur
		if (blurLevel <= 0) {
			// No blur applied
			imageView.alpha = 1f
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				imageView.setRenderEffect(null)
			}
			return
		}
		val radius = 1f + (blurLevel / 100f) * 24f // maps 1-100 to 1f-25f
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			imageView.setRenderEffect(
				android.graphics.RenderEffect.createBlurEffect(
					radius, radius,
					android.graphics.Shader.TileMode.MIRROR,
				),
			)
		} else {
			imageView.alpha = 1f - (blurLevel / 100f) * 0.7f // maps 1-100 to ~1.0-0.3
		}
	}

	/**
	 * 观察折叠屏状态变化并调整布局
	 */
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

	/**
	 * 根据折叠屏状态调整布局
	 */
    private fun adjustLayoutForFoldableState() {
        // 仅在折叠屏展开且窗口满足双栏宽度时重建，避免分屏窄窗口反复重建
        if (isFoldUnfolded && viewBinding.cardChapters == null && FoldableUtils.shouldUseTwoPaneLayout(this)) {
            recreate()
            return
        }

        viewBinding.root.requestLayout()
    }

	private fun getContentType(source: org.skepsun.kototoro.parsers.model.ContentSource): org.skepsun.kototoro.parsers.model.ContentType {
		return source.getContentType()
	}

	private fun String.withEstimatedTime(time: ReadingTime?): String {
		if (time == null) {
			return this
		}
		val timeFormatted = time.formatShort(resources)
		return getString(R.string.chapters_time_pattern, this, timeFormatted)
	}

	private fun Content.getAuthorsString(): SpannedString? {
		if (authors.isEmpty()) {
			return null
		}
		return buildSpannedString {
			authors.forEach { a ->
				if (a.isNotEmpty()) {
					if (isNotEmpty()) {
						append(", ")
					}
					inSpans(AuthorSpan(this@DetailsActivity)) {
						append(a)
					}
				}
			}
		}.nullIfEmpty()
	}

	private class PrefetchObserver(
		private val context: Context,
	) : FlowCollector<List<ChapterListItem>?> {

		private var isCalled = false

		override suspend fun emit(value: List<ChapterListItem>?) {
			if (value.isNullOrEmpty()) {
				return
			}
			if (!isCalled) {
				isCalled = true
				val item = value.find { it.isCurrent } ?: value.first()
				ContentPrefetchService.prefetchPages(context, item.chapter)
			}
		}
	}

	companion object {

		private const val FAV_LABEL_LIMIT = 16
	}
}
