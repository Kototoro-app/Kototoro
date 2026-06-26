package org.skepsun.kototoro.backups.domain

import android.content.ComponentName
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings

@OptIn(ExperimentalCoroutinesApi::class)
class BackupStartupCoordinatorTest {

    private val appContext = mockk<Context>(relaxed = true)
    private val settings = mockk<AppSettings>(relaxed = true)
    private val backupFlowPolicy = BackupFlowPolicy(settings)

    @Test
    fun `startOnFirstLaunch starts periodic backup service`() = runTest {
        every { appContext.startService(any()) } returns mockk<ComponentName>(relaxed = true)
        every { settings.isBackupWebDavAutoRestoreEnabled } returns false

        val coordinator = BackupStartupCoordinator(appContext, backupFlowPolicy)
        coordinator.startOnFirstLaunch(this)
        advanceUntilIdle()

        verify(exactly = 1) { appContext.startService(any()) }
    }

    @Test
    fun `startOnFirstLaunch skips auto restore when config is incomplete`() = runTest {
        every { appContext.startService(any()) } returns mockk<ComponentName>(relaxed = true)
        every { settings.isBackupWebDavAutoRestoreEnabled } returns true
        every { settings.backupWebDavServerUrl } returns ""
        every { settings.backupWebDavUsername } returns "user"
        every { settings.backupWebDavPassword } returns "pass"

        val coordinator = BackupStartupCoordinator(appContext, backupFlowPolicy)
        coordinator.startOnFirstLaunch(this)
        advanceUntilIdle()

        verify(exactly = 1) { appContext.startService(any()) }
    }
}
