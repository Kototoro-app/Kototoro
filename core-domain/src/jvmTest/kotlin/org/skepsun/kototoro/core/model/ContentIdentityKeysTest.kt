package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentIdentityKeysTest {

	@Test
	fun `bindingKey prefers url over public url`() {
		assertEquals(
			"url:/work",
			ContentIdentityKeys.bindingKey(
				url = " /work ",
				publicUrl = " https://example.test/work ",
			),
		)
	}

	@Test
	fun `bindingKey falls back to public url`() {
		assertEquals(
			"public_url:https://example.test/work",
			ContentIdentityKeys.bindingKey(
				url = " ",
				publicUrl = " https://example.test/work ",
			),
		)
	}

	@Test
	fun `bindingKey returns null when remote identity is missing`() {
		assertNull(ContentIdentityKeys.bindingKey(url = "", publicUrl = " "))
	}

	@Test
	fun `bindingKeys retains url and public url aliases`() {
		assertEquals(
			setOf("url:/work", "public_url:https://example.test/work"),
			ContentIdentityKeys.bindingKeys(
				url = " /work ",
				publicUrl = " https://example.test/work ",
			),
		)
	}

	@Test
	fun `contentCompactKey uses content key before legacy id fallback`() {
		assertEquals(
			"projection:source:url:/work",
			ContentIdentityKeys.contentCompactKey(
				source = "source",
				id = 7L,
				url = "/work",
				publicUrl = "https://example.test/work",
			),
		)
		assertEquals(
			"projection-id:7",
			ContentIdentityKeys.contentCompactKey(
				source = "source",
				id = 7L,
				url = "",
				publicUrl = "",
			),
		)
	}

	@Test
	fun `hasSameIdentity requires same source and matching content key`() {
		assertTrue(
			ContentIdentityKeys.hasSameIdentity(
				source = "source",
				url = "/work",
				publicUrl = "",
				otherSource = "source",
				otherUrl = "/work",
				otherPublicUrl = "",
			),
		)
		assertFalse(
			ContentIdentityKeys.hasSameIdentity(
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
