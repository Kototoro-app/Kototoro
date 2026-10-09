package org.skepsun.kototoro.core.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.util.ext.getSafeInt
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingColor
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingStyle

/** Pins the actual SETTINGS restore entry point that previously shadowed the shared putAll helper. */
@RunWith(AndroidJUnit4::class)
class PreferencesRestoreTest {

    @Test
    fun settingsRestoreKeepsNovelMarkingValuesReadableAsInts() {
        val context = IsolatedPreferencesContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val settings = AppSettings(context)
        val colorKey = "novel_active_marking_color"
        val styleKey = "novel_active_marking_style"
        settings.upsertAll(mapOf(
            colorKey to NovelMarkingColor.YELLOW.id,
            styleKey to NovelMarkingStyle.UNDERLINE.id.toLong(),
        ))

        assertTrue(settings.prefs.all[colorKey] is Int)
        assertTrue(settings.prefs.all[styleKey] is Int)
        assertEquals(NovelMarkingColor.YELLOW.id, settings.prefs.getInt(colorKey, -1))
        assertEquals(NovelMarkingStyle.UNDERLINE.id, settings.prefs.getInt(styleKey, -1))

        settings.prefs.edit().putLong(colorKey, NovelMarkingColor.YELLOW.id.toLong()).commit()
        assertEquals(NovelMarkingColor.YELLOW.id, settings.prefs.getSafeInt(colorKey, -1))
        assertEquals(NovelMarkingColor.YELLOW.id, settings.prefs.getInt(colorKey, -1))
    }

    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("issue573_test_$name", mode)
    }
}
