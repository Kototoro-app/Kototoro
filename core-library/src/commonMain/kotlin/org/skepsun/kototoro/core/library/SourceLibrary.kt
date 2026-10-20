package org.skepsun.kototoro.core.library

import androidx.room.immediateTransaction
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.skepsun.kototoro.bookmarks.data.BookmarkEntity
import org.skepsun.kototoro.bookmarks.domain.novelBookmarkProgress
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.*
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.migration.domain.MigrationChapter
import org.skepsun.kototoro.migration.domain.MigrationContentInput
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.migration.domain.MigrationPlanRules
import org.skepsun.kototoro.migration.domain.MigrationSnapshot

/** Platform DTO projection over the existing shared schema, with no parallel library or history database. */
class SourceLibrary(
    private val database: MangaDatabase,
    private val sourceLookup: (String) -> SourceRef?,
) {
    suspend fun favourites(withChapters: Boolean = true): List<SourceContent> = database.getFavouritesDao()
        .findAllWithActiveCategory(0, Int.MAX_VALUE).distinctBy { it.manga.id }
        .map { content(it.manga, it.tags, withChapters) }

    suspend fun history(withChapters: Boolean = true): List<SourceContent> = database.getHistoryDao().findRecent(200)
        .map { content(it.manga, it.tags, withChapters) }

    suspend fun progress(id: Long): HistoryEntity? = database.getHistoryDao().find(id)

    /** Collection pages can defer full chapter lists; last-read captions and progress stay in the snapshot. */
    suspend fun snapshot(
        history: Boolean = false,
        withChapters: Boolean = true,
    ): SourceLibrarySnapshot = database.useReaderConnection { connection ->
        connection.deferredTransaction {
            val categories = database.getFavouriteCategoriesDao().findAll()
            val categoryIds = categories.map { it.categoryId.toLong() }.toSet()
            val memberships = database.getFavouritesDao().findAllActiveEntries()
                .filter { membership -> membership.categoryId in categoryIds }
                .groupBy { it.mangaId }
            val items = if (history) history(withChapters) else favourites(withChapters)
            val progress = database.getHistoryDao().findAllByMangaIds(items.map { it.id }).associateBy { it.mangaId }
            // One lookup of just the chapters being read; works can hold thousands of chapters each.
            val chapters = if (history) {
                database.getChaptersDao().findReadingByMangaIds(progress.keys)
                    .associateBy { it.mangaId to it.chapterId }
            } else emptyMap()
            SourceLibrarySnapshot(items.map { item ->
                val member = memberships[item.id].orEmpty()
                val reading = progress[item.id]
                SourceLibraryEntry(item, member.map { it.categoryId }.toSet(),
                    if (history) reading?.updatedAt ?: 0L else member.maxOfOrNull { it.updatedAt } ?: 0L,
                    reading?.updatedAt, reading?.percent,
                    reading?.let { chapters[item.id to it.chapterId] }?.let { SourceLibraryChapter(it.title, it.number) })
            }, categories.map { SourceLibraryCategory(it.categoryId.toLong(), it.title) })
        }
    }

    suspend fun bookmarks(contentId: Long): List<SourceBookmark> = database.getBookmarksDao().observe(contentId)
        .first().map { it.desktopBookmark() }

    /** Toggle by reading position, as Android does, even if a source regenerates its page id. */
    suspend fun toggleBookmark(content: SourceContent, chapter: SourceChapter, page: SourcePage,
        index: Int, pageCount: Int, scroll: Float = 0f) {
        require(scroll.isFinite() && scroll >= 0f && scroll.toDouble() <= Int.MAX_VALUE.toDouble())
        require(page.source.name == chapter.source.name)
        toggleBookmarkPosition(content, chapter, index, pageCount, page.id, scroll.toInt(), page.preview ?: page.url, null)
    }

    /**
     * Novel positions are engine block indexes; the schema's image field holds a plain text excerpt. A chapter of a
     * downloaded book is bookmarked under its own id, placed by the download it came from ([part]).
     */
    suspend fun toggleNovelBookmark(content: SourceContent, chapter: SourceChapter, block: Int,
        blockCount: Int, preview: String, part: SourceChapterPart? = null) {
        require(content.source.contentType in setOf("NOVEL", "HENTAI_NOVEL"))
        toggleBookmarkPosition(content, chapter, block, blockCount, null, 0, sourceNovelBookmarkPreview(preview), part)
    }

    suspend fun removeBookmark(content: SourceContent, bookmark: SourceBookmark) =
        database.useWriterConnection { connection ->
            require(content.id == bookmark.contentId)
            connection.immediateTransaction {
                requireSameIdentity(requireNotNull(database.getMangaDao().find(content.id)).manga, content)
                val dao = database.getBookmarksDao()
                val stored = dao.find(content.id, bookmark.pageId)
                // A stale card must not delete a replacement bookmark at the same position.
                require(stored?.desktopBookmark() == bookmark) { "Bookmark has changed or no longer exists" }
                dao.delete(requireNotNull(stored))
            }
        }

    private suspend fun toggleBookmarkPosition(content: SourceContent, chapter: SourceChapter, index: Int,
        pageCount: Int, pageId: Long?, scroll: Int, preview: String, part: SourceChapterPart?,
    ) = database.useWriterConnection { connection ->
        require(pageCount > 0 && index in 0 until pageCount)
        val stored = part?.let { require(it.index in 0 until it.count); it.parent } ?: chapter
        val branch = requireNotNull(content.chapters).filter { it.branch == stored.branch }
        val storedIndex = branch.indexOfFirst { it.id == stored.id }
        require(storedIndex >= 0 && branch[storedIndex].url == stored.url &&
            branch[storedIndex].source.name == stored.source.name)
        // A book's chapter takes its share of the download's place in the branch.
        val parts = part?.count ?: 1
        val chapterIndex = storedIndex * parts + (part?.index ?: 0)
        connection.immediateTransaction {
            val existing = database.getMangaDao().find(content.id)?.manga
            if (existing == null) saveInTransaction(content) else requireSameIdentity(existing, content)
            require(database.getChaptersDao().findAll(content.id).any {
                it.chapterId == stored.id && it.branch == stored.branch && it.url == stored.url &&
                    it.source == stored.source.name
            }) { "Refresh details before bookmarking a changed chapter" }
            val dao = database.getBookmarksDao()
            if (dao.observe(content.id, chapter.id, index).first() != null) {
                dao.delete(content.id, chapter.id, index)
            } else {
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                var id = pageId ?: now
                if (pageId == null) while (dao.find(content.id, id) != null) id++
                dao.insert(BookmarkEntity(content.id, id, chapter.id, index, scroll,
                    preview, now,
                    novelBookmarkProgress(chapterIndex, branch.size * parts, index, pageCount)))
            }
        }
    }

    private fun BookmarkEntity.desktopBookmark() =
        SourceBookmark(mangaId, pageId, chapterId, page, scroll, createdAt, percent, imageUrl)

    suspend fun find(id: Long): SourceContent? = database.getMangaDao().find(id)?.let { content(it.manga, it.tags) }

    suspend fun findMany(ids: Collection<Long>): List<SourceContent> = ids.distinct().chunked(200).flatMap { chunk ->
        database.getMangaDao().findWithTagsByIds(chunk).map { content(it.manga, it.tags, withChapters = false) }
    }

    suspend fun isFavourite(id: Long): Boolean {
        val categories = database.getFavouritesDao().findCategories(id)
        return database.getFavouriteCategoriesDao().findByIds(categories).isNotEmpty()
    }

    suspend fun removeFavourite(id: Long) = database.useWriterConnection { connection ->
        connection.immediateTransaction { database.getFavouritesDao().delete(id) }
    }

    suspend fun removeHistory(id: Long) = database.getHistoryDao().delete(id)

    suspend fun setCategories(content: SourceContent, categoryIds: Set<Long>) = database.useWriterConnection { connection ->
        connection.immediateTransaction {
            val valid = database.getFavouriteCategoriesDao().findAll().map { it.categoryId.toLong() }.toSet()
            require(categoryIds.all { it in valid }) { "Category no longer exists" }
            saveInTransaction(content)
            val dao = database.getFavouritesDao()
            dao.findActiveByMangaId(content.id).filter { it.categoryId !in categoryIds }.forEach {
                dao.delete(content.id, it.categoryId)
            }
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            for (categoryId in categoryIds) {
                addMembership(content.id, categoryId, now)
            }
        }
    }

    /** Android's bulk category toggle: untouched memberships and richer stored details remain intact. */
    suspend fun updateCategories(contents: List<SourceContent>, included: Set<Long>, excluded: Set<Long>) {
        require(contents.isNotEmpty() && contents.map { it.id }.distinct().size == contents.size)
        require(included.intersect(excluded).isEmpty())
        if (included.isEmpty() && excluded.isEmpty()) return
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val valid = database.getFavouriteCategoriesDao().findAll().map { it.categoryId.toLong() }.toSet()
                require((included + excluded).all { it in valid }) { "Category no longer exists" }
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                for (content in contents) {
                    val existing = database.getMangaDao().find(content.id)?.manga
                    if (existing == null) {
                        if (included.isEmpty()) continue
                        saveInTransaction(content)
                    } else {
                        requireSameIdentity(existing, content)
                    }
                    for (categoryId in excluded) database.getFavouritesDao().delete(content.id, categoryId)
                    for (categoryId in included) addMembership(content.id, categoryId, now)
                }
            }
        }
    }

    private suspend fun addMembership(contentId: Long, categoryId: Long, now: Long) {
        val dao = database.getFavouritesDao()
        val previous = dao.find(contentId, categoryId)
        dao.upsert(FavouriteEntity(mangaId = contentId, categoryId = categoryId,
            sortKey = previous?.sortKey ?: 0, isPinned = previous?.isPinned ?: false,
            createdAt = previous?.createdAt ?: now, updatedAt = now, deletedAt = 0))
    }

    suspend fun save(content: SourceContent) = database.useWriterConnection { connection ->
        connection.immediateTransaction { saveInTransaction(content) }
    }

    /** Android's migration plan, committed with target details in one transaction; files and bookmarks stay owned
     * by the original work. The target's other categories and newer reading position are retained when merging. */
    suspend fun migrate(old: SourceContent, target: SourceContent, keepOriginal: Boolean) {
        require(old.id != target.id && old.source.name != target.source.name) { "请选择其他来源的作品" }
        require(old.source.contentType.removePrefix("HENTAI_") == target.source.contentType.removePrefix("HENTAI_")) {
            "只能换到同类型作品"
        }
        val targetChapters = target.chapters
        require(!targetChapters.isNullOrEmpty() && targetChapters.all { it.source.name == target.source.name }) {
            "目标作品没有可用章节"
        }
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                currentCoroutineContext().ensureActive()
                val storedOld = find(old.id)
                storedOld?.let { requireSameIdentity(requireNotNull(database.getMangaDao().find(old.id)).manga, old) }
                val original = storedOld?.takeIf { !it.chapters.isNullOrEmpty() } ?: old
                val dao = database.getMigrationDao()
                val favourites = database.getFavouritesDao()
                val history = database.getHistoryDao()
                val prefs = database.getPreferencesDao()
                val links = database.getTrackingSiteDao()
                val tracks = database.getTracksDao()
                val snapshot = MigrationSnapshot(favourites.findActiveByMangaId(old.id), history.find(old.id),
                    prefs.find(old.id), links.findLinksByManga(old.id), tracks.find(old.id),
                    dao.findNotes(old.id), dao.findSessions(old.id), dao.findJumpPoints(old.id))
                val previousTargetHistory = history.find(target.id)
                val plan = MigrationPlanRules.plan(original.migrationInput(snapshot.history?.chapterId),
                    target.migrationInput(null), snapshot,
                    if (keepOriginal) MigrationMode.COPY else MigrationMode.REPLACE,
                    MigrationDataFlag.ALL, kotlin.time.Clock.System.now().toEpochMilliseconds())
                saveInTransaction(target)
                if (plan.deleteOldFavourites) favourites.delete(old.id)
                for (entry in plan.favouritesToUpsert) {
                    // Existing target memberships keep their own pin and sorting preference.
                    if (favourites.find(target.id, entry.categoryId)?.deletedAt != 0L) favourites.upsert(entry)
                }
                if (plan.deleteOldHistory) history.delete(old.id)
                plan.historyToUpsert?.let {
                    if (previousTargetHistory == null || previousTargetHistory.updatedAt < it.updatedAt) history.upsert(it)
                }
                plan.prefsToUpsert?.let { if (prefs.find(target.id) == null) prefs.upsert(it) }
                plan.trackingLinksToDelete.forEach { links.deleteLink(it.service, it.remoteId, it.mangaId) }
                plan.trackingLinksToUpsert.forEach { links.upsertLink(it) }
                if (plan.deleteOldTrack) tracks.delete(old.id)
                plan.trackToUpsert?.let { tracks.upsert(it) }
                if (plan.notesToUpdate.isNotEmpty()) dao.updateNotes(plan.notesToUpdate)
                if (plan.notesToInsert.isNotEmpty()) dao.insertNotes(plan.notesToInsert)
                if (plan.moveStats) dao.moveStats(old.id, target.id)
                if (plan.sessionsToUpdate.isNotEmpty()) dao.updateSessions(plan.sessionsToUpdate)
                if (plan.jumpPointsToUpdate.isNotEmpty()) dao.updateJumpPoints(plan.jumpPointsToUpdate)
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private fun SourceContent.migrationInput(historyChapterId: Long?): MigrationContentInput {
        val chapters = chapters.orEmpty()
        val current = chapters.firstOrNull { it.id == historyChapterId }
        val branch = if (current != null) current.branch else chapters.groupBy { it.branch }.maxByOrNull { it.value.size }?.key
        return MigrationContentInput(id, source.name, chapters.map {
            MigrationChapter(it.id, it.volume, it.number, it.branch, it.uploadDate)
        }, branch)
    }

    /** The stored suggestions, most relevant first. */
    suspend fun suggestions(limit: Int): List<SourceContent> = database.getSuggestionDao().getTopContent(limit)
        .map { content(it.manga, it.tags) }

    /**
     * Replaces the suggestions table in one transaction, as Android's SuggestionRepository.replace does.
     * A work whose id collides with a different stored work is skipped instead of failing the batch.
     */
    suspend fun replaceSuggestions(items: List<Pair<SourceContent, Float>>, createdAt: Long): Int =
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val dao = database.getSuggestionDao()
                dao.deleteAll()
                var stored = 0
                for ((content, relevance) in items) {
                    val existing = database.getMangaDao().find(content.id)?.manga
                    if (existing != null && runCatching { requireSameIdentity(existing, content) }.isFailure) continue
                    // List results carry no description or chapters; a stored work keeps its richer row.
                    if (existing == null) saveInTransaction(content)
                    dao.upsert(org.skepsun.kototoro.suggestions.data.SuggestionEntity(content.id, relevance, createdAt))
                    stored++
                }
                stored
            }
        }

    suspend fun addFavourite(content: SourceContent) = database.useWriterConnection { connection ->
        connection.immediateTransaction {
            saveInTransaction(content)
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val categories = database.getFavouriteCategoriesDao()
            val category = categories.findAll().firstOrNull { it.title == "收藏" }
            val categoryId = category?.categoryId?.toLong() ?: categories.insert(FavouriteCategoryEntity(
                categoryId = 0, createdAt = now, sortKey = categories.getNextSortKey(),
                // Android creates categories with update tracking on; the subscriptions page relies on it.
                title = "收藏", order = "NEWEST", track = true, isVisibleInLibrary = true, deletedAt = 0,
            ))
            addMembership(content.id, categoryId, now)
        }
    }

    /**
     * A chapter of a downloaded book ([part]) is recorded under its own id with the download as its parent, as
     * Android records EPUB chapters; the download is what the stored chapter list holds.
     */
    suspend fun recordPage(content: SourceContent, chapter: SourceChapter, page: Int, pageCount: Int,
        lastVisiblePage: Int = page, scroll: Float = 0f, part: SourceChapterPart? = null) =
        database.useWriterConnection { connection ->
            require(pageCount > 0 && page in 0 until pageCount)
            require(lastVisiblePage in page until pageCount)
            require(scroll.isFinite() && scroll >= 0f)
            val stored = part?.let { require(it.index in 0 until it.count); it.parent } ?: chapter
            val branch = requireNotNull(content.chapters).filter { it.branch == stored.branch }
            val storedIndex = branch.indexOfFirst { it.id == stored.id }
            require(storedIndex >= 0) { "Reading chapter is missing from this work" }
            val parts = part?.count ?: 1
            val chapterIndex = storedIndex * parts + (part?.index ?: 0)
            connection.immediateTransaction {
                val existing = database.getMangaDao().find(content.id)?.manga
                if (existing == null) saveInTransaction(content) else requireSameIdentity(existing, content)
                require(database.getChaptersDao().findAll(content.id).any {
                    it.chapterId == stored.id && it.branch == stored.branch && it.url == stored.url
                }) { "Refresh details before recording a changed chapter" }
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val previous = database.getHistoryDao().find(content.id)
                database.getHistoryDao().upsert(HistoryEntity(
                    mangaId = content.id, createdAt = previous?.createdAt ?: now, updatedAt = now,
                    chapterId = chapter.id, page = page, scroll = scroll,
                    percent = (chapterIndex + (lastVisiblePage + 1f) / pageCount) / (branch.size * parts),
                    deletedAt = 0, chaptersCount = branch.size, parentChapterId = part?.parent?.id,
                ))
            }
        }

    private suspend fun saveInTransaction(content: SourceContent) {
        database.getMangaDao().find(content.id)?.manga?.let { requireSameIdentity(it, content) }
        val entity = MangaEntity(
            id = content.id, title = content.title, altTitles = content.altTitles.joinToString("\n"),
            url = content.url, publicUrl = content.publicUrl, rating = content.rating,
            isNsfw = content.contentRating == "ADULT" || content.source.contentType.startsWith("HENTAI_"),
            contentRating = content.contentRating, coverUrl = content.coverUrl.orEmpty(),
            largeCoverUrl = content.largeCoverUrl, state = content.state, authors = content.authors.joinToString("\n"),
            source = content.source.name, description = content.description,
            contentType = content.source.contentType, sourceData = content.sourceData,
        )
        val tags = content.tags.map { tag ->
            // Matches the existing Android TagEntity identity, including its signed 64-bit result.
            val id = "${tag.key}_${tag.source.name}".fold(1125899906842597L) { hash, char -> 31 * hash + char.code }
            TagEntity(id, tag.title, tag.key, tag.source.name, false)
        }
        val previousTags = database.getTagsDao().findByIds(tags.map { it.id }).associateBy { it.id }
        val stableTags = tags.map { tag ->
            val previous = previousTags[tag.id]
            require(previous == null || (previous.key == tag.key && previous.source == tag.source)) {
                "Tag identity collision"
            }
            tag.copy(isPinned = previous?.isPinned ?: tag.isPinned)
        }
        database.getTagsDao().upsert(stableTags)
        database.getMangaDao().upsert(entity, stableTags)
        content.chapters?.let { chapters ->
            database.getChaptersDao().replaceAll(content.id, chapters.mapIndexed { index, chapter ->
                ChapterEntity(chapter.id, content.id, chapter.title.orEmpty(), chapter.number, chapter.volume,
                    chapter.url, chapter.scanlator, chapter.uploadDate, chapter.branch, chapter.source.name,
                    index, chapter.sourceData)
            })
        }
    }

    private fun requireSameIdentity(existing: MangaEntity, content: SourceContent) {
        require(existing.source == content.source.name &&
            (existing.url == content.url || (existing.publicUrl.isNotBlank() && existing.publicUrl == content.publicUrl))) {
            "Content identity collision: ${content.id}"
        }
    }

    private suspend fun content(entity: MangaEntity, tags: List<TagEntity>, withChapters: Boolean = true): SourceContent {
        val source = sourceLookup(entity.source) ?: SourceRef(entity.source, "", entity.contentType ?: "MANGA")
        val chapters = (if (withChapters) database.getChaptersDao().findAll(entity.id) else emptyList()).map { chapter ->
            SourceChapter(chapter.chapterId, chapter.title.takeIf { it.isNotBlank() }, chapter.number, chapter.volume,
                chapter.url, chapter.scanlator, chapter.uploadDate, chapter.branch,
                sourceLookup(chapter.source) ?: source, chapter.sourceData)
        }
        return SourceContent(entity.id, entity.title, split(entity.altTitles), entity.url, entity.publicUrl,
            entity.rating, entity.contentRating, entity.coverUrl.takeIf { it.isNotBlank() },
            tags.map { SourceTag(it.title, it.key, sourceLookup(it.source) ?: source) }.toSet(),
            entity.state, split(entity.authors), source, entity.largeCoverUrl, entity.description,
            chapters.takeIf { it.isNotEmpty() }, entity.sourceData)
    }

    private fun split(value: String?): Set<String> = value?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()
}

/** Chapter [index] of the [count] inside a downloaded book, listed in place of the download ([parent]). */
data class SourceChapterPart(val parent: SourceChapter, val index: Int, val count: Int)
