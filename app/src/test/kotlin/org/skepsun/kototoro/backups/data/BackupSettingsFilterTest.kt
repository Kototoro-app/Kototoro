package org.skepsun.kototoro.backups.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupSettingsFilterTest {

    @Test
    fun `password keys are excluded`() {
        assertFalse(BackupSettingsFilter.isBackupableKey("backup_periodic_webdav_password"))
        assertFalse(BackupSettingsFilter.isBackupableKey("app_password"))
    }

    @Test
    fun `uri keys are excluded`() {
        assertFalse(BackupSettingsFilter.isBackupableKey("backups_output_dir_uri"))
        assertFalse(BackupSettingsFilter.isBackupableKey("pages_dir"))
    }

    @Test
    fun `regular reader setting is included`() {
        assertTrue(BackupSettingsFilter.isBackupableKey("reader_mode"))
        assertTrue(BackupSettingsFilter.isBackupableKey("backup_periodic_webdav_server_url"))
    }
}
