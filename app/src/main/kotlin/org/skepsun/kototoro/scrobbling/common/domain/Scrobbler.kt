package org.skepsun.kototoro.scrobbling.common.domain

import androidx.annotation.FloatRange
import androidx.core.text.parseAsHtml
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.ext.findKeyByValue
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.sanitize
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.data.findByWorkOrMangaCandidates
import org.skepsun.kototoro.scrobbling.common.data.observeByWorkOrMangaCandidates
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobblingPreview
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.work.domain.WorkResolver
import java.util.EnumMap

abstract class Scrobbler(
	protected val db: MangaDatabase,
	val scrobblerService: ScrobblerService,
	private val repository: ScrobblerRepository,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val workResolver: WorkResolver,
) {

	private val infoCache = java.util.concurrent.ConcurrentHashMap<InfoCacheKey, ScrobblerContentInfo>()
	protected val statuses = EnumMap<ScrobblingStatus, String>(ScrobblingStatus::class.java)

	val user: Flow<ScrobblerUser> = flow {
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
		val context = resolveScrobblingContext(mangaId)
		repository.createRate(context.persistedLocalMangaId, content)
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
		return observeScrobblingContext(mangaId)
			.distinctUntilChanged()
			.flatMapLatest { context ->
				db.getScrobblingDao().observeByWorkOrMangaCandidates(
					scrobbler = scrobblerService.id,
					entityId = context.entityId,
					mangaIds = context.candidateMangaIds,
				)
					.map { entities ->
						selectScrobblingEntity(context, entities)?.toScrobblingInfo(context)
					}
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
	}

	suspend fun warmUpScrobblingInfo(info: ScrobblingInfo) {
		warmUpScrobblingInfoInternal(info)
		invalidateInfoCache(info.targetId, info.mediaType.orEmpty())
	}

	suspend fun unregisterScrobbling(mangaId: Long) {
		val context = resolveScrobblingContext(mangaId)
		val entity = resolveScrobblingEntity(context) ?: return
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

	private suspend fun ScrobblingEntity.toScrobblingInfo(
		context: ScrobblingContext = ScrobblingContext(
			entityId = entityId,
			requestedMangaId = mangaId,
			preferredLocalMangaId = mangaId.takeIf { it != 0L },
			persistedLocalMangaId = mangaId.takeIf { it != 0L } ?: 0L,
			candidateMangaIds = mangaId.takeIf { it != 0L }?.let(::listOf) ?: emptyList(),
		),
	): ScrobblingInfo? {
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
			entityId = context.entityId ?: entityId,
			preferredLocalMangaId = context.preferredLocalMangaId ?: mangaId.takeIf { it != 0L },
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
				workResolver = workResolver,
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
		return resolveScrobblingEntity(resolveScrobblingContext(mangaId))
	}

	private suspend fun resolveScrobblingEntity(context: ScrobblingContext): ScrobblingEntity? {
		val entities = db.getScrobblingDao().findByWorkOrMangaCandidates(
			scrobbler = scrobblerService.id,
			entityId = context.entityId,
			mangaIds = context.candidateMangaIds,
		)
		return selectScrobblingEntity(context, entities)
	}

	private fun observeScrobblingContext(mangaId: Long): Flow<ScrobblingContext> {
		return db.invalidationTracker.createFlow(
			tables = arrayOf(
				"entity_binding",
				"entity_preferences",
			),
			emitInitialState = true,
		).map {
			resolveScrobblingContext(mangaId)
		}
	}

	private suspend fun resolveScrobblingContext(mangaId: Long): ScrobblingContext {
		val identity = workResolver.resolveByMangaId(mangaId)
		val entityId = identity.entityId
		if (entityId == null) {
			return ScrobblingContext(
				entityId = null,
				requestedMangaId = mangaId,
				preferredLocalMangaId = mangaId,
				persistedLocalMangaId = mangaId,
				candidateMangaIds = listOf(mangaId),
			)
		}
		val localMangaIds = identity.localMangaIds
			.distinct()
			.filter { localId -> db.getMangaDao().contains(localId) }
		val preferredLocalMangaId = identity.preferredMangaId
			?.takeIf { preferredId -> db.getMangaDao().contains(preferredId) }
		val persistedLocalMangaId = preferredLocalMangaId
			?: localMangaIds.firstOrNull()
			?: mangaId
		val candidateMangaIds = buildList {
			add(mangaId)
			preferredLocalMangaId?.let(::add)
			addAll(localMangaIds)
		}.distinct()
		return ScrobblingContext(
			entityId = entityId,
			requestedMangaId = mangaId,
			preferredLocalMangaId = preferredLocalMangaId ?: persistedLocalMangaId,
			persistedLocalMangaId = persistedLocalMangaId,
			candidateMangaIds = candidateMangaIds.ifEmpty { listOf(mangaId) },
		)
	}

	private fun selectScrobblingEntity(
		context: ScrobblingContext,
		entities: List<ScrobblingEntity>,
	): ScrobblingEntity? {
		if (entities.isEmpty()) {
			return null
		}
		return entities.firstOrNull { it.mangaId == context.requestedMangaId }
			?: entities.firstOrNull { it.mangaId == context.preferredLocalMangaId }
			?: entities.firstOrNull { it.mangaId == context.persistedLocalMangaId }
			?: entities.first()
	}

	private data class InfoCacheKey(
		val targetId: Long,
		val mangaId: Long,
		val mediaType: String,
	)

	private data class ScrobblingContext(
		val entityId: Long?,
		val requestedMangaId: Long,
		val preferredLocalMangaId: Long?,
		val persistedLocalMangaId: Long,
		val candidateMangaIds: List<Long>,
	)
}

suspend fun Scrobbler.tryScrobble(manga: Content, chapterId: Long): Boolean {
	return runCatchingCancellable {
		scrobble(manga, chapterId)
	}.onFailure {
		it.printStackTraceDebug()
	}.isSuccess
}
