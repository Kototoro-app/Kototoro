package org.skepsun.kototoro.extensions.recovery

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity

class SourceRecoveryDerivationTest {

    private val snapshot = mockk<SourceRuntimeSnapshot>()

    @Test
    fun `installed with matching signature resolves`() {
        every { snapshot.isInstalled(any()) } returns true
        every { snapshot.currentSigningDigest(any()) } returns "digest-a"
        val origin = origin(packageName = "eu.kanade.tachiyomi.extension.en", signingDigest = "digest-a")

        assertEquals(SourceRecoveryStatus.RESOLVED, SourceRecoveryDerivation.deriveStatus(origin, snapshot))
    }

    @Test
    fun `installed with different signature and recorded digest requires confirmation`() {
        every { snapshot.isInstalled(any()) } returns true
        every { snapshot.currentSigningDigest(any()) } returns "digest-b"
        val origin = origin(packageName = "eu.kanade.tachiyomi.extension.en", signingDigest = "digest-a")

        assertEquals(
            SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED,
            SourceRecoveryDerivation.deriveStatus(origin, snapshot),
        )
    }

    @Test
    fun `installed without current signature info resolves without guessing`() {
        every { snapshot.isInstalled(any()) } returns true
        every { snapshot.currentSigningDigest(any()) } returns null
        val origin = origin(packageName = "eu.kanade.tachiyomi.extension.en", signingDigest = "digest-a")

        assertEquals(SourceRecoveryStatus.RESOLVED, SourceRecoveryDerivation.deriveStatus(origin, snapshot))
    }

    @Test
    fun `not installed with repository requires repository recovery`() {
        every { snapshot.isInstalled(any()) } returns false
        val origin = origin(repositoryUrl = "https://repo.example.com/index.min.json")

        assertEquals(SourceRecoveryStatus.REPOSITORY_REQUIRED, SourceRecoveryDerivation.deriveStatus(origin, snapshot))
    }

    @Test
    fun `not installed with locator and no repository requires re-import`() {
        every { snapshot.isInstalled(any()) } returns false
        val origin = origin(kind = "LEGADO", locator = "https://book.example.com/api/source/42")

        assertEquals(SourceRecoveryStatus.REIMPORT_REQUIRED, SourceRecoveryDerivation.deriveStatus(origin, snapshot))
    }

    @Test
    fun `not installed with package name and no repository or locator requires side-load`() {
        every { snapshot.isInstalled(any()) } returns false
        val origin = origin(packageName = "com.example.novelsource")

        assertEquals(SourceRecoveryStatus.SIDELOAD_REQUIRED, SourceRecoveryDerivation.deriveStatus(origin, snapshot))
    }

    @Test
    fun `not installed package-backed kind without package name requires side-load`() {
        every { snapshot.isInstalled(any()) } returns false
        val origin = origin(kind = "MIHON")

        assertEquals(SourceRecoveryStatus.SIDELOAD_REQUIRED, SourceRecoveryDerivation.deriveStatus(origin, snapshot))
    }

    @Test
    fun `not installed unknown kind without locator info is missing`() {
        every { snapshot.isInstalled(any()) } returns false
        val origin = origin(kind = "UNKNOWN")

        assertEquals(SourceRecoveryStatus.MISSING, SourceRecoveryDerivation.deriveStatus(origin, snapshot))
    }

    @Test
    fun `isMissing is false for resolved and true otherwise`() {
        for (status in SourceRecoveryStatus.entries) {
            if (status == SourceRecoveryStatus.RESOLVED) {
                assertFalse(status.isMissing, "$status should not be missing")
            } else {
                assertTrue(status.isMissing, "$status should be missing")
            }
        }
    }

    /**
     * Builds an origin supplying every field explicitly so the test compiles against any
     * default-less constructor the entity owner ships.
     */
    private fun origin(
        sourceKey: String = "MIHON_kototoro.test",
        kind: String = "MIHON",
        displayName: String? = "Example",
        contentType: String? = "manga",
        packageName: String? = null,
        sourceId: String? = null,
        repositoryUrl: String? = null,
        repositoryName: String? = null,
        locator: String? = null,
        versionName: String? = null,
        versionCode: Long? = null,
        signingDigest: String? = null,
        lastSeenAt: Long? = null,
        updatedAt: Long = 0L,
    ): SourceOriginEntity = SourceOriginEntity(
        sourceKey = sourceKey,
        kind = kind,
        displayName = displayName,
        contentType = contentType,
        packageName = packageName,
        sourceId = sourceId,
        repositoryUrl = repositoryUrl,
        repositoryName = repositoryName,
        locator = locator,
        versionName = versionName,
        versionCode = versionCode,
        signingDigest = signingDigest,
        lastSeenAt = lastSeenAt,
        updatedAt = updatedAt,
    )
}
