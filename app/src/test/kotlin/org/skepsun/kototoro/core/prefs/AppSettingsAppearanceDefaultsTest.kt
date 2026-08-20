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

class AppSettingsAppearanceDefaultsTest {

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
		every { preferences.getInt(any(), any()) } answers { secondArg() }
		every { preferences.getString(any(), any()) } answers { secondArg() }
		every { preferences.getStringSet(any(), any()) } answers { (secondArg<Set<String>?>() ?: emptySet()).toMutableSet() }
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic(PreferenceManager::class)
	}

	@Test
	fun `modern details dock is enabled by default`() {
		val settings = AppSettings(context)

		settings.isModernDetailsDockEnabled shouldBe true
	}

	@Test
	fun `panorama transition range is full by default`() {
		val settings = AppSettings(context)

		settings.panoramaTransitionRange shouldBe 100
	}

	@Test
	fun `panorama top opacity defaults to preset value`() {
		val settings = AppSettings(context)

		settings.panoramaTopOpacity shouldBe 90
	}

	@Test
	fun `interface font presets default to Inter`() {
		val settings = AppSettings(context)

		settings.appFontPreset shouldBe AppFontPreset.INTER
		settings.expressiveAppFontPreset shouldBe AppFontPreset.INTER
	}

	@Test
	fun `legacy surface choices normalize to the standard background`() {
		BackgroundStyle.DYNAMIC_TONAL_GLASS.normalized() shouldBe BackgroundStyle.DEFAULT
		BackgroundStyle.SYSTEM_DYNAMIC_TINT.normalized() shouldBe BackgroundStyle.DEFAULT
		BackgroundStyle.ELEVATED_CONTAINERS.normalized() shouldBe BackgroundStyle.DEFAULT
		BackgroundStyle.DYNAMIC_ARTWORK_BLUR.normalized() shouldBe BackgroundStyle.DYNAMIC_ARTWORK_BLUR
	}

	@Test
	fun `only semantic page backgrounds are selectable`() {
		BackgroundStyle.selectableEntries shouldBe listOf(
			BackgroundStyle.DEFAULT,
			BackgroundStyle.DYNAMIC_ARTWORK_BLUR,
		)
	}

	@Test
	fun `legacy layered background is recognized as a navigation surface`() {
		BackgroundStyle.ELEVATED_CONTAINERS.usesLayeredNavigationSurface shouldBe true
		BackgroundStyle.DEFAULT.usesLayeredNavigationSurface shouldBe false
		BackgroundStyle.DYNAMIC_ARTWORK_BLUR.usesLayeredNavigationSurface shouldBe false
	}
}
