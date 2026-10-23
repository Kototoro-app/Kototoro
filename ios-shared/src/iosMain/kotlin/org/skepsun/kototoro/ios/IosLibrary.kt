package org.skepsun.kototoro.ios

import androidx.room.Room
import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.skepsun.kototoro.core.db.DATABASE_VERSION
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.migrations.sharedDatabaseMigrations
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity
import org.skepsun.kototoro.parsers.network.CloudFlareProtection
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.core.library.SourceLibrary
import org.skepsun.kototoro.core.library.SourceTracker
import org.skepsun.kototoro.history.domain.recoverHistoryChapterId
import org.skepsun.kototoro.core.source.SourceRef
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

data class IosCategory(val id: Int, val title: String, val isVisibleInLibrary: Boolean = true)

data class IosSourceSetting(
    val name: String,
    val enabled: Boolean,
    val pinned: Boolean,
    val sortKey: Int,
    val lastUsedAt: Long,
    val storedContentCount: Int,
    val addedIn: Int,
)

/** App-lifetime database owner. Swift supplies a persistent, app-private file path. */
@OptIn(ExperimentalAtomicApi::class)
class IosLibrary {
    internal val database: MangaDatabase
    private val sourceRefs = AtomicReference<Map<String, SourceRef>>(emptyMap())
    internal val records by lazy { SourceLibrary(database) { sourceRefs.load()[it] } }
    val tracking by lazy { IosTracking(this) }
    val duplicates by lazy { IosLibraryDuplicates(this) }
    val statistics by lazy { IosStatistics(this) }
    val recovery by lazy { IosLibraryRecovery(this) }
    val googleDriveSync by lazy { IosGoogleDriveSync(this) }
    val suggestions by lazy { IosSuggestions(this) }

    /** Sources of the current catalog; works of other sources cannot be opened or refreshed here. */
    internal val knownSources: Set<String> get() = sourceRefs.load().keys

    fun updateSources(sources: List<IosSource>) {
        sourceRefs.store(sources.associate { it.name to SourceRef(it.name, it.locale, it.contentType) })
    }

    @Throws(Exception::class)
    constructor(databasePath: String) {
        require(databasePath.startsWith('/') && !databasePath.endsWith('/')) {
            "Database path must be an absolute file path"
        }
        database = Room.databaseBuilder<MangaDatabase>(name = databasePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*sharedDatabaseMigrations())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    internal constructor(database: MangaDatabase) {
        this.database = database
    }

    val schemaVersion: Int get() = DATABASE_VERSION

    @Throws(Exception::class)
    suspend fun categories(): List<IosCategory> = database.getFavouriteCategoriesDao().findAll().map {
        IosCategory(id = it.categoryId, title = it.title, isVisibleInLibrary = it.isVisibleInLibrary)
    }

    /** Saves the editor snapshot atomically; a changed category list must be reloaded before saving. */
    @Throws(Exception::class)
    suspend fun saveCategoryLayout(orderedIds: List<Int>, visibleIds: List<Int>) {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val categories = database.getFavouriteCategoriesDao()
                val currentIds = categories.findAll().map { it.categoryId }.toSet()
                require(orderedIds.size == currentIds.size && orderedIds.toSet() == currentIds) {
                    localized("分类列表已变化，请重新打开管理分类后保存")
                }
                val visible = visibleIds.toSet()
                require(visible.all { it in currentIds }) { "Visible category no longer exists" }
                orderedIds.forEachIndexed { index, id ->
                    categories.updateSortKey(id.toLong(), index)
                    categories.updateVisibility(id.toLong(), id in visible)
                }
            }
        }
    }

    @Throws(Exception::class)
    suspend fun addCategory(title: String) {
        val normalized = title.trim()
        require(normalized.isNotEmpty()) { "Category title must not be blank" }
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val categories = database.getFavouriteCategoriesDao()
                categories.insert(
                    FavouriteCategoryEntity(
                        categoryId = 0,
                        createdAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                        sortKey = categories.getNextSortKey(),
                        title = normalized,
                        order = ListSortOrder.NEWEST.name,
                        track = true,
                        isVisibleInLibrary = true,
                        deletedAt = 0,
                    ),
                )
            }
        }
    }

    /** Renames a category, keeping its sort order, update tracking and shelf visibility. */
    @Throws(Exception::class)
    suspend fun renameCategory(id: Int, title: String) {
        val normalized = title.trim()
        require(normalized.isNotEmpty()) { "Category title must not be blank" }
        val categories = database.getFavouriteCategoriesDao()
        val current = categories.find(id)
        categories.update(id.toLong(), normalized, current.order, current.track, current.isVisibleInLibrary)
    }

    /** As Android: the category and its favourite rows are removed; the works' other categories stay. */
    @Throws(Exception::class)
    suspend fun removeCategory(id: Int) {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                database.getFavouritesDao().deleteAll(id.toLong())
                database.getFavouriteCategoriesDao().delete(id.toLong())
                database.getChaptersDao().gc()
            }
        }
    }

    /**
     * Removes the category and unfavourites every work in it, including from their other categories. All rows are
     * tombstoned with fresh timestamps, so the deletion reaches Google Drive and other devices.
     */
    @Throws(Exception::class)
    suspend fun removeCategoryWithWorks(id: Int) {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val favourites = database.getFavouritesDao()
                favourites.findActiveMangaIds(id.toLong()).forEach { favourites.delete(it) }
                favourites.deleteAll(id.toLong())
                database.getFavouriteCategoriesDao().delete(id.toLong())
                database.getChaptersDao().gc()
            }
        }
    }

    /** Sources the user switched on or off, in Android's `sources` table (and its backups); others use defaults. */
    @Throws(Exception::class)
    suspend fun sourceStates(): Map<String, Boolean> =
        database.getSourcesDao().findAll().associate { it.source to it.isEnabled }

    /** Every stored source row with Android's recent-use timestamp and stored-work popularity metric. */
    @Throws(Exception::class)
    suspend fun sourceSettings(): List<IosSourceSetting> = database.useReaderConnection { connection ->
        connection.deferredTransaction {
            val dao = database.getSourcesDao()
            val counts = dao.findUsageCounts().associate { it.source to it.contentCount }
            dao.findAll().map {
                IosSourceSetting(it.source, it.isEnabled, it.isPinned, it.sortKey, it.lastUsedAt,
                    counts[it.source] ?: 0, it.addedIn)
            }
        }
    }

    /** Registers catalog additions once; the first iOS catalog establishes a baseline without new-source badges. */
    @Throws(Exception::class)
    suspend fun registerSourceCatalog(names: List<String>, enabled: List<Boolean>, appVersion: Int, baseline: Boolean) {
        require(appVersion > 0 && names.size == enabled.size && names.distinct().size == names.size)
        require(names.all { it.isNotBlank() })
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val dao = database.getSourcesDao()
                val known = dao.findAll().mapTo(HashSet()) { it.source }
                var position = dao.getMaxSortKey()
                dao.insertIfAbsent(names.mapIndexedNotNull { index, name ->
                    if (name in known) null else MangaSourceEntity(name, enabled[index], ++position,
                        if (baseline) 0 else appVersion, 0, false, CloudFlareProtection.NOT_DETECTED)
                })
            }
        }
    }

    /** Visiting a source records usage without changing its switch, pin or manual position. */
    @Throws(Exception::class)
    suspend fun recordSourceUsage(name: String, enabled: Boolean) {
        require(name.isNotBlank())
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val dao = database.getSourcesDao()
                dao.insertIfAbsent(listOf(MangaSourceEntity(name, enabled, dao.getMaxSortKey() + 1,
                    SOURCE_APP_VERSION, 0, false, CloudFlareProtection.NOT_DETECTED)))
                dao.setLastUsed(name, kotlin.time.Clock.System.now().toEpochMilliseconds())
            }
        }
    }

    @Throws(Exception::class)
    suspend fun setSourcePinned(name: String, pinned: Boolean) =
        database.getSourcesDao().setPinned(name, pinned, SOURCE_APP_VERSION)

    /**
     * Saves a manual order. Sources without a row yet get one carrying [enabled] (their current effective state),
     * so ordering never changes which sources are switched on.
     */
    @Throws(Exception::class)
    suspend fun setSourceOrder(names: List<String>, enabled: List<Boolean>) {
        require(names.size == enabled.size && names.distinct().size == names.size)
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val dao = database.getSourcesDao()
                dao.insertIfAbsent(names.mapIndexed { index, name ->
                    MangaSourceEntity(name, enabled[index], index, SOURCE_APP_VERSION, 0, false,
                        CloudFlareProtection.NOT_DETECTED)
                })
                names.forEachIndexed { index, name -> dao.setSortKey(name, index) }
            }
        }
    }

    @Throws(Exception::class)
    suspend fun setSourcesEnabled(names: List<String>, enabled: Boolean) {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val dao = database.getSourcesDao()
                for (name in names.distinct()) dao.setEnabled(name, enabled, SOURCE_APP_VERSION)
            }
        }
    }

    @Throws(Exception::class)
    suspend fun favourites(): List<IosLibraryEntry> {
        val entries = records.snapshot(withChapters = false).entries
        val metadata = tracking.selected(entries.map { it.content.id })
        return entries.map { IosLibraryEntry(it, metadata[it.content.id]) }
    }

    @Throws(Exception::class)
    suspend fun history(): List<IosLibraryEntry> {
        val entries = records.snapshot(history = true, withChapters = false).entries
        val metadata = tracking.selected(entries.map { it.content.id })
        return entries.map { IosLibraryEntry(it, metadata[it.content.id]) }
    }

    @Throws(Exception::class)
    suspend fun isFavourite(content: IosSourceContent): Boolean = records.isFavourite(content.value.id)

    @Throws(Exception::class)
    suspend fun setFavourite(content: IosSourceContent, favourite: Boolean) {
        if (favourite) records.addFavourite(content.value) else records.removeFavourite(content.value.id)
    }

    /** Unfavourites the works from every category in one transaction. */
    @Throws(Exception::class)
    suspend fun removeFavourites(contents: List<IosSourceContent>) {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                contents.forEach { database.getFavouritesDao().delete(it.value.id) }
            }
        }
    }

    @Throws(Exception::class)
    suspend fun setCategories(content: IosSourceContent, categoryIds: List<Int>) =
        records.setCategories(content.value, categoryIds.map(Int::toLong).toSet())

    @Throws(Exception::class)
    suspend fun updateCategories(contents: List<IosSourceContent>, included: List<Int>, excluded: List<Int>) =
        records.updateCategories(contents.map { it.value }, included.map(Int::toLong).toSet(),
            excluded.map(Int::toLong).toSet())

    @Throws(Exception::class)
    suspend fun favouriteCategories(content: IosSourceContent): List<Int> =
        database.getFavouritesDao().findCategories(content.value.id).map(Long::toInt)

    @Throws(Exception::class)
    suspend fun removeHistory(content: IosSourceContent) = records.removeHistory(content.value.id)

    @Throws(Exception::class)
    suspend fun saveDetails(content: IosSourceContent) =
        // Details without chapters (a source that failed quietly) keep the saved list instead of erasing it.
        records.save(content.value.let { if (it.chapters?.isEmpty() == true) it.copy(chapters = null) else it })

    /** Lightweight saved details for restoring a space's last browsed artwork, without loading its chapters. */
    @Throws(Exception::class)
    suspend fun storedContent(id: String): IosSourceContent? {
        val contentId = id.toLongOrNull() ?: return null
        val content = records.findMany(listOf(contentId)).firstOrNull() ?: return null
        return IosSourceContent(content, tracking.selected(listOf(contentId))[contentId])
    }

    /** Moves Android-compatible library records after Swift has fetched and confirmed the target's details. */
    @Throws(Exception::class)
    suspend fun changeSource(content: IosSourceContent, target: IosSourceContent, keepOriginal: Boolean) =
        records.migrate(content.value, target.value, keepOriginal)

    /**
     * Details saved when this work was last opened (every details screen saves them), with their chapters; null when
     * none were saved or they belong to another source. Lets a long list show before the source answers again.
     */
    @Throws(Exception::class)
    suspend fun storedDetails(content: IosSourceContent): IosSourceContent? =
        records.find(content.value.id)
            ?.takeIf { it.source.name == content.value.source.name && !it.chapters.isNullOrEmpty() }
            ?.let(::IosSourceContent)

    /** Resolve a download from its saved update log, refreshing missing chapter metadata on explicit request. */
    @Throws(Exception::class)
    suspend fun prepareUpdateDownload(event: IosTimelineEvent, sources: IosSources): IosTimelineEvent {
        require(event.isUpdate) { "Not an update event" }
        val logId = requireNotNull(event.id.removePrefix("update-").toLongOrNull())
        val log = checkNotNull(database.getTrackLogsDao().find(logId)) {
            localized("更新记录已不存在，请刷新记录页。")
        }
        require(log.mangaId == event.content.value.id) { "Update content mismatch" }
        val names = log.chapters.lines().filter(String::isNotBlank)
        var content = storedDetails(event.content) ?: event.content
        var chapters = resolveUpdateDownloadChapters(content, names)
        if (chapters.isEmpty()) {
            content = sources.refreshDetails(content)
            require(content.value.id == log.mangaId && content.sourceName == event.content.sourceName) {
                "Update details mismatch"
            }
            saveDetails(content)
            chapters = resolveUpdateDownloadChapters(content, names)
        }
        check(chapters.isNotEmpty()) {
            localized("无法匹配更新章节，请在作品详情中选择章节下载。")
        }
        return event.copy(content = content, downloadChapters = chapters)
    }

    @Throws(Exception::class)
    suspend fun position(content: IosSourceContent): IosReadingPosition? {
        val history = records.progress(content.value.id) ?: return null
        // A downloaded book's chapters are listed, and recorded, in place of the download.
        val chapters = content.listed.takeIf { content.value.chapters != null }
            ?: storedDetails(content)?.value?.chapters ?: return null
        val ids = chapters.map { it.id }
        val recovered = recoverHistoryChapterId(false, history.chapterId, history.parentChapterId, history.percent, ids)
            // A book's chapter whose download is not listed by its chapters resumes at the download.
            ?: history.parentChapterId?.takeIf { history.chapterId !in ids && it in ids }
        val chapterId = recovered ?: history.chapterId
        if (chapterId !in ids) return null
        return IosReadingPosition(chapterId.toString(), if (recovered == null) history.page else 0,
            if (recovered == null) history.scroll else 0f, history.percent)
    }

    @Throws(Exception::class)
    suspend fun recordPosition(content: IosSourceContent, chapter: IosSourceChapter, index: Int,
        count: Int, scroll: Float) = recordPosition(content, chapter, index, count, index, scroll)

    @Throws(Exception::class)
    suspend fun recordPosition(content: IosSourceContent, chapter: IosSourceChapter, index: Int,
        count: Int, lastVisible: Int, scroll: Float) =
        records.recordPage(content.value, chapter.value, index, count, lastVisible, scroll, content.part(chapter.value))

    @Throws(Exception::class)
    suspend fun bookmarks(content: IosSourceContent): List<IosBookmark> = records.bookmarks(content.value.id).map(::IosBookmark)

    @Throws(Exception::class)
    suspend fun toggleBookmark(content: IosSourceContent, chapter: IosSourceChapter, page: IosSourcePage,
        index: Int, count: Int, scroll: Float) = records.toggleBookmark(content.value, chapter.value, page.value, index, count, scroll)

    @Throws(Exception::class)
    suspend fun toggleNovelBookmark(content: IosSourceContent, chapter: IosSourceChapter, index: Int,
        count: Int, preview: String) =
        records.toggleNovelBookmark(content.value, chapter.value, index, count, preview, content.part(chapter.value))

    @Throws(Exception::class)
    suspend fun removeBookmark(content: IosSourceContent, bookmark: IosBookmark) = records.removeBookmark(content.value, bookmark.value)

    @Throws(Exception::class)
    suspend fun updates(): List<IosUpdate> = database.useReaderConnection { connection ->
        connection.deferredTransaction {
            val tracks = database.getTracksDao().findUpdated()
            val works = records.findMany(tracks.map { it.mangaId }).associateBy { it.id }
            tracks.mapNotNull { track ->
                works[track.mangaId]?.let { IosUpdate(IosSourceContent(it), track.newChapters, track.lastCheckTime) }
            }
        }
    }

    @Throws(Exception::class)
    suspend fun checkUpdates(sources: IosSources): IosUpdateReport {
        val tracker = SourceTracker(database, records,
            { sources.refreshDetails(IosSourceContent(it)).value }, { it.chapters?.firstOrNull()?.branch })
        val result = tracker.checkAll()
        return IosUpdateReport(result.checked, result.withUpdates, result.failed, result.newChapters)
    }

    @Throws(Exception::class)
    suspend fun markUpdateRead(content: IosSourceContent) = SourceTracker(database, records,
        { it }, { it.chapters?.firstOrNull()?.branch }).markRead(content.value.id)

    private val backups by lazy { IosBackupService(this) }

    /** Verifies the whole archive and counts restorable rows; nothing is written yet. */
    @Throws(Exception::class)
    suspend fun previewBackup(path: String): IosBackupPreview = backups.preview(path)

    /** Merges in one transaction; local rows are never cleared and newer local state wins. */
    @Throws(Exception::class)
    suspend fun restoreBackup(preview: IosBackupPreview): Int = backups.restore(preview)

    /** Writes the Android-compatible library sections; [path] must not exist yet. */
    @Throws(Exception::class)
    suspend fun exportBackup(path: String) = backups.export(path, kotlin.time.Clock.System.now().toEpochMilliseconds())

    private val externalBackups by lazy { IosExternalBackupService(this) }

    /** Kototoro / Kotatsu archives restore through [previewBackup]; everything else is another app's backup. */
    fun isKototoroBackup(path: String): Boolean = externalBackups.isKototoroBackup(path)

    /** Decodes a Mihon/Tachiyomi, Aniyomi or Venera backup and counts what it would import. */
    @Throws(Exception::class)
    suspend fun previewExternalBackup(path: String): IosExternalBackupPreview = externalBackups.preview(path)

    /**
     * Imports with Android's rules: [sources] are the installed ones (for Venera name matching and to tell which
     * extensions still need installing); works of missing extensions are kept under their original keys.
     */
    @Throws(Exception::class)
    suspend fun importExternalBackup(preview: IosExternalBackupPreview, sources: List<IosSource>): IosExternalImportResult =
        externalBackups.import(preview, sources)

    fun close() = database.close()
}

/** `added_in` for sources first seen on iOS; Android's "new sources" hint compares it with its own version code. */
private const val SOURCE_APP_VERSION = 0
