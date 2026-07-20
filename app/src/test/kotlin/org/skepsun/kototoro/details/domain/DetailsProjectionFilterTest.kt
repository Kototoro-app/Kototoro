package org.skepsun.kototoro.details.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

class DetailsProjectionFilterTest {

	@Test
	fun `same content type is allowed without a space restriction`() {
		assertTrue(
			isDetailsProjectionAllowed(
				currentType = ContentType.VIDEO,
				projectionType = ContentType.VIDEO,
				spaceAllowedTypes = null,
			),
		)
	}

	@Test
	fun `different content type is rejected`() {
		assertFalse(
			isDetailsProjectionAllowed(
				currentType = ContentType.VIDEO,
				projectionType = ContentType.MANGA,
				spaceAllowedTypes = null,
			),
		)
	}

	@Test
	fun `different manga subtypes are allowed in the same work`() {
		assertTrue(
			isDetailsProjectionAllowed(
				currentType = ContentType.MANGA,
				projectionType = ContentType.MANHUA,
				spaceAllowedTypes = null,
			),
		)
	}

	@Test
	fun `space restriction is applied after content type matching`() {
		assertFalse(
			isDetailsProjectionAllowed(
				currentType = ContentType.VIDEO,
				projectionType = ContentType.VIDEO,
				spaceAllowedTypes = setOf(ContentType.MANGA),
			),
		)
	}

	@Test
	fun `unknown type is rejected`() {
		assertFalse(
			isDetailsProjectionAllowed(
				currentType = null,
				projectionType = ContentType.VIDEO,
				spaceAllowedTypes = null,
			),
		)
	}
}
