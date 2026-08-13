package org.skepsun.kototoro.stats.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class StatsDashboardTest {

	@Test
	fun `content types use media-specific statistics groups`() {
		ContentType.MANGA.toStatsContentKind() shouldBe StatsContentKind.MANGA
		ContentType.MANHWA.toStatsContentKind() shouldBe StatsContentKind.MANGA
		ContentType.NOVEL.toStatsContentKind() shouldBe StatsContentKind.NOVEL
		ContentType.HENTAI_NOVEL.toStatsContentKind() shouldBe StatsContentKind.NOVEL
		ContentType.VIDEO.toStatsContentKind() shouldBe StatsContentKind.VIDEO
		ContentType.HENTAI_VIDEO.toStatsContentKind() shouldBe StatsContentKind.VIDEO
	}

	@Test
	fun `streak includes today and consecutive previous days`() {
		val today = LocalDate.of(2026, 8, 13)
		val activeDates = setOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(4))

		calculateCurrentStatsStreak(activeDates, today) shouldBe 3
	}

	@Test
	fun `streak remains current when user has not read yet today`() {
		val today = LocalDate.of(2026, 8, 13)
		val activeDates = setOf(today.minusDays(1), today.minusDays(2))

		calculateCurrentStatsStreak(activeDates, today) shouldBe 2
	}

	@Test
	fun `streak is zero after a full inactive day`() {
		val today = LocalDate.of(2026, 8, 13)
		val activeDates = setOf(today.minusDays(2), today.minusDays(3))

		calculateCurrentStatsStreak(activeDates, today) shouldBe 0
	}

	@Test
	fun `reading session duration is split across local hour boundaries`() {
		val zoneId = ZoneId.of("Asia/Shanghai")
		val start = ZonedDateTime.of(2026, 8, 13, 10, 30, 0, 0, zoneId).toInstant().toEpochMilli()
		val end = ZonedDateTime.of(2026, 8, 13, 12, 15, 0, 0, zoneId).toInstant().toEpochMilli()

		val activity = calculateHourlyStatsActivity(listOf(start to end), zoneId)

		activity[10] shouldBe TimeUnit.MINUTES.toMillis(30)
		activity[11] shouldBe TimeUnit.HOURS.toMillis(1)
		activity[12] shouldBe TimeUnit.MINUTES.toMillis(15)
		activity.sum() shouldBe end - start
	}
}
