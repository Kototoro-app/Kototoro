package org.skepsun.kototoro.tracker.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import java.time.Instant

class TrackingLogItemMapperTest {

	@Test
	fun `fromAllTrackedContent keeps latest ten chapter titles in newest-first order`() {
		val track = contentTracking(
			mangaId = 42L,
			newChapters = 12,
			lastChapterDate = Instant.parse("2026-07-01T10:00:00Z"),
		)
		val chapters = (1..12).map { index ->
			chapter(mangaId = 42L, index = index)
		}

		val result = TrackingLogItemMapper.fromAllTrackedContent(listOf(track), chapters)

		result.size shouldBe 1
		result.single().chapters shouldContainExactly (12 downTo 3).map { "Chapter $it" }
		result.single().count shouldBe 12
		result.single().isNew shouldBe true
		result.single().createdAt shouldBe Instant.parse("2026-07-01T10:00:00Z")
	}

	@Test
	fun `fromAllTrackedContent uses check time then epoch when chapter date is missing`() {
		val checkedTrack = contentTracking(
			mangaId = 1L,
			newChapters = 0,
			lastCheck = Instant.parse("2026-07-01T09:00:00Z"),
			lastChapterDate = null,
		)
		val undatedTrack = contentTracking(
			mangaId = 2L,
			newChapters = 0,
			lastCheck = null,
			lastChapterDate = null,
		)

		val result = TrackingLogItemMapper.fromAllTrackedContent(
			tracks = listOf(checkedTrack, undatedTrack),
			chapters = emptyList(),
		)

		result.map { it.createdAt } shouldContainExactly listOf(
			Instant.parse("2026-07-01T09:00:00Z"),
			Instant.EPOCH,
		)
		result.map { it.isNew } shouldContainExactly listOf(false, false)
		result.map { it.count } shouldContainExactly listOf(0, 0)
	}

	@Test
	fun `fromAllTrackedContent preserves work identity fields and synthetic log id`() {
		val track = contentTracking(
			mangaId = 7L,
			entityId = 70L,
			preferredLocalMangaId = 700L,
			newChapters = 1,
		)

		val result = TrackingLogItemMapper.fromAllTrackedContent(listOf(track), emptyList()).single()

		result.id shouldBe -7L
		result.anchorMangaId shouldBe 7L
		result.entityId shouldBe 70L
		result.preferredLocalMangaId shouldBe 700L
		result.manga.id shouldBe 7L
	}

	@Test
	fun `fromAllTrackedContent matches unread state by work owner`() {
		val workTrack = contentTracking(
			mangaId = 7L,
			entityId = 70L,
			newChapters = 1,
		)
		val legacyTrack = contentTracking(
			mangaId = 8L,
			entityId = null,
			newChapters = 1,
		)

		val result = TrackingLogItemMapper.fromAllTrackedContent(
			tracks = listOf(workTrack, legacyTrack),
			chapters = emptyList(),
			unreadOwnerIds = setOf(70L, -8L),
		)

		result.map { it.isNew } shouldContainExactly listOf(true, true)
	}

	@Test
	fun `fromAllTrackedContent does not treat manga id as unread work owner`() {
		val track = contentTracking(
			mangaId = 7L,
			entityId = 70L,
			newChapters = 1,
		)

		val result = TrackingLogItemMapper.fromAllTrackedContent(
			tracks = listOf(track),
			chapters = emptyList(),
			unreadOwnerIds = setOf(7L),
		)

		result.single().isNew shouldBe false
	}

	private fun contentTracking(
		mangaId: Long,
		entityId: Long? = null,
		preferredLocalMangaId: Long? = null,
		newChapters: Int,
		lastCheck: Instant? = Instant.parse("2026-07-01T08:00:00Z"),
		lastChapterDate: Instant? = Instant.parse("2026-07-01T08:30:00Z"),
	): ContentTracking {
		return ContentTracking(
			anchorMangaId = mangaId,
			entityId = entityId,
			preferredLocalMangaId = preferredLocalMangaId,
			manga = content(mangaId),
			lastChapterId = mangaId * 100,
			lastCheck = lastCheck,
			lastChapterDate = lastChapterDate,
			newChapters = newChapters,
		)
	}

	private fun content(id: Long): Content {
		return Content(
			id = id,
			title = "Work $id",
			altTitles = emptySet(),
			url = "/$id",
			publicUrl = "https://example.org/$id",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = TestContentSource,
		)
	}

	private fun chapter(
		mangaId: Long,
		index: Int,
	): ChapterEntity {
		return ChapterEntity(
			chapterId = index.toLong(),
			mangaId = mangaId,
			title = "Chapter $index",
			number = index.toFloat(),
			volume = 0,
			url = "/$mangaId/$index",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = TestContentSource.name,
			index = index,
		)
	}

	private object TestContentSource : ContentSource {
		override val name: String = "test"
		override val locale: String = "en"
		override val contentType: ContentType = ContentType.MANGA
	}
}
