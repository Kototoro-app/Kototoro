package org.skepsun.kototoro.entitygraph.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class EntityGraphModelsTest {

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
}
