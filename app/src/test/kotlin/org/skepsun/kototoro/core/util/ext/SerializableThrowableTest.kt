package org.skepsun.kototoro.core.util.ext

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SerializableThrowableTest {

	@Test
	fun `keeps fully serializable throwable`() {
		val error = IllegalStateException("broken")

		assertSame(error, error.toSerializableThrowable())
	}

	@Test
	fun `snapshots throwable with non serializable fields`() {
		val error = NonSerializableError()

		val result = error.toSerializableThrowable()

		assertTrue(result.isSerializable())
		assertEquals("${NonSerializableError::class.java.name}: broken", result.message)
		assertEquals(error.stackTrace.toList(), result.stackTrace.toList())
	}

	private class NonSerializableError : Exception("broken") {
		@Suppress("unused")
		private val payload = Any()
	}
}
