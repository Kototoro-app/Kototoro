package org.skepsun.kototoro.extensions.runtime.tachiyomi

import android.content.pm.PackageInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalApkCandidateResolverTest {

    /** PackageInfo.versionCode is a public field; real instances are the repo pattern. */
    private fun pkg(name: String, version: Int): PackageInfo = PackageInfo().apply {
        packageName = name
        @Suppress("DEPRECATION")
        versionCode = version
    }

    // ---- Legacy Mihon semantics: system always wins ----

    @Test
    fun `system first keeps system package even when local version is higher`() {
        val system = pkg("com.example.extension", version = 5)
        val local = pkg("com.example.extension", version = 9)
        val resolved = ExternalApkCandidateResolver.resolve(
            installed = listOf(system),
            local = listOf(local),
            mode = ExternalApkCandidateSelection.SYSTEM_FIRST_KEEP_FIRST,
        )
        assertEquals(listOf(system), resolved)
    }

    @Test
    fun `system first dedupes identical names across lists`() {
        val system = pkg("com.example.a", version = 1)
        val local = pkg("com.example.a", version = 1)
        val other = pkg("com.example.b", version = 2)
        val resolved = ExternalApkCandidateResolver.resolve(
            installed = listOf(system, other),
            local = listOf(local),
            mode = ExternalApkCandidateSelection.SYSTEM_FIRST_KEEP_FIRST,
        )
        assertEquals(listOf(system, other), resolved)
    }

    // ---- Tsundoku semantics: highest version, tie -> system ----

    @Test
    fun `version first picks the local package when it is newer`() {
        val system = pkg("com.example.extension", version = 5)
        val local = pkg("com.example.extension", version = 9)
        val resolved = ExternalApkCandidateResolver.resolve(
            installed = listOf(system),
            local = listOf(local),
            mode = ExternalApkCandidateSelection.VERSION_HIGHER_FIRST_TIE_SYSTEM,
        )
        assertEquals(listOf(local), resolved)
    }

    @Test
    fun `version first picks the system package on equal version`() {
        val system = pkg("com.example.extension", version = 7)
        val local = pkg("com.example.extension", version = 7)
        val resolved = ExternalApkCandidateResolver.resolve(
            installed = listOf(system),
            local = listOf(local),
            mode = ExternalApkCandidateSelection.VERSION_HIGHER_FIRST_TIE_SYSTEM,
        )
        assertEquals(listOf(system), resolved)
    }

    @Test
    fun `version first keeps system package when system is newer`() {
        val system = pkg("com.example.extension", version = 12)
        val local = pkg("com.example.extension", version = 3)
        val resolved = ExternalApkCandidateResolver.resolve(
            installed = listOf(system),
            local = listOf(local),
            mode = ExternalApkCandidateSelection.VERSION_HIGHER_FIRST_TIE_SYSTEM,
        )
        assertEquals(listOf(system), resolved)
    }

    @Test
    fun `version first handles multiple distinct packages independently`() {
        val systemA = pkg("com.example.a", version = 1)
        val localA = pkg("com.example.a", version = 10)
        val systemB = pkg("com.example.b", version = 4)
        val localB = pkg("com.example.b", version = 4)
        val resolved = ExternalApkCandidateResolver.resolve(
            installed = listOf(systemA, systemB),
            local = listOf(localA, localB),
            mode = ExternalApkCandidateSelection.VERSION_HIGHER_FIRST_TIE_SYSTEM,
        )
        assertTrue(
            resolved == listOf(localA, systemB) || resolved.containsAll(listOf(localA, systemB)),
        )
    }
}
