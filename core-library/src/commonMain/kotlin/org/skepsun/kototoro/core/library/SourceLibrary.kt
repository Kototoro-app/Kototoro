package org.skepsun.kototoro.core.library

import androidx.room.immediateTransaction
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.TimeZone
import org.skepsun.kototoro.bookmarks.data.BookmarkEntity
import org.skepsun.kototoro.bookmarks.domain.novelBookmarkProgress
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.*
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.tracker.domain.completeUpdateChapter
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.migration.domain.MigrationChapter
import org.skepsun.kototoro.migration.domain.MigrationContentInput
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.migration.domain.MigrationPlanRules
import org.skepsun.kototoro.migration.domain.MigrationSnapshot
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import org.skepsun.kototoro.stats.domain.ReadingStatsWindow
import org.skepsun.kototoro.stats.domain.StatsContentKind
import org.skepsun.kototoro.stats.domain.deriveReadingStatistics

/** Platform DTO projection over the existing shared schema, with no parallel library or history database. */
class SourceLibrary(
    private val database: MangaDatabase,
    /** The clock the reading stop measures with; a host that has one already passes it, tests move it. */
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val sourceLookup: (String) -> SourceRef?,
) {
    /** Android keeps its collector in the reader view model; the library holds the open stop for the host instead. */
    private val readingSessions = ReadingSessionTracker(clock)

    /**
     * Android's `stats_on`. With statistics switched off the reader still saves history and progress, but the tracker
     * writes no session or statistics row — Android's `StatsCollector` is simply not attached. The host keeps this in
     * step with its own services setting; it starts on, which is Android's default.
     */
    var statsRecording: Boolean = true

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
            val ids = items.map { it.id }
            // Room expands `IN (:ids)` into a literal list, so an empty shelf has to skip the bulk queries.
            val progress = if (ids.isEmpty()) {
                emptyMap()
            } else {
                database.getHistoryDao().findAllByMangaIds(ids).associateBy { it.mangaId }
            }
            // The macro filters read the same rows Android's snapshot carries: the stored nsfw flag and the
            // update checker's new-chapter counter. Both are one bulk query, not a per-card lookup.
            val nsfwIds: Set<Long> = if (ids.isEmpty()) {
                emptySet()
            } else {
                database.getMangaDao().findEntitiesByIds(ids)
                    .mapNotNullTo(HashSet()) { if (it.isNsfw) it.id else null }
            }
            val newChapters: Map<Long, Int> = if (ids.isEmpty()) {
                emptyMap()
            } else {
                database.getTracksDao().findNewChapters(ids).associate { it.mangaId to it.count }
            }
            // One lookup of just the chapters being read; works can hold thousands of chapters each.
            val chapters = if (history && progress.isNotEmpty()) {
                database.getChaptersDao().findReadingByMangaIds(progress.keys)
                    .associateBy { it.mangaId to it.chapterId }
            } else emptyMap()
            SourceLibrarySnapshot(items.map { item ->
                val member = memberships[item.id].orEmpty()
                val reading = progress[item.id]
                SourceLibraryEntry(item, member.map { it.categoryId }.toSet(),
                    if (history) reading?.updatedAt ?: 0L else member.maxOfOrNull { it.updatedAt } ?: 0L,
                    reading?.updatedAt, reading?.percent,
                    reading?.let { chapters[item.id to it.chapterId] }?.let { SourceLibraryChapter(it.title, it.number) },
                    item.id in nsfwIds, newChapters[item.id] ?: 0,
                    // A work can sit in several categories; Android's DAO reads MAX(pinned), i.e. any row may pin it.
                    isPinned = member.any { it.isPinned })
            }, categories.map { SourceLibraryCategory(it.categoryId.toLong(), it.title) })
        }
    }

    /** The tab rail in Android's category order; cheap enough to re-read after every edit. */
    suspend fun categories(): List<SourceLibraryCategory> = database.getFavouriteCategoriesDao().findAll()
        .map { SourceLibraryCategory(it.categoryId.toLong(), it.title) }

    /**
     * Android's favourites-category screen lives in the shared layer so every platform edits the same rows:
     * creating appends to the tab rail, renaming only touches the title, and deleting takes the memberships
     * with it (the work itself stays favourited nowhere, exactly like Android's `removeCategories`).
     */
    suspend fun createCategory(
        title: String,
        order: ListSortOrder = ListSortOrder.NEWEST,
        track: Boolean = false,
        onShelf: Boolean = true,
    ): Long {
        val name = title.trim()
        require(name.isNotEmpty()) { "a category needs a title" }
        val dao = database.getFavouriteCategoriesDao()
        return dao.insert(FavouriteCategoryEntity(
            categoryId = 0,
            createdAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            sortKey = dao.getNextSortKey(),
            title = name,
            order = order.name,
            track = track,
            isVisibleInLibrary = onShelf,
            deletedAt = 0L,
        )).toLong()
    }

    suspend fun renameCategory(id: Long, title: String) {
        val name = title.trim()
        require(name.isNotEmpty()) { "a category needs a title" }
        val dao = database.getFavouriteCategoriesDao()
        val stored = dao.findIncludingDeleted(id) ?: return
        dao.update(id, name, stored.order, stored.track, stored.isVisibleInLibrary)
    }

    suspend fun setCategoryTracking(id: Long, enabled: Boolean) =
        database.getFavouriteCategoriesDao().updateTracking(id, enabled)

    suspend fun setCategoryVisibility(id: Long, visible: Boolean) =
        database.getFavouriteCategoriesDao().updateVisibility(id, visible)

    suspend fun removeCategories(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                for (id in ids.distinct()) {
                    database.getFavouritesDao().deleteAll(id)
                    database.getFavouriteCategoriesDao().delete(id)
                }
                database.getChaptersDao().gc()
            }
        }
    }

    suspend fun reorderCategories(orderedIds: List<Long>) {
        val dao = database.getFavouriteCategoriesDao()
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                for ((index, id) in orderedIds.withIndex()) {
                    dao.updateSortKey(id, index)
                }
            }
        }
    }

    suspend fun bookmarks(contentId: Long): List<SourceBookmark> = database.getBookmarksDao().observe(contentId)
        .first().map { it.desktopBookmark() }

    /**
     * Android's `BookmarksRepository.observeBookmarks()`: every work that has saved positions, with those positions.
     * One joined read, so a shelf of the whole table does not ask the source for anything; works come back without
     * chapters, which only the reader needs. Groups keep the DAO's per-work `percent` order. Chapter titles are
     * resolved from this work's chapter table in one more read, because the sections hold works that are not open.
     */
    suspend fun bookmarkGroups(): List<SourceBookmarkGroup> {
        val rows = database.getBookmarksDao().observe().first()
        val titles = database.getChaptersDao()
            .findByIds(rows.values.flatten().mapTo(HashSet()) { it.chapterId })
            .associate { it.chapterId to it.title }
        return rows.map { (row, saved) ->
            SourceBookmarkGroup(content(row.manga, row.tags, withChapters = false),
                saved.map { entity -> entity.desktopBookmark().copy(chapterTitle = titles[entity.chapterId].orEmpty()) })
        }
    }

    /**
     * Android's `removeBookmarks(ids)` for cards held across works: one transaction, by page id, so a grid can drop a
     * whole selection without re-reading each work. Returns how many rows are gone.
     */
    suspend fun removeBookmarks(pageIds: Collection<Long>): Int = database.useWriterConnection { connection ->
        connection.immediateTransaction {
            val dao = database.getBookmarksDao()
            pageIds.distinct().sumOf { dao.delete(it) }
        }
    }

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

    /**
     * Android's chapter card bookmark: the whole chapter at its first page, without a reading position.
     * The chapter id doubles as the page id, so one chapter holds at most one such card.
     */
    suspend fun addChapterBookmark(content: SourceContent, chapter: SourceChapter) =
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val existing = database.getMangaDao().find(content.id)?.manga
                if (existing == null) saveInTransaction(content) else requireSameIdentity(existing, content)
                require(database.getChaptersDao().findAll(content.id).any {
                    it.chapterId == chapter.id && it.branch == chapter.branch && it.url == chapter.url &&
                        it.source == chapter.source.name
                }) { "Refresh details before bookmarking a changed chapter" }
                val dao = database.getBookmarksDao()
                if (dao.find(content.id, chapter.id) == null) {
                    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    dao.insert(BookmarkEntity(content.id, chapter.id, chapter.id, 0, 0,
                        content.coverUrl.orEmpty(), now, 0f))
                }
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

    suspend fun findMany(ids: Collection<Long>, withChapters: Boolean = false): List<SourceContent> =
        ids.distinct().chunked(200).flatMap { chunk ->
            database.getMangaDao().findWithTagsByIds(chunk)
                .map { content(it.manga, it.tags, withChapters = withChapters) }
        }

    suspend fun isFavourite(id: Long): Boolean {
        val categories = database.getFavouritesDao().findCategories(id)
        return database.getFavouriteCategoriesDao().findByIds(categories).isNotEmpty()
    }

    /**
     * Android's batch pin writes the flag on the favourites rows, so it survives a reload and the shelf can order by
     * it; works that are not favourites have no row to write and are simply left alone.
     */
    suspend fun setPinned(ids: Collection<Long>, isPinned: Boolean) =
        database.getFavouritesDao().setPinned(ids.toList(), isPinned)

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
                migrateInTransaction(old, target, if (keepOriginal) MigrationMode.COPY else MigrationMode.REPLACE,
                    MigrationDataFlag.ALL, forceHistory = false, keepTargetTrack = false)
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /**
     * Android's duplicate merge: every duplicate folds into [keep] in one transaction. Categories, notes, stats and
     * tracking links move over; only [progressFrom] (the duplicate read furthest beyond [keep]) moves its reading
     * progress, so the furthest progress survives. The duplicates leave favourites and history, but their rows stay
     * (downloads, bookmarks), and the kept entry's own update tracking is kept.
     */
    suspend fun mergeDuplicates(keep: SourceContent, duplicates: List<SourceContent>, progressFrom: Long?) {
        require(duplicates.isNotEmpty() && duplicates.none { it.id == keep.id } &&
            duplicates.map { it.id }.distinct().size == duplicates.size)
        require(progressFrom == null || duplicates.any { it.id == progressFrom })
        require(progressFrom == null || !keep.chapters.isNullOrEmpty()) { "无法读取保留作品的章节，进度不能合并" }
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                currentCoroutineContext().ensureActive()
                requireSameIdentity(requireNotNull(database.getMangaDao().find(keep.id)) { "保留的作品已不在作品库" }.manga, keep)
                for (duplicate in duplicates) {
                    val takeProgress = duplicate.id == progressFrom
                    migrateInTransaction(duplicate, keep, MigrationMode.REPLACE,
                        if (takeProgress) MigrationDataFlag.ALL else MigrationDataFlag.ALL - MigrationDataFlag.PROGRESS,
                        forceHistory = takeProgress, keepTargetTrack = true)
                    // The duplicate is gone from the library; do not leave it behind in history.
                    database.getHistoryDao().delete(duplicate.id)
                }
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun migrateInTransaction(
        old: SourceContent,
        target: SourceContent,
        mode: MigrationMode,
        flags: Set<MigrationDataFlag>,
        forceHistory: Boolean,
        keepTargetTrack: Boolean,
    ) {
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
            target.migrationInput(null), snapshot, mode, flags, kotlin.time.Clock.System.now().toEpochMilliseconds())
        saveInTransaction(target)
        if (plan.deleteOldFavourites) favourites.delete(old.id)
        for (entry in plan.favouritesToUpsert) {
            // Existing target memberships keep their own pin and sorting preference.
            if (favourites.find(target.id, entry.categoryId)?.deletedAt != 0L) favourites.upsert(entry)
        }
        if (plan.deleteOldHistory) history.delete(old.id)
        plan.historyToUpsert?.let {
            if (forceHistory || previousTargetHistory == null || previousTargetHistory.updatedAt < it.updatedAt) {
                history.upsert(it)
            }
        }
        plan.prefsToUpsert?.let { if (prefs.find(target.id) == null) prefs.upsert(it) }
        plan.trackingLinksToDelete.forEach { links.deleteLink(it.service, it.remoteId, it.mangaId) }
        plan.trackingLinksToUpsert.forEach { links.upsertLink(it) }
        if (plan.deleteOldTrack) tracks.delete(old.id)
        plan.trackToUpsert?.let { if (!keepTargetTrack || tracks.find(target.id) == null) tracks.upsert(it) }
        if (plan.notesToUpdate.isNotEmpty()) dao.updateNotes(plan.notesToUpdate)
        if (plan.notesToInsert.isNotEmpty()) dao.insertNotes(plan.notesToInsert)
        if (plan.moveStats) dao.moveStats(old.id, target.id)
        if (plan.sessionsToUpdate.isNotEmpty()) dao.updateSessions(plan.sessionsToUpdate)
        if (plan.jumpPointsToUpdate.isNotEmpty()) dao.updateJumpPoints(plan.jumpPointsToUpdate)
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
        lastVisiblePage: Int = page, scroll: Float = 0f, part: SourceChapterPart? = null) {
        // Android's StatsCollector upserts its row on every state change of an open reader; the row is written after
        // the history transaction closes, because a second writer connection cannot be opened from inside it.
        val stats = database.useWriterConnection { connection ->
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
                val now = clock()
                val percent = (chapterIndex + (lastVisiblePage + 1f) / pageCount) / (branch.size * parts)
                val previous = database.getHistoryDao().find(content.id)
                database.getHistoryDao().upsert(HistoryEntity(
                    mangaId = content.id, createdAt = previous?.createdAt ?: now, updatedAt = now,
                    chapterId = chapter.id, page = page, scroll = scroll,
                    percent = percent,
                    deletedAt = 0, chaptersCount = branch.size, parentChapterId = part?.parent?.id,
                ))
                val write = if (statsRecording) readingSessions.onPage(content.id, stored.id, page, percent, now) else null
                if (lastVisiblePage == pageCount - 1 && (part == null || part.index == part.count - 1)) {
                    database.completeUpdateChapter(content.id, stored.id, now)
                }
                write
            }
        }
        stats?.let { writeReadingSession(it) }
    }

    /** Android's `StatsCollector.onPause` together with `ReadingRecordRepository.recordSession`. */
    suspend fun beginReadingSession() = readingSessions.begin()

    /** Closes the open stop, if any: the session row and the final stats row land in one transaction. */
    suspend fun finishReadingSession() {
        val write = readingSessions.finish() ?: return
        writeReadingSession(write)
    }

    /** Drops the open stop without writing its session, for a reader the host stops tracking. */
    fun abandonReadingSession() = readingSessions.abandon()

    private suspend fun writeReadingSession(write: ReadingSessionWrite): Unit {
        // Statistics off: the stop is dropped rather than stored, so the stats page keeps the last recorded read.
        if (!statsRecording) return
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val dao = database.getStatsDao()
                // Android's commit keeps the larger of the two rows, so a late writer never shrinks a stop.
                val previous = dao.find(write.stats.mangaId, write.stats.startedAt)
                dao.upsert(write.stats.copy(
                    duration = maxOf(write.stats.duration, previous?.duration ?: 0L),
                    pages = maxOf(write.stats.pages, previous?.pages ?: 0),
                ))
                write.session?.let { database.getReadingRecordDao().insertSession(it) }
            }
        }
    }

    /**
     * Android's `StatsRepository.getDashboard` without the category filter Windows does not offer yet: the shared
     * derivation over this library's session rows, legacy stats rows and the works they belong to. [kind] drops the
     * works of the other content types before deriving, the way Android filters `contents` — the derivation only walks
     * the ids it is given, so the daily and hourly series shrink with the same filter.
     */
    suspend fun readingStatistics(period: ReadingStatsPeriod, zone: TimeZone, now: Long = clock(),
        kind: StatsContentKind = StatsContentKind.ALL): LibraryReadingStats = database.useReaderConnection { connection ->
        connection.deferredTransaction {
            val window = ReadingStatsWindow(period, now, zone)
            val sessions = database.getReadingRecordDao().findOverlapping(window.from, window.now)
            val legacy = database.getStatsDao().findOverlapping(window.from, window.now)
            val ids = (sessions.map { it.mangaId } + legacy.map { it.mangaId }).distinct()
            val contents = ids.chunked(200).flatMap { database.getMangaDao().findWithTagsByIds(it) }
            val kinds = contents.associate { it.manga.id to StatsContentKind.fromType(it.manga.contentType) }
                .filterValues { kind == StatsContentKind.ALL || it == kind }
            val snapshot = deriveReadingStatistics(sessions, legacy, kinds, window)
            val works = contents.associate { it.manga.id to content(it.manga, it.tags, withChapters = false) }
            LibraryReadingStats(
                works = snapshot.works.mapNotNull { record ->
                    works[record.contentId]?.let { work ->
                        LibraryReadingStatsWork(work, record.kind, record.duration, record.sessions, record.units,
                            record.lastActivityAt)
                    }
                },
                dailyActivity = snapshot.dailyActivity,
                hourlyActivity = snapshot.hourlyActivity,
                totalDuration = snapshot.totalDuration,
                activeDays = snapshot.activeDays,
                currentStreak = snapshot.currentStreak,
                sessionCount = snapshot.sessionCount,
            )
        }
    }

    /**
     * Android's `StatsRepository.getContentSnapshot`: one work over the whole history, as its statistics sheet shows
     * it. Picking a work on Android opens that sheet rather than its details, so the numbers and the day series here
     * come from the same shared derivation the dashboard uses, just narrowed to a single work.
     */
    suspend fun contentReadingStats(contentId: Long, zone: TimeZone,
        now: Long = clock()): LibraryContentStats = database.useReaderConnection { connection ->
        connection.deferredTransaction {
            val kind = StatsContentKind.fromType(database.getMangaDao().find(contentId)?.manga?.contentType)
            val snapshot = deriveReadingStatistics(
                database.getReadingRecordDao().findSessions(contentId),
                database.getStatsDao().findAll(contentId),
                mapOf(contentId to kind),
                ReadingStatsWindow(ReadingStatsPeriod.ALL, now, zone),
            )
            val record = snapshot.works.singleOrNull()
            LibraryContentStats(
                kind = kind,
                totalDuration = snapshot.totalDuration,
                sessionCount = snapshot.sessionCount,
                units = record?.units ?: 0,
                firstActivityAt = record?.firstActivityAt,
                dailyActivity = snapshot.dailyActivity,
            )
        }
    }

    /** Android's "clear statistics": the session rows and the legacy rows both go, history stays. */
    suspend fun clearReadingStatistics() = database.useWriterConnection { connection ->
        connection.immediateTransaction {
            database.getStatsDao().clear()
            database.getReadingRecordDao().clearAllSessions()
        }
    }

    private suspend fun saveInTransaction(content: SourceContent) {
        database.getMangaDao().find(content.id)?.manga?.let { requireSameIdentity(it, content) }
        val entity = MangaEntity(
            id = content.id, title = content.title, altTitles = content.altTitles.joinToString("\n"),
            url = content.url, publicUrl = content.publicUrl, rating = content.rating,
            isNsfw = content.isNsfw,
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
