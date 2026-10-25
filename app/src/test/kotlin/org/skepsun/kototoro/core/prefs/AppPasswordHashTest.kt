package org.skepsun.kototoro.core.prefs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.util.md5

class AppPasswordHashTest {

    @Test
    fun saltedHashesVerifyAndDiffer() {
        val first = AppPasswordHash.hash("1234")
        val second = AppPasswordHash.hash("1234")
        assertNotEquals(first, second, "Each hash has its own salt")
        assertTrue(AppPasswordHash.verify("1234", first))
        assertFalse(AppPasswordHash.verify("4321", first))
        assertFalse(AppPasswordHash.needsUpgrade(first))
    }

    @Test
    fun legacyMd5StillUnlocksAndAsksForAnUpgrade() {
        val legacy = "1234".md5()
        assertTrue(AppPasswordHash.verify("1234", legacy))
        assertFalse(AppPasswordHash.verify("0000", legacy))
        assertTrue(AppPasswordHash.needsUpgrade(legacy))
        assertFalse(AppPasswordHash.verify("1234", null))
    }
}
