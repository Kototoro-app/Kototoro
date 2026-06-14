package org.skepsun.kototoro.alternatives.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseActivity
import org.skepsun.kototoro.core.ui.BaseListAdapter
import org.skepsun.kototoro.core.ui.dialog.buildAlertDialog
import org.skepsun.kototoro.core.ui.list.OnListItemClickListener
import org.skepsun.kototoro.core.util.ext.consumeAllSystemBarsInsets
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.systemBarsInsets
import org.skepsun.kototoro.databinding.ActivityAlternativesBinding
import org.skepsun.kototoro.list.ui.adapter.ListItemType
import org.skepsun.kototoro.list.ui.adapter.ListStateHolderListener
import org.skepsun.kototoro.list.ui.adapter.TypedListSpacingDecoration
import org.skepsun.kototoro.list.ui.adapter.buttonFooterAD
import org.skepsun.kototoro.list.ui.adapter.emptyStateListAD
import org.skepsun.kototoro.list.ui.adapter.loadingFooterAD
import org.skepsun.kototoro.list.ui.adapter.loadingStateAD
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

@AndroidEntryPoint
class AlternativesActivity : BaseActivity<ActivityAlternativesBinding>(),
	ListStateHolderListener,
	OnListItemClickListener<ContentAlternativeModel> {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<AlternativesViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityAlternativesBinding.inflate(layoutInflater))
		supportActionBar?.run {
			setDisplayHomeAsUpEnabled(true)
			subtitle = viewModel.manga.title
		}
		val listAdapter = BaseListAdapter<ListModel>()
			.addDelegate(ListItemType.MANGA_LIST_DETAILED, alternativeAD(coil, this, this))
			.addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(null))
			.addDelegate(ListItemType.FOOTER_LOADING, loadingFooterAD())
			.addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
			.addDelegate(ListItemType.FOOTER_BUTTON, buttonFooterAD(this))
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, addHorizontalPadding = false))
			adapter = listAdapter
		}

		addMenuProvider(AlternativesMenuProvider(viewModel))

		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		viewModel.list.observe(this, listAdapter)
		viewModel.onMigrated.observeEvent(this) {
			Toast.makeText(this, R.string.migration_completed, Toast.LENGTH_SHORT).show()
			router.openResolvedDetails(it)
			finishAfterTransition()
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.recyclerView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			top = barsInsets.top,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onItemClick(item: ContentAlternativeModel, view: View) {
		when (view.id) {
			R.id.chip_source -> router.openSearch(item.manga.source, viewModel.manga.title)
			else -> router.openResolvedDetails(item.manga, view)
		}
	}

	override fun onItemLongClick(item: ContentAlternativeModel, view: View): Boolean {
		when (view.id) {
			R.id.button_migrate -> {
				confirmMigration(item.manga)
				return true
			}
			else -> return false
		}
	}

	override fun onRetryClick(error: Throwable) = viewModel.retry()

	override fun onEmptyActionClick() = Unit

	override fun onFooterButtonClick() = viewModel.continueSearch()

	private fun confirmMigration(target: Content) {
		buildAlertDialog(this, isCentered = true) {
			setIcon(R.drawable.ic_replace)
			setTitle(R.string.manga_migration)
			setMessage(
				getString(
					R.string.migrate_confirmation,
					viewModel.manga.title,
					viewModel.manga.source.getTitle(context),
					target.title,
					target.source.getTitle(context),
				),
			)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate) { _, _ ->
				viewModel.migrate(target)
			}
		}.show()
	}
}
