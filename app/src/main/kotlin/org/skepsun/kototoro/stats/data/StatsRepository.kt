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
import org.skepsun.kototoro.entitygraph.data.resolveWorkEntityIdByMangaId
import org.skepsun.kototoro.stats.domain.StatsPeriod
import org.skepsun.kototoro.stats.domain.StatsRecord
import java.util.NavigableMap
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class StatsRepository @Inject constructor(
	private val settings: AppSettings,
	private val db: MangaDatabase,
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
		val dao = db.getStatsDao()
		val entityId = resolveStatsEntityId(mangaId)
		val workPages = entityId?.let { db.getWorkStatsDao().getReadPagesCount(it) } ?: 0
		val pages = if (workPages != 0) workPages else dao.getReadPagesCount(mangaId)
		val time = if (pages >= 10) {
			entityId?.let { db.getWorkStatsDao().getAverageTimePerPage(it) } ?: dao.getAverageTimePerPage(mangaId)
		} else {
			dao.getAverageTimePerPage()
		}
		time
	}

	suspend fun getTotalPagesRead(mangaId: Long): Int {
		val entityId = resolveStatsEntityId(mangaId)
		return entityId?.let { db.getWorkStatsDao().getReadPagesCount(it) }
			?: db.getStatsDao().getReadPagesCount(mangaId)
	}

	suspend fun getContentTimeline(mangaId: Long): NavigableMap<Long, Int> {
		val entityId = resolveStatsEntityId(mangaId)
		val workEntities = entityId?.let { db.getWorkStatsDao().findAll(it) }.orEmpty()
		val map = TreeMap<Long, Int>()
		if (workEntities.isNotEmpty()) {
			for (e in workEntities) {
				map[e.startedAt] = e.pages
			}
			return map
		}
		val entities = db.getStatsDao().findAll(mangaId)
		for (e in entities) {
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
		val entityId = resolveStatsEntityId(mangaId)
		if (entityId != null && db.getWorkStatsDao().getRowCount(entityId) > 0) {
			return true
		}
		return db.getStatsDao().getReadPagesCount(mangaId) > 0
	}

	// The incoming mangaId is a projection/local anchor. When a work/entity exists, stats should
	// read from work-owned aggregates first and only fall back to legacy manga rows as needed.
	private suspend fun resolveStatsEntityId(mangaId: Long): Long? {
		return db.resolveWorkEntityIdByMangaId(mangaId)
	}
}
