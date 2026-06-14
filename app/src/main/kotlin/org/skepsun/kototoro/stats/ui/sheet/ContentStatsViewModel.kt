package org.skepsun.kototoro.stats.ui.sheet

import androidx.collection.MutableIntList
import androidx.collection.emptyIntList
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.model.DateTimeAgo
import org.skepsun.kototoro.core.util.ext.calculateTimeAgo
import org.skepsun.kototoro.stats.data.StatsRepository
import org.skepsun.kototoro.parsers.model.Content
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class ContentStatsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val contentDataRepository: ContentDataRepository,
	private val repository: StatsRepository,
) : BaseViewModel() {

	private val initialManga = savedStateHandle.get<ParcelableContent>(AppRouter.KEY_MANGA)?.manga
	private val mangaState = MutableStateFlow<Content?>(initialManga)
	private var loadJob: Job? = null
	private var initializedMangaId: Long? = null

	val manga: Content
		get() = checkNotNull(mangaState.value) {
			"ContentStatsViewModel is not initialized with a manga"
		}

	val stats = MutableStateFlow(emptyIntList())
	val startDate = MutableStateFlow<DateTimeAgo?>(null)
	val totalPagesRead = MutableStateFlow(0)

	init {
		launchJob(Dispatchers.Default) {
			val resolved = initialManga?.id
				?.takeIf { it != 0L }
				?.let {
					contentDataRepository.findPreferredLocalContentById(it, withChapters = false)
						?: contentDataRepository.findContentById(it, withChapters = false)
				}
				?: initialManga
			resolved?.let(::initialize)
		}
	}

	fun initialize(manga: Content) {
		if (initializedMangaId == manga.id) {
			mangaState.value = manga
			return
		}
		initializedMangaId = manga.id
		mangaState.value = manga
		loadJob?.cancel()
		stats.value = emptyIntList()
		startDate.value = null
		totalPagesRead.value = 0
		loadJob = launchLoadingJob(Dispatchers.Default) {
			val timeline = repository.getContentTimeline(manga.id)
			if (timeline.isEmpty()) {
				startDate.value = null
				stats.value = emptyIntList()
			} else {
				val startDay = TimeUnit.MILLISECONDS.toDays(timeline.firstKey())
				val endDay = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())
				val res = MutableIntList((endDay - startDay).toInt() + 1)
				for (day in startDay..endDay) {
					val from = TimeUnit.DAYS.toMillis(day)
					val to = TimeUnit.DAYS.toMillis(day + 1)
					res.add(timeline.subMap(from, true, to, false).values.sum())
				}
				stats.value = res
				startDate.value = calculateTimeAgo(Instant.ofEpochMilli(timeline.firstKey()))
			}
			totalPagesRead.value = repository.getTotalPagesRead(manga.id)
		}
	}
}
