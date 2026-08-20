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
import org.json.JSONArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppSettingsBackupRestoreCompatTest {

	private val context = mockk<Context>()
	private val preferences = mockk<SharedPreferences>(relaxed = true)
	private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

	@BeforeEach
	fun setUp() {
		mockkStatic(PreferenceManager::class)
		every { PreferenceManager.getDefaultSharedPreferences(context) } returns preferences
		every { context.resources } returns mockk<Resources> {
			every { getStringArray(any()) } returns emptyArray()
		}
		every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockk<ConnectivityManager>()
		every { preferences.edit() } returns editor
		every { preferences.getBoolean(any(), any()) } answers { secondArg() }
		every { preferences.getInt(any(), any()) } answers { secondArg() }
		every { preferences.getLong(any(), any()) } answers { secondArg() }
		every { preferences.getFloat(any(), any()) } answers { secondArg() }
		every { preferences.getString(any(), any()) } answers { secondArg() }
		every { preferences.getStringSet(any(), any()) } answers { (secondArg<Set<String>?>() ?: emptySet()).toMutableSet() }
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic(PreferenceManager::class)
	}

	@Test
	fun `feed limit restored as long is read and normalized as int`() {
		every { preferences.getInt(AppSettings.KEY_FEED_LIMIT, 200) } throws ClassCastException()
		every { preferences.getLong(AppSettings.KEY_FEED_LIMIT, 200L) } returns 75L

		AppSettings(context).feedLimit shouldBe 75

		verify { editor.putInt(AppSettings.KEY_FEED_LIMIT, 75) }
	}

	@Test
	fun `restore retains floating point and string set values`() {
		val settings = AppSettings(context)

		settings.upsertAll(
			mapOf(
				"legacy_float" to 0.75,
				"legacy_set" to JSONArray(listOf("manga", "novel")),
			),
		)

		verify { editor.putFloat("legacy_float", 0.75f) }
		verify { editor.putStringSet("legacy_set", setOf("manga", "novel")) }
	}
}
