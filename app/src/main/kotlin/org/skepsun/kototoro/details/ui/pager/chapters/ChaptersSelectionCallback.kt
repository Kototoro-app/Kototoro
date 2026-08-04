package org.skepsun.kototoro.details.ui.pager.chapters

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.view.ActionMode
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.list.BaseListSelectionCallback
import org.skepsun.kototoro.core.ui.list.ListSelectionController
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toCollection
import org.skepsun.kototoro.core.util.ext.toSet
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesViewModel
import org.skepsun.kototoro.local.ui.LocalChaptersRemoveService

class ChaptersSelectionCallback(
	private val viewModel: ChaptersPagesViewModel,
	private val router: AppRouter,
	recyclerView: RecyclerView,
) : BaseListSelectionCallback(recyclerView) {

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_chapters, menu)
		return true
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val selectedIds = controller.peekCheckedIds()
		val allItems = viewModel.chapters.value
		val items = allItems.withIndex().filter { it.value.chapter.id in selectedIds }
		var canSave = true
		var canDelete = true
		val allLocal = items.isNotEmpty() && items.all { it.value.isDownloaded || it.value.chapter.source.isLocal }
		val contentType = viewModel.getContentOrNull()?.source?.getContentType()
		items.forEach { (_, x) ->
			val isLocal = x.isDownloaded || x.chapter.source.isLocal
			if (isLocal) canSave = false else canDelete = false
		}
		menu.findItem(R.id.action_save).isVisible = canSave
		menu.findItem(R.id.action_delete).isVisible = canDelete
		menu.findItem(R.id.action_prepare_translation).isVisible = allLocal && contentType != null && contentType != ContentType.VIDEO && contentType != ContentType.HENTAI_VIDEO
		menu.findItem(R.id.action_prepare_super_resolution).isVisible = allLocal && contentType != null && contentType != ContentType.VIDEO && contentType != ContentType.HENTAI_VIDEO && contentType != ContentType.NOVEL && contentType != ContentType.HENTAI_NOVEL
		menu.findItem(R.id.action_select_all).isVisible = items.size < allItems.size
		menu.findItem(R.id.action_mark_current).isVisible = items.size == 1
		mode?.title = items.size.toString()
		var hasGap = false
		for (i in 0 until items.size - 1) {
			if (items[i].index + 1 != items[i + 1].index) {
				hasGap = true
				break
			}
		}
		menu.findItem(R.id.action_select_range).isVisible = hasGap
		return true
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_save -> {
				val snapshot = controller.snapshot()
				mode?.finish()
				if (snapshot.isNotEmpty()) {
					val manga = viewModel.mangaDetails.value?.toContent()
					if (manga?.source?.getContentType() == ContentType.VIDEO) {
						viewModel.probeAndDownload(snapshot)
					} else {
						router.askForDownloadOverMeteredNetwork {
							viewModel.download(snapshot, it)
						}
					}
				}
				true
			}

			R.id.action_delete -> {
				val ids = controller.peekCheckedIds()
				val manga = viewModel.getContentOrNull()
				if (ids.isNotEmpty() && manga != null) {
					LocalChaptersRemoveService.start(recyclerView.context, manga, ids.toSet())
					try {
						Snackbar.make(
							recyclerView,
							R.string.chapters_will_removed_background,
							Snackbar.LENGTH_LONG,
						).show()
					} catch (e: IllegalArgumentException) {
						e.printStackTraceDebug()
						Toast.makeText(
							recyclerView.context,
							R.string.chapters_will_removed_background,
							Toast.LENGTH_SHORT,
						).show()
					}
				}
				mode?.finish()
				true
			}

			R.id.action_prepare_translation -> {
				val snapshot = controller.snapshot()
				mode?.finish()
				if (snapshot.isNotEmpty()) {
					router.askForDownloadOverMeteredNetwork {
						viewModel.prepareTranslation(snapshot, it)
					}
				}
				true
			}

			R.id.action_prepare_super_resolution -> {
				val snapshot = controller.snapshot()
				mode?.finish()
				if (snapshot.isNotEmpty()) {
					viewModel.prepareSuperResolution(snapshot)
				}
				true
			}

			R.id.action_select_range -> {
				val items = viewModel.chapters.value
				val ids = controller.peekCheckedIds().toCollection(HashSet())
				val buffer = HashSet<Long>()
				var isAdding = false
				for (x in items) {
					if (x.chapter.id in ids) {
						isAdding = true
						if (buffer.isNotEmpty()) {
							ids.addAll(buffer)
							buffer.clear()
						}
					} else if (isAdding) {
						buffer.add(x.chapter.id)
					}
				}
				controller.addAll(ids)
				true
			}

			R.id.action_select_all -> {
				val ids = viewModel.chapters.value.map {
					it.chapter.id
				}
				controller.addAll(ids)
				true
			}

			R.id.action_mark_current -> {
				val ids = controller.peekCheckedIds()
				if (ids.size == 1) {
					viewModel.markChapterAsCurrent(ids.first())
				} else {
					return false
				}
				mode?.finish()
				true
			}

			else -> false
		}
	}
}
