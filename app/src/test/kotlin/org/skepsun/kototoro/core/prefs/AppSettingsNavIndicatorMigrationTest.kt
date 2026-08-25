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

/**
 * Guards the one-time migration that folds the mutually exclusive legacy
 * "navigation pill button" / "full-width capsule indicator" booleans into the
 * single [NavIndicatorStyle] plus an independent full-width toggle.
 */
class AppSettingsNavIndicatorMigrationTest {

	private val context = mockk<Context>()
	private val preferences = mockk<SharedPreferences>()
	private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
	private val values = mutableMapOf<String, Any>()

	@BeforeEach
	fun setUp() {
		mockkStatic(PreferenceManager::class)
		every { PreferenceManager.getDefaultSharedPreferences(context) } returns preferences
		every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockk<ConnectivityManager>()
		every { context.resources } returns mockk<Resources> {
			every { getStringArray(any()) } returns emptyArray()
		}
		every { preferences.edit() } returns editor
		every { preferences.contains(any()) } answers { values.containsKey(firstArg<String>()) }
		every { preferences.getBoolean(any(), any()) } answers {
			values[firstArg<String>()] as? Boolean ?: secondArg<Boolean>()
		}
		every { preferences.getInt(any(), any()) } answers {
			values[firstArg<String>()] as? Int ?: secondArg<Int>()
		}
		every { preferences.getLong(any(), any()) } answers {
			values[firstArg<String>()] as? Long ?: secondArg<Long>()
		}
		every { preferences.getFloat(any(), any()) } answers {
			values[firstArg<String>()] as? Float ?: secondArg<Float>()
		}
		every { preferences.getString(any(), any()) } answers {
			values[firstArg<String>()] as? String ?: secondArg<Any?>() as? String
		}
		every { preferences.getStringSet(any(), any()) } answers {
			when (val stored = values[firstArg<String>()]) {
				is Set<*> -> stored.filterIsInstance<String>().toMutableSet()
				else -> secondArg<Set<String>?>() ?: emptySet()
			}
		}
		every { editor.putBoolean(any(), any()) } answers {
			values[firstArg<String>()] = secondArg<Boolean>()
			editor
		}
		every { editor.putString(any(), any()) } answers {
			values[firstArg<String>()] = secondArg<String>()
			editor
		}
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic(PreferenceManager::class)
	}

	private fun freshSettings(): AppSettings {
		values.clear()
		return AppSettings(context)
	}

	@Test
	fun `fresh install has no stored flags and gets labels below, full width off`() {
		val settings = freshSettings()

		settings.navIndicatorStyle shouldBe NavIndicatorStyle.LABELS_BELOW
		settings.isNavFullWidth shouldBe false
		values[AppSettings.KEY_NAV_INDICATOR_STYLE] shouldBe NavIndicatorStyle.LABELS_BELOW.name
		values[AppSettings.KEY_NAV_FULL_WIDTH] shouldBe false
	}

	@Test
	fun `persisted pill with the ios 2_0_1 default full width maps to labels below plus full width`() {
		val settings = freshSettings()
		values[AppSettings.KEY_NAV_EXPRESSIVE_PILL] = true
		// interface style defaults to iOS; the full-width key is absent, which is
		// exactly the 2.0.1 both-on carry-over conflict.

		settings.navIndicatorStyle shouldBe NavIndicatorStyle.LABELS_BELOW
		settings.isNavFullWidth shouldBe true
	}

	@Test
	fun `persisted pill without the ios full-width default maps to labels at the right`() {
		val settings = freshSettings()
		values[AppSettings.KEY_INTERFACE_STYLE] = InterfaceStyle.MATERIAL_3_EXPRESSIVE.name
		values[AppSettings.KEY_NAV_EXPRESSIVE_PILL] = true

		settings.navIndicatorStyle shouldBe NavIndicatorStyle.LABELS_RIGHT
		settings.isNavFullWidth shouldBe false
	}

	@Test
	fun `explicit full width wins even when the pill was also enabled`() {
		val settings = freshSettings()
		values[AppSettings.KEY_NAV_EXPRESSIVE_PILL] = true
		values[AppSettings.KEY_NAV_INDICATOR_FULL_WIDTH] = true

		settings.navIndicatorStyle shouldBe NavIndicatorStyle.LABELS_BELOW
		settings.isNavFullWidth shouldBe true
	}

	@Test
	fun `pill with full width explicitly off maps to labels at the right`() {
		val settings = freshSettings()
		values[AppSettings.KEY_NAV_EXPRESSIVE_PILL] = true
		values[AppSettings.KEY_NAV_INDICATOR_FULL_WIDTH] = false

		settings.navIndicatorStyle shouldBe NavIndicatorStyle.LABELS_RIGHT
		settings.isNavFullWidth shouldBe false
	}

	@Test
	fun `existing new style preference is never overwritten`() {
		val settings = freshSettings()
		values[AppSettings.KEY_NAV_INDICATOR_STYLE] = NavIndicatorStyle.LABELS_RIGHT.name
		values[AppSettings.KEY_NAV_FULL_WIDTH] = true
		values[AppSettings.KEY_NAV_EXPRESSIVE_PILL] = true

		settings.navIndicatorStyle shouldBe NavIndicatorStyle.LABELS_RIGHT
		settings.isNavFullWidth shouldBe true
	}
}
