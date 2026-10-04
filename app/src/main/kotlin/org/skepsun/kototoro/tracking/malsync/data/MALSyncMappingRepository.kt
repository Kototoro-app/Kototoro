package org.skepsun.kototoro.tracking.malsync.data

import androidx.collection.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.malsync.MALSyncKind
import org.skepsun.kototoro.tracking.malsync.MALSyncMappingApi
import org.skepsun.kototoro.tracking.malsync.MALSyncService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a (service, remoteId) pair to the equivalent entries on other tracking sites using MALSync.
 *
 * Uses the free public endpoint: https://api.malsync.moe/{service}/{kind}/{id}
 * Response shape: { "Sites": { "Anilist": { "<id>": { identifier, url, title, ... }, ... }, ... } }
 */
@Singleton
class MALSyncMappingRepository @Inject constructor(
    private val api: MALSyncMappingApi,
) {

    enum class Kind(val slug: String) { MANGA("manga"), ANIME("anime") }

    data class Mapping(
        val service: ScrobblerService,
        val remoteId: Long,
        val title: String?,
        val url: String?,
    )

    private val cache = LruCache<String, List<Mapping>>(CACHE_SIZE)
    private val inflightMutex = Mutex()
    private val perKeyMutexes = mutableMapOf<String, Mutex>()

    suspend fun resolve(service: ScrobblerService, remoteId: Long, kind: Kind): List<Mapping> {
        val source = service.toMALSyncService() ?: return emptyList()
        val servicePath = source.apiPath ?: return emptyList()
        currentCoroutineContext().ensureActive()
        val key = "$servicePath:${kind.slug}:$remoteId"
        cache.get(key)?.let { return it }

        val mutex = inflightMutex.withLock {
            perKeyMutexes.getOrPut(key) { Mutex() }
        }
        return mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            val fetched = try {
                api.resolve(source, remoteId, kind.toMALSyncKind()).map {
                    Mapping(it.service.toScrobblerService(), it.remoteId, it.title, it.url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                emptyList()
            }
            currentCoroutineContext().ensureActive()
            cache.put(key, fetched)
            fetched
        }
    }

    private fun ScrobblerService.toMALSyncService(): MALSyncService? = when (this) {
        ScrobblerService.MAL -> MALSyncService.MAL
        ScrobblerService.ANILIST -> MALSyncService.ANILIST
        ScrobblerService.KITSU -> MALSyncService.KITSU
        ScrobblerService.SHIKIMORI -> MALSyncService.SHIKIMORI
        ScrobblerService.BANGUMI -> MALSyncService.BANGUMI
        ScrobblerService.MANGAUPDATES -> MALSyncService.MANGAUPDATES
        ScrobblerService.SIMKL -> null
    }

    private fun MALSyncService.toScrobblerService(): ScrobblerService = when (this) {
        MALSyncService.MAL -> ScrobblerService.MAL
        MALSyncService.ANILIST -> ScrobblerService.ANILIST
        MALSyncService.KITSU -> ScrobblerService.KITSU
        MALSyncService.SHIKIMORI -> ScrobblerService.SHIKIMORI
        MALSyncService.BANGUMI -> ScrobblerService.BANGUMI
        MALSyncService.MANGAUPDATES -> ScrobblerService.MANGAUPDATES
    }

    private fun Kind.toMALSyncKind(): MALSyncKind = when (this) {
        Kind.MANGA -> MALSyncKind.MANGA
        Kind.ANIME -> MALSyncKind.ANIME
    }

    private companion object {
        const val CACHE_SIZE = 64
    }
}
