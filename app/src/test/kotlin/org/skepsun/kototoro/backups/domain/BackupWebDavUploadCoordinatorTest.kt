package org.skepsun.kototoro.backups.domain

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.backups.ui.periodical.WebDavBackupUploader
import org.skepsun.kototoro.core.prefs.AppSettings
import java.io.File

class BackupWebDavUploadCoordinatorTest {

	private val settings = mockk<AppSettings>(relaxed = true)
	private val uploader = mockk<WebDavBackupUploader>(relaxed = true)
	private val coordinator = BackupWebDavUploadCoordinator(settings, uploader)

	@Test
	fun `uploadAndCommit uploads next version and persists upload state`() {
		every { settings.backupWebDavDataVersion } returns 7
		every { settings.backupWebDavLastUploadTime = any() } returns Unit
		every { settings.backupWebDavLastUploadKind = any() } returns Unit
		every { settings.backupWebDavDataVersion = any() } returns Unit
		coEvery { uploader.uploadBackup(any(), any()) } returns Unit
		val file = File.createTempFile("test_backup", ".zip").apply { deleteOnExit() }

		val result = kotlinx.coroutines.runBlocking {
			coordinator.uploadAndCommit(
				file = file,
				uploadKind = "manual",
				now = 1234L,
			)
		}

		coVerify(exactly = 1) { uploader.uploadBackup(any(), 8) }
		verify(exactly = 1) { settings.backupWebDavLastUploadTime = 1234L }
		verify(exactly = 1) { settings.backupWebDavLastUploadKind = "manual" }
		verify(exactly = 1) { settings.backupWebDavDataVersion = 8 }
		assertEquals(
			BackupWebDavUploadCoordinator.UploadCommitResult(
				uploadedAt = 1234L,
				targetVersion = 8,
				uploadKind = "manual",
			),
			result,
		)
	}
}
