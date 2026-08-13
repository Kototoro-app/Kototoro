package org.skepsun.kototoro.core.network.webview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserSessionProfileStoreTest {

	@Test
	fun `missing or older profile requires migration`() {
		assertTrue(BrowserSessionProfileStore.requiresMigration(0, 3))
		assertTrue(BrowserSessionProfileStore.requiresMigration(2, 3))
	}

	@Test
	fun `current or newer profile does not require migration`() {
		assertFalse(BrowserSessionProfileStore.requiresMigration(3, 3))
		assertFalse(BrowserSessionProfileStore.requiresMigration(4, 3))
	}
}
