package org.skepsun.kototoro.core.network.cookies

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SensitiveValueFingerprintTest {

	@Test
	fun `fingerprint is stable and does not expose the value`() {
		val value = "secret-clearance-value"
		val fingerprint = sensitiveValueFingerprint(value)

		assertEquals(fingerprint, sensitiveValueFingerprint(value))
		assertEquals("sha256=664f80849c98,length=22", fingerprint)
		assertFalse(fingerprint.contains(value))
	}

	@Test
	fun `different values and empty state remain distinguishable`() {
		assertNotEquals(sensitiveValueFingerprint("first"), sensitiveValueFingerprint("second"))
		assertEquals("<empty>", sensitiveValueFingerprint(null))
		assertEquals("<empty>", sensitiveValueFingerprint(""))
	}
}
