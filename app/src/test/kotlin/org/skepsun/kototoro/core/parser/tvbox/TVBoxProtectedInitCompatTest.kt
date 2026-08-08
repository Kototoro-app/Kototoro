package org.skepsun.kototoro.core.parser.tvbox

import android.app.Application
import android.content.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class TVBoxProtectedInitCompatTest {

	@Test
	fun `legacy compatible c field takes priority`() {
		val field = TVBoxProtectedInitCompat.findContextField(LegacyInit::class.java, Application::class.java)

		assertEquals("c", field?.name)
	}

	@Test
	fun `incompatible c field falls back to application field`() {
		val field = TVBoxProtectedInitCompat.findContextField(CurrentInit::class.java, Application::class.java)

		assertEquals("f", field?.name)
	}

	@Test
	fun `unrelated fields do not receive context`() {
		val field = TVBoxProtectedInitCompat.findContextField(UnsupportedInit::class.java, Application::class.java)

		assertNull(field)
	}

	private class LegacyInit {
		@Suppress("unused")
		private var c: Context? = null

		@Suppress("unused")
		private var application: Application? = null
	}

	private class CurrentInit {
		@Suppress("unused")
		private val c = AtomicBoolean(false)

		@Suppress("unused")
		private var f: Application? = null
	}

	private class UnsupportedInit {
		@Suppress("unused")
		private val c = AtomicBoolean(false)
	}
}
