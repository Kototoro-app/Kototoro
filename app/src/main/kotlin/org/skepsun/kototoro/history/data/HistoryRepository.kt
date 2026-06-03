package org.skepsun.kototoro.history.data

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentList
import org.skepsun.kototoro.core.db.entity.toContentTags
import org.skepsun.kototoro.core.db.entity.toContentTagsList
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.toContentSources
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.core.ui.util.ReversibleHandle
import org.skepsun.kototoro.core.util.ext.mapItems
import org.skepsun.kototoro.history.domain.model.ContentWithHistory
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.tryScrobble
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.tracker.domain.CheckNewChaptersUseCase
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class HistoryRepository @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val mangaRepository: ContentDataRepository,
	private val localObserver: HistoryLocalObserver,
	private val newChaptersUseCaseProvider: Provider<CheckNewChaptersUseCase>,
) {

	suspend fun getList(offset: Int, limit: Int): List<Content> {
		val entities = db.getHistoryDao().findAll(offset, limit)
		return entities.map { it.toContent() }
	}

	suspend fun search(query: String, kind: SearchKind, limit: Int): List<Content> {
		val dao = db.getHistoryDao()
		val q = "%$query%"
		val entities = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> dao.searchByTitle(q, limit).sortedBy { it.manga.title.levenshteinDistance(query) }

			SearchKind.AUTHOR -> dao.searchByAuthor(q, limit)
			SearchKind.TAG -> dao.searchByTag(q, limit)
			SearchKind.ADVANCED -> dao.searchByTitle(q, limit)
		}
		return entities.toContentList()
	}

	suspend fun getLastOrNull(): Content? {
		val entity = db.getHistoryDao().findAll(0, 1).firstOrNull() ?: return null
		return entity.toContent()
	}

	fun observeLast(): Flow<Content?> {
		return db.getHistoryDao().observeAll(1).map {
			val first = it.firstOrNull()
			first?.toContent()
		}
	}

	fun observeAll(): Flow<List<Content>> {
		return db.getHistoryDao().observeAll().mapItems {
			it.toContent()
		}
	}

	fun observeCount(): Flow<Int> {
		return db.getHistoryDao().observeCount()
	}

	fun observeAll(limit: Int): Flow<List<Content>> {
		return db.getHistoryDao().observeAll(limit).mapItems {
			it.toContent()
		}
	}

	fun observeAllWithHistory(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	): Flow<List<ContentWithHistory>> {
		if (ListFilterOption.Downloaded in filterOptions) {
			return localObserver.observeAll(order, filterOptions, limit)
		}
		return db.getHistoryDao().observeAll(order, filterOptions, limit).mapItems {
			ContentWithHistory(
				it.toContent(),
				it.history.toContentHistory(),
			)
		}
	}

	fun observeOne(id: Long): Flow<ContentHistory?> {
		return db.getHistoryDao().observe(id).map {
			android.util.Log.d("HistoryRepository", "observeOne: mangaId=$id, entity=${it?.let { "chapterId=${it.chapterId}, parentChapterId=${it.parentChapterId}" } ?: "null"}")
			it?.toContentHistory()
		}
	}

	suspend fun addOrUpdate(
		manga: Content, 
		chapterId: Long, 
		page: Int, 
		scroll: Int, 
		percent: Float, 
		force: Boolean,
		parentChapterId: Long? = null  // EPUB父章节ID，用于支持内部章节
	) {
		// 添加调用栈日志，帮助追踪谁在保存历史记录
		if (parentChapterId != null && chapterId == parentChapterId) {
			android.util.Log.w("HistoryRepository", "WARNING: chapterId == parentChapterId! This might be incorrect.")
			android.util.Log.w("HistoryRepository", "Stack trace:", Exception("Stack trace"))
		}
		
		if (!force && shouldSkip(manga)) {
			return
		}
		assert(manga.chapters != null)
		db.withTransaction {
			mangaRepository.storeContent(manga, replaceExisting = true)
			val branch = manga.chapters?.findById(chapterId)?.branch
			val entity = HistoryEntity(
				mangaId = manga.id,
				createdAt = System.currentTimeMillis(),
				updatedAt = System.currentTimeMillis(),
				chapterId = chapterId,
				page = page,
				scroll = scroll.toFloat(), // we migrate to int, but decide to not update database
				percent = percent,
				chaptersCount = manga.chapters?.count { it.branch == branch } ?: 0,
				deletedAt = 0L,
				parentChapterId = parentChapterId,  // 保存父章节ID
			)
			android.util.Log.d("HistoryRepository", "Upserting history: mangaId=${manga.id}, chapterId=$chapterId, parentChapterId=$parentChapterId")
			try {
				val result = db.getHistoryDao().upsert(entity)
				android.util.Log.d("HistoryRepository", "Upsert result: $result (true=inserted, false=updated)")
			} catch (e: Exception) {
				android.util.Log.e("HistoryRepository", "Upsert failed", e)
				throw e
			}
			newChaptersUseCaseProvider.get()(manga, chapterId)
			scrobblers.forEach { it.tryScrobble(manga, chapterId) }
		}
	}

	suspend fun getOne(manga: Content): ContentHistory? {
		val entity = db.getHistoryDao().find(manga.id)
		android.util.Log.d("HistoryRepository", "getOne: mangaId=${manga.id}, entity=${entity?.let { "chapterId=${it.chapterId}, parentChapterId=${it.parentChapterId}" } ?: "null"}")
		val recovered = entity?.recoverIfNeeded(manga)
		android.util.Log.d("HistoryRepository", "getOne after recover: ${recovered?.let { "chapterId=${it.chapterId}, parentChapterId=${it.parentChapterId}" } ?: "null"}")
		return recovered?.toContentHistory()
	}

	suspend fun getProgress(mangaId: Long, mode: ProgressIndicatorMode): ReadingProgress? {
		val entity = db.getHistoryDao().find(mangaId) ?: return null
		val fixedPercent = if (ReadingProgress.isCompleted(entity.percent)) 1f else entity.percent
		return ReadingProgress(
			percent = fixedPercent,
			totalChapters = entity.chaptersCount,
			mode = mode,
		).takeIf { it.isValid() }
	}

	suspend fun getProgress(mangaIds: Collection<Long>, mode: ProgressIndicatorMode): Map<Long, ReadingProgress> {
		if (mangaIds.isEmpty()) return emptyMap()
		return db.getHistoryDao().findProgress(mangaIds.toList()).mapNotNull { entry ->
			val fixedPercent = if (ReadingProgress.isCompleted(entry.percent)) 1f else entry.percent
			val progress = ReadingProgress(
				percent = fixedPercent,
				totalChapters = entry.chaptersCount,
				mode = mode,
			).takeIf { it.isValid() } ?: return@mapNotNull null
			entry.mangaId to progress
		}.toMap()
	}

	suspend fun clear() {
		db.getHistoryDao().clear()
	}

	suspend fun delete(manga: Content) = db.withTransaction {
		db.getHistoryDao().delete(manga.id)
		mangaRepository.gcChaptersCache()
	}

	suspend fun deleteAfter(minDate: Long) = db.withTransaction {
		db.getHistoryDao().deleteAfter(minDate)
		mangaRepository.gcChaptersCache()
	}

	suspend fun deleteNotFavorite() = db.withTransaction {
		db.getHistoryDao().deleteNotFavorite()
		mangaRepository.gcChaptersCache()
	}

	suspend fun delete(ids: Collection<Long>): ReversibleHandle {
		db.withTransaction {
			for (id in ids) {
				db.getHistoryDao().delete(id)
			}
			mangaRepository.gcChaptersCache()
		}
		return ReversibleHandle {
			recover(ids)
		}
	}

	/**
	 * Try to replace one manga with another one
	 * Useful for replacing saved manga on deleting it with remote source
	 */
	suspend fun deleteOrSwap(manga: Content, alternative: Content?) {
		if (alternative == null || db.getMangaDao().update(alternative.toEntity()) <= 0) {
			delete(manga)
		}
	}

	suspend fun getPopularTags(limit: Int): List<ContentTag> {
		return db.getHistoryDao().findPopularTags(limit).toContentTagsList()
	}

	suspend fun getPopularSources(limit: Int): List<ContentSource> {
		return db.getHistoryDao().findPopularSources(limit).toContentSources()
	}

	fun shouldSkip(manga: Content): Boolean = settings.isIncognitoModeEnabled(manga.isNsfw())

	fun observeShouldSkip(manga: Content): Flow<Boolean> {
		return settings.observe(AppSettings.KEY_INCOGNITO_MODE, AppSettings.KEY_INCOGNITO_NSFW)
			.map { shouldSkip(manga) }
			.distinctUntilChanged()
	}

	private suspend fun recover(ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) {
				db.getHistoryDao().recover(id)
			}
		}
	}

	private suspend fun HistoryEntity.recoverIfNeeded(manga: Content): HistoryEntity {
		val chapters = manga.chapters
		if (manga.isLocal || chapters.isNullOrEmpty() || chapters.findById(chapterId) != null) {
			return this
		}
		
		// 对于EPUB内部章节，不要尝试恢复
		// parentChapterId != null && parentChapterId != chapterId 表示这是EPUB内部章节
		// 详情页显示的是父章节列表，所以内部章节ID在列表中找不到是正常的
		if (parentChapterId != null && parentChapterId != chapterId) {
			android.util.Log.d("HistoryRepository", "Skipping recovery for EPUB internal chapter: $chapterId (parent: $parentChapterId)")
			return this
		}
		
		android.util.Log.w("HistoryRepository", "recoverIfNeeded: Chapter $chapterId not found in ${chapters.size} chapters, attempting recovery")
		android.util.Log.w("HistoryRepository", "First 3 chapter IDs: ${chapters.take(3).map { it.id }}")
		val newChapterId = chapters.getOrNull(
			(chapters.size * percent).toInt(),
		)?.id ?: return this
		android.util.Log.w("HistoryRepository", "Recovered: $chapterId -> $newChapterId (percent=$percent)")
		val newEntity = copy(chapterId = newChapterId)
		db.getHistoryDao().update(newEntity)
		return newEntity
	}

	private fun HistoryWithContent.toContent() = manga.toContent(tags.toContentTags(), null)
}
