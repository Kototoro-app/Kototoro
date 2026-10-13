package org.skepsun.kototoro.settings.sources.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceGroupModelsTest {

	private val group = SourceGroup(
		id = "group-1",
		name = "Turkish sources",
		createdAt = 1_700_000_000_000L,
		repos = listOf(
			SourceGroupRepo(type = "JAR", url = "https://example.com/repo", name = "Example"),
		),
		packages = listOf(
			SourceGroupPackage(
				pkg = "kototoro-parsers",
				kind = "jar",
				versionName = null,
				versionCode = 12L,
				repoUrl = "https://example.com/repo",
			),
		),
		sourceStates = listOf(
			SourceGroupSourceState(source = "TRMANGAHUB_1", enabled = true, pinned = true, sortKey = 3),
		),
		jarPriority = listOf("kototoro-parsers", "uma"),
	)

	@Test
	fun `round trip preserves every field`() {
		val decoded = SourceGroupJson.decode(SourceGroupJson.encode(group))
		assertEquals(group, decoded)
	}

	@Test
	fun `unknown keys are ignored on decode`() {
		val text = SourceGroupJson.encode(group).replaceFirst("{", "{\n\"future_field\": 42,")
		assertEquals(group, SourceGroupJson.decode(text))
	}

	@Test
	fun `valid group passes validation`() {
		assertTrue(group.isValid())
	}

	@Test
	fun `unsupported schema is rejected`() {
		assertFalse(group.copy(schema = 2).isValid())
	}

	@Test
	fun `unsafe id is rejected`() {
		assertFalse(group.copy(id = "../evil").isValid())
	}
}
