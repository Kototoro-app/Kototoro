package org.skepsun.kototoro.core.nav

import androidx.lifecycle.SavedStateHandle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ContentListSourceResolverTest {

	@BeforeEach
	fun setUp() {
		PendingContentListNavigation.clear()
	}

	@AfterEach
	fun tearDown() {
		PendingContentListNavigation.clear()
	}

	@Test
	fun `saved state KEY_SOURCE wins over pending navigation`() {
		PendingContentListNavigation.setSource("PENDING_SOURCE")
		val handle = SavedStateHandle(mapOf(AppRouter.KEY_SOURCE to "SAVED_SOURCE"))

		assertEquals(
			"SAVED_SOURCE",
			resolveContentListSourceName(handle, consumePending = true),
		)
		// Consume must not have run: the saved state already provided the name.
		assertEquals("PENDING_SOURCE", PendingContentListNavigation.peekSourceName())
	}

	@Test
	fun `legacy sourceName key is honoured`() {
		val handle = SavedStateHandle(mapOf("sourceName" to "LEGACY_SOURCE"))

		assertEquals(
			"LEGACY_SOURCE",
			resolveContentListSourceName(handle, consumePending = false),
		)
	}

	@Test
	fun `falls back to pending navigation with peek when consumePending is false`() {
		PendingContentListNavigation.setSource("PENDING_SOURCE")

		assertEquals(
			"PENDING_SOURCE",
			resolveContentListSourceName(SavedStateHandle(), consumePending = false),
		)
		assertEquals("PENDING_SOURCE", PendingContentListNavigation.peekSourceName())
	}

	@Test
	fun `falls back to pending navigation and consumes it exactly once when consumePending is true`() {
		PendingContentListNavigation.setSource("PENDING_SOURCE")

		assertEquals(
			"PENDING_SOURCE",
			resolveContentListSourceName(SavedStateHandle(), consumePending = true),
		)
		assertNull(PendingContentListNavigation.peekSourceName())
	}

	@Test
	fun `returns null when neither saved state nor pending navigation has a source`() {
		assertNull(resolveContentListSourceName(SavedStateHandle(), consumePending = false))
	}
}
