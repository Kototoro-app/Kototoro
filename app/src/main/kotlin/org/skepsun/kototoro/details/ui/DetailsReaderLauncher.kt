package org.skepsun.kototoro.details.ui

import android.content.Context
import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.getMergeKey
import org.skepsun.kototoro.core.model.isManga
import org.skepsun.kototoro.core.model.mergeRepeated
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.ui.ReaderState

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
		.languages(
			translatedLanguage = viewModel.resolvedReadingLanguage.value,
			sourceLanguage = viewModel.resolvedMetadataLanguage.value,
		)
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
			val useMerge = viewModel.isMergeRepeatedChapters.value && contentType.isManga()
			val details = viewModel.mangaDetails.value
			val chapters = if (useMerge && details != null) {
				val allBranches = details.chapters.keys.toList()
				val rawChapters = allBranches.flatMap { details.chapters[it].orEmpty() }
				rawChapters.mergeRepeated()
			} else {
				manga.chapters?.filter { it.branch == preferredBranch } ?: manga.chapters
			}
			var matchedChapter = chapters?.find { it.id == history.chapterId }

			if (matchedChapter == null && useMerge && details != null) {
				val historyChapter = details.allChapters.find { it.id == history.chapterId }
				if (historyChapter != null) {
					val historyKey = historyChapter.getMergeKey()
					matchedChapter = chapters?.find { it.getMergeKey() == historyKey }
				}
			}

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
						ReaderState(
							chapterId = matchedChapter.id,
							page = 0,
							scroll = 0,
						)
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
