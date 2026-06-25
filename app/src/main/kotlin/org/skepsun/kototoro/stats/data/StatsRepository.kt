package org.skepsun.kototoro.stats.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.stats.domain.StatsPeriod
import org.skepsun.kototoro.stats.domain.StatsRecord
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import org.skepsun.kototoro.work.domain.WorkResolver
import java.util.NavigableMap
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class StatsRepository @Inject constructor(
	private val settings: AppSettings,
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
	private val workAggregateRepository: WorkAggregateRepository,
) {

	suspend fun getReadingStats(period: StatsPeriod, categories: Set<Long>): List<StatsRecord> {
		val fromDate = if (period == StatsPeriod.ALL) {
			0L
		} else {
			System.currentTimeMillis() - TimeUnit.DAYS.toMillis(period.days.toLong())
		}
		val stats = db.getWorkStatsDao().getDurationStats(fromDate, null, categories)
		val result = ArrayList<StatsRecord>(stats.size)
		var other = StatsRecord(null, 0)
		val total = stats.values.sum()
		for ((mangaEntity, duration) in stats) {
			val manga = mangaEntity.toContent(emptySet(), null)
			val percent = duration.toDouble() / total
			if (percent < 0.05) {
				other = other.copy(duration = other.duration + duration)
			} else {
				result += StatsRecord(
					manga = manga,
					duration = duration,
				)
			}
		}
		if (other.duration != 0L) {
			result += other
		}
		return result
	}

	suspend fun getTimePerPage(mangaId: Long): Long = db.withTransaction {
		val aggregate = workAggregateRepository.findAggregateByMangaId(mangaId) ?: return@withTransaction 0L
		val pages = aggregate.stats?.totalPages ?: 0
		val time = if (pages >= 10) {
			aggregate.stats?.averageTimePerPage ?: 0L
		} else {
			db.getWorkStatsDao().getAverageTimePerPage()
		}
		time
	}

	suspend fun getTotalPagesRead(mangaId: Long): Int {
		return workAggregateRepository.findAggregateByMangaId(mangaId)?.stats?.totalPages ?: 0
	}

	suspend fun getContentTimeline(mangaId: Long): NavigableMap<Long, Int> {
		val entityId = resolveStatsEntityId(mangaId) ?: return TreeMap()
		val workEntities = db.getWorkStatsDao().findAll(entityId)
		val map = TreeMap<Long, Int>()
		for (e in workEntities) {
			map[e.startedAt] = e.pages
		}
		return map
	}

	suspend fun clearStats() {
		db.getWorkStatsDao().clear()
		db.getStatsDao().clear()
	}

	fun observeHasStats(mangaId: Long): Flow<Boolean> = settings.observeAsFlow(AppSettings.KEY_STATS_ENABLED) {
		isStatsEnabled
	}.flatMapLatest { isEnabled ->
		if (isEnabled) {
			flowOf(hasStats(mangaId))
		} else {
			flowOf(false)
		}
	}.distinctUntilChanged()

	private suspend fun hasStats(mangaId: Long): Boolean {
		return (workAggregateRepository.findAggregateByMangaId(mangaId)?.stats?.entryCount ?: 0) > 0
	}

	// The incoming mangaId is a projection/local anchor. User-visible stats are work-owned.
	private suspend fun resolveStatsEntityId(mangaId: Long): Long? {
		return workResolver.resolveByMangaId(mangaId).entityId
	}
}
