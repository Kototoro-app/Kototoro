package org.skepsun.kototoro.backups.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceRowRestoreActionTest {

	@Test
	fun `merge without preserve overwrites local source rows`() {
		assertEquals(SourceRowRestoreAction.UPSERT, sourceRowRestoreAction(preserveLocalSources = false))
	}

	@Test
	fun `preserve local sources only inserts missing rows`() {
		assertEquals(SourceRowRestoreAction.INSERT_IF_ABSENT, sourceRowRestoreAction(preserveLocalSources = true))
	}
}
