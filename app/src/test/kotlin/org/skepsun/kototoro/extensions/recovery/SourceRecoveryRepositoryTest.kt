package org.skepsun.kototoro.extensions.recovery

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.dao.SourceOriginsDao
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity

class SourceRecoveryRepositoryTest {

    private val dao = mockk<SourceOriginsDao>(relaxed = true)
    // Relaxed snapshot: nothing installed, no signature info -> statuses derive purely
    // from the origin fields.
    private val snapshot = mockk<SourceRuntimeSnapshot>(relaxed = true)
    private val refsProvider = mockk<SourceReferenceProvider> {
        every { referencedSourceKeys() } returns emptySet()
    }
    private val repository = SourceRecoveryRepository(dao, snapshot, refsProvider)

    @Test
    fun `deriveAll preserves storage order and derives statuses`() = runTest {
        val repoOrigin = origin(sourceKey = "key-repo", repositoryUrl = "https://repo.example.com/index.min.json")
        val pkgOrigin = origin(sourceKey = "key-pkg", kind = "MIHON", packageName = "com.example.src")
        val missingOrigin = origin(sourceKey = "key-unknown", kind = "UNKNOWN")
        coEvery { dao.findAll() } returns listOf(repoOrigin, pkgOrigin, missingOrigin)

        val states = repository.deriveAll()

        assertEquals(
            listOf("key-repo", "key-pkg", "key-unknown"),
            states.map { it.origin.sourceKey },
        )
        assertEquals(SourceRecoveryStatus.REPOSITORY_REQUIRED, states[0].status)
        assertEquals(SourceRecoveryStatus.SIDELOAD_REQUIRED, states[1].status)
        assertEquals(SourceRecoveryStatus.MISSING, states[2].status)
    }

    @Test
    fun `deriveAll merges referenced flag from provider`() = runTest {
        every { refsProvider.referencedSourceKeys() } returns setOf("key-referenced")
        val referencedOrigin = origin(sourceKey = "key-referenced", repositoryUrl = "https://repo.example.com")
        val orphanOrigin = origin(sourceKey = "key-orphan", repositoryUrl = "https://repo.example.com")
        coEvery { dao.findAll() } returns listOf(referencedOrigin, orphanOrigin)

        val states = repository.deriveAll()

        assertTrue(states[0].referenced)
        assertFalse(states[1].referenced)
    }

    @Test
    fun `statusOf derives status for known key`() = runTest {
        val origin = origin(sourceKey = "key-pkg", kind = "TSUNDOKU", packageName = "com.example.tsu")
        coEvery { dao.getByKey("key-pkg") } returns origin

        assertEquals(SourceRecoveryStatus.SIDELOAD_REQUIRED, repository.statusOf("key-pkg"))
    }

    @Test
    fun `statusOf returns null for unknown key`() = runTest {
        coEvery { dao.getByKey("key-absent") } returns null

        assertNull(repository.statusOf("key-absent"))
    }

    @Test
    fun `upsert forwards to dao`() = runTest {
        val origin = origin(sourceKey = "key-upsert")

        repository.upsert(origin)

        coVerify(exactly = 1) { dao.upsert(origin) }
    }

    @Test
    fun `remove forwards to dao`() = runTest {
        repository.remove("key-remove")

        coVerify(exactly = 1) { dao.deleteByKey("key-remove") }
    }

    @Test
    fun `countByKey forwards to dao`() = runTest {
        coEvery { dao.countByKey("key-count") } returns 3

        assertEquals(3, repository.countByKey("key-count"))
    }

    @Test
    fun `observeAll emits derived states with referenced merge`() = runTest {
        every { refsProvider.referencedSourceKeys() } returns setOf("key-a")
        val a = origin(sourceKey = "key-a", kind = "MIHON")
        val b = origin(sourceKey = "key-b", kind = "UNKNOWN")
        coEvery { dao.observeAll() } returns flowOf(listOf(a, b))

        val states = repository.observeAll().first()

        assertEquals(
            listOf("key-a", "key-b"),
            states.map { it.origin.sourceKey },
        )
        assertEquals(SourceRecoveryStatus.SIDELOAD_REQUIRED, states[0].status)
        assertEquals(SourceRecoveryStatus.MISSING, states[1].status)
        assertTrue(states[0].referenced)
        assertFalse(states[1].referenced)
    }

    @Test
    fun `referencedSourceKeys delegates to provider`() = runTest {
        every { refsProvider.referencedSourceKeys() } returns setOf("key-a", "key-b")

        assertEquals(setOf("key-a", "key-b"), repository.referencedSourceKeys())
    }

    /**
     * Builds an origin supplying every field explicitly so the test compiles against any
     * default-less constructor the entity owner ships.
     */
    private fun origin(
        sourceKey: String = "MIHON_kototoro.test",
        kind: String = "MIHON",
        displayName: String? = "Example",
        contentType: String? = "manga",
        packageName: String? = null,
        sourceId: String? = null,
        repositoryUrl: String? = null,
        repositoryName: String? = null,
        locator: String? = null,
        versionName: String? = null,
        versionCode: Long? = null,
        signingDigest: String? = null,
        lastSeenAt: Long? = null,
        updatedAt: Long = 0L,
    ): SourceOriginEntity = SourceOriginEntity(
        sourceKey = sourceKey,
        kind = kind,
        displayName = displayName,
        contentType = contentType,
        packageName = packageName,
        sourceId = sourceId,
        repositoryUrl = repositoryUrl,
        repositoryName = repositoryName,
        locator = locator,
        versionName = versionName,
        versionCode = versionCode,
        signingDigest = signingDigest,
        lastSeenAt = lastSeenAt,
        updatedAt = updatedAt,
    )
}
