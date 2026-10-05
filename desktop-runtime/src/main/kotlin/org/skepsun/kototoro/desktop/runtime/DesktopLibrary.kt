package org.skepsun.kototoro.desktop.runtime

import androidx.room.immediateTransaction
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.bookmarks.data.BookmarkEntity
import org.skepsun.kototoro.bookmarks.domain.novelBookmarkProgress
import org.skepsun.kototoro.bookmarks.domain.parseNovelBookmarkPreview
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.*
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity

/** Platform DTO projection over the existing shared schema, with no parallel library or history database. */
class DesktopLibrary(
    private val database: MangaDatabase,
    private val sourceLookup: (String) -> SourceRef?,
) {
    suspend fun favourites(): List<SourceContent> = database.getFavouritesDao()
        .findAllWithActiveCategory(0, Int.MAX_VALUE).distinctBy { it.manga.id }
        .map { content(it.manga, it.tags) }

    suspend fun history(): List<SourceContent> = database.getHistoryDao().findRecent(200)
        .map { content(it.manga, it.tags) }

    suspend fun progress(id: Long): HistoryEntity? = database.getHistoryDao().find(id)

    suspend fun snapshot(history: Boolean = false): DesktopLibrarySnapshot = database.useReaderConnection { connection ->
        connection.deferredTransaction {
            val categories = database.getFavouriteCategoriesDao().findAll()
            val categoryIds = categories.map { it.categoryId.toLong() }.toSet()
            val memberships = database.getFavouritesDao().findAllActiveEntries()
                .filter { membership -> membership.categoryId in categoryIds }
                .groupBy { it.mangaId }
            val items = if (history) history() else favourites()
            val progress = database.getHistoryDao().findAllByMangaIds(items.map { it.id }).associateBy { it.mangaId }
            DesktopLibrarySnapshot(items.map { item ->
                val member = memberships[item.id].orEmpty()
                val reading = progress[item.id]
                DesktopLibraryEntry(item, member.map { it.categoryId }.toSet(),
                    if (history) reading?.updatedAt ?: 0L else member.maxOfOrNull { it.updatedAt } ?: 0L,
                    reading?.updatedAt, reading?.percent)
            }, categories.map { DesktopLibraryCategory(it.categoryId.toLong(), it.title) })
        }
    }

    suspend fun bookmarks(contentId: Long): List<DesktopBookmark> = database.getBookmarksDao().observe(contentId)
        .first().map { it.desktopBookmark() }

    /** Toggle by reading position, as Android does, even if a source regenerates its page id. */
    suspend fun toggleBookmark(content: SourceContent, chapter: SourceChapter, page: SourcePage,
        index: Int, pageCount: Int, scroll: Float = 0f) {
        require(scroll.isFinite() && scroll >= 0f && scroll.toDouble() <= Int.MAX_VALUE.toDouble())
        require(page.source.name == chapter.source.name)
        toggleBookmarkPosition(content, chapter, index, pageCount, page.id, scroll.toInt(), page.preview ?: page.url)
    }

    /** Novel positions are engine block indexes; the schema's image field holds a plain text excerpt. */
    suspend fun toggleNovelBookmark(content: SourceContent, chapter: SourceChapter, block: Int,
        blockCount: Int, preview: String) {
        require(content.source.contentType in setOf("NOVEL", "HENTAI_NOVEL"))
        toggleBookmarkPosition(content, chapter, block, blockCount, null, 0, parseNovelBookmarkPreview(preview))
    }

    suspend fun removeBookmark(content: SourceContent, bookmark: DesktopBookmark) =
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
        pageCount: Int, pageId: Long?, scroll: Int, preview: String) = database.useWriterConnection { connection ->
        require(pageCount > 0 && index in 0 until pageCount)
        val branch = requireNotNull(content.chapters).filter { it.branch == chapter.branch }
        val chapterIndex = branch.indexOfFirst { it.id == chapter.id }
        require(chapterIndex >= 0 && branch[chapterIndex].url == chapter.url &&
            branch[chapterIndex].source.name == chapter.source.name)
        connection.immediateTransaction {
            val existing = database.getMangaDao().find(content.id)?.manga
            if (existing == null) saveInTransaction(content) else requireSameIdentity(existing, content)
            require(database.getChaptersDao().findAll(content.id).any {
                it.chapterId == chapter.id && it.branch == chapter.branch && it.url == chapter.url &&
                    it.source == chapter.source.name
            }) { "Refresh details before bookmarking a changed chapter" }
            val dao = database.getBookmarksDao()
            if (dao.observe(content.id, chapter.id, index).first() != null) {
                dao.delete(content.id, chapter.id, index)
            } else {
                val now = System.currentTimeMillis()
                var id = pageId ?: now
                if (pageId == null) while (dao.find(content.id, id) != null) id++
                dao.insert(BookmarkEntity(content.id, id, chapter.id, index, scroll,
                    preview, now,
                    novelBookmarkProgress(chapterIndex, branch.size, index, pageCount)))
            }
        }
    }

    private fun BookmarkEntity.desktopBookmark() =
        DesktopBookmark(mangaId, pageId, chapterId, page, scroll, createdAt, percent, imageUrl)

    suspend fun find(id: Long): SourceContent? = database.getMangaDao().find(id)?.let { content(it.manga, it.tags) }

    suspend fun isFavourite(id: Long): Boolean {
        val categories = database.getFavouritesDao().findCategories(id)
        return database.getFavouriteCategoriesDao().findByIds(categories).isNotEmpty()
    }

    suspend fun save(content: SourceContent) = database.useWriterConnection { connection ->
        connection.immediateTransaction { saveInTransaction(content) }
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
            val now = System.currentTimeMillis()
            val categories = database.getFavouriteCategoriesDao()
            val category = categories.findAll().firstOrNull { it.title == "收藏" }
            val categoryId = category?.categoryId?.toLong() ?: categories.insert(FavouriteCategoryEntity(
                categoryId = 0, createdAt = now, sortKey = categories.getNextSortKey(),
                // Android creates categories with update tracking on; the subscriptions page relies on it.
                title = "收藏", order = "NEWEST", track = true, isVisibleInLibrary = true, deletedAt = 0,
            ))
            val existing = database.getFavouritesDao().find(content.id, categoryId)
            database.getFavouritesDao().upsert(FavouriteEntity(
                mangaId = content.id, categoryId = categoryId, sortKey = existing?.sortKey ?: 0,
                isPinned = existing?.isPinned ?: false, createdAt = existing?.createdAt ?: now,
                updatedAt = now, deletedAt = 0,
            ))
        }
    }

    suspend fun recordPage(content: SourceContent, chapter: SourceChapter, page: Int, pageCount: Int,
        lastVisiblePage: Int = page, scroll: Float = 0f) =
        database.useWriterConnection { connection ->
            require(pageCount > 0 && page in 0 until pageCount)
            require(lastVisiblePage in page until pageCount)
            require(scroll.isFinite() && scroll >= 0f)
            val branch = requireNotNull(content.chapters).filter { it.branch == chapter.branch }
            val chapterIndex = branch.indexOfFirst { it.id == chapter.id }
            require(chapterIndex >= 0) { "Reading chapter is missing from this work" }
            connection.immediateTransaction {
                val existing = database.getMangaDao().find(content.id)?.manga
                if (existing == null) saveInTransaction(content) else requireSameIdentity(existing, content)
                require(database.getChaptersDao().findAll(content.id).any {
                    it.chapterId == chapter.id && it.branch == chapter.branch && it.url == chapter.url
                }) { "Refresh details before recording a changed chapter" }
                val now = System.currentTimeMillis()
                val previous = database.getHistoryDao().find(content.id)
                database.getHistoryDao().upsert(HistoryEntity(
                    mangaId = content.id, createdAt = previous?.createdAt ?: now, updatedAt = now,
                    chapterId = chapter.id, page = page, scroll = scroll,
                    percent = (chapterIndex + (lastVisiblePage + 1f) / pageCount) / branch.size,
                    deletedAt = 0, chaptersCount = branch.size, parentChapterId = null,
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

    private suspend fun content(entity: MangaEntity, tags: List<TagEntity>): SourceContent {
        val source = sourceLookup(entity.source) ?: SourceRef(entity.source, "", entity.contentType ?: "MANGA")
        val chapters = database.getChaptersDao().findAll(entity.id).map { chapter ->
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
