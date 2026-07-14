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

class AppSettingsSpaceFeatureDefaultsTest {

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
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic(PreferenceManager::class)
	}

	@Test
	fun `space feature gates are enabled when preferences are absent`() {
		val settings = AppSettings(context)

		settings.isEntitySpaceEnabled shouldBe true
		settings.isSpaceSwitcherEnabled shouldBe true
		settings.isSpacePersistentNavigationEnabled shouldBe true
		settings.isSpaceImmersiveSwitchEnabled shouldBe true
		settings.isSpaceRoutePreferencesEnabled shouldBe true
	}
}
