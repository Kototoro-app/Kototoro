package org.skepsun.kototoro.extensions.recovery

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.extensions.repo.InstalledExtensionSignatureValidator
import org.skepsun.kototoro.extensions.runtime.tachiyomi.SourceIdentity

/**
 * Production [SourceRuntimeSnapshot] behavior: parses `PREFIX_<sourceId>` keys, resolves the
 * installed package via the injected per-ecosystem lookup, and reads real signing digests.
 *
 * The snapshot takes `(sourceId) -> packageName?` resolvers (wired from the live managers in
 * RecoveryModule), so these tests drive it with pure fixture lambdas — no manager mocks, no
 * Android or extension-library classes (the ireader API jar is Java 21 bytecode and must not
 * be loaded by the Java 17 unit-test worker).
 */
class ManagerBackedSourceRuntimeSnapshotTest {

    private val validator = mockk<InstalledExtensionSignatureValidator>(relaxed = true)

    private fun newSnapshot(
        mihonPackageFor: (String) -> String? = { null },
        tsundokuPackageFor: (String) -> String? = { null },
    ) = ManagerBackedSourceRuntimeSnapshot(
        mihonPackageFor = mihonPackageFor,
        aniyomiPackageFor = { null },
        ireaderPackageFor = { null },
        tsundokuPackageFor = tsundokuPackageFor,
        signatureValidator = validator,
    )

    @Test
    fun `isInstalled is true when a source key resolves to an installed Mihon package`() {
        val snapshot = newSnapshot(mihonPackageFor = { if (it == "9") "eu.mihon.pkg" else null })
        assertTrue(snapshot.isInstalled("MIHON_9"))
        assertFalse(snapshot.isInstalled("MIHON_8"))
    }

    @Test
    fun `packageNameFor returns the package resolvable for the source id`() {
        val snapshot = newSnapshot(mihonPackageFor = { if (it == "7") "eu.mihon.pkg" else null })
        assertEquals("eu.mihon.pkg", snapshot.packageNameFor("MIHON_7"))
        assertNull(snapshot.packageNameFor("MIHON_42"))
    }

    @Test
    fun `tsundoku keys resolve through the tsundoku resolver`() {
        val snapshot = newSnapshot(tsundokuPackageFor = { if (it == "9001") "novel.sourcery.pkg" else null })
        assertEquals("novel.sourcery.pkg", snapshot.packageNameFor("TSUNDOKU_9001"))
        assertTrue(snapshot.isInstalled("TSUNDOKU_9001"))
    }

    @Test
    fun `currentSigningDigest delegates to the installed-package signature validator`() {
        val snapshot = newSnapshot(mihonPackageFor = { if (it == "3") "eu.mihon.pkg" else null })
        every { validator.firstFingerprint("eu.mihon.pkg") } returns "abc123"
        assertEquals("abc123", snapshot.currentSigningDigest("MIHON_3"))
        assertNull(snapshot.currentSigningDigest("MIHON_404"))
        assertNull(snapshot.currentSigningDigest("plain-name"))
    }

    @Test
    fun `non-tachiyomi keys report nothing without throwing`() {
        val snapshot = newSnapshot(mihonPackageFor = { "eu.mihon.pkg" })
        assertNull(snapshot.packageNameFor("JSON_abc"))
        assertNull(snapshot.packageNameFor("TVBOX_1"))
        assertNull(snapshot.packageNameFor("plain-source-name"))
        assertNull(snapshot.packageNameFor("CLOUDSTREAM_5"))
        assertFalse(snapshot.isInstalled("plain-source-name"))
    }

    @Test
    fun `source identity round-trips for every tachiyomi ecosystem`() {
        for (key in listOf("MIHON_1", "ANIYOMI_2", "IREADER_3", "TSUNDOKU_4")) {
            val identity = SourceIdentity.fromSourceKey(key)
            assertEquals(key, identity?.sourceKey)
        }
        assertNull(SourceIdentity.fromSourceKey("JAR_1"))
        assertNull(SourceIdentity.fromSourceKey(""))
    }

    @Test
    fun `an installed package never leaks across ecosystems`() {
        // 12 exists only in mihon; the other ecosystem resolvers report nothing.
        val snapshot = newSnapshot(mihonPackageFor = { if (it == "12") "eu.mihon.pkg" else null })
        assertEquals("eu.mihon.pkg", snapshot.packageNameFor("MIHON_12"))
        assertNull(snapshot.packageNameFor("ANIYOMI_12"))
        assertNull(snapshot.packageNameFor("IREADER_12"))
        assertNull(snapshot.packageNameFor("TSUNDOKU_12"))
    }
}
