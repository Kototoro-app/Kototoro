package org.skepsun.kototoro.tracking.malsync.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.malsync.MALSyncKind
import org.skepsun.kototoro.tracking.malsync.MALSyncMapping
import org.skepsun.kototoro.tracking.malsync.MALSyncMappingApi
import org.skepsun.kototoro.tracking.malsync.MALSyncService

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MALSyncMappingRepositoryTest {
    private val api = mockk<MALSyncMappingApi>()
    private val repository = MALSyncMappingRepository(api)
    private val manga = MALSyncMappingRepository.Kind.MANGA
    private val anime = MALSyncMappingRepository.Kind.ANIME
    private val mapping = MALSyncMapping(MALSyncService.ANILIST, 8, "Title", "/path")

    @Test
    fun `UI models are preserved and successful result is cached`() = runTest {
        coEvery { api.resolve(MALSyncService.MAL, 1, MALSyncKind.MANGA) } returns listOf(mapping)
        val expected = listOf(MALSyncMappingRepository.Mapping(ScrobblerService.ANILIST, 8, "Title", "/path"))
        assertEquals(expected, repository.resolve(ScrobblerService.MAL, 1, manga))
        assertEquals(expected, repository.resolve(ScrobblerService.MAL, 1, manga))
        coVerify(exactly = 1) { api.resolve(MALSyncService.MAL, 1, MALSyncKind.MANGA) }
    }

    @Test
    fun `all six remote services map back to existing UI enum`() = runTest {
        coEvery { api.resolve(any(), any(), any()) } returns MALSyncService.entries.map { mapping.copy(service = it) }
        assertEquals(
            listOf(ScrobblerService.MAL, ScrobblerService.ANILIST, ScrobblerService.KITSU,
                ScrobblerService.SHIKIMORI, ScrobblerService.BANGUMI, ScrobblerService.MANGAUPDATES),
            repository.resolve(ScrobblerService.MAL, 1, manga).map { it.service },
        )
    }

    @Test
    fun `unsupported source services return empty without fetching`() = runTest {
        for (service in listOf(ScrobblerService.BANGUMI, ScrobblerService.MANGAUPDATES, ScrobblerService.SIMKL)) {
            assertEquals(emptyList<MALSyncMappingRepository.Mapping>(), repository.resolve(service, 1, manga))
        }
        coVerify(exactly = 0) { api.resolve(any(), any(), any()) }
    }

    @Test
    fun `service media kind and remote id use independent cache entries`() = runTest {
        coEvery { api.resolve(any(), any(), any()) } returns emptyList()
        repository.resolve(ScrobblerService.MAL, 1, manga)
        repository.resolve(ScrobblerService.MAL, 1, anime)
        repository.resolve(ScrobblerService.KITSU, 1, manga)
        repository.resolve(ScrobblerService.SHIKIMORI, 1, manga)
        repository.resolve(ScrobblerService.ANILIST, 2, manga)
        coVerify(exactly = 5) { api.resolve(any(), any(), any()) }
        coVerify(exactly = 1) { api.resolve(MALSyncService.MAL, 1, MALSyncKind.ANIME) }
        coVerify(exactly = 1) { api.resolve(MALSyncService.KITSU, 1, MALSyncKind.MANGA) }
        coVerify(exactly = 1) { api.resolve(MALSyncService.SHIKIMORI, 1, MALSyncKind.MANGA) }
        coVerify(exactly = 1) { api.resolve(MALSyncService.ANILIST, 2, MALSyncKind.MANGA) }
    }

    @Test
    fun `ordinary network or parse failure retains cached empty fallback`() = runTest {
        for ((id, failure) in listOf(java.io.IOException("offline"), IllegalArgumentException("JSON")).withIndex()) {
            coEvery { api.resolve(any(), id.toLong(), any()) } throws failure
            repeat(2) {
                assertEquals(emptyList<MALSyncMappingRepository.Mapping>(),
                    repository.resolve(ScrobblerService.MAL, id.toLong(), manga))
            }
            coVerify(exactly = 1) { api.resolve(any(), id.toLong(), any()) }
        }
    }

    @Test
    fun `same key callers share one fetch`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { api.resolve(any(), any(), any()) } coAnswers { release.await(); listOf(mapping) }
        val calls = List(5) { async { repository.resolve(ScrobblerService.MAL, 1, manga) } }
        runCurrent()
        coVerify(exactly = 1) { api.resolve(any(), any(), any()) }
        release.complete(Unit)
        assertTrue(calls.awaitAll().all { it.single().remoteId == 8L })
        coVerify(exactly = 1) { api.resolve(any(), any(), any()) }
    }

    @Test
    fun `different keys fetch concurrently`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { api.resolve(any(), any(), any()) } coAnswers { release.await(); emptyList() }
        val calls = listOf(1L, 2L).map { id -> async { repository.resolve(ScrobblerService.MAL, id, manga) } }
        runCurrent()
        coVerify(exactly = 2) { api.resolve(any(), any(), any()) }
        release.complete(Unit)
        calls.awaitAll()
    }

    @Test
    fun `LRU remains bounded at 64 and recent hit protects entry from eviction`() = runTest {
        coEvery { api.resolve(any(), any(), any()) } returns emptyList()
        for (id in 1L..64L) repository.resolve(ScrobblerService.MAL, id, manga)
        repository.resolve(ScrobblerService.MAL, 1, manga)
        repository.resolve(ScrobblerService.MAL, 65, manga)
        repository.resolve(ScrobblerService.MAL, 1, manga)
        repository.resolve(ScrobblerService.MAL, 2, manga)
        coVerify(exactly = 1) { api.resolve(any(), 1, any()) }
        coVerify(exactly = 2) { api.resolve(any(), 2, any()) }
    }

    @Test
    fun `cancellation is propagated and never cached as empty`() = runTest {
        coEvery { api.resolve(any(), any(), any()) } throws CancellationException("cancel")
        val error = runCatching { repository.resolve(ScrobblerService.MAL, 1, manga) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        coEvery { api.resolve(any(), any(), any()) } returns listOf(mapping)
        assertEquals(8L, repository.resolve(ScrobblerService.MAL, 1, manga).single().remoteId)
        coVerify(exactly = 2) { api.resolve(any(), any(), any()) }
    }

    @Test
    fun `cancelling fetch releases per key lock for waiting caller`() = runTest {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        coEvery { api.resolve(any(), any(), any()) } coAnswers {
            attempts++
            if (attempts == 1) release.await()
            listOf(mapping)
        }
        val first = launch { repository.resolve(ScrobblerService.MAL, 1, manga) }
        runCurrent()
        val second = async { repository.resolve(ScrobblerService.MAL, 1, manga) }
        runCurrent()
        first.cancelAndJoin()
        assertEquals(8L, second.await().single().remoteId)
        assertEquals(2, attempts)
    }

    @Test
    fun `cancelled coroutine with ordinary IO error cannot poison cache`() = runTest {
        coEvery { api.resolve(any(), any(), any()) } coAnswers {
            currentCoroutineContext().cancel()
            throw java.io.IOException("cancelled socket")
        }
        val call = async { repository.resolve(ScrobblerService.MAL, 1, manga) }
        val error = runCatching { call.await() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        coEvery { api.resolve(any(), any(), any()) } returns listOf(mapping)
        assertEquals(8L, repository.resolve(ScrobblerService.MAL, 1, manga).single().remoteId)
    }

    @Test
    fun `cancellation just before successful result prevents cache insertion`() = runTest {
        coEvery { api.resolve(any(), any(), any()) } coAnswers {
            currentCoroutineContext().cancel()
            listOf(mapping)
        }
        val call = async { repository.resolve(ScrobblerService.MAL, 1, manga) }
        assertTrue(runCatching { call.await() }.exceptionOrNull() is CancellationException)
        coEvery { api.resolve(any(), any(), any()) } returns listOf(mapping.copy(remoteId = 9))
        assertEquals(9L, repository.resolve(ScrobblerService.MAL, 1, manga).single().remoteId)
    }
}
