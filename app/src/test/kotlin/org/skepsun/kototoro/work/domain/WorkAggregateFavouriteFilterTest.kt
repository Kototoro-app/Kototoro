package org.skepsun.kototoro.work.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

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

	@Test
	fun `publication state filters use OR within their group`() {
		val content = content(2L, "available", ContentState.PAUSED)
		val filters = setOf(
			ListFilterOption.PublicationState(ContentState.ONGOING),
			ListFilterOption.PublicationState(ContentState.PAUSED),
		)

		assertTrue(content.matchesPublicationStateFilters(filters))
		assertFalse(
			content(3L, "available", ContentState.FINISHED)
				.matchesPublicationStateFilters(filters),
		)
		assertFalse(content(4L, "available", null).matchesPublicationStateFilters(filters))
		assertTrue(content.matchesPublicationStateFilters(emptySet()))
	}

	@Test
	fun `reading status falls back to history progress`() {
		assertEquals(ScrobblingStatus.PLANNED, aggregate().resolveReadingStatus(null))
		assertEquals(ScrobblingStatus.READING, aggregate(percent = 0.5f).resolveReadingStatus(null))
		assertEquals(ScrobblingStatus.COMPLETED, aggregate(percent = 1f).resolveReadingStatus(null))
		assertEquals(
			ScrobblingStatus.ON_HOLD,
			aggregate(percent = 0.5f).resolveReadingStatus(ScrobblingStatus.ON_HOLD),
		)
	}

	@Test
	fun `reading status filters use OR within their group`() {
		val filters = setOf(
			ListFilterOption.ReadingStatus(ScrobblingStatus.READING),
			ListFilterOption.ReadingStatus(ScrobblingStatus.RE_READING),
		)

		assertTrue(ScrobblingStatus.READING.matchesReadingStatusFilters(filters))
		assertTrue(ScrobblingStatus.RE_READING.matchesReadingStatusFilters(filters))
		assertFalse(ScrobblingStatus.COMPLETED.matchesReadingStatusFilters(filters))
		assertTrue(ScrobblingStatus.COMPLETED.matchesReadingStatusFilters(emptySet()))
	}

	@Test
	fun `source filters use OR and match any projection`() {
		val aggregate = aggregate(projectionSources = listOf("primary", "secondary"))
		val filters = setOf(
			ListFilterOption.Source(source("missing")),
			ListFilterOption.Source(source("secondary")),
		)

		assertTrue(aggregate.matchesTagAndSourceFilters(filters))
		assertFalse(
			aggregate.matchesTagAndSourceFilters(setOf(ListFilterOption.Source(source("missing")))),
		)
	}

	@Test
	fun `tag filters use OR and match any projection`() {
		val matchingTag = tag("action", "secondary")
		val aggregate = aggregate(
			projections = listOf(
				content(2L, "primary"),
				content(3L, "secondary", tags = setOf(matchingTag)),
			),
		)
		val filters = setOf(
			ListFilterOption.Tag(tag("missing", "primary")),
			ListFilterOption.Tag(matchingTag),
		)

		assertTrue(aggregate.matchesTagAndSourceFilters(filters))
		assertFalse(
			aggregate.matchesTagAndSourceFilters(
				setOf(ListFilterOption.Tag(tag("action", "primary"))),
			),
		)
	}

	private fun aggregate(
		percent: Float? = null,
		newChapters: Int? = null,
		projectionSources: List<String> = emptyList(),
		projections: List<Content>? = null,
	): WorkAggregate = WorkAggregate(
		identity = WorkIdentity(
			entityId = 1L,
			requestedMangaId = 2L,
			preferredMangaId = 2L,
			localMangaIds = setOf(2L),
			migrationState = WorkMigrationState.VALID,
		),
		displayProjection = projections?.firstOrNull() ?: projectionSources.firstOrNull()?.let { content(2L, it) },
		projections = projections ?: projectionSources.mapIndexed { index, source -> content(index + 2L, source) },
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

	private fun content(
		id: Long,
		sourceName: String,
		state: ContentState? = null,
		tags: Set<ContentTag> = emptySet(),
	): Content = Content(
		id = id,
		title = "Work $id",
		altTitles = emptySet(),
		url = "/$id",
		publicUrl = "https://example.org/$id",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = tags,
		state = state,
		authors = emptySet(),
		source = source(sourceName),
	)

	private fun tag(key: String, sourceName: String) = ContentTag(
		title = key.replaceFirstChar(Char::uppercase),
		key = key,
		source = source(sourceName),
	)

	private fun source(name: String) = object : ContentSource {
		override val name = name
		override val locale = ""
		override val contentType = ContentType.MANGA
	}
}
