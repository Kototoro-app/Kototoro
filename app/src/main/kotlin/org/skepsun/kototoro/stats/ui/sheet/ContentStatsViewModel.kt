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
import org.skepsun.kototoro.stats.domain.StatsContentKind
import org.skepsun.kototoro.parsers.model.Content
import java.time.Instant
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
	val totalDuration = MutableStateFlow(0L)
	val sessionCount = MutableStateFlow(0)
	val units = MutableStateFlow(0)
	val kind = MutableStateFlow(StatsContentKind.MANGA)

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
		totalDuration.value = 0L
		sessionCount.value = 0
		units.value = 0
		loadJob = launchLoadingJob(Dispatchers.Default) {
			val snapshot = repository.getContentSnapshot(manga)
			stats.value = MutableIntList(snapshot.dailyActivity.size).apply {
				snapshot.dailyActivity.forEach(::add)
			}
			startDate.value = snapshot.firstActivityAt?.let { calculateTimeAgo(Instant.ofEpochMilli(it)) }
			totalDuration.value = snapshot.totalDuration
			sessionCount.value = snapshot.sessionCount
			units.value = snapshot.units
			kind.value = snapshot.kind
		}
	}
}
