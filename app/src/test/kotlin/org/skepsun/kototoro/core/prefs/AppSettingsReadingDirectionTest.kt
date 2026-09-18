package org.skepsun.kototoro.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.Editor
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

/**
 * Direction preference for the continuous-horizontal reading mode.
 *
 * It is a preference of its own rather than a new `ReaderMode` value: the mode enum describes how
 * pages are laid out, and the scene engine reads a direction, so keeping the two apart here is what
 * stops the coupling from being copied into the scene core.
 */
class AppSettingsReadingDirectionTest {

    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<Editor>(relaxed = true)

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
        every { preferences.getLong(any(), any()) } answers { secondArg() }
        every { preferences.getFloat(any(), any()) } answers { secondArg() }
        every { preferences.getString(any(), any()) } answers { secondArg() }
        every { preferences.getStringSet(any(), any()) } answers {
            secondArg<Set<String>?>()?.toMutableSet()
        }
        every { preferences.edit() } returns editor
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(PreferenceManager::class)
    }

    @Test
    fun `continuous horizontal reads left to right until the reader turns it around`() {
        AppSettings(context).isContinuousHorizontalReversed shouldBe false
    }

    @Test
    fun `an explicitly reversed continuous mode is preserved`() {
        every {
            preferences.getBoolean(AppSettings.KEY_READER_CONTINUOUS_HORIZONTAL_REVERSED, false)
        } returns true

        AppSettings(context).isContinuousHorizontalReversed shouldBe true
    }

    @Test
    fun `turning the direction around persists it`() {
        AppSettings(context).isContinuousHorizontalReversed = true

        verify {
            editor.putBoolean(AppSettings.KEY_READER_CONTINUOUS_HORIZONTAL_REVERSED, true)
        }
    }
}
