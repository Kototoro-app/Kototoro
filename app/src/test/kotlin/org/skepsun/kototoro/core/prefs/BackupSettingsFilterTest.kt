package org.skepsun.kototoro.core.prefs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupSettingsFilterTest {

    @Test
    fun `local-only keys are excluded`() {
        assertFalse(BackupSettingsFilter.isBackupableKey("app_password"))
        assertFalse(BackupSettingsFilter.isBackupableKey("available_sources"))
    }

    @Test
    fun `webdav credentials and paths are kept`() {
        assertTrue(BackupSettingsFilter.isBackupableKey("backup_periodic_webdav_password"))
        assertTrue(BackupSettingsFilter.isBackupableKey("backup_periodic_webdav_remote_path"))
    }

    @Test
    fun `regular reader setting is included`() {
        assertTrue(BackupSettingsFilter.isBackupableKey("reader_mode"))
    }
}
