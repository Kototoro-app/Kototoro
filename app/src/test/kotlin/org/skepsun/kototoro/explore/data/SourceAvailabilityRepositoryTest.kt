package org.skepsun.kototoro.explore.data

import io.mockk.every
import io.mockk.mockk
import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.sync.google.domain.MemorySyncPreferences
import java.io.IOException

class SourceAvailabilityRepositoryTest {

	private val prefs = MemorySyncPreferences()
	private val settings = mockk<AppSettings>().also { every { it.prefs } returns prefs }
	private val repository = SourceAvailabilityRepository(settings)

	@Test
	fun `cloudflare anywhere in the cause chain is CLOUDFLARE and everything else EMPTY`() {
		val cloudFlare = CloudFlareProtectedException("https://example.org/", TestContentSource, Headers.headersOf())
		assertEquals(ContentSourceAvailability.CLOUDFLARE, classifyAvailability(IOException("x", cloudFlare)))
		assertEquals(ContentSourceAvailability.EMPTY, classifyAvailability(IOException("boom")))
	}

	@Test
	fun `setAvailabilities moves names between verdict sets in one batch`() {
		repository.setAvailabilities(
			mapOf(
				"a" to ContentSourceAvailability.AVAILABLE,
				"b" to ContentSourceAvailability.EMPTY,
				"c" to ContentSourceAvailability.CLOUDFLARE,
			),
		)
		assertEquals(setOf("a"), prefs.getStringSet(AppSettings.KEY_AVAILABLE_SOURCES, null))
		assertEquals(setOf("b"), prefs.getStringSet(AppSettings.KEY_EMPTY_SOURCES, null))
		assertEquals(setOf("c"), prefs.getStringSet(AppSettings.KEY_CLOUDFLARE_SOURCES, null))

		repository.setAvailabilities(
			mapOf("a" to ContentSourceAvailability.EMPTY, "b" to ContentSourceAvailability.UNKNOWN),
		)
		assertEquals(emptySet<String>(), prefs.getStringSet(AppSettings.KEY_AVAILABLE_SOURCES, null))
		assertEquals(setOf("a"), prefs.getStringSet(AppSettings.KEY_EMPTY_SOURCES, null))
		assertEquals(setOf("c"), prefs.getStringSet(AppSettings.KEY_CLOUDFLARE_SOURCES, null))
	}

	@Test
	fun `empty batch does not touch the prefs`() {
		repository.setAvailabilities(emptyMap())
		assertEquals(false, prefs.contains(AppSettings.KEY_AVAILABLE_SOURCES))
	}
}
