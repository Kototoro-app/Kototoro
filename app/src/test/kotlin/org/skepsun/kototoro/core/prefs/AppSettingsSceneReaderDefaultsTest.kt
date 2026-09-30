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
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderOptionsState

class AppSettingsSceneReaderDefaultsTest {

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
        every { preferences.getStringSet(any(), any()) } answers { (secondArg<Set<String>?>() ?: emptySet()).toMutableSet() }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(PreferenceManager::class)
    }

    @Test
    fun `scene reader gates default to disabled when preferences are absent`() {
        val settings = AppSettings(context)

        settings.isExperimentalSceneReaderEnabled shouldBe false
        settings.isExperimentalPagedSceneReaderEnabled shouldBe false
    }

    @Test
    fun `options state defaults webtoon scene reader to disabled`() {
        ComposeReaderOptionsState().webtoonSceneReader shouldBe false
        ComposeReaderOptionsState().pagedSceneReader shouldBe false
    }
}
