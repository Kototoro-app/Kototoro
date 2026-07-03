package org.skepsun.kototoro.details.ui.pager

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.sheet.BaseAdaptiveSheet
import org.skepsun.kototoro.core.util.ext.setValueRounded
import org.skepsun.kototoro.core.util.progress.IntPercentLabelFormatter
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesSheet.Companion.TAB_BOOKMARKS
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesSheet.Companion.TAB_CHAPTERS
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesSheet.Companion.TAB_PAGES
import java.lang.ref.WeakReference

class ChapterPagesMenuProvider(
	private val viewModel: ChaptersPagesViewModel,
	private val sheet: BaseAdaptiveSheet<*>,
	private val getCurrentTab: () -> Int,
	private val getTabCount: () -> Int,
	private val settings: AppSettings,
) : OnBackPressedCallback(false), MenuProvider, SearchView.OnQueryTextListener, MenuItem.OnActionExpandListener,
	Slider.OnChangeListener {

	private var expandedItemRef: WeakReference<MenuItem>? = null

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		val tab = getCurrentTab()
		when (tab) {
			TAB_CHAPTERS -> {
				menuInflater.inflate(R.menu.opt_chapters, menu)
				menu.findItem(R.id.action_search)?.run {
					setOnActionExpandListener(this@ChapterPagesMenuProvider)
					(actionView as? SearchView)?.setupChaptersSearchView()
				}
				menu.findItem(R.id.action_search)?.isVisible = viewModel.emptyReason.value == null
				menu.findItem(R.id.action_reversed)?.isChecked = viewModel.isChaptersReversed.value == true
				menu.findItem(R.id.action_grid_view)?.isChecked = viewModel.isChaptersInGridView.value == true
				menu.findItem(R.id.action_hide_read_chapters)?.isChecked = viewModel.isHideReadChapters.value == true
				menu.findItem(R.id.action_merge_repeated_chapters)?.run {
					isVisible = viewModel.showMergeRepeatedChapters.value
					isChecked = viewModel.isMergeRepeatedChapters.value
				}
				menu.findItem(R.id.action_downloaded)?.let { menuItem ->
					menuItem.isVisible = viewModel.mangaDetails.value?.local != null
					menuItem.isChecked = viewModel.isDownloadedOnly.value == true
				}
			}

			TAB_PAGES, TAB_BOOKMARKS -> {
				menuInflater.inflate(R.menu.opt_pages, menu)
				menu.findItem(R.id.action_grid_size)?.run {
					setOnActionExpandListener(this@ChapterPagesMenuProvider)
					(actionView as? Slider)?.setupPagesSizeSlider()
				}
			}
		}
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_reversed -> {
			viewModel.setChaptersReversed(!menuItem.isChecked)
			true
		}

		R.id.action_grid_view -> {
			viewModel.setChaptersInGridView(!menuItem.isChecked)
			true
		}

		R.id.action_hide_read_chapters -> {
			viewModel.setHideReadChapters(!menuItem.isChecked)
			true
		}

		R.id.action_merge_repeated_chapters -> {
			viewModel.setMergeRepeatedChapters(!menuItem.isChecked)
			true
		}

		R.id.action_downloaded -> {
			viewModel.isDownloadedOnly.value = !menuItem.isChecked
			true
		}

		else -> false
	}

	override fun handleOnBackPressed() {
		expandedItemRef?.get()?.collapseActionView()
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		expandedItemRef = WeakReference(item)
		sheet.expandAndLock()
		isEnabled = true
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		expandedItemRef = null
		isEnabled = false
		(item.actionView as? SearchView)?.setQuery("", false)
		viewModel.performChapterSearch(null)
		sheet.unlock()
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		viewModel.performChapterSearch(newText)
		return true
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) {
			settings.gridSizePages = value.toInt()
		}
	}

	private fun SearchView.setupChaptersSearchView() {
		setOnQueryTextListener(this@ChapterPagesMenuProvider)
		setIconifiedByDefault(false)
		queryHint = context.getString(R.string.search_chapters)
	}

	private fun Slider.setupPagesSizeSlider() {
		valueFrom = 50f
		valueTo = 150f
		stepSize = 5f
		tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
		labelBehavior = LabelFormatter.LABEL_FLOATING
		setLabelFormatter(IntPercentLabelFormatter(context))
		setValueRounded(settings.gridSizePages.toFloat())
		addOnChangeListener(this@ChapterPagesMenuProvider)
	}
}
