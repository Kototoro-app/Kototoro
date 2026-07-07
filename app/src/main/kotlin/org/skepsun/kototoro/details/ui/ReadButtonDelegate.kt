package org.skepsun.kototoro.details.ui

import android.content.Context
import android.graphics.Color
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.view.MenuCompat
import androidx.core.view.get
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialSplitButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.core.util.ext.getThemeColor
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.details.ui.model.HistoryInfo
import org.skepsun.kototoro.parsers.model.ContentType

import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.model.getContentType

internal fun openDetailsReader(
	context: Context,
	viewModel: DetailsViewModel,
	router: AppRouter,
	isIncognitoMode: Boolean,
	snackbarHost: View,
) {
	val manga = viewModel.getContentOrNull() ?: return
	if (viewModel.historyInfo.value.isChapterMissing) {
		Snackbar.make(snackbarHost, R.string.chapter_is_missing, Snackbar.LENGTH_SHORT).show()
		return
	}

	val intentBuilder = ReaderIntent.Builder(context)
		.manga(manga)
		.branch(viewModel.selectedBranchValue)

	runCatching {
		val source = manga.source.unwrap()
		val history = viewModel.historyInfo.value.history
		val contentType = source.getContentType()

		if ((contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO) && !manga.chapters.isNullOrEmpty()) {
			val selectedBranch = viewModel.selectedBranchValue
			val historyChapter = history?.let { hist -> manga.chapters?.find { it.id == hist.chapterId } }
			val historyMatchesSelectedBranch = historyChapter?.branch == selectedBranch
			val state = if (history != null && historyMatchesSelectedBranch) {
				ReaderState(history)
			} else {
				ReaderState(manga, selectedBranch)
			}
			intentBuilder.state(state)
		} else if (history != null && !manga.chapters.isNullOrEmpty()) {
			val preferredBranch = viewModel.selectedBranchValue
			val chapters = manga.chapters?.filter { it.branch == preferredBranch } ?: manga.chapters
			var matchedChapter = chapters?.find { it.id == history.chapterId }

			if (matchedChapter == null) {
				val potentialParentChapter = chapters?.find { it.id == history.chapterId }
				if (potentialParentChapter != null && potentialParentChapter.url.endsWith(".epub", ignoreCase = true)) {
					return@runCatching
				}
			}

			if (matchedChapter == null && history.parentChapterId != null) {
				val parentChapter = chapters?.find { it.id == history.parentChapterId }
				if (parentChapter != null) {
					val internalChapters = chapters.filter { chapter ->
						chapter.url.startsWith(parentChapter.url) && chapter.url.contains("#chapter/")
					}
					matchedChapter = internalChapters.find { it.id == history.chapterId }
				}
			}

			if (matchedChapter == null) {
				matchedChapter = chapters
					?.filter { chapter ->
						val diff = kotlin.math.abs(chapter.id - history.chapterId)
						diff in 1..1000000
					}
					?.minByOrNull { chapter ->
						kotlin.math.abs(chapter.id - history.chapterId)
					}
			}

			if (matchedChapter != null) {
				val isCompleted = history.percent >= 0.99999f
				val targetState = if (isCompleted) {
					val sortedChapters = chapters?.sortedBy { it.number } ?: emptyList()
					val index = sortedChapters.indexOfFirst { it.id == matchedChapter.id }
					if (index != -1 && index + 1 < sortedChapters.size) {
						val nextChapter = sortedChapters[index + 1]
						ReaderState(
							chapterId = nextChapter.id,
							page = 0,
							scroll = 0,
						)
					} else {
						ReaderState(history.copy(chapterId = matchedChapter.id))
					}
				} else {
					ReaderState(history.copy(chapterId = matchedChapter.id))
				}
				intentBuilder.state(targetState)
			}
		}
	}.getOrElse { }

	if (isIncognitoMode) {
		intentBuilder.incognito()
	}
	router.openReader(intentBuilder.build())
	if (isIncognitoMode) {
		Toast.makeText(context, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
	}
}

class ReadButtonDelegate(
	private val splitButton: MaterialSplitButton,
	private val viewModel: DetailsViewModel,
	private val router: AppRouter,
) : View.OnClickListener, PopupMenu.OnMenuItemClickListener, PopupMenu.OnDismissListener {

	private val buttonRead = splitButton[0] as MaterialButton
	private val buttonMenu = splitButton[1] as MaterialButton

	private val context: Context
		get() = buttonRead.context

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_read -> openReader(isIncognitoMode = false)
			R.id.button_read_menu -> showMenu()
		}
	}

	override fun onMenuItemClick(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.action_incognito -> openReader(isIncognitoMode = true)
			R.id.action_forget -> viewModel.removeFromHistory()
			R.id.action_download -> {
				router.showDownloadDialog(
					manga = setOf(viewModel.getContentOrNull() ?: return false),
					snackbarHost = splitButton,
				)
			}

			Menu.NONE -> {
				val branch = viewModel.branches.value.getOrNull(item.order) ?: return false
				viewModel.setSelectedBranch(branch.name)
			}

			else -> return false
		}
		return true
	}

	override fun onDismiss(menu: PopupMenu?) {
		buttonMenu.isChecked = false
	}

	fun attach(lifecycleOwner: LifecycleOwner) {
		buttonRead.setOnClickListener(this)
		buttonMenu.setOnClickListener(this)
		combine(viewModel.isLoading, viewModel.historyInfo, ::Pair)
			.observe(lifecycleOwner) { (isLoading, historyInfo) ->
				onHistoryChanged(isLoading, historyInfo)
			}
	}

	private fun showMenu() {
		val menu = PopupMenu(context, buttonMenu)
		menu.inflate(R.menu.popup_read)
		prepareMenu(menu.menu)
		menu.setOnMenuItemClickListener(this)
		menu.setForceShowIcon(true)
		menu.setOnDismissListener(this)
		if (menu.menu.hasVisibleItems()) {
			buttonMenu.isChecked = true
			menu.show()
		} else {
			buttonMenu.isChecked = false
		}
	}

	private fun prepareMenu(menu: Menu) {
		MenuCompat.setGroupDividerEnabled(menu, true)
		menu.populateBranchList()
		val history = viewModel.historyInfo.value
		menu.findItem(R.id.action_incognito)?.isVisible = !history.isIncognitoMode
		menu.findItem(R.id.action_forget)?.isVisible = history.history != null
		menu.findItem(R.id.action_download)?.isVisible = viewModel.getContentOrNull()?.isLocal == false
	}

	private fun openReader(isIncognitoMode: Boolean) {
		openDetailsReader(
			context = context,
			viewModel = viewModel,
			router = router,
			isIncognitoMode = isIncognitoMode,
			snackbarHost = buttonRead,
		)
	}

	private fun onHistoryChanged(isLoading: Boolean, info: HistoryInfo) {
		val isChaptersLoading = isLoading && (info.totalChapters <= 0 || info.isChapterMissing)
		
		// 根据内容类型选择合适的文案
		val manga = viewModel.getContentOrNull()
		val source = manga?.source?.unwrap()
		
		// Check if this is LocalEpubSource (NEW ARCHITECTURE)
		val isLocalEpub = source is org.skepsun.kototoro.local.epub.LocalEpubSource
		
		val contentType = when {
			isLocalEpub -> ContentType.NOVEL  // LocalEpubSource is always NOVEL
			source != null -> source.getContentType()
			else -> null
		}
		
		val readText = when (contentType) {
			ContentType.VIDEO, ContentType.HENTAI_VIDEO -> R.string.play // 播放
			ContentType.NOVEL, ContentType.HENTAI_NOVEL -> R.string.read // 阅读
			else -> R.string.read // 默认：阅读
		}
		
		val continueText = when (contentType) {
			ContentType.VIDEO, ContentType.HENTAI_VIDEO -> R.string._continue_play // 继续播放
			else -> R.string._continue // 继续
		}
		
		buttonRead.setText(
			when {
				isChaptersLoading -> R.string.loading_
				info.isIncognitoMode -> R.string.incognito
				info.canContinue -> continueText
				else -> readText
			},
		)
		splitButton.isEnabled = !isChaptersLoading && info.isValid
	}

	private fun Menu.populateBranchList() {
		val branches = viewModel.branches.value
		if (branches.size <= 1) {
			return
		}
		for ((i, branch) in branches.withIndex()) {
			val title = buildSpannedString {
				if (branch.isCurrent) {
					inSpans(
						ImageSpan(
							context,
							R.drawable.ic_current_chapter,
							DynamicDrawableSpan.ALIGN_BASELINE,
						),
					) {
						append(' ')
					}
					append(' ')
				}
				append(branch.name ?: context.getString(R.string.system_default))
				append(' ')
				append(' ')
				inSpans(
					ForegroundColorSpan(
						context.getThemeColor(
							android.R.attr.textColorSecondary,
							Color.LTGRAY,
						),
					),
					RelativeSizeSpan(0.74f),
				) {
					append(branch.count.toString())
				}
			}
			val item = add(R.id.group_branches, Menu.NONE, i, title)
			item.isCheckable = true
			item.isChecked = branch.isSelected
		}
		setGroupCheckable(R.id.group_branches, true, true)
	}
}
