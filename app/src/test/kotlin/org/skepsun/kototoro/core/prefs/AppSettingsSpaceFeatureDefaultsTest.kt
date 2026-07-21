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
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppSettingsSpaceFeatureDefaultsTest {

	private val context = mockk<Context>()
	private val preferences = mockk<SharedPreferences>()
	private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

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
		every { preferences.edit() } returns editor
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic(PreferenceManager::class)
	}

	@Test
	fun `spaces require explicit opt in while subordinate defaults stay ready`() {
		val settings = AppSettings(context)

		settings.isEntitySpaceEnabled shouldBe false
		settings.hasSeenSpaceOnboarding shouldBe false
		settings.isSpaceSwitcherEnabled shouldBe true
		settings.isSpacePersistentNavigationEnabled shouldBe true
		settings.isSpaceImmersiveSwitchEnabled shouldBe true
		settings.isSpaceRoutePreferencesEnabled shouldBe true
	}

	@Test
	fun `existing installs preserve legacy enabled default and skip onboarding`() {
		every { preferences.getInt(AppSettings.KEY_APP_VERSION, 0) } returns 1201
		val settings = AppSettings(context)

		settings.reconcileAfterAppUpgrade(currentVersion = 1201)

		verify { editor.putBoolean(AppSettings.KEY_ENTITY_SPACE_ENABLED, true) }
		verify { editor.putBoolean(AppSettings.KEY_SPACE_ONBOARDING_SEEN, true) }
	}

	@Test
	fun `existing explicit space choice is not overwritten`() {
		every { preferences.getInt(AppSettings.KEY_APP_VERSION, 0) } returns 1201
		every { preferences.contains(AppSettings.KEY_ENTITY_SPACE_ENABLED) } returns true
		val settings = AppSettings(context)

		settings.reconcileAfterAppUpgrade(currentVersion = 1201)

		verify(exactly = 0) { editor.putBoolean(AppSettings.KEY_ENTITY_SPACE_ENABLED, any()) }
		verify { editor.putBoolean(AppSettings.KEY_SPACE_ONBOARDING_SEEN, true) }
	}
}
