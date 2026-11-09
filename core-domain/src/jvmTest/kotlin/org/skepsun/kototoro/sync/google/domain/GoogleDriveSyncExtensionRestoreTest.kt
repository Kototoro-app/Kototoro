package org.skepsun.kototoro.sync.google.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionPackage

class GoogleDriveSyncExtensionRestoreTest {

    @Test
    fun `upload retains packages skipped or failed during restore across repeated syncs`() {
        val restored = extension("restored", payload = "YQ==")
        val tooLarge = extension("too-large", size = 6 * 1024 * 1024L)
        val failed = extension("failed", payload = "Yg==")
        val remote = GoogleDriveSyncSnapshot(extensions = listOf(restored, tooLarge, failed))
        val applied = GoogleDriveSyncSnapshot(extensions = listOf(restored))
        var upload = remote

        repeat(2) {
            val merged = GoogleDriveSyncMerger.mergeSnapshots(applied, upload)
            upload = GoogleDriveSyncMerger.buildUploadSnapshot(applied, merged)
            assertEquals(setOf("restored", "too-large", "failed"), upload.extensions.map { it.packageId }.toSet())
            assertSame(tooLarge, upload.extensions.single { it.packageId == "too-large" })
            assertEquals("Yg==", upload.extensions.single { it.packageId == "failed" }.payloadBase64)
        }
    }

    @Test
    fun `upload keeps local newer package and does not downgrade newer remote metadata`() {
        val applied = GoogleDriveSyncSnapshot(extensions = listOf(
            extension("local-newer", version = 3, payload = "YQ=="),
            extension("remote-newer", version = 1, payload = "Yg=="),
        ))
        val remote = GoogleDriveSyncSnapshot(extensions = listOf(
            extension("local-newer", version = 2, payload = "Yw=="),
            extension("remote-newer", version = 4, size = 6 * 1024 * 1024L),
        ))

        val upload = GoogleDriveSyncMerger.buildUploadSnapshot(
            applied,
            GoogleDriveSyncMerger.mergeSnapshots(applied, remote),
        )

        assertEquals(3L, upload.extensions.single { it.packageId == "local-newer" }.versionCode)
        assertEquals(4L, upload.extensions.single { it.packageId == "remote-newer" }.versionCode)
    }

    @Test
    fun `a removal beats copies installed before it, on either side and across repeated syncs`() {
        // The removing device uploads only the tombstone; the remote snapshot (or another device that
        // restored the package from it) still carries the earlier install.
        val applied = GoogleDriveSyncSnapshot(extensions = listOf(SyncExtensionPackage.tombstone("jar", "gone", 200)))
        var upload = GoogleDriveSyncSnapshot(extensions = listOf(extension("gone", payload = "YQ==", installedAt = 100)))

        repeat(2) {
            val merged = GoogleDriveSyncMerger.mergeSnapshots(applied, upload)
            upload = GoogleDriveSyncMerger.buildUploadSnapshot(applied, merged)
            val entry = upload.extensions.single { it.packageId == "gone" }
            assertTrue(entry.isDeleted)
            assertEquals(200L, entry.deletedAt)
            assertNull(entry.payloadBase64)
        }

        // The other device keeps its stale copy until it applies the merged snapshot.
        val otherDevice = GoogleDriveSyncSnapshot(extensions = listOf(extension("gone", payload = "YQ==", installedAt = 100)))
        assertTrue(GoogleDriveSyncMerger.mergeSnapshots(otherDevice, upload).extensions.single().isDeleted)
    }

    @Test
    fun `a reinstall after the removal brings the package back`() {
        val reinstalled = extension("back", version = 2, payload = "Yg==", installedAt = 300)
        val merged = GoogleDriveSyncMerger.mergeSnapshots(
            GoogleDriveSyncSnapshot(extensions = listOf(reinstalled)),
            GoogleDriveSyncSnapshot(extensions = listOf(SyncExtensionPackage.tombstone("jar", "back", 200))),
        )

        assertSame(reinstalled, merged.extensions.single())
    }

    @Test
    fun `snapshots from clients without install times still lose to a removal`() {
        val merged = GoogleDriveSyncMerger.mergeSnapshots(
            GoogleDriveSyncSnapshot(extensions = listOf(extension("legacy", payload = "YQ=="))),
            GoogleDriveSyncSnapshot(extensions = listOf(SyncExtensionPackage.tombstone("jar", "legacy", 1))),
        )

        assertTrue(merged.extensions.single().isDeleted)
    }

    private fun extension(
        id: String,
        version: Long = 1,
        payload: String? = null,
        size: Long = 1,
        installedAt: Long = 0,
    ) = SyncExtensionPackage(
        packageId = id,
        name = id,
        kind = "jar",
        versionCode = version,
        sizeBytes = size,
        payloadBase64 = payload,
        isPayloadIncluded = payload != null,
        installedAt = installedAt,
    )
}
