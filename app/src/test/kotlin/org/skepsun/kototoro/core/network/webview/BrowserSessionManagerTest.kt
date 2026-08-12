package org.skepsun.kototoro.core.network.webview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserSessionManagerTest {
	@Test
	fun `unknown session cannot be attached or detached`() {
		val manager = BrowserSessionManager()

		assertFalse(manager.isAttached("missing"))
	}
}
