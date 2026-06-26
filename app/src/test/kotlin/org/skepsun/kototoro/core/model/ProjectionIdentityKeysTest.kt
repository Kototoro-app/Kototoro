package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectionIdentityKeysTest {

	@Test
	fun `bindingKey prefers url over public url`() {
		assertEquals(
			"url:/work",
			ProjectionIdentityKeys.bindingKey(
				url = " /work ",
				publicUrl = " https://example.test/work ",
			),
		)
	}

	@Test
	fun `bindingKey falls back to public url`() {
		assertEquals(
			"public_url:https://example.test/work",
			ProjectionIdentityKeys.bindingKey(
				url = " ",
				publicUrl = " https://example.test/work ",
			),
		)
	}

	@Test
	fun `bindingKey returns null when remote identity is missing`() {
		assertNull(ProjectionIdentityKeys.bindingKey(url = "", publicUrl = " "))
	}

	@Test
	fun `contentCompactKey uses projection key before legacy id fallback`() {
		assertEquals(
			"projection:source:url:/work",
			ProjectionIdentityKeys.contentCompactKey(
				source = "source",
				id = 7L,
				url = "/work",
				publicUrl = "https://example.test/work",
			),
		)
		assertEquals(
			"projection-id:7",
			ProjectionIdentityKeys.contentCompactKey(
				source = "source",
				id = 7L,
				url = "",
				publicUrl = "",
			),
		)
	}

	@Test
	fun `hasSameIdentity requires same source and matching projection key`() {
		assertTrue(
			ProjectionIdentityKeys.hasSameIdentity(
				source = "source",
				url = "/work",
				publicUrl = "",
				otherSource = "source",
				otherUrl = "/work",
				otherPublicUrl = "",
			),
		)
		assertFalse(
			ProjectionIdentityKeys.hasSameIdentity(
				source = "source",
				url = "/work",
				publicUrl = "",
				otherSource = "other",
				otherUrl = "/work",
				otherPublicUrl = "",
			),
		)
	}
}
