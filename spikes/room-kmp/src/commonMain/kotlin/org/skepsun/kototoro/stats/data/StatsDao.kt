package org.skepsun.kototoro.stats.data

import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.room.RoomRawQuery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.skepsun.kototoro.core.db.entity.MangaEntity

@Dao
abstract class StatsDao {

    @Query("SELECT * FROM stats WHERE manga_id = :mangaId ORDER BY started_at")
    abstract suspend fun findAll(mangaId: Long): List<StatsEntity>

    @Query("SELECT IFNULL(SUM(pages), 0) FROM stats WHERE manga_id = :mangaId")
    abstract suspend fun getReadPagesCount(mangaId: Long): Int

    @Query("SELECT IFNULL(SUM(duration) / SUM(pages), 0) FROM stats WHERE manga_id = :mangaId")
    abstract suspend fun getAverageTimePerPage(mangaId: Long): Long

    @Query("SELECT IFNULL(SUM(duration) / SUM(pages), 0) FROM stats")
    abstract suspend fun getAverageTimePerPage(): Long

    @Query("DELETE FROM stats")
    abstract suspend fun clear()

    @Query("DELETE FROM stats WHERE manga_id = :mangaId")
    abstract suspend fun deleteByMangaId(mangaId: Long): Int

    @Query("SELECT COUNT(*) FROM stats WHERE manga_id = :mangaId")
    abstract fun observeRowCount(mangaId: Long): Flow<Int>

    @Upsert
    abstract suspend fun upsert(entity: StatsEntity)

    suspend fun getDurationStats(
        fromDate: Long,
        isNsfw: Boolean?,
        favouriteCategories: Set<Long>,
    ): Map<MangaEntity, Long> {
        val conditions = ArrayList<String>()
        conditions.add("(SELECT deleted_at FROM history WHERE history.manga_id = stats.manga_id) = 0")
        conditions.add("stats.started_at >= $fromDate")
        if (favouriteCategories.isNotEmpty()) {
            val ids = favouriteCategories.joinToString(",")
            conditions.add("stats.manga_id IN (SELECT manga_id FROM favourites WHERE category_id IN ($ids))")
        }
        if (isNsfw != null) {
            val flag = if (isNsfw) 1 else 0
            conditions.add("manga.nsfw = $flag")
        }
        val where = conditions.joinToString(separator = " AND ")
        val query = RoomRawQuery(
            "SELECT manga.*, SUM(duration) AS d FROM stats LEFT JOIN manga ON manga.manga_id = stats.manga_id WHERE $where GROUP BY manga.manga_id ORDER BY d DESC",
        )
        return getDurationStatsImpl(query)
    }

    @RawQuery
    protected abstract suspend fun getDurationStatsImpl(
        query: RoomRawQuery,
    ): Map<@MapColumn("manga") MangaEntity, @MapColumn("d") Long>

    @Query("SELECT * FROM stats ORDER BY started_at LIMIT :limit OFFSET :offset")
    protected abstract suspend fun findAll(offset: Int, limit: Int): List<StatsEntity>

    fun dump(): Flow<StatsEntity> = dumpEnabled()

    fun dumpEnabled(): Flow<StatsEntity> = flow {
        val window = 10
        var offset = 0
        while (currentCoroutineContext().isActive) {
            val list = findAll(offset, window)
            if (list.isEmpty()) {
                break
            }
            offset += window
            list.forEach { emit(it) }
        }
    }
}
