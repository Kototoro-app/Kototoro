package org.skepsun.kototoro.desktop.player

import com.sun.jna.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * mpv's client API refuses to create a player unless `LC_NUMERIC` is the C locale, which a Linux process inherits from
 * its environment. The switch has to leave the JVM's own locale (and therefore every formatted number in the UI) alone.
 */
class MpvLocaleTest {
    @Test
    fun `libmpv gets the C numeric locale without touching the UI locale`() {
        val uiLocale = Locale.getDefault()
        assertTrue(MpvLocale.ensure())
        assertTrue(MpvLocale.ensure(), "a repeated call stays true")
        assertEquals(uiLocale, Locale.getDefault(), "the C locale must not change what the UI formats with")
        assumeFalse(Platform.isWindows(), "Windows starts and stays in the C numeric locale")
        val numeric = MpvLocale.current
        assertTrue(numeric == "C" || numeric == "POSIX") { "LC_NUMERIC is $numeric" }
        assertTrue(MpvLocale.isC)
    }
}
