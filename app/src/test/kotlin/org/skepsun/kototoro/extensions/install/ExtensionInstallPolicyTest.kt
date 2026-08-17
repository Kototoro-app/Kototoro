package org.skepsun.kototoro.extensions.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionInstallPolicyTest {

	@Test
	fun `policies survive storage round trip`() {
		val policies = mapOf(
			"LEGADO" to ExtensionInstallPolicy.INSTALL_AND_ENABLE,
			"LNREADER" to ExtensionInstallPolicy.INSTALL_ONLY,
		)

		assertEquals(policies, decodeExtensionInstallPolicies(encodeExtensionInstallPolicies(policies)))
	}

	@Test
	fun `invalid stored entries are ignored`() {
		val decoded = decodeExtensionInstallPolicies(
			setOf(
				"LEGADO=INSTALL_AND_ENABLE",
				"LNREADER=REMOVED_POLICY",
				"missing_separator",
			),
		)

		assertEquals(mapOf("LEGADO" to ExtensionInstallPolicy.INSTALL_AND_ENABLE), decoded)
	}
}
