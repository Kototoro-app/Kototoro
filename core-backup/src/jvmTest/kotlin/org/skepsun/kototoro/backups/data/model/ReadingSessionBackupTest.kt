package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.stats.data.StatsEntity

class ReadingSessionBackupTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `old statistics decode without inventing a reading session`() {
        val value = json.decodeFromString<StatisticBackup>(
            """{"manga_id":9007199254740993,"started_at":1000,"duration":20000,"pages":3}""",
        )
        assertNull(value.session)
        assertEquals(9007199254740993L, value.mangaId)
        assertEquals(20_000L, value.duration)
    }

    @Test
    fun `new statistics preserve legacy counters and round trip session positions`() {
        val record = ReadingRecordEntity(mangaId = 9007199254740993, startAt = 1000, endAt = 31_000,
            startChapterId = 10, startPage = 1, startScroll = 4, endChapterId = 11, endPage = 3,
            endScroll = 8, startPercent = 0.2f, endPercent = 0.4f)
        val value = StatisticBackup(StatsEntity(record.mangaId, record.startAt, 20_000, 3), record)
        val encoded = json.encodeToString(StatisticBackup.serializer(), value)
        val restored = json.decodeFromString<StatisticBackup>(encoded)
        assertEquals(record, restored.session!!.toEntity(restored.mangaId, restored.startedAt))
        val legacy = json.decodeFromString<LegacyStatistic>(encoded)
        assertEquals(record.mangaId, legacy.mangaId)
        assertEquals(30_000L, legacy.duration)
        assertEquals(3, legacy.pages)
    }

    @Serializable
    private data class LegacyStatistic(
        @SerialName("manga_id") val mangaId: Long,
        @SerialName("started_at") val startedAt: Long,
        val duration: Long,
        val pages: Int,
    )
}
