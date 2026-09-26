package org.skepsun.kototoro.scrobbling.common.domain

import androidx.annotation.FloatRange
import androidx.core.text.parseAsHtml
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.ext.findKeyByValue
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.sanitize
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobblingPreview
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import java.util.EnumMap

abstract class Scrobbler(
    protected val db: MangaDatabase,
    val scrobblerService: ScrobblerService,
    private val repository: ScrobblerRepository,
    private val mangaRepositoryFactory: ContentRepository.Factory,
) {

    private val infoCache = java.util.concurrent.ConcurrentHashMap<InfoCacheKey, ScrobblerContentInfo>()
    protected val statuses = EnumMap<ScrobblingStatus, String>(ScrobblingStatus::class.java)

    val user: Flow<ScrobblerUser> = kotlinx.coroutines.flow.flow {
        if (!repository.isAuthorized) {
            return@flow
        }
        val cached = repository.cachedUser
            ?: runCatchingCancellable {
                repository.loadUser()
            }.getOrNull()
        if (cached != null) {
            emit(cached)
        }
    }

    val isEnabled: Boolean
        get() = repository.isAuthorized

    suspend fun authorize(authCode: String): ScrobblerUser {
        repository.authorize(authCode)
        return repository.loadUser().also { user ->
            onAuthorized(user)
        }
    }

    protected open suspend fun onAuthorized(user: ScrobblerUser) = Unit

    /**
     * Sync library from remote service. Returns the count of synced items.
     * Override in subclasses that support remote library sync.
     */
    open suspend fun syncLibrary(): Int = -1

    fun logout() {
        repository.logout()
    }

    suspend fun findContent(query: String, offset: Int, isAnime: Boolean = false): List<ScrobblerContent> {
        return repository.findContent(query, offset, isAnime)
    }

    suspend fun linkContent(mangaId: Long, content: ScrobblerContent) {
        repository.createRate(mangaId, content)
    }

    suspend fun scrobble(manga: Content, chapterId: Long) {
        var chapters = manga.chapters
        if (chapters.isNullOrEmpty()) {
            chapters = mangaRepositoryFactory.create(manga.source).getDetails(manga).chapters
        }
        requireNotNull(chapters)
        val chapter = checkNotNull(chapters.findById(chapterId)) {
            "Chapter $chapterId not found in this manga"
        }
        val number = resolveAbsoluteChapterNumber(chapters, chapter)
        val entity = resolveScrobblingEntity(manga.id) ?: return
        repository.updateRate(entity.id, entity.mangaId, number)
    }

    suspend fun getScrobblingInfoOrNull(mangaId: Long): ScrobblingInfo? {
        val entity = resolveScrobblingEntity(mangaId) ?: return null
        return entity.toScrobblingInfo()
    }

    abstract suspend fun updateScrobblingInfo(
        mangaId: Long,
        @FloatRange(from = 0.0, to = 1.0) rating: Float,
        status: ScrobblingStatus?,
        comment: String?,
    )

    fun observeScrobblingInfo(mangaId: Long): Flow<ScrobblingInfo?> {
        return db.getScrobblingDao().observeByLocalManga(scrobblerService.id, mangaId)
            .distinctUntilChanged()
            .map { entity ->
                entity?.toScrobblingInfo()
            }
    }

    fun resolveStatus(statusValue: String?): ScrobblingStatus? {
        if (statusValue == null) return null
        return statuses.findKeyByValue(statusValue)
    }

    fun observeAllScrobblingInfo(): Flow<List<ScrobblingInfo>> {
        return db.getScrobblingDao().observe(scrobblerService.id)
            .map { entities ->
                coroutineScope {
                    entities.map {
                        async {
                            it.toScrobblingInfo()
                        }
                    }.awaitAll()
                }.filterNotNull()
            }
            // The `scrobblings` table can contain several rows that map to the same
            // (scrobbler, preferredLocalMangaId, targetId, mangaId, mediaType)
            // because the primary key also includes the rate `id`. Such rows
            // would produce identical LazyColumn keys and crash the config screen, so
            // collapse them. The SQL ordering already puts the preferred row first, and
            // distinctBy keeps the first occurrence.
            .map { infos -> infos.distinctBy { it.identityKey() } }
    }

    suspend fun warmUpScrobblingInfo(info: ScrobblingInfo) {
        warmUpScrobblingInfoInternal(info)
        invalidateInfoCache(info.targetId, info.mediaType.orEmpty())
    }

    suspend fun unregisterScrobbling(mangaId: Long) {
        val entity = resolveScrobblingEntity(mangaId) ?: return
        repository.unregister(entity.mangaId)
    }

    protected suspend fun requireScrobblingEntity(mangaId: Long): ScrobblingEntity {
        return requireNotNull(resolveScrobblingEntity(mangaId)) {
            "Scrobbling info for manga $mangaId not found"
        }
    }

    protected open suspend fun getContentInfo(entity: ScrobblingEntity): ScrobblerContentInfo {
        return repository.getContentInfo(entity.targetId)
    }

    protected open suspend fun warmUpScrobblingInfoInternal(info: ScrobblingInfo) = Unit

    protected open suspend fun fallbackScrobblingInfo(entity: ScrobblingEntity): ScrobblingInfo? = null

    private suspend fun ScrobblingEntity.toScrobblingInfo(): ScrobblingInfo? {
        val cacheKey = InfoCacheKey(
            targetId = targetId,
            mangaId = mangaId,
            mediaType = mediaType,
        )
        val mangaInfo = infoCache[cacheKey] ?: runCatchingCancellable {
            val cached = cachedContentInfo(this)
            android.util.Log.d(
                "Scrobbler",
                "toScrobblingInfo: service=${scrobblerService.name}, targetId=$targetId, cachedTitle=${cached?.name}, cachedCover=${cached?.cover}",
            )
            cached ?: getContentInfo(this).also { info ->
                cacheContentInfo(this, info)
            }
        }.onFailure {
            android.util.Log.w(
                "Scrobbler",
                "Failed to load content info: service=${scrobblerService.name}, targetId=$targetId, mangaId=$mangaId, mediaType=$mediaType",
                it,
            )
        }.onSuccess {
            infoCache[cacheKey] = it
        }.getOrNull()
        if (mangaInfo == null) {
            return fallbackScrobblingInfo(this)
        }
        val title = mangaInfo?.name ?: "#$targetId"
        val coverUrl = mangaInfo?.cover ?: ""
        android.util.Log.d(
            "Scrobbler",
            "toScrobblingInfo: service=${scrobblerService.name}, targetId=$targetId, finalCoverUrl=$coverUrl",
        )
        val description = mangaInfo?.descriptionHtml?.let { it.parseAsHtml().sanitize() } ?: ""
        val externalUrl = mangaInfo?.url ?: ""
        return ScrobblingInfo(
            scrobbler = scrobblerService,
            preferredLocalMangaId = mangaId.takeIf { it != 0L },
            mangaId = mangaId,
            targetId = targetId,
            status = statuses.findKeyByValue(status),
            chapter = chapter,
            comment = comment,
            rating = rating,
            title = title,
            coverUrl = coverUrl,
            description = description,
            externalUrl = externalUrl,
            mediaType = mediaType.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun cacheContentInfo(entity: ScrobblingEntity, info: ScrobblerContentInfo) {
        runCatchingCancellable {
            db.upsertScrobblingPreview(
                entity = entity,
                title = info.name.takeIf { it.isNotBlank() },
                coverUrl = info.cover.takeIf { it.isNotBlank() },
                url = info.url.takeIf { it.isNotBlank() },
            )
        }.onFailure {
            android.util.Log.w(
                "Scrobbler",
                "Failed to cache content info preview: service=${scrobblerService.name}, targetId=${entity.targetId}",
                it,
            )
        }
    }

    protected open fun cachedContentInfo(entity: ScrobblingEntity): ScrobblerContentInfo? {
        val title = entity.remoteTitle?.takeIf { it.isNotBlank() } ?: return null
        return ScrobblerContentInfo(
            id = entity.targetId,
            name = title,
            cover = entity.remoteCoverUrl.orEmpty(),
            url = entity.remoteUrl.orEmpty(),
            descriptionHtml = "",
        )
    }

    private fun invalidateInfoCache(targetId: Long, mediaType: String) {
        infoCache.entries.removeIf { (key, _) ->
            key.targetId == targetId && key.mediaType == mediaType
        }
    }

    private suspend fun resolveScrobblingEntity(mangaId: Long): ScrobblingEntity? {
        return db.getScrobblingDao().findByLocalManga(scrobblerService.id, mangaId)
    }

    private data class InfoCacheKey(
        val targetId: Long,
        val mangaId: Long,
        val mediaType: String,
    )
}

suspend fun Scrobbler.tryScrobble(manga: Content, chapterId: Long): Boolean {
    return runCatchingCancellable {
        scrobble(manga, chapterId)
    }.onFailure {
        it.printStackTraceDebug()
    }.isSuccess
}
