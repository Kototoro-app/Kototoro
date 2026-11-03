package org.skepsun.kototoro.sync.google.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionPackage

class ExtensionReinstallTest {

    @Test
    fun `an install after the removal supersedes it`() {
        assertTrue(isReinstalledAfterRemoval("mihon", "pkg", 200, listOf(installed("mihon", "pkg", 300))))
    }

    @Test
    fun `the copy that was removed or an earlier one does not`() {
        assertFalse(isReinstalledAfterRemoval("mihon", "pkg", 200, listOf(installed("mihon", "pkg", 200))))
        assertFalse(isReinstalledAfterRemoval("mihon", "pkg", 200, listOf(installed("mihon", "pkg", 100))))
    }

    @Test
    fun `another kind or package does not`() {
        assertFalse(isReinstalledAfterRemoval("mihon", "pkg", 200, listOf(installed("aniyomi", "pkg", 300))))
        assertFalse(isReinstalledAfterRemoval("mihon", "pkg", 200, listOf(installed("mihon", "other", 300))))
    }

    @Test
    fun `a jar reinstalled under a versioned file name supersedes the removal`() {
        val reinstalled = installed("jar", "org.skepsun.kototoro.parsers.v703", 300)
        assertTrue(isReinstalledAfterRemoval("jar", "org.skepsun.kototoro.parsers", 200, listOf(reinstalled)))
        assertFalse(
            isReinstalledAfterRemoval(
                "jar",
                "org.skepsun.kototoro.parsers",
                200,
                listOf(installed("jar", "org.skepsun.kototoro.parsers.extra", 300)),
            ),
        )
    }

    private fun installed(kind: String, id: String, installedAt: Long) = SyncExtensionPackage(
        packageId = id,
        name = id,
        kind = kind,
        installedAt = installedAt,
    )
}
