package org.skepsun.kototoro.entitygraph.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class EntityGraphModelsTest {

	@Test
	fun `repair report counts conflicting source projections`() {
		val report = EntityGraphRepairReport(
			issues = listOf(
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.CONFLICTING_SOURCE_PROJECTIONS,
					entityId = 1L,
					localMangaId = 11L,
				),
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.CONFLICTING_SOURCE_PROJECTIONS,
					entityId = 1L,
					localMangaId = 12L,
				),
			),
		)

		assertEquals(1, report.conflictingSourceProjectionsEntityCount)
		assertEquals(2, report.conflictingSourceProjectionsCount)
	}

	@Test
	fun `combined projection repair count does not count the same entity twice`() {
		val report = EntityGraphRepairReport(
			issues = listOf(
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.DUPLICATE_LOCAL_PROJECTIONS,
					entityId = 1L,
					count = 2,
				),
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.CONFLICTING_SOURCE_PROJECTIONS,
					entityId = 1L,
				),
			),
		)

		assertEquals(1, report.localProjectionRepairEntityCount)
		assertEquals(3, report.localProjectionRepairCount)
		assertEquals(2, report.localProjectionRepairGroupCount)
	}

	@Test
	fun `cross entity duplicate contributes to duplicate repair counts`() {
		val report = EntityGraphRepairReport(
			issues = listOf(
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.CROSS_ENTITY_DUPLICATE_LOCAL_PROJECTIONS,
					entityId = 9L,
					count = 2,
				),
			),
		)

		assertEquals(1, report.duplicateLocalProjectionsEntityCount)
		assertEquals(2, report.duplicateLocalProjectionsCount)
		assertEquals(1, report.localProjectionRepairEntityCount)
		assertEquals(2, report.localProjectionRepairCount)
		assertEquals(1, report.localProjectionRepairGroupCount)
	}

	@Test
	fun `dangling local projection binding contributes to projection repair counts`() {
		val report = EntityGraphRepairReport(
			issues = listOf(
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.DANGLING_LOCAL_PROJECTION_BINDING,
					entityId = 9L,
					localMangaId = 101L,
				),
			),
		)

		assertEquals(1, report.duplicateLocalProjectionsEntityCount)
		assertEquals(1, report.duplicateLocalProjectionsCount)
		assertEquals(1, report.localProjectionRepairEntityCount)
		assertEquals(1, report.localProjectionRepairCount)
		assertEquals(1, report.localProjectionRepairGroupCount)
	}

	@Test
	fun `repair report counts mixed content type entities once`() {
		val report = EntityGraphRepairReport(
			issues = listOf(
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.MIXED_WORK_CONTENT_TYPES,
					entityId = 7L,
					localMangaId = 101L,
				),
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.MIXED_WORK_CONTENT_TYPES,
					entityId = 7L,
					localMangaId = 102L,
				),
			),
		)
		assertEquals(1, report.mixedWorkContentTypeEntityCount)
		assertEquals(2, report.mixedWorkContentTypeProjectionCount)
	}

	@Test
	fun `repair report counts duplicate local projections entities and count correctly`() {
		val report = EntityGraphRepairReport(
			issues = listOf(
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.DUPLICATE_LOCAL_PROJECTIONS,
					entityId = 7L,
					localMangaId = 101L,
				),
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.DUPLICATE_LOCAL_PROJECTIONS,
					entityId = 7L,
					localMangaId = 102L,
				),
				EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.DUPLICATE_LOCAL_PROJECTIONS,
					entityId = 8L,
					localMangaId = 201L,
				),
			),
		)
		assertEquals(2, report.duplicateLocalProjectionsEntityCount)
		assertEquals(3, report.duplicateLocalProjectionsCount)
	}

	@Test
	fun `strict title key ignores case and punctuation`() {
		assertEquals(
			normalizeStrictTitleKey("Kami wa Game ni Ueteiru"),
			normalizeStrictTitleKey("KAMI WA GAME NI UETEIRU!!!"),
		)
		assertEquals(
			normalizeStrictTitleKey("SPY FAMILY"),
			normalizeStrictTitleKey("SPY x FAMILY".replace(" x ", "\u00d7")),
		)
	}

	@Test
	fun `strict title key still keeps text boundaries`() {
		assertNotEquals(
			normalizeStrictTitleKey("AB"),
			normalizeStrictTitleKey("A B"),
		)
	}

	@Test
	fun `strict title key strips matching trailing source suffix`() {
		assertEquals(
			normalizeStrictTitleKey("作品"),
			normalizeStrictTitleKey("作品 (YKMH)", listOf("YKMH")),
		)
		assertEquals(
			normalizeStrictTitleKey("作品"),
			normalizeStrictTitleKey("作品（YKMH）", listOf("YKMH")),
		)
	}

	@Test
	fun `strict title key keeps non source trailing parentheses`() {
		assertEquals(
			normalizeStrictTitleKey("作品 (OVA)"),
			normalizeStrictTitleKey("作品 (OVA)", listOf("YKMH")),
		)
	}

	@Test
	fun `entity name normalization preserves unicode letters and numbers`() {
		assertEquals("сутінковазона", normalizeEntityName("Сутінкова зона"))
		assertEquals("الحب123", normalizeEntityName("الحب 123"))
		assertEquals("作品42", normalizeEntityName("作品 #42"))
	}
}
