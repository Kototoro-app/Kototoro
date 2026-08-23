package org.skepsun.kototoro.extensions.recovery

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.extensions.install.ExtensionInstallMode
import org.skepsun.kototoro.extensions.install.ExtensionInstallResult
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.tsundoku.TsundokuExtensionManager

class RecoveryActionCoordinatorTest {

    private val recoveryRepository = mockk<SourceRecoveryRepository>(relaxed = true)
    private val snapshot = mockk<SourceRuntimeSnapshot>(relaxed = true)
    private val extensionRepoRepository = mockk<ExternalExtensionRepoRepository>(relaxed = true)
    private val installService = mockk<ExtensionInstallService>(relaxed = true)
    private val jsonSourceManager = mockk<JsonSourceManager>(relaxed = true)
    private val legadoHttpClient = mockk<LegadoHttpClient>(relaxed = true)
    private val mihonManager = mockk<MihonExtensionManager>(relaxed = true)
    private val aniyomiManager = mockk<AniyomiExtensionManager>(relaxed = true)
    private val ireaderManager = mockk<IReaderExtensionManager>(relaxed = true)
    private val tsundokuManager = mockk<TsundokuExtensionManager>(relaxed = true)
    private val notifier = mockk<RecoveryNotifier>(relaxed = true)
    @Suppress("unused")
    private val appContext = mockk<Context>(relaxed = true)

    private val coordinator = RecoveryActionCoordinator(
        recoveryRepository = recoveryRepository,
        runtimeSnapshot = snapshot,
        extensionRepoRepository = extensionRepoRepository,
        installService = installService,
        jsonSourceManager = jsonSourceManager,
        legadoHttpClient = legadoHttpClient,
        mihonExtensionManager = mihonManager,
        aniyomiExtensionManager = aniyomiManager,
        ireaderExtensionManager = ireaderManager,
        tsundokuExtensionManager = tsundokuManager,
        notifier = notifier,
        appContext = appContext,
    )

    /** Flip this (inside an action mock) to emulate the runtime discovering the package. */
    private var resolvedNow = false

    private val repoUrl = "https://repo.example.com/index.min.json"

    @Test
    fun `run installs from repository and auto-exits missing once resolved`() = runTest {
        val origin = origin(
    kind = "MIHON",
    packageName = "eu.kanade.tachiyomi.extension.en.demo",
    repositoryUrl = repoUrl,
)
        stubDerive(origin, SourceRecoveryStatus.REPOSITORY_REQUIRED, installResolvesAfter = true)
        val candidate = repoExtension()
        coEvery { extensionRepoRepository.getCatalogExtensions(ExternalExtensionType.MIHON) } returns listOf(candidate)
        coEvery { installService.install(any(), any()) } coAnswers {
            resolvedNow = true
            ExtensionInstallResult.Completed
        }

        val result = coordinator.run(origin.sourceKey)

        assertTrue(result.ok, "expected ok but was ${result.message}")
        coVerify(exactly = 1) { installService.install(candidate, ExtensionInstallMode.LOCAL_APK) }
        coVerify { notifier.notifyRecovered(origin.sourceKey) }
        coVerify(exactly = 1) { mihonManager.loadExtensions() }
        // Re-derivation leaves the snapshot without this source missing.
        assertEquals(SourceRecoveryStatus.RESOLVED, coordinator.snapshot.value.perSource[origin.sourceKey])
        assertEquals(0, coordinator.snapshot.value.missingCount)
    }

    @Test
    fun `run with no install candidate fails and keeps missing`() = runTest {
        val origin = origin(
    kind = "MIHON",
    packageName = "eu.kanade.tachiyomi.extension.en.demo",
    repositoryUrl = repoUrl,
)
        stubDerive(origin, SourceRecoveryStatus.REPOSITORY_REQUIRED)
        coEvery { extensionRepoRepository.getCatalogExtensions(ExternalExtensionType.MIHON) } returns emptyList()

        val result = coordinator.run(origin.sourceKey)

        assertFalse(result.ok)
        assertTrue(result.message.orEmpty().contains("no install candidate"))
        coVerify(exactly = 0) { installService.install(any(), any()) }
        coVerify(exactly = 0) { notifier.notifyRecovered(any()) }
        assertEquals(SourceRecoveryStatus.REPOSITORY_REQUIRED, coordinator.snapshot.value.perSource[origin.sourceKey])
    }

    @Test
    fun `sideLoad with null uri fails cleanly without touching managers`() = runTest {
        val origin = origin(kind = "MIHON", packageName = "eu.kanade.tachiyomi.extension.en.demo")
        stubDerive(origin, SourceRecoveryStatus.SIDELOAD_REQUIRED)

        val result = coordinator.sideLoad(origin.sourceKey, null)

        assertFalse(result.ok)
        assertTrue(result.message.orEmpty().contains("no file selected"))
        coVerify(exactly = 0) { mihonManager.loadExtensions() }
    }

    @Test
    fun `confirmSignature writes the current digest and resolves`() = runTest {
        val origin = origin(
            kind = "MIHON",
            packageName = "eu.kanade.tachiyomi.extension.en.demo",
            signingDigest = "sha256:old",
        )
        stubDerive(origin, SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED, installResolvesAfter = true)
        every { snapshot.currentSigningDigest(origin.sourceKey) } returns "sha256:new"
        coEvery { recoveryRepository.upsert(any()) } answers {
            resolvedNow = true
        }

        val result = coordinator.confirmSignature(origin.sourceKey)

        assertTrue(result.ok, "expected ok but was ${result.message}")
        val captured = slot<SourceOriginEntity>()
        coVerify { recoveryRepository.upsert(capture(captured)) }
        assertEquals("sha256:new", captured.captured.signingDigest)
        coVerify { notifier.notifyRecovered(origin.sourceKey) }
        assertEquals(SourceRecoveryStatus.RESOLVED, coordinator.snapshot.value.perSource[origin.sourceKey])
    }

    @Test
    fun `rejectSignature never writes the digest and keeps the confirmation-required state`() = runTest {
        val origin = origin(
            kind = "MIHON",
            packageName = "eu.kanade.tachiyomi.extension.en.demo",
            signingDigest = "sha256:old",
        )
        // T5.6 invariant: dismissal does not re-associate while the digest differs.
        stubDerive(origin, SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED)

        val result = coordinator.rejectSignature(origin.sourceKey)

        assertFalse(result.ok)
        coVerify(exactly = 0) { recoveryRepository.upsert(any()) }
        assertEquals(
            SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED,
            coordinator.snapshot.value.perSource[origin.sourceKey],
        )
    }

    @Test
    fun `concurrent run on the same source is serialized through the in-flight guard`() = runTest {
        val origin = origin(
    kind = "MIHON",
    packageName = "eu.kanade.tachiyomi.extension.en.demo",
    repositoryUrl = repoUrl,
)
        stubDerive(origin, SourceRecoveryStatus.REPOSITORY_REQUIRED, installResolvesAfter = true)
        coEvery {
    extensionRepoRepository.getCatalogExtensions(ExternalExtensionType.MIHON)
} returns listOf(repoExtension())
        coEvery { installService.install(any(), any()) } coAnswers {
            delay(100)
            resolvedNow = true
            ExtensionInstallResult.Completed
        }

        val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.run(origin.sourceKey) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.run(origin.sourceKey) }
        val results = listOf(first, second).map { it.await() }

        // Exactly one install happens: the second call is gated by the in-flight guard and
        // never interleaves with the first (serialized execution).
        assertEquals(1, results.count { it.ok })
        assertTrue(results.count { !it.ok } >= 1)
        coVerify(exactly = 1) { installService.install(any(), any()) }
    }

    @Test
    fun `rescanAll reloads managers and reports how many resolved`() = runTest {
        val a = origin(sourceKey = "MIHON_1", kind = "MIHON", packageName = "com.example.a")
        val b = origin(sourceKey = "MIHON_2", kind = "MIHON", packageName = "com.example.b")
        var scanned = false
        coEvery { recoveryRepository.deriveAll() } answers {
            if (scanned) {
                listOf(
                    stateOf(a, SourceRecoveryStatus.RESOLVED),
                    stateOf(b, SourceRecoveryStatus.SIDELOAD_REQUIRED),
                )
            } else {
                listOf(
                    stateOf(a, SourceRecoveryStatus.SIDELOAD_REQUIRED),
                    stateOf(b, SourceRecoveryStatus.SIDELOAD_REQUIRED),
                )
            }
        }
        coEvery { mihonManager.loadExtensions() } answers { scanned = true }

        val resolved = coordinator.rescanAll()

        assertEquals(1, resolved)
        coVerify(exactly = 1) { mihonManager.loadExtensions() }
        coVerify { notifier.notifyMissingSummary(1) }
        assertEquals(1, coordinator.snapshot.value.missingCount)
    }

    @Test
    fun `run on a plain missing origin is a no-op`() = runTest {
        val origin = origin(kind = "UNKNOWN")
        stubDerive(origin, SourceRecoveryStatus.MISSING)

        val result = coordinator.run(origin.sourceKey)

        assertFalse(result.ok)
        assertTrue(result.message.orEmpty().contains("no recovery channel"))
        coVerify(exactly = 0) { installService.install(any(), any()) }
        coVerify(exactly = 0) { recoveryRepository.upsert(any()) }
    }

    @Test
    fun `run on an unknown source key fails cleanly`() = runTest {
        coEvery { recoveryRepository.deriveAll() } returns emptyList()

        val result = coordinator.run("MIHON_absent")

        assertFalse(result.ok)
        assertTrue(result.message.orEmpty().contains("unknown origin"))
    }

    @Test
    fun `sideLoad of a picked file stores the apk and resolves`() = runTest {
        val origin = origin(kind = "MIHON", packageName = "eu.kanade.tachiyomi.extension.en.demo")
        stubDerive(origin, SourceRecoveryStatus.SIDELOAD_REQUIRED, installResolvesAfter = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        val uri = mockk<Uri>(relaxed = true)
        // Keep the managed-apk writes inside the OS temp dir instead of the repo tree.
        val tempRoot = java.io.File(System.getProperty("java.io.tmpdir"))
        every { appContext.filesDir } returns tempRoot
        every { appContext.cacheDir } returns tempRoot
        every { appContext.contentResolver } returns resolver
        every { resolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        coEvery { mihonManager.loadExtensions() } answers { resolvedNow = true }

        val result = coordinator.sideLoad(origin.sourceKey, uri)

        assertTrue(result.ok, "expected ok but was ${result.message}")
        coVerify(exactly = 1) { mihonManager.loadExtensions() }
        coVerify { notifier.notifyRecovered(origin.sourceKey) }
        assertEquals(SourceRecoveryStatus.RESOLVED, coordinator.snapshot.value.perSource[origin.sourceKey])
    }

    // ---------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------

    private fun stubDerive(
        origin: SourceOriginEntity = origin(),
        status: SourceRecoveryStatus = SourceRecoveryStatus.MISSING,
        installResolvesAfter: Boolean = false,
    ) {
        coEvery { recoveryRepository.deriveAll() } answers {
            val effective = if (installResolvesAfter && resolvedNow) SourceRecoveryStatus.RESOLVED else status
            listOf(stateOf(origin, effective))
        }
        coEvery { recoveryRepository.statusOf(origin.sourceKey) } answers {
            if (installResolvesAfter && resolvedNow) SourceRecoveryStatus.RESOLVED else status
        }
    }

    private fun stateOf(
        origin: SourceOriginEntity,
        status: SourceRecoveryStatus,
    ): SourceRecoveryState {
        return SourceRecoveryState(origin = origin, status = status, referenced = false)
    }

    private fun repoExtension(): RepoAvailableExtension {
        return RepoAvailableExtension(
            type = ExternalExtensionType.MIHON,
            name = "Demo",
            pkgName = "eu.kanade.tachiyomi.extension.en.demo",
            versionName = "1.0.0",
            versionCode = 1L,
            libVersion = 1.0,
            lang = "en",
            isNsfw = false,
            sourceNames = listOf("Demo"),
            archiveName = "eu.kanade.tachiyomi.extension.en.demo-v1.0.0.apk",
            iconUrl = "",
            repoUrl = "https://repo.example.com",
            repoName = "Example",
            signatureHash = "abc",
            isCompatible = true,
        )
    }

    private fun origin(
        sourceKey: String = "MIHON_kototoro.test",
        kind: String = "MIHON",
        packageName: String? = null,
        repositoryUrl: String? = null,
        locator: String? = null,
        signingDigest: String? = null,
    ): SourceOriginEntity = SourceOriginEntity(
        sourceKey = sourceKey,
        kind = kind,
        displayName = "Example",
        contentType = "manga",
        packageName = packageName,
        sourceId = null,
        repositoryUrl = repositoryUrl,
        repositoryName = null,
        locator = locator,
        versionName = null,
        versionCode = null,
        signingDigest = signingDigest,
        lastSeenAt = null,
        updatedAt = 0L,
    )
}
