package org.skepsun.kototoro.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.Editor
import android.content.res.Resources
import android.net.ConnectivityManager
import androidx.preference.PreferenceManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppSettingsLnReaderDefaultsTest {

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
    fun `lnreader repository list is empty until user adds one`() {
        AppSettings(context).lnReaderRepoUrls.shouldBeEmpty()
    }

    @Test
    fun `explicitly configured lnreader repositories are preserved`() {
        val configured = mutableSetOf("https://example.com/lnreader/plugins.json")
        every { preferences.getStringSet(AppSettings.KEY_LNREADER_REPOS, null) } returns configured

        AppSettings(context).lnReaderRepoUrls shouldBe configured
    }
}
