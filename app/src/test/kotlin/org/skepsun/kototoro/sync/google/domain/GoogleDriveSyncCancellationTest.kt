package org.skepsun.kototoro.sync.google.domain

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncApi
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncAuth
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncSettings

class GoogleDriveSyncCancellationTest {

    private val settings = mockk<GoogleDriveSyncSettings>(relaxed = true) {
        every { isSyncEnabled } returns true
        every { isSignedIn } returns true
    }
    private val auth = mockk<GoogleDriveSyncAuth>()
    private val api = mockk<GoogleDriveSyncApi>()
    private val repository = GoogleDriveSyncRepository(
        context = mockk(),
        settings = settings,
        appSettings = mockk(),
        auth = auth,
        api = api,
        database = mockk(),
        favouritesRepository = mockk(),
        historyRepository = mockk(),
        trackingRepository = mockk(),
        mihonExtensionManager = mockk(),
        aniyomiExtensionManager = mockk(),
        ireaderExtensionManager = mockk(),
        tsundokuExtensionManager = mockk(),
        cloudstreamRuntimeManager = mockk(),
    )

    private suspend fun expectCancellation(action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue(error is CancellationException, "Expected cancellation, got $error")
    }

    @Test
    fun `sync cancellation releases mutex without recording an error`() = runTest {
        coEvery { auth.requireAccessToken() } throws CancellationException("cancelled")
        repeat(2) {
            expectCancellation { repository.sync() }
            assertFalse(repository.isSyncing.value)
        }
        coVerify(exactly = 2) { auth.requireAccessToken() }
        verify(exactly = 0) { settings.lastSyncError = any() }
        verify(exactly = 0) { settings.isDirty = false }
    }

    @Test
    fun `legacy import cancellation releases mutex without recording an error`() = runTest {
        coEvery { auth.requireAccessToken() } throws CancellationException("cancelled")
        repeat(2) {
            expectCancellation { repository.importLegacyRemoteData() }
            assertFalse(repository.isSyncing.value)
        }
        coVerify(exactly = 2) { auth.requireAccessToken() }
        verify(exactly = 0) { settings.lastSyncError = any() }
    }

    @Test
    fun `remote listing cancellation does not clear sync settings`() = runTest {
        coEvery { auth.requireAccessToken() } returns "token"
        coEvery { api.findCurrentSyncFiles("token") } throws CancellationException("cancelled")
        expectCancellation { repository.deleteRemoteData() }
        verify(exactly = 0) { settings.lastSyncTimestamp = 0L }
        verify(exactly = 0) { settings.isDirty = false }
    }

    @Test
    fun `best effort remote deletion still propagates cancellation`() = runTest {
        coEvery { auth.requireAccessToken() } returns "token"
        coEvery { api.findCurrentSyncFiles("token") } returns listOf(GoogleDriveSyncApi.DriveFile("id"))
        coEvery { api.delete("token", "id") } throws CancellationException("cancelled")
        expectCancellation { repository.deleteRemoteData() }
        verify(exactly = 0) { settings.lastSyncTimestamp = 0L }
        verify(exactly = 0) { settings.isDirty = false }
    }

    @Test
    fun `ordinary remote deletion failures remain best effort`() = runTest {
        coEvery { auth.requireAccessToken() } returns "token"
        coEvery { api.findCurrentSyncFiles("token") } returns listOf(GoogleDriveSyncApi.DriveFile("id"))
        coEvery { api.delete("token", "id") } throws GoogleDriveSyncApiException(403, "forbidden")
        assertEquals(GoogleDriveSyncResult.Success, repository.deleteRemoteData())
        verify { settings.lastSyncTimestamp = 0L }
        verify { settings.isDirty = false }
    }
}
