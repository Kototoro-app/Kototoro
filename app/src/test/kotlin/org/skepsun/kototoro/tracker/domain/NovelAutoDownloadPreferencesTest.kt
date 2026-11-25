package org.skepsun.kototoro.tracker.domain

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NovelAutoDownloadPreferencesTest {
    @Test
    fun `only opted in titles are added to the tracking anchors`() {
        val values = mapOf("41" to true, "42" to false, "invalid" to true)
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        every { context.getSharedPreferences("novel_auto_download", Context.MODE_PRIVATE) } returns preferences
        every { preferences.all } returns values
        val policy = NovelAutoDownloadPreferences(context)

        assertEquals(listOf(41L), policy.enabledIds())
        assertFalse(policy.policies.value.getValue(42L))
        assertNull(policy.policies.value[43L])
    }

    @Test
    fun `a disable remains an explicit override instead of falling back to global downloads`() {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences("novel_auto_download", Context.MODE_PRIVATE) } returns preferences
        every { preferences.all } returns emptyMap()
        every { preferences.edit() } returns editor
        val policy = NovelAutoDownloadPreferences(context)

        policy.toggle(41L)
        policy.toggle(41L)

        assertFalse(policy.policies.value.getValue(41L))
        assertEquals(emptyList<Long>(), policy.enabledIds())
        verify { editor.putBoolean("41", true) }
        verify { editor.putBoolean("41", false) }
    }
}
