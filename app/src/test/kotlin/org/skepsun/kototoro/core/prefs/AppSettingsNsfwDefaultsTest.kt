package org.skepsun.kototoro.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.ConnectivityManager
import androidx.preference.PreferenceManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppSettingsNsfwDefaultsTest {

	private val context = mockk<Context>()
	private val preferences = mockk<SharedPreferences>()

	@BeforeEach
	fun setUp() {
		mockkStatic(PreferenceManager::class)
		every { PreferenceManager.getDefaultSharedPreferences(context) } returns preferences
		every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockk<ConnectivityManager>()
		every { context.resources } returns mockk<Resources> {
			every { getStringArray(any()) } returns emptyArray()
		}
		every { preferences.contains(any()) } returns false
		every { preferences.getBoolean(any(), any()) } answers { secondArg() }
		every { preferences.getString(any(), any()) } answers { secondArg() }
		every { preferences.getStringSet(any(), any()) } returns mutableSetOf()
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic(PreferenceManager::class)
	}

	@Test
	fun `adult content hiding is disabled when preferences are absent`() {
		val settings = AppSettings(context)

		settings.isNsfwContentDisabled shouldBe false
		settings.isHistoryExcludeNsfw shouldBe false
		settings.isFavouritesExcludeNsfw shouldBe false
		settings.isFeedExcludeNsfw shouldBe false
		settings.isTrackerNsfwDisabled shouldBe false
		settings.isSuggestionsExcludeNsfw shouldBe false
		settings.isDiscordRpcSkipNsfw shouldBe false
	}
}
