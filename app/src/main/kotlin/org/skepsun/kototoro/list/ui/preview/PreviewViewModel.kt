package org.skepsun.kototoro.list.ui.preview

import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.text.getSpans
import androidx.core.text.parseAsHtml
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.require
import org.skepsun.kototoro.core.util.ext.sanitize
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val mangaListMapper: ContentListMapper,
	private val repositoryFactory: ContentRepository.Factory,
	private val contentDataRepository: ContentDataRepository,
	private val historyRepository: HistoryRepository,
	private val imageGetter: Html.ImageGetter,
) : BaseViewModel() {

	private val seed = savedStateHandle.require<ParcelableContent>(AppRouter.KEY_MANGA).manga
	private val intent = ContentIntent(savedStateHandle)
	val manga = MutableStateFlow(
		seed,
	)
	private val observedLocalMangaId = MutableStateFlow<Long?>(intent.mangaId.takeIf { it != 0L } ?: seed.id)

	val footer = combine(
		manga,
		observedLocalMangaId.flatMapLatest { mangaId ->
			if (mangaId == null) {
				flowOf(null)
			} else {
				historyRepository.observeOne(mangaId)
			}
		},
		manga.flatMapLatest { historyRepository.observeShouldSkip(it) }.distinctUntilChanged(),
	) { m, history, incognito ->
		if (m.chapters == null) {
			return@combine null
		}
		val b = m.getPreferredBranch(history)
		val chapters = m.getChapters(b)
		FooterInfo(
			percent = history?.percent ?: PROGRESS_NONE,
			currentChapter = history?.chapterId?.let {
				chapters.indexOfFirst { x -> x.id == it }
			} ?: -1,
			totalChapters = chapters.size,
			isIncognito = incognito,
		)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, null)

	val description = manga
		.distinctUntilChangedBy { it.description.orEmpty() }
		.transformLatest {
			val description = it.description
			if (description.isNullOrEmpty()) {
				emit(null)
			} else {
				emit(description.parseAsHtml().filterSpans().sanitize())
				emit(description.parseAsHtml(imageGetter = imageGetter).filterSpans())
			}
		}.combine(isLoading) { desc, loading ->
			if (loading) null else desc ?: ""
		}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), null)

	val tagsChips = manga.map {
		mangaListMapper.mapTags(it.tags)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	init {
		launchLoadingJob(Dispatchers.Default) {
			val current = resolveCurrentContent()
				?: throw IllegalStateException("Unable to resolve preview content context")
			observedLocalMangaId.value = current.id
			val repo = repositoryFactory.create(current.source)
			manga.value = repo.getDetails(current).also { details ->
				observedLocalMangaId.value = details.id
			}
		}
	}

	private suspend fun resolveCurrentContent(): org.skepsun.kototoro.parsers.model.Content? {
		val resolved = contentDataRepository.resolveIntent(intent, withChapters = true)
		if (resolved != null) {
			return resolved
		}
		return seed.takeIf { intent.mangaId == 0L }
	}

	private fun Spanned.filterSpans(): CharSequence {
		val spannable = SpannableString.valueOf(this)
		val spans = spannable.getSpans<ForegroundColorSpan>()
		for (span in spans) {
			spannable.removeSpan(span)
		}
		return spannable.trim()
	}

	data class FooterInfo(
		val currentChapter: Int,
		val totalChapters: Int,
		val isIncognito: Boolean,
		val percent: Float,
	) {

		fun isInProgress() = currentChapter >= 0
	}
}
