package org.skepsun.kototoro.backups.ui.periodical

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.backups.webdav.WebDavBackupFile
import org.skepsun.kototoro.core.prefs.AppSettings
import java.io.File

class WebDavBackupUploaderTest {
    private val settings = mockk<AppSettings>(relaxed = true).also {
        every { it.backupWebDavServerUrl } returns "https://fixture.test/"
        every { it.backupWebDavRemotePath } returns ""
        every { it.periodicalBackupRemoteMaxCount } returns 1
    }
    private val client = mockk<WebDavBackupClient>(relaxed = true)
    private val uploader = WebDavBackupUploader(settings, client)
    private val files = listOf("new.zip", "old.zip", "oldest.zip").mapIndexed { index, name ->
        WebDavBackupFile(name, (3 - index).toLong(), 1, 8, RemoteNamespace.V3)
    } + WebDavBackupFile("v2.zip", 0, 1, 7, RemoteNamespace.V2)

    @Test
    fun `disabled retention performs no listing`() = runBlocking {
        uploader.trimRemote(0)
        uploader.trimRemote(-1)
        coVerify(exactly = 0) { client.list(any()) }
        coVerify(exactly = 0) { client.delete(any(), any()) }
    }

    @Test
    fun `ordinary delete failure continues with remaining selected backups`() = runBlocking {
        coEvery { client.list(any()) } returns files
        coEvery { client.delete(any(), "old.zip") } throws java.io.IOException("offline")
        uploader.trimRemote(1)
        coVerify(exactly = 1) { client.delete(any(), "old.zip") }
        coVerify(exactly = 1) { client.delete(any(), "oldest.zip") }
        coVerify(exactly = 0) { client.delete(any(), "v2.zip") }
        coVerify(exactly = 0) { client.delete(any(), "new.zip") }
    }

    @Test
    fun `retention cancellation stops subsequent deletion`() = runBlocking {
        coEvery { client.list(any()) } returns files
        coEvery { client.delete(any(), "old.zip") } throws CancellationException("cancel")
        assertTrue(runCatching { uploader.trimRemote(1) }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { client.delete(any(), "oldest.zip") }
    }

    @Test
    fun `post upload retention cancellation propagates`() = runBlocking {
        coEvery { client.list(any()) } throws CancellationException("cancel")
        val file = File.createTempFile("webdav-upload-cancel-", ".zip").apply { deleteOnExit() }
        assertTrue(runCatching { uploader.uploadBackup(file, 8) }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `post upload ordinary retention failure remains best effort`() = runBlocking {
        coEvery { client.list(any()) } throws java.io.IOException("offline")
        val file = File.createTempFile("webdav-upload-trim-", ".zip").apply { deleteOnExit() }
        uploader.uploadBackup(file, 8)
        coVerify(exactly = 1) { client.upload(any(), any(), any(), any()) }
    }
}
