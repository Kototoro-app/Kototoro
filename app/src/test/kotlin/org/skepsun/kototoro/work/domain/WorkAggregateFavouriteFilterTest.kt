package org.skepsun.kototoro.work.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

class WorkAggregateFavouriteFilterTest {

	@Test
	fun `completed filter requires completed history`() {
		assertTrue(aggregate(percent = 1f).matchesFavouriteMacroFilter(ListFilterOption.Macro.COMPLETED))
		assertFalse(aggregate(percent = 0.5f).matchesFavouriteMacroFilter(ListFilterOption.Macro.COMPLETED))
		assertFalse(aggregate().matchesFavouriteMacroFilter(ListFilterOption.Macro.COMPLETED))
	}

	@Test
	fun `new chapters filter requires positive tracked count`() {
		assertTrue(aggregate(newChapters = 2).matchesFavouriteMacroFilter(ListFilterOption.Macro.NEW_CHAPTERS))
		assertFalse(aggregate(newChapters = 0).matchesFavouriteMacroFilter(ListFilterOption.Macro.NEW_CHAPTERS))
		assertFalse(aggregate().matchesFavouriteMacroFilter(ListFilterOption.Macro.NEW_CHAPTERS))
	}

	@Test
	fun `broken filter matches when any associated projection source is unavailable`() {
		val aggregate = aggregate(projectionSources = listOf("available", "missing"))

		assertTrue(
			aggregate.matchesFavouriteMacroFilter(
				option = ListFilterOption.Macro.BROKEN_PROJECTION,
				brokenProjectionSourceNames = setOf("missing"),
			),
		)
		assertFalse(
			aggregate.matchesFavouriteMacroFilter(
				option = ListFilterOption.Macro.BROKEN_PROJECTION,
				brokenProjectionSourceNames = emptySet(),
			),
		)
	}

	private fun aggregate(
		percent: Float? = null,
		newChapters: Int? = null,
		projectionSources: List<String> = emptyList(),
	): WorkAggregate = WorkAggregate(
		identity = WorkIdentity(
			entityId = 1L,
			requestedMangaId = 2L,
			preferredMangaId = 2L,
			localMangaIds = setOf(2L),
			migrationState = WorkMigrationState.VALID,
		),
		displayProjection = projectionSources.firstOrNull()?.let { content(2L, it) },
		projections = projectionSources.mapIndexed { index, source -> content(index + 2L, source) },
		history = percent?.let {
			WorkHistoryEntity(
				entityId = 1L,
				anchorMangaId = 2L,
				createdAt = 0L,
				updatedAt = 0L,
				chapterId = 0L,
				page = 0,
				scroll = 0f,
				percent = it,
				deletedAt = 0L,
				chaptersCount = 1,
			)
		},
		tracking = newChapters?.let {
			WorkTrackingSummary(
				anchorMangaId = 2L,
				lastChapterId = 0L,
				newChapters = it,
				lastCheckTime = 0L,
				lastChapterDate = 0L,
			)
		},
	)

	private fun content(id: Long, sourceName: String): Content = Content(
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
		source = object : ContentSource {
			override val name = sourceName
			override val locale = ""
			override val contentType = ContentType.MANGA
		},
	)
}
