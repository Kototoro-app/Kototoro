package org.skepsun.kototoro.backups.domain

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings

class BackupWebDavRestoreCoordinatorTest {

    private val settings = mockk<AppSettings>(relaxed = true)
    private val coordinator = BackupWebDavRestoreCoordinator(settings)

    @Test
    fun `commitAutoRestore updates last restore time and raises data version when newer`() {
        every { settings.backupWebDavDataVersion } returns 5
        every { settings.backupWebDavLastRestoreTime = any() } returns Unit
        every { settings.backupWebDavDataVersion = any() } returns Unit
        every { settings.backupWebDavLastImportedSemanticSchemaVersion = any() } returns Unit
        every { settings.backupWebDavWriterGeneration } returns 0
        every { settings.backupWebDavWriterGeneration = any() } returns Unit
        every { settings.isWorkMigrationSyncWriteBlocked } returns false
        every { settings.isWorkMigrationSyncWriteBlocked = any() } returns Unit
        every { settings.requiresWorkMigrationNormalization = any() } returns Unit

        val state = BackupWebDavRestoreCoordinator.RestoreSemanticState(
            semanticSchemaVersion = 2,
            transportGeneration = 3,
        )
        val result = coordinator.commitAutoRestore(
            restoredVersion = 8,
            state = state,
            now = 1234L,
        )

        verify(exactly = 1) { settings.backupWebDavLastRestoreTime = 1234L }
        verify(exactly = 1) { settings.backupWebDavDataVersion = 8 }
        assertEquals(8, result.effectiveDataVersion)
        assertEquals("auto", result.restoreKind)
        assertEquals(2, result.semanticSchemaVersion)
        assertEquals(3, result.transportGeneration)
        assertEquals(false, result.writeBlocked)
    }

    @Test
    fun `commitAutoRestore keeps current data version when restored version is older`() {
        every { settings.backupWebDavDataVersion } returns 9
        every { settings.backupWebDavLastRestoreTime = any() } returns Unit
        every { settings.backupWebDavLastImportedSemanticSchemaVersion = any() } returns Unit
        every { settings.backupWebDavWriterGeneration } returns 0
        every { settings.backupWebDavWriterGeneration = any() } returns Unit
        every { settings.isWorkMigrationSyncWriteBlocked } returns false
        every { settings.isWorkMigrationSyncWriteBlocked = any() } returns Unit
        every { settings.requiresWorkMigrationNormalization = any() } returns Unit

        val state = BackupWebDavRestoreCoordinator.RestoreSemanticState(
            semanticSchemaVersion = 1,
            transportGeneration = 1,
        )
        val result = coordinator.commitAutoRestore(
            restoredVersion = 4,
            state = state,
            now = 1234L,
        )

        verify(exactly = 1) { settings.backupWebDavLastRestoreTime = 1234L }
        verify(exactly = 0) { settings.backupWebDavDataVersion = any() }
        assertEquals(9, result.effectiveDataVersion)
    }

    @Test
    fun `commitManualRestore updates manual restore timestamp only`() {
        every { settings.backupWebDavDataVersion } returns 6
        every { settings.backupWebDavLastManualRestoreTime = any() } returns Unit
        every { settings.backupWebDavLastImportedSemanticSchemaVersion = any() } returns Unit
        every { settings.backupWebDavWriterGeneration } returns 0
        every { settings.backupWebDavWriterGeneration = any() } returns Unit
        every { settings.isWorkMigrationSyncWriteBlocked } returns false
        every { settings.isWorkMigrationSyncWriteBlocked = any() } returns Unit
        every { settings.requiresWorkMigrationNormalization = any() } returns Unit

        val state = BackupWebDavRestoreCoordinator.RestoreSemanticState(
            semanticSchemaVersion = 1,
            transportGeneration = 1,
        )
        val result = coordinator.commitManualRestore(state = state, now = 2222L)

        verify(exactly = 1) { settings.backupWebDavLastManualRestoreTime = 2222L }
        verify(exactly = 0) { settings.backupWebDavDataVersion = any() }
        assertEquals(6, result.effectiveDataVersion)
        assertEquals("manual", result.restoreKind)
    }
}
