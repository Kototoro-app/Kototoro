package org.skepsun.kototoro.stats.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

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

}
