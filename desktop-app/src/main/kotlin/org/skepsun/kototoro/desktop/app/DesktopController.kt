package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.history.domain.recoverHistoryChapterId
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.core.IntSize
import java.nio.file.Path
import org.skepsun.kototoro.desktop.runtime.DesktopNovelHtml
import org.skepsun.kototoro.desktop.runtime.NovelBlock
import org.skepsun.kototoro.desktop.runtime.bookmarkText
import org.skepsun.kototoro.bookmarks.domain.parseNovelBookmarkPreview
import org.skepsun.kototoro.bookmarks.domain.novelBookmarkChapterProgress
import org.skepsun.kototoro.bookmarks.domain.resolveNovelBookmarkPosition
import org.skepsun.kototoro.bookmarks.domain.NovelBookmarkTextIndex
import org.skepsun.kototoro.desktop.runtime.DesktopBackupArchive
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryBackup
import org.skepsun.kototoro.desktop.runtime.DesktopBookmark
import org.skepsun.kototoro.desktop.runtime.DesktopLibrarySnapshot
import org.skepsun.kototoro.desktop.runtime.DesktopRepository
import org.skepsun.kototoro.desktop.runtime.DesktopRepositoryCatalog
import org.skepsun.kototoro.extensions.repo.ExtensionStoreIndex
import org.skepsun.kototoro.core.ui.chapters.ChapterBranchLocale
import org.skepsun.kototoro.core.ui.chapters.chaptersOfBranch
import org.skepsun.kototoro.core.ui.chapters.resolvePreferredChapterBranch
import java.util.Locale
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.tapgrid.TapAction
import org.skepsun.kototoro.reader.ui.tapgrid.TapActions
import org.skepsun.kototoro.reader.ui.tapgrid.TapGridConfig

/** Reader-settings key prefix of the tap-grid actions (Android keeps them in its own `tap_grid` preferences). */
private const val TAP_GRID_PREFIX = "tap_grid."

/** Lists that open details as a floating preview and get it back on dismiss. */
private const val LAST_TRACKER_RUN = "last_tracker_run"
private const val LAST_SUGGESTIONS_RUN = "last_suggestions_run"
/** Page groups (paged) or pages (continuous) before a chapter's end at which the next chapter is prepared. */
private const val NEXT_CHAPTER_LOOKAHEAD = 2
/** First pages of the next chapter loaded with it. */
private const val NEXT_CHAPTER_PAGES = 2

internal val DetailsOrigins = setOf(DesktopScreen.HOME, DesktopScreen.FEED, DesktopScreen.EXPLORE, DesktopScreen.LIBRARY,
    DesktopScreen.HISTORY)

/** The JVM's display locale, matched against branch names as Android matches its locale list. */
internal fun desktopChapterBranchLocales(): List<ChapterBranchLocale> =
    listOf(Locale.getDefault(Locale.Category.DISPLAY), Locale.getDefault()).distinct()
        .map { ChapterBranchLocale(it.getDisplayLanguage(it), it.getDisplayName(it)) }

enum class DesktopScreen { HOME, FEED, EXPLORE, LIBRARY, HISTORY, DETAILS, READER, NOVEL, VIDEO, PREFERENCES, BROWSER, DOWNLOADS, BACKUPS, EXTENSIONS, MORE }

data class DesktopAppState(
    val screen: DesktopScreen = DesktopScreen.EXPLORE,
    val appearance: DesktopAppearance = DesktopAppearance.SYSTEM,
    val interfaceStyle: DesktopInterfaceStyle = DesktopInterfaceStyle.MATERIAL,
    val sources: List<SourceListing> = emptyList(),
    val repositories: List<DesktopRepository> = emptyList(),
    val extensionCatalog: DesktopRepositoryCatalog? = null,
    val installedExtensions: Map<String, Long> = emptyMap(),
    val installedEntries: List<DesktopInstalledEntry> = emptyList(),
    val extensionUpdates: List<DesktopExtensionUpdate> = emptyList(),
    val autoUpdateExtensions: Boolean = false,
    val selectedSource: SourceListing? = null,
    /** Browse page's source-type/content-type filters (Android keeps them per page). */
    val exploreFilter: DesktopSourceFilter = DesktopSourceFilter(),
    val descriptor: SourceDescriptor? = null,
    val items: List<SourceContent> = emptyList(),
    val library: DesktopLibrarySnapshot = DesktopLibrarySnapshot(),
    val librarySelection: DesktopLibrarySelection = DesktopLibrarySelection(),
    val historySelection: DesktopLibrarySelection = DesktopLibrarySelection(),
    val bookmarks: List<DesktopBookmark> = emptyList(),
    val content: SourceContent? = null,
    /** List that owns the open details; retained while reading so returning does not lose the list context. */
    val detailsOrigin: DesktopScreen? = null,
    val detailsExpanded: Boolean = false,
    /** Chapter branch (translation) shown in the details chapter list. */
    val chapterBranch: String? = null,
    /** Home's history row; separate from [library], which the favourites/history pages own. */
    val homeHistory: DesktopLibrarySnapshot = DesktopLibrarySnapshot(),
    /** Android's feed read model (update logs and works with pending new chapters). */
    val feed: org.skepsun.kototoro.tracker.domain.feed.FeedSnapshot = org.skepsun.kototoro.tracker.domain.feed.FeedSnapshot.Empty,
    /** Android's suggestion and tracker settings, persisted under Android's keys. */
    val suggestionSettings: org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings = org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings(),
    val trackerSettings: org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings = org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings(),
    /** Stored suggestions, most relevant first (Android's recommendations). */
    val suggestions: List<SourceContent> = emptyList(),
    /** Works shown by [feed] rows, keyed by manga id, for covers and opening. */
    val feedContents: Map<Long, SourceContent> = emptyMap(),
    /** Favourite categories with update tracking; -1 until the subscriptions page loads. */
    val trackedCategories: Int = -1,
    val homeFilter: DesktopSourceFilter = DesktopSourceFilter(),
    val feedFilter: DesktopSourceFilter = DesktopSourceFilter(),
    val chapter: SourceChapter? = null,
    val pages: List<SourcePage> = emptyList(),
    val pageIndex: Int = 0,
    val readerImages: Map<Long, DesktopReaderImage> = emptyMap(),
    val readerGeometry: Map<Long, IntSize> = emptyMap(),
    val readerSettings: DesktopReaderSettings = DesktopReaderSettings(),
    val novel: DesktopNovel? = null,
    val novelSettings: DesktopNovelSettings = DesktopNovelSettings(),
    val video: DesktopVideo? = null,
    /** The DLNA cast of the open episode, if any; [castEndedAt] tells the local player where to continue. */
    val cast: DesktopCast? = null,
    val castEndedAt: Pair<Long, Double>? = null,
    val videoEnhancement: org.skepsun.kototoro.desktop.player.MpvEnhancement = org.skepsun.kototoro.desktop.player.MpvEnhancement(),
    val readerScroll: Float = 0f,
    val readerLastVisible: Int = 0,
    val readerAtEnd: Boolean = false,
    val readerNavigation: Long = 0,
    val readerTargetPage: Int = 0,
    val readerTargetScroll: Float = 0f,
    val readerScrollReady: Boolean = false,
    val readerVisiblePages: List<Int> = emptyList(),
    val readerLoading: Set<Long> = emptySet(),
    val readerFailedPages: Map<Long, String> = emptyMap(),
    /** Batch index shown to the user; what the source receives depends on its paging mode ([pageOffsets]). */
    val offset: Int = 0,
    /** Source offsets of the batches visited so far; offset-paged sources continue after the items already shown. */
    val pageOffsets: List<Int> = listOf(0),
    val query: String = "",
    val browseOrder: String? = null,
    val dynamicFilters: SourceDynamicFilters? = null,
    val appliedFilters: List<SourceFilterChange> = emptyList(),
    val filterDialogOpen: Boolean = false,
    val preferences: SourcePreferenceScreen? = null,
    val isFavourite: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val image: Path? get() = if (screen == DesktopScreen.READER) pages.getOrNull(pageIndex)
        ?.let { readerImages[it.id]?.path } else null
}

/** One owned scope and immutable UI state. Source methods retain their existing cancellation/lease semantics. */
class DesktopController(val session: DesktopSession) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val gate = Mutex()
    private val actionState = Any()
    private var pendingActions = 0
    private val readerPreferences = session.storage.preferences.open("desktop_reader")
    private val novelPreferences = session.storage.preferences.open("desktop_novel")
    private val videoPreferences = session.storage.preferences.open("desktop_video")
    private val updatePreferences = session.storage.preferences.open("desktop_extension_updates")
    private val backgroundPreferences = session.storage.preferences.open("desktop_background")
    /** Background and manual runs of the same work never overlap. */
    private val backgroundMutex = Mutex()
    private val appearancePreferences = session.storage.preferences.open("desktop_appearance")
    private val mutableState = MutableStateFlow(DesktopAppState(readerSettings = restoredReaderSettings(),
        novelSettings = restoredNovelSettings(), videoEnhancement = restoredVideoEnhancement(),
        appearance = DesktopAppearance.entries.firstOrNull {
            it.name == (appearancePreferences.snapshot()["theme"] as? SourcePreferenceValue.Text)?.value
        } ?: DesktopAppearance.SYSTEM,
        interfaceStyle = DesktopInterfaceStyle.entries.firstOrNull {
            it.name == (appearancePreferences.snapshot()["interface_style"] as? SourcePreferenceValue.Text)?.value
        } ?: DesktopInterfaceStyle.MATERIAL))
    val state = mutableState.asStateFlow()
    private val continuous = DesktopScrollOperations(mutableState, session, scope, gate)
    private val novelOperations = DesktopNovelOperations(mutableState, session, scope, gate)
    private val videoOperations = DesktopVideoOperations(mutableState, session, scope, gate)
    internal val downloads = DesktopDownloads(session.downloadStore, scope,
        { session.readerRevision(it.source.name) }, session::downloadPages, session::prepareDownload)
    private val mutableCleanup = MutableStateFlow<DesktopDownloadCleanupPlan?>(null)
    internal val cleanupPreview = mutableCleanup.asStateFlow()
    private val backups = DesktopLibraryBackup(session.storage.database)
    private val mutableBackupPreview = MutableStateFlow<DesktopBackupArchive?>(null)
    internal val backupPreview = mutableBackupPreview.asStateFlow()

    init {
        refreshSources()
        mutableState.update { it.copy(autoUpdateExtensions =
            (updatePreferences.snapshot()["auto_update"] as? SourcePreferenceValue.Toggle)?.value ?: false) }
        backgroundPreferences.snapshot().let { saved ->
            mutableState.update { it.copy(
                suggestionSettings = org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings.from(saved),
                trackerSettings = org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings.from(saved),
            ) }
        }
        // Repositories are read in the background at start: updates are installed (when allowed) or announced.
        scope.launch { runCatching { startupUpdates() } }
    }

    private suspend fun startupUpdates() {
        if (session.repositories.saved().isEmpty() || session.registry.installed().isEmpty()) return
        val check = session.checkUpdates()
        mutableState.update { it.copy(extensionUpdates = check.updates) }
        if (check.updates.isNotEmpty() && state.value.autoUpdateExtensions) applyUpdates(check.updates).join()
        else if (check.updates.isNotEmpty()) mutableState.update { it.copy(message = "${check.updates.size} 个扩展有更新，可在“扩展与仓库”中更新") }
    }

    fun setAutoUpdateExtensions(enabled: Boolean) {
        mutableState.update { it.copy(autoUpdateExtensions = enabled) }
        if (!updatePreferences.edit(SourcePreferenceEdit(changes = mapOf("auto_update" to SourcePreferenceValue.Toggle(enabled))))) {
            mutableState.update { it.copy(error = "自动更新设置保存失败") }
        }
    }

    internal fun appearance(value: DesktopAppearance) = action {
        check(appearancePreferences.edit(SourcePreferenceEdit(changes = mapOf(
            "theme" to SourcePreferenceValue.Text(value.name),
        )))) { "外观设置保存失败" }
        mutableState.update { it.copy(appearance = value) }
    }

    internal fun interfaceStyle(value: DesktopInterfaceStyle) = action {
        check(appearancePreferences.edit(SourcePreferenceEdit(changes = mapOf(
            "interface_style" to SourcePreferenceValue.Text(value.name),
        )))) { "界面风格保存失败" }
        mutableState.update { it.copy(interfaceStyle = value) }
    }

    fun checkExtensionUpdates() = action {
        val check = session.checkUpdates()
        mutableState.update { it.copy(extensionUpdates = check.updates, installedEntries = session.installedEntries(),
            message = if (check.updates.isEmpty()) "所有扩展都是最新版本" else "${check.updates.size} 个扩展有更新",
            error = check.failures.takeIf { failures -> failures.isNotEmpty() }?.joinToString("；", "部分仓库读取失败：")) }
    }

    /** Installs the given updates one by one; one failing update does not stop the others. */
    fun applyUpdates(updates: List<DesktopExtensionUpdate> = state.value.extensionUpdates) = action {
        val failures = mutableListOf<String>()
        var done = 0
        for (update in updates) {
            try { session.applyUpdate(update); done++ }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { failures += "${update.extension.name}：${error.message ?: error.javaClass.simpleName}" }
        }
        runCatching { session.cleanupArtifacts() }
        val updated = updates.filter { update -> session.registry.installed().any {
            it.metadata.packageName == update.extension.packageName && it.metadata.versionCode >= update.extension.versionCode } }
        val descriptors = sourceDescriptors()
        mutableState.update { previous -> previous.copy(sources = descriptors, installedExtensions = installedVersions(),
            installedEntries = session.installedEntries(),
            extensionUpdates = previous.extensionUpdates.filterNot { it in updated },
            selectedSource = previous.selectedSource?.let { selected -> descriptors.firstOrNull { it.source.name == selected.source.name } },
            message = "已更新 $done 个扩展", error = failures.takeIf { it.isNotEmpty() }?.joinToString("；", "更新失败：")) }
    }

    fun uninstall(entry: DesktopInstalledEntry) = action {
        session.uninstall(entry)
        val descriptors = sourceDescriptors()
        mutableState.update { previous ->
            val removed = previous.selectedSource != null && descriptors.none { it.source.name == previous.selectedSource.source.name }
            previous.copy(sources = descriptors, installedExtensions = installedVersions(), installedEntries = session.installedEntries(),
                extensionUpdates = previous.extensionUpdates.filterNot { it.extension.packageName == entry.id },
                selectedSource = if (removed) null else previous.selectedSource,
                descriptor = if (removed) null else previous.descriptor, items = if (removed) emptyList() else previous.items,
                message = "已卸载 ${entry.label}")
        }
    }

    fun refreshSources() = action {
        mutableState.update { it.copy(sources = sourceDescriptors(), error = session.startupErrors.firstOrNull()) }
    }

    fun importJar(path: Path) = action {
        val extension = session.importJar(path)
        publishInstallation(extension.label, "已导入", DesktopScreen.EXPLORE)
    }

    fun showExtensions() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.EXTENSIONS, repositories = session.repositories.saved(),
            installedExtensions = installedVersions(), installedEntries = session.installedEntries()) }
    }

    fun fetchRepository(address: String) = action {
        val catalog = session.repositories.fetch(address)
        mutableState.update { it.copy(screen = DesktopScreen.EXTENSIONS, extensionCatalog = catalog,
            repositories = session.repositories.saved(), installedExtensions = installedVersions(),
            message = "已读取 ${catalog.repository.name}，${catalog.extensions.size} 个扩展") }
    }

    fun installExtension(extension: ExtensionStoreIndex.Extension) = action {
        val catalog = requireNotNull(state.value.extensionCatalog) { "请先读取仓库" }
        publishInstallation(session.installExtension(catalog, extension).label, "已安装")
    }

    private fun installedVersions() = session.installedVersions()

    private fun publishInstallation(label: String, message: String,
        destination: DesktopScreen = DesktopScreen.EXTENSIONS) {
        val descriptors = sourceDescriptors()
        mutableState.update { previous -> previous.copy(screen = destination,
            sources = descriptors, installedExtensions = installedVersions(), repositories = session.repositories.saved(),
            installedEntries = session.installedEntries(),
            extensionUpdates = previous.extensionUpdates.filter { update -> installedVersions()[update.extension.packageName]
                ?.let { it < update.extension.versionCode } == true },
            selectedSource = previous.selectedSource?.let { selected -> descriptors.firstOrNull { it.source.name == selected.source.name } },
            items = emptyList(), content = null, pages = emptyList(), readerImages = emptyMap(),
            dynamicFilters = null, appliedFilters = emptyList(),
            message = "$message $label") }
    }

    fun selectSource(source: SourceListing) = navigate {
        val descriptor = session.sources.describe(source.source.name)
        mutableState.update { it.copy(screen = DesktopScreen.EXPLORE, selectedSource = source,
            descriptor = descriptor, items = emptyList(), offset = 0, pageOffsets = listOf(0), query = "", content = null,
            browseOrder = null, dynamicFilters = null, appliedFilters = emptyList(), filterDialogOpen = false) }
        browseOnIo(0, "")
    }

    fun browse(offset: Int = 0, query: String = state.value.query) = navigate { browseOnIo(offset, query) }

    fun explore() = navigate {
        if (state.value.selectedSource == null) mutableState.update {
            it.copy(screen = DesktopScreen.EXPLORE, items = emptyList(), content = null)
        } else browseOnIo(state.value.offset, state.value.query)
    }

    /** Leaves the opened source and returns to the browse page's source grid (Android's back from a source). */
    fun exitSource() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.EXPLORE, selectedSource = null, descriptor = null,
            items = emptyList(), offset = 0, pageOffsets = listOf(0), query = "", content = null, browseOrder = null,
            dynamicFilters = null, appliedFilters = emptyList(), filterDialogOpen = false) }
    }

    fun browseUnfiltered(latest: Boolean = false) = navigate {
        browseOnIo(0, "", supportedOrder(if (latest) "UPDATED" else "POPULARITY"), emptyList())
    }

    /** Lists in the given sort order; [order] must be one of the source's declared orders. */
    fun browseOrdered(order: String) = navigate {
        require(order in state.value.descriptor?.sortOrders.orEmpty()) { "来源不支持此排序" }
        browseOnIo(0, state.value.query, order, state.value.appliedFilters)
    }

    /** Parser sources do not all have a POPULARITY order; fall back to the source's default rather than failing. */
    private fun supportedOrder(wanted: String): String? {
        val descriptor = state.value.descriptor ?: return wanted
        return if (wanted in descriptor.sortOrders) wanted else descriptor.defaultSortOrder
    }

    fun filters() = action {
        val source = requireNotNull(state.value.selectedSource) { "请先选择来源" }
        val definition = session.sources.getDynamicFilters(source.source.name)
        require(definition.source.name == source.source.name)
        mutableState.update { it.copy(dynamicFilters = definition, filterDialogOpen = true) }
    }

    fun dismissFilters() { mutableState.update { it.copy(filterDialogOpen = false) } }

    fun applyFilters(query: String, changes: List<SourceFilterChange>) = navigate {
        val definition = requireNotNull(state.value.dynamicFilters)
        val validated = MihonFilterRules.changes(definition.nodes, emptySet(), emptySet(), changes,
            strict = true, applyLegacy = false)
        browseOnIo(0, query, null, validated)
        mutableState.update { it.copy(filterDialogOpen = false) }
    }

    private suspend fun browseOnIo(offset: Int, query: String, order: String? = state.value.browseOrder,
        changes: List<SourceFilterChange> = state.value.appliedFilters) {
        val source = requireNotNull(state.value.selectedSource) { "请先选择来源" }
        val filter = if (query.isNotBlank() || changes.isNotEmpty()) {
            SourceFilter(query = query.takeIf { it.isNotBlank() }, dynamicFilters = changes)
        } else null
        // Page-indexed sources (Mihon) take the batch number; offset-paged ones (parsers) continue after what is shown.
        val previous = state.value
        val offsets = if (offset == 0) listOf(0) else previous.pageOffsets
        val requestOffset = if (previous.descriptor?.pagingMode == SourcePagingMode.OFFSET) {
            offsets.getOrNull(offset) ?: (offsets.last() + previous.items.size)
        } else offset
        val items = session.sources.getList(source.source.name, requestOffset, order, filter)
        // Commit the query and controls only when the source has returned a successful result.
        mutableState.update { it.copy(screen = DesktopScreen.EXPLORE, items = items, offset = offset,
            pageOffsets = if (offset < offsets.size) offsets else offsets + requestOffset,
            query = query, browseOrder = order, appliedFilters = changes) }
    }

    fun library(history: Boolean = false) = navigate {
        val snapshot = session.library.snapshot(history)
        mutableState.update { it.copy(screen = if (history) DesktopScreen.HISTORY else DesktopScreen.LIBRARY,
            items = snapshot.entries.map { row -> row.content }, library = snapshot, content = null) }
    }

    internal fun librarySelection(selection: DesktopLibrarySelection) {
        mutableState.update {
            val screen = if (it.screen == DesktopScreen.DETAILS) it.detailsOrigin else it.screen
            if (screen == DesktopScreen.HISTORY) it.copy(historySelection = selection)
            else if (screen == DesktopScreen.LIBRARY) it.copy(librarySelection = selection) else it
        }
    }

    fun details(content: SourceContent) = navigate { detailsOnIo(content) }

    private suspend fun detailsOnIo(content: SourceContent) {
        val previous = state.value
        val origin = if (previous.screen == DesktopScreen.DETAILS) previous.detailsOrigin
            else previous.screen.takeIf { it in DetailsOrigins }
        val details = session.sources.getDetails(content, SourceDetailsFetchMode.FORCE_REFRESH)
        session.library.save(details)
        val favourite = session.library.isFavourite(details.id)
        val branch = resolvePreferredChapterBranch(details.chapters.orEmpty(), SourceChapter::branch,
            SourceChapter::id, session.library.progress(details.id)?.chapterId, desktopChapterBranchLocales())
        mutableState.update { it.copy(screen = DesktopScreen.DETAILS, content = details,
            detailsOrigin = origin, detailsExpanded = false, isFavourite = favourite, chapterBranch = branch) }
    }

    internal fun exploreFilter(filter: DesktopSourceFilter) {
        mutableState.update { it.copy(exploreFilter = filter) }
    }

    /** Android's home: hero, history and updates rows over the shared history/tracker tables. */
    fun home() = navigate {
        val history = session.library.snapshot(history = true)
        val feed = session.feed.observe(session.storage.database).first()
        val contents = feedContentsOnIo(feed)
        val suggestions = session.suggestions.suggestions()
        mutableState.update { it.copy(screen = DesktopScreen.HOME, homeHistory = history, feed = feed,
            feedContents = contents, suggestions = suggestions, content = null) }
    }

    /** Rebuilds recommendations now, as Android's suggestions worker does periodically. */
    fun refreshSuggestions() = action {
        mutableState.update { it.copy(message = "正在从已安装来源生成推荐…") }
        val stored = backgroundMutex.withLock { runSuggestionsOnIo() }
        mutableState.update { it.copy(message = when {
            stored > 0 -> "已生成 $stored 条推荐"
            state.value.sources.isEmpty() -> "请先安装内容源"
            else -> "暂无推荐：阅读或收藏一些作品后再试"
        }) }
    }

    /** Android shows suggestions only once enabled; enabling also generates them, like the worker's first run. */
    fun enableSuggestions() {
        suggestionSettings(state.value.suggestionSettings.copy(enabled = true))
        refreshSuggestions()
    }

    internal fun suggestionSettings(settings: org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings) {
        mutableState.update { it.copy(suggestionSettings = settings) }
        if (!backgroundPreferences.edit(SourcePreferenceEdit(changes = settings.toPreferences()))) {
            mutableState.update { it.copy(error = "推荐设置保存失败") }
        }
    }

    internal fun trackerSettings(settings: org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings) {
        mutableState.update { it.copy(trackerSettings = settings) }
        if (!backgroundPreferences.edit(SourcePreferenceEdit(changes = settings.toPreferences()))) {
            mutableState.update { it.copy(error = "更新检查设置保存失败") }
        }
    }

    private suspend fun runSuggestionsOnIo(): Int {
        val stored = session.suggestions.refresh(session.sourceListings(), state.value.suggestionSettings)
        recordRun(LAST_SUGGESTIONS_RUN)
        mutableState.update { it.copy(suggestions = session.suggestions.suggestions()) }
        return stored
    }

    private suspend fun runTrackerOnIo(onProgress: suspend (Int, Int) -> Unit = { _, _ -> }) =
        session.tracker.checkAll(onProgress).also { recordRun(LAST_TRACKER_RUN) }

    private fun recordRun(key: String) {
        backgroundPreferences.edit(SourcePreferenceEdit(changes = mapOf(
            key to SourcePreferenceValue.LongInteger(System.currentTimeMillis()))))
    }

    private fun lastRun(key: String): Long =
        (backgroundPreferences.snapshot()[key] as? SourcePreferenceValue.LongInteger)?.value ?: 0L

    /**
     * Runs Android's periodic jobs (tracker, suggestions) while the app is open, outside the UI action queue so the
     * window stays responsive. Only the real app starts it; probes drive the work explicitly.
     */
    fun startBackgroundWork(initialDelayMillis: Long = 60_000L, pollMillis: Long = 15 * 60_000L) {
        scope.launch {
            delay(initialDelayMillis)
            while (isActive) {
                runCatching { runDueBackgroundWork() }
                delay(pollMillis)
            }
        }
    }

    internal suspend fun runDueBackgroundWork(now: Long = System.currentTimeMillis()): Set<DesktopBackgroundTask> {
        val due = dueBackgroundTasks(now, lastRun(LAST_TRACKER_RUN), lastRun(LAST_SUGGESTIONS_RUN),
            state.value.trackerSettings, state.value.suggestionSettings)
        if (DesktopBackgroundTask.TRACKER in due) {
            val report = backgroundMutex.withLock { runTrackerOnIo() }
            val feed = session.feed.observe(session.storage.database).first()
            val contents = feedContentsOnIo(feed)
            mutableState.update { it.copy(feed = feed, feedContents = contents,
                message = if (report.withUpdates > 0) "后台检查：${report.withUpdates} 部作品有 ${report.newChapters} 个新章节" else it.message) }
        }
        if (DesktopBackgroundTask.SUGGESTIONS in due) backgroundMutex.withLock { runSuggestionsOnIo() }
        return due
    }

    /** Android's subscriptions page: tracked works with new chapters and the update log timeline. */
    fun subscriptions() = navigate { loadFeedOnIo(DesktopScreen.FEED) }

    private suspend fun loadFeedOnIo(screen: DesktopScreen? = null) {
        val feed = session.feed.observe(session.storage.database).first()
        val tracked = session.tracker.trackedCategoryCount()
        val contents = feedContentsOnIo(feed)
        mutableState.update { it.copy(screen = screen ?: it.screen, feed = feed, feedContents = contents,
            trackedCategories = tracked, content = if (screen != null) null else it.content) }
    }

    private suspend fun feedContentsOnIo(feed: org.skepsun.kototoro.tracker.domain.feed.FeedSnapshot): Map<Long, SourceContent> {
        val ids = feed.rows.mapNotNull { it.displayMangaId } +
            feed.updateRowsByOwnerId.values.map { it.displayMangaId ?: it.mangaId }
        return ids.distinct().mapNotNull { id -> session.library.find(id)?.let { id to it } }.toMap()
    }

    /** Checks every tracked work now, reporting progress, as Android's tracker worker does in the background. */
    fun checkUpdates() = action {
        val report = backgroundMutex.withLock {
            runTrackerOnIo { done, total -> mutableState.update { it.copy(message = "正在检查更新 $done / $total") } }
        }
        loadFeedOnIo()
        mutableState.update { it.copy(message = when {
            report.checked == 0 -> "没有需要检查的作品：请先收藏作品，并为收藏分类开启更新追踪"
            report.withUpdates == 0 -> "已检查 ${report.checked} 部作品，暂无新章节" +
                if (report.failed > 0) "（${report.failed} 部检查失败）" else ""
            else -> "${report.withUpdates} 部作品有 ${report.newChapters} 个新章节" +
                if (report.failed > 0) "（${report.failed} 部检查失败）" else ""
        }) }
    }

    /** Android tracks favourites only in categories with tracking enabled. */
    fun enableTracking() = action {
        session.tracker.enableTrackingForAllCategories()
        loadFeedOnIo()
    }

    /** Opening an update marks it read (counter and unread logs), then shows the work. */
    fun openTracked(mangaId: Long) = navigate {
        session.tracker.markRead(mangaId)
        val content = requireNotNull(session.library.find(mangaId)) { "作品记录不存在" }
        detailsOnIo(content)
        loadFeedOnIo()
    }

    /** Android's "continue reading" on a feed entry: mark it read and resume from history. */
    fun continueTracked(mangaId: Long) = navigate {
        session.tracker.markRead(mangaId)
        val content = requireNotNull(session.library.find(mangaId)) { "作品记录不存在" }
        detailsOnIo(content)
        when (DesktopReaderKind.of(state.value.content)) {
            DesktopReaderKind.NOVEL -> readNovelOnIo()
            DesktopReaderKind.VIDEO -> watchOnIo()
            DesktopReaderKind.PAGES -> readOnIo()
        }
    }

    /** Android's "random" quick action: open a random favourite. */
    fun randomFavourite() = navigate {
        val favourite = session.library.favourites().randomOrNull() ?: error("收藏为空")
        detailsOnIo(favourite)
    }

    internal fun feedFilter(filter: DesktopSourceFilter) {
        mutableState.update { it.copy(feedFilter = filter) }
    }

    internal fun homeFilter(filter: DesktopSourceFilter) {
        mutableState.update { it.copy(homeFilter = filter) }
    }

    /** Android's branch chips: the list shows one branch at a time. */
    internal fun selectChapterBranch(branch: String?) {
        mutableState.update { it.copy(chapterBranch = branch) }
    }

    /** With no requested chapter and no history, start the selected branch, as Android's reader launcher does. */
    private fun firstChapterOfBranch(chapters: List<SourceChapter>): SourceChapter =
        chaptersOfBranch(chapters, SourceChapter::branch, state.value.chapterBranch).first()

    internal fun expandDetails() {
        mutableState.update { if (it.screen == DesktopScreen.DETAILS) it.copy(detailsExpanded = true) else it }
    }

    internal fun dismissDetails() = navigate {
        val origin = state.value.detailsOrigin ?: DesktopScreen.EXPLORE
        val library = if (origin == DesktopScreen.LIBRARY || origin == DesktopScreen.HISTORY) {
            session.library.snapshot(origin == DesktopScreen.HISTORY)
        } else state.value.library
        // Reading from home or the feed changes their rows (history, cleared counters).
        val homeHistory = if (origin == DesktopScreen.HOME) session.library.snapshot(history = true) else state.value.homeHistory
        val feed = if (origin == DesktopScreen.HOME || origin == DesktopScreen.FEED) {
            session.feed.observe(session.storage.database).first()
        } else state.value.feed
        val feedContents = if (feed !== state.value.feed) feedContentsOnIo(feed) else state.value.feedContents
        mutableState.update { it.copy(screen = origin, content = null, detailsOrigin = null,
            detailsExpanded = false, library = library, homeHistory = homeHistory, feed = feed, feedContents = feedContents) }
    }

    fun addFavourite() = action {
        session.library.addFavourite(requireNotNull(state.value.content))
        mutableState.update { it.copy(isFavourite = true, message = "已加入收藏") }
    }

    fun read(chapter: SourceChapter? = null) = navigate {
        when (DesktopReaderKind.of(state.value.content)) {
            DesktopReaderKind.NOVEL -> readNovelOnIo(chapter)
            DesktopReaderKind.VIDEO -> watchOnIo(chapter)
            DesktopReaderKind.PAGES -> readOnIo(chapter)
        }
    }

    private suspend fun readNovelOnIo(chapter: SourceChapter? = null, bookmark: DesktopBookmark? = null) {
        val content = requireNotNull(state.value.content)
        val chapters = requireNotNull(content.chapters) { "没有可阅读章节" }
        val progress = session.library.progress(content.id)
        val restoredId = progress?.let { recoverHistoryChapterId(false, it.chapterId, it.parentChapterId,
            it.percent, chapters.map { c -> c.id }) ?: it.chapterId }
        val selected = chapter ?: chapters.firstOrNull { it.id == restoredId } ?: firstChapterOfBranch(chapters)
        val next = SourceChapterNavigation.adjacent(chapters, selected.id, true)?.url
        val body = session.sources.getChapterContent(selected, next) ?: error("此章节没有可显示的文本")
        val blocks = DesktopNovelHtml.parse(body.html, content.publicUrl.takeIf { it.startsWith("http") }.orEmpty())
        require(blocks.isNotEmpty()) { "此章节没有可显示的文本" }
        val start = if (bookmark != null) {
            require(bookmark.contentId == content.id && bookmark.chapterId == selected.id)
            novelBookmarkIndex(content, selected, blocks, bookmark)
        } else if (chapter == null && selected.id == restoredId) (progress?.page ?: 0).coerceIn(blocks.indices) else 0
        val bookmarks = session.library.bookmarks(content.id)
        novelOperations.forget()
        mutableState.update { it.copy(screen = DesktopScreen.NOVEL, chapter = selected, pages = emptyList(),
            readerImages = emptyMap(), bookmarks = bookmarks,
            novel = DesktopNovel(selected, blocks, body.images.associateBy { image -> image.url }, start,
                navigation = (it.novel?.navigation ?: 0) + 1,
                bookmarkBlocks = novelBookmarkBlocks(content, selected, blocks, bookmarks))) }
        // Opening a chapter is itself reading it: record the starting position straight away.
        novelOperations.report(selected.id, start, start)
    }

    /**
     * Opens an episode in the player: the requested one, else the one history points at (resuming its second), else
     * the first. The source's streams are the episode's pages; the first is played, the others stay selectable.
     */
    private suspend fun watchOnIo(chapter: SourceChapter? = null) {
        closeCast(resume = false)
        val content = requireNotNull(state.value.content)
        val chapters = requireNotNull(content.chapters) { "没有可播放的剧集" }
        val progress = session.library.progress(content.id)
        val selected = chapter ?: chapters.firstOrNull { it.id == progress?.chapterId } ?: firstChapterOfBranch(chapters)
        val next = SourceChapterNavigation.adjacent(chapters, selected.id, true)?.url
        val streams = session.sources.getPages(selected, next).filter { it.url.isNotBlank() }
        require(streams.isNotEmpty()) { "此集没有可播放的视频" }
        val start = if (progress != null && progress.chapterId == selected.id) progress.page.toDouble() else 0.0
        videoOperations.forget()
        mutableState.update { it.copy(screen = DesktopScreen.VIDEO, chapter = selected, pages = emptyList(),
            readerImages = emptyMap(), novel = null,
            video = DesktopVideo(selected, streams, 0, start, generation = (it.video?.generation ?: 0) + 1)) }
    }

    /** Video enhancement is a viewer preference, kept across videos and restarts like Android's remembered choice. */
    fun setVideoEnhancement(enhancement: org.skepsun.kototoro.desktop.player.MpvEnhancement) {
        mutableState.update { it.copy(videoEnhancement = enhancement) }
        val saved = videoPreferences.edit(SourcePreferenceEdit(changes = mapOf(
            "enhancement" to SourcePreferenceValue.Text(enhancement.mode.name),
            "fsr_sharpness" to SourcePreferenceValue.FloatBits(enhancement.fsrSharpness.toRawBits()),
        )))
        if (!saved) mutableState.update { it.copy(error = "画质增强设置已应用，磁盘保存失败") }
    }

    private fun restoredVideoEnhancement(): org.skepsun.kototoro.desktop.player.MpvEnhancement {
        val snapshot = videoPreferences.snapshot()
        val mode = org.skepsun.kototoro.desktop.player.MpvEnhancementMode.entries.firstOrNull {
            it.name == (snapshot["enhancement"] as? SourcePreferenceValue.Text)?.value
        } ?: org.skepsun.kototoro.desktop.player.MpvEnhancementMode.OFF
        val sharpness = (snapshot["fsr_sharpness"] as? SourcePreferenceValue.FloatBits)?.let { Float.fromBits(it.bits) }
            ?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.9f
        return org.skepsun.kototoro.desktop.player.MpvEnhancement(mode, sharpness)
    }
    /** Casts the playing stream to [device] from the current position; the local player pauses meanwhile. */
    fun startCast(device: org.skepsun.kototoro.desktop.player.DlnaDevice) = action {
        val snapshot = state.value
        val video = requireNotNull(snapshot.video) { "没有正在播放的视频" }
        snapshot.cast?.let { previous -> mutableState.update { it.copy(cast = null) }; withContext(Dispatchers.IO) { previous.close() } }
        val title = listOfNotNull(snapshot.content?.title, video.chapter.title).joinToString(" · ")
        val cast = withContext(Dispatchers.IO) { DesktopCast.start(device, video.stream, title, video.position) }
        mutableState.update { it.copy(cast = cast, message = "正在投屏到 ${device.name}") }
    }

    /** Ends the cast; the local player continues from where the renderer was. */
    fun endCast() = action { closeCast(resume = true) }

    private suspend fun closeCast(resume: Boolean) {
        val cast = state.value.cast ?: return
        val position = cast.status.value.position
        mutableState.update { it.copy(cast = null,
            castEndedAt = if (resume) (it.video?.generation ?: 0L) to position else it.castEndedAt) }
        withContext(Dispatchers.IO) { cast.close() }
    }

    /** Another quality or mirror of the same episode, continuing where playback is. */
    fun selectVideoStream(index: Int) {
        mutableState.update { snapshot ->
            val video = snapshot.video ?: return@update snapshot
            if (index !in video.streams.indices) return@update snapshot
            snapshot.copy(video = video.copy(selected = index, startSeconds = video.position, generation = video.generation + 1))
        }
    }

    fun changeVideoEpisode(forward: Boolean) = action {
        state.value.adjacentChapter(forward)?.let { watchOnIo(it) }
    }

    internal fun videoProgress(chapterId: Long, position: Double, duration: Double) =
        videoOperations.report(chapterId, position, duration)
    /** Reports the visible blocks of the novel reader; writes are debounced and flushed before navigation. */
    internal fun novelProgress(chapterId: Long, first: Int, last: Int) = novelOperations.report(chapterId, first, last)

    fun changeNovelChapter(forward: Boolean) = action {
        state.value.adjacentChapter(forward)?.let { readNovelOnIo(it) }
    }

    fun toggleNovelBookmark() = action {
        val snapshot = state.value
        if (snapshot.screen != DesktopScreen.NOVEL) return@action
        val content = requireNotNull(snapshot.content)
        val novel = requireNotNull(snapshot.novel)
        // Image/rule anchors keep the legacy numeric slot instead of taking the following paragraph's text.
        val preview = novel.blocks[novel.firstVisible].bookmarkText()
        val existing = snapshot.bookmarks.firstOrNull { novel.bookmarkBlocks[it.pageId] == novel.firstVisible }
        if (existing != null) session.library.removeBookmark(content, existing)
        else session.library.toggleNovelBookmark(content, novel.chapter, novel.firstVisible, novel.blocks.size, preview)
        val bookmarks = session.library.bookmarks(content.id)
        mutableState.update { it.copy(bookmarks = bookmarks, novel = it.novel?.copy(
            bookmarkBlocks = novelBookmarkBlocks(content, novel.chapter, novel.blocks, bookmarks))) }
    }

    fun openNovelBookmark(bookmark: DesktopBookmark) = action {
        val snapshot = state.value
        require(snapshot.screen == DesktopScreen.NOVEL && snapshot.content?.id == bookmark.contentId)
        require(session.library.bookmarks(bookmark.contentId).contains(bookmark)) { "书签已不存在" }
        val chapter = snapshot.content.chapters?.firstOrNull { it.id == bookmark.chapterId }
            ?: error("书签章节已不存在，请刷新作品详情")
        val novel = requireNotNull(snapshot.novel)
        if (novel.chapter == chapter) {
            val target = novelBookmarkIndex(snapshot.content, chapter, novel.blocks, bookmark)
            novelOperations.forget()
            mutableState.update { it.copy(novel = novel.copy(startBlock = target,
                firstVisible = target, lastVisible = target, navigation = novel.navigation + 1)) }
            novelOperations.report(chapter.id, target, target)
        } else readNovelOnIo(chapter, bookmark)
    }

    fun removeNovelBookmark(bookmark: DesktopBookmark) = action {
        val snapshot = state.value
        require(snapshot.screen == DesktopScreen.NOVEL)
        val content = requireNotNull(snapshot.content)
        session.library.removeBookmark(content, bookmark)
        val bookmarks = session.library.bookmarks(content.id)
        val novel = requireNotNull(snapshot.novel)
        mutableState.update { it.copy(bookmarks = bookmarks, novel = it.novel?.copy(
            bookmarkBlocks = novelBookmarkBlocks(content, novel.chapter, novel.blocks, bookmarks))) }
    }

    private fun novelBookmarkIndex(content: SourceContent, chapter: SourceChapter,
        blocks: List<NovelBlock>, bookmark: DesktopBookmark): Int {
        val branch = content.chapters.orEmpty().filter { it.branch == chapter.branch }
        val index = branch.indexOfFirst { it.id == chapter.id }
        val hint = novelBookmarkChapterProgress(bookmark.percent, index, branch.size)
        return requireNotNull(resolveNovelBookmarkPosition(blocks.map { it.bookmarkText() },
            parseNovelBookmarkPreview(bookmark.preview), bookmark.page, hint)) {
            "书签正文已变化或位置已失效，请刷新作品详情"
        }
    }

    private fun novelBookmarkBlocks(content: SourceContent, chapter: SourceChapter,
        blocks: List<NovelBlock>, bookmarks: List<DesktopBookmark>): Map<Long, Int> {
        val branch = content.chapters.orEmpty().filter { it.branch == chapter.branch }
        val index = branch.indexOfFirst { it.id == chapter.id }
        val text = NovelBookmarkTextIndex(blocks.map { it.bookmarkText() })
        return bookmarks.filter { it.chapterId == chapter.id }.mapNotNull { bookmark ->
            text.resolve(parseNovelBookmarkPreview(bookmark.preview), bookmark.page,
                novelBookmarkChapterProgress(bookmark.percent, index, branch.size))?.let { bookmark.pageId to it }
        }.toMap()
    }

    fun novelSettings(settings: DesktopNovelSettings) {
        mutableState.update { it.copy(novelSettings = settings) }
        val saved = novelPreferences.edit(SourcePreferenceEdit(changes = mapOf(
            "font_size" to SourcePreferenceValue.Integer(settings.fontSize),
            "line_spacing" to SourcePreferenceValue.FloatBits(settings.lineSpacing.toRawBits()),
            "width" to SourcePreferenceValue.Integer(settings.width),
            "theme" to SourcePreferenceValue.Text(settings.theme.name),
            "serif" to SourcePreferenceValue.Toggle(settings.serif),
        )))
        if (!saved) mutableState.update { it.copy(error = "阅读设置已更新到内存，磁盘保存失败") }
    }

    private fun restoredNovelSettings(): DesktopNovelSettings {
        val snapshot = novelPreferences.snapshot()
        val defaults = DesktopNovelSettings()
        return DesktopNovelSettings(
            fontSize = ((snapshot["font_size"] as? SourcePreferenceValue.Integer)?.value ?: defaults.fontSize)
                .coerceIn(DesktopNovelSettings.FONT_SIZES),
            lineSpacing = ((snapshot["line_spacing"] as? SourcePreferenceValue.FloatBits)?.let { Float.fromBits(it.bits) }
                ?.takeIf(Float::isFinite) ?: defaults.lineSpacing).coerceIn(DesktopNovelSettings.LINE_SPACINGS),
            width = ((snapshot["width"] as? SourcePreferenceValue.Integer)?.value ?: defaults.width)
                .coerceIn(DesktopNovelSettings.WIDTHS),
            theme = DesktopNovelTheme.entries.firstOrNull {
                it.name == (snapshot["theme"] as? SourcePreferenceValue.Text)?.value } ?: defaults.theme,
            serif = (snapshot["serif"] as? SourcePreferenceValue.Toggle)?.value ?: defaults.serif,
        )
    }

    /** Inline images of the open chapter go through the same source client, cache and challenge handling as pages. */
    internal suspend fun novelImage(url: String): DesktopReaderImage {
        val novel = requireNotNull(state.value.novel) { "没有打开的小说章节" }
        return session.novelImage(novel.chapter, novel.images[url] ?: SourceContentImage(url))
    }

    fun downloadChapter(chapter: SourceChapter) = action {
        val content = requireNotNull(state.value.content)
        require(content.chapters.orEmpty().any { it == chapter }) { "章节不属于当前作品" }
        downloads.enqueue(content.id, chapter, content.title)
    }

    fun showDownloads() = navigate { mutableState.update { it.copy(screen = DesktopScreen.DOWNLOADS) } }

    fun showBackups() = navigate { mutableState.update { it.copy(screen = DesktopScreen.BACKUPS) } }

    internal fun exportLibraryBackup(path: Path) = action {
        backups.export(path)
        mutableState.update { it.copy(message = "图书馆备份已保存：${path.fileName}") }
    }

    internal fun previewLibraryBackup(path: Path) = action {
        val preview = backups.preview(path)
        val previous = mutableBackupPreview.value
        mutableBackupPreview.value = preview
        previous?.close()
        mutableState.update { it.copy(screen = DesktopScreen.BACKUPS) }
    }

    internal fun dismissLibraryBackup() = action {
        val preview = mutableBackupPreview.value
        mutableBackupPreview.value = null
        preview?.close()
    }

    internal fun restoreLibraryBackup(preview: DesktopBackupArchive) = action {
        require(mutableBackupPreview.value === preview) { "备份预览已过期，请重新选择文件" }
        mutableBackupPreview.value = null
        val processed = preview.use { backups.restore(it) }
        mutableState.update { it.copy(items = emptyList(), content = null,
            message = "合并恢复完成，已处理 $processed 条记录") }
    }

    internal fun previewDownloadCleanup() = action {
        val context = currentCoroutineContext()
        mutableCleanup.value = session.downloadStore.previewCleanup(session.storage.preferences) { context.ensureActive() }
    }

    internal fun dismissDownloadCleanup() { mutableCleanup.value = null }

    internal fun cleanupDownloads(plan: DesktopDownloadCleanupPlan) = action {
        require(mutableCleanup.value === plan) { "回收预览已过期，请重新检查" }
        mutableCleanup.value = null
        val context = currentCoroutineContext()
        val result = session.downloadStore.cleanup(plan, session.storage.preferences) { context.ensureActive() }
        mutableState.update { it.copy(message = "已回收 ${result.removed} 条旧记录（${result.bytes / 1024} KB）" +
            if (result.skipped == 0) "" else "，跳过 ${result.skipped} 条已变化记录") }
    }

    internal fun resumeDownload(download: SourceChapterDownload) = action {
        downloads.enqueue(download.contentId, download.chapter)
    }

    internal fun readDownload(download: SourceChapterDownload) = action {
        val content = requireNotNull(session.library.find(download.contentId)) { "本地作品记录不存在" }
        require(session.downloadStore.find(download.contentId, download.chapter)?.isComplete == true) { "章节尚未下载完成" }
        mutableState.update { it.copy(content = content) }
        readOnIo(download.chapter)
    }

    private suspend fun readOnIo(chapter: SourceChapter? = null, startAtEnd: Boolean = false,
        bookmark: DesktopBookmark? = null) {
        val content = requireNotNull(state.value.content)
        val chapters = requireNotNull(content.chapters) { "没有可阅读章节" }
        val progress = session.library.progress(content.id)
        val restoredId = progress?.let { recoverHistoryChapterId(false, it.chapterId, it.parentChapterId,
            it.percent, chapters.map { c -> c.id }) ?: it.chapterId }
        val selected = chapter ?: chapters.firstOrNull { it.id == restoredId } ?: firstChapterOfBranch(chapters)
        // A chapter preloaded at the end of the previous one opens without asking the source again.
        val pages = synchronized(this) {
            skippedChapter = null
            nextChapter?.takeIf { it.first == selected.id }?.second.also { nextChapter = null }
        }
            ?: session.chapterPages(content.id, selected)
        require(pages.isNotEmpty()) { "此章节没有页面" }
        val bookmarkedIndex = bookmark?.let { saved ->
            require(saved.contentId == content.id && saved.chapterId == selected.id)
            pages.indexOfFirst { it.id == saved.pageId }.takeIf { it >= 0 }
                ?: saved.page.takeIf { it in pages.indices }
                ?: error("书签页面已不存在")
        }
        val index = bookmarkedIndex ?: if (startAtEnd) pages.lastIndex else if (chapter == null && selected.id == restoredId) {
            progress.page.coerceIn(pages.indices)
        } else 0
        val restoredScroll = bookmark?.scroll?.coerceAtLeast(0)?.toFloat() ?: if (chapter == null && selected.id == restoredId)
            progress.scroll.takeIf { it.isFinite() && it >= 0f } ?: 0f else 0f
        val candidate = state.value.let { it.copy(screen = DesktopScreen.READER, chapter = selected, pages = pages,
            pageIndex = index, readerImages = emptyMap(), readerGeometry = session.readerGeometry(pages), readerScroll = restoredScroll,
            readerTargetPage = index, readerTargetScroll = if (startAtEnd) Float.MAX_VALUE else restoredScroll,
            readerLastVisible = index, readerAtEnd = false, readerNavigation = it.readerNavigation + 1, readerScrollReady = false,
            readerVisiblePages = emptyList(), readerLoading = emptySet(), readerFailedPages = emptyMap(),
            bookmarks = session.library.bookmarks(content.id)) }
        val loaded = if (candidate.readerSettings.mode == DesktopReaderMode.CONTINUOUS) {
            val image = session.readerImage(pages[index])
            candidate.copy(readerImages = mapOf(pages[index].id to image))
        } else loadPagedOnIo(candidate, index)
        mutableState.value = loaded
        prefetchAroundSlot(loaded)
    }

    fun page(index: Int) = action { pageOnIo(index) }

    fun toggleBookmark() = action {
        val snapshot = state.value
        if (snapshot.screen != DesktopScreen.READER) return@action
        val content = requireNotNull(snapshot.content)
        val chapter = requireNotNull(snapshot.chapter)
        if (snapshot.readerSettings.mode == DesktopReaderMode.CONTINUOUS && !snapshot.readerScrollReady) return@action
        session.library.toggleBookmark(content, chapter, snapshot.pages[snapshot.pageIndex], snapshot.pageIndex,
            snapshot.pages.size, snapshot.readerScroll)
        val bookmarks = session.library.bookmarks(content.id)
        mutableState.update { it.copy(bookmarks = bookmarks) }
    }

    fun openBookmark(bookmark: DesktopBookmark) = action {
        val snapshot = state.value
        require(snapshot.screen == DesktopScreen.READER && snapshot.content?.id == bookmark.contentId)
        require(snapshot.bookmarks.contains(bookmark)) { "书签已不存在" }
        val chapter = snapshot.content.chapters?.firstOrNull { it.id == bookmark.chapterId }
            ?: error("书签章节已不存在，请刷新作品详情")
        readOnIo(chapter, bookmark = bookmark)
    }

    fun turnPage(forward: Boolean) = action {
        val snapshot = state.value
        val continuousMode = snapshot.readerSettings.mode == DesktopReaderMode.CONTINUOUS
        val boundary = if (continuousMode) snapshot.readerScrollReady &&
            (if (forward) snapshot.readerAtEnd else snapshot.pageIndex == 0 && snapshot.readerScroll == 0f)
            else readerLayout(snapshot).turnIndex(forward) == null
        val target = if (continuousMode) (snapshot.pageIndex + if (forward) 1 else -1).takeIf {
            !boundary && it in snapshot.pages.indices
        } else readerLayout(snapshot).turnIndex(forward)
        if (target != null) pageOnIo(target)
        else if (boundary && snapshot.readerSettings.automaticChapter) {
            snapshot.adjacentChapter(forward)?.let { readOnIo(it, startAtEnd = !forward) }
        }
    }

    fun changeChapter(forward: Boolean) = action {
        state.value.adjacentChapter(forward)?.let { readOnIo(it) }
    }

    internal fun continuousChapter(forward: Boolean, chapterId: Long, navigation: Long) = action {
        val snapshot = state.value
        if (snapshot.screen != DesktopScreen.READER || snapshot.chapter?.id != chapterId ||
            snapshot.readerNavigation != navigation || !snapshot.readerSettings.automaticChapter ||
            snapshot.readerSettings.mode != DesktopReaderMode.CONTINUOUS || !snapshot.readerScrollReady) return@action
        val boundary = if (forward) snapshot.readerAtEnd else snapshot.pageIndex == 0 && snapshot.readerScroll == 0f
        if (boundary) snapshot.adjacentChapter(forward)?.let { readOnIo(it, startAtEnd = !forward) }
    }

    fun reloadPage(): Job {
        val snapshot = state.value
        return if (snapshot.readerSettings.mode == DesktopReaderMode.CONTINUOUS && snapshot.chapter != null) {
            continuous.load(snapshot.chapter.id, snapshot.readerVisiblePages.ifEmpty { listOf(snapshot.pageIndex) }, true)
        } else action { pageOnIo(state.value.pageIndex, refresh = true) }
    }

    internal fun continuousImages(chapterId: Long, indices: List<Int>) = continuous.load(chapterId, indices)

    internal fun continuousProgress(chapterId: Long, first: Int, offset: Float, last: Int, atEnd: Boolean) {
        val snapshot = state.value
        if (snapshot.chapter?.id == chapterId && last >= snapshot.pages.lastIndex - NEXT_CHAPTER_LOOKAHEAD) preloadNextChapter(snapshot)
        continuous.report(chapterId, first, offset, last, atEnd)
    }

    private var nextChapter: Pair<Long, List<SourcePage>>? = null

    /** The chapter prepared for reading on, if any (tests observe the preload). */
    internal val preloadedChapterId: Long? get() = synchronized(this) { nextChapter?.first }

    /** Whether the next chapter is still being prepared. */
    internal val isPreloadingChapter: Boolean get() = synchronized(this) { nextChapterJob?.isActive == true }

    /** Drops a prepared chapter, so the next chapter turn asks the source again (tests of failing turns). */
    internal fun forgetPreloadedChapter() = synchronized(this) {
        nextChapterJob?.cancel(); nextChapter?.first?.let { skippedChapter = it }; nextChapter = null
    }

    /** A chapter whose preparation failed (or was dropped): not retried in the background; opening it still loads it. */
    private var skippedChapter: Long? = null
    private var nextChapterJob: Job? = null

    /**
     * Near the end of a chapter the next one is prepared in the background, as Android's reader does: its page list
     * and first pages, so reading on (or the automatic chapter turn) does not wait for the source.
     */
    private fun preloadNextChapter(snapshot: DesktopAppState) {
        val content = snapshot.content ?: return
        val next = snapshot.adjacentChapter(true) ?: return
        synchronized(this) {
            if (nextChapter?.first == next.id || nextChapterJob?.isActive == true || skippedChapter == next.id) return
            nextChapterJob = scope.launch(Dispatchers.IO) {
                val pages = try { session.chapterPages(content.id, next) } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { synchronized(this@DesktopController) { skippedChapter = next.id }; return@launch }
                synchronized(this@DesktopController) { nextChapter = next.id to pages }
                for (page in pages.take(NEXT_CHAPTER_PAGES)) {
                    try { session.readerImage(page) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
                }
            }
        }
    }

    fun readerSettings(settings: DesktopReaderSettings) = action {
        val previous = state.value.readerSettings
        val saved = readerPreferences.edit(SourcePreferenceEdit(changes = mapOf(
            "mode" to SourcePreferenceValue.Text(settings.mode.name),
            "right_to_left" to SourcePreferenceValue.Toggle(settings.rightToLeft),
            "fit_mode" to SourcePreferenceValue.Text(settings.fitMode.name),
            "automatic_chapter" to SourcePreferenceValue.Toggle(settings.automaticChapter),
            "upscale_model" to SourcePreferenceValue.Text(settings.upscale.model?.name ?: ""),
            "upscale_noise" to SourcePreferenceValue.Integer(settings.upscale.noise),
            "animation" to SourcePreferenceValue.Text(settings.animation.name),
            "cf_brightness" to SourcePreferenceValue.FloatBits(settings.colorFilter.brightness.toRawBits()),
            "cf_contrast" to SourcePreferenceValue.FloatBits(settings.colorFilter.contrast.toRawBits()),
            "cf_invert" to SourcePreferenceValue.Toggle(settings.colorFilter.inverted),
            "cf_grayscale" to SourcePreferenceValue.Toggle(settings.colorFilter.grayscale),
            "cf_book" to SourcePreferenceValue.Toggle(settings.colorFilter.book),
            "background" to SourcePreferenceValue.Text(settings.background.name),
            "page_numbers" to SourcePreferenceValue.Toggle(settings.pageNumbers),
            "vertical" to SourcePreferenceValue.Toggle(settings.vertical),
            "crop_paged" to SourcePreferenceValue.Toggle(settings.cropPaged),
            "crop_continuous" to SourcePreferenceValue.Toggle(settings.cropContinuous),
            "as_speed" to SourcePreferenceValue.FloatBits(settings.autoScrollSpeed.toRawBits()),
            "double_cover" to SourcePreferenceValue.Toggle(settings.doublePageCover),
        ) + tapGridPreferences(settings.tapGrid)))
        session.upscale = settings.upscale
        session.cropPages = settings.cropActive
        // Loaded pages were made for the previous super-resolution or crop choice; they are loaded (from cache) again.
        val reload = previous.upscale != settings.upscale || previous.cropActive != settings.cropActive
        if (reload) synchronized(this) { prefetchJob?.cancel() }
        mutableState.update { it.copy(readerSettings = settings,
            readerImages = if (reload) emptyMap() else it.readerImages) }
        if (state.value.screen == DesktopScreen.READER &&
            previous.copy(automaticChapter = settings.automaticChapter, animation = settings.animation,
                colorFilter = settings.colorFilter, background = settings.background, pageNumbers = settings.pageNumbers,
                autoScrollSpeed = settings.autoScrollSpeed, tapGrid = settings.tapGrid) != settings) {
            pageOnIo(state.value.pageIndex)
        }
        if (!saved) mutableState.update { it.copy(error = "阅读设置已更新到内存，磁盘保存失败") }
    }

    private fun restoredReaderSettings(): DesktopReaderSettings {
        val snapshot = readerPreferences.snapshot()
        val mode = (snapshot["mode"] as? SourcePreferenceValue.Text)?.value
        val fitMode = (snapshot["fit_mode"] as? SourcePreferenceValue.Text)?.value
        return DesktopReaderSettings(
            DesktopReaderMode.entries.firstOrNull { it.name == mode } ?: DesktopReaderMode.SINGLE,
            (snapshot["right_to_left"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            ZoomMode.entries.firstOrNull { it.name == fitMode } ?: ZoomMode.FIT_CENTER,
            (snapshot["automatic_chapter"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            org.skepsun.kototoro.desktop.runtime.DesktopUpscaleSetting(
                org.skepsun.kototoro.desktop.runtime.DesktopUpscaleModel.entries.firstOrNull {
                    it.name == (snapshot["upscale_model"] as? SourcePreferenceValue.Text)?.value
                },
                ((snapshot["upscale_noise"] as? SourcePreferenceValue.Integer)?.value ?: -1).coerceIn(-1, 3),
            ),
            org.skepsun.kototoro.core.prefs.ReaderAnimation.entries.firstOrNull {
                it.name == (snapshot["animation"] as? SourcePreferenceValue.Text)?.value
            } ?: org.skepsun.kototoro.core.prefs.ReaderAnimation.DEFAULT,
            DesktopReaderColorFilter(
                Float.fromBits((snapshot["cf_brightness"] as? SourcePreferenceValue.FloatBits)?.bits ?: 0).coerceIn(-1f, 1f),
                Float.fromBits((snapshot["cf_contrast"] as? SourcePreferenceValue.FloatBits)?.bits ?: 0).coerceIn(-1f, 1f),
                (snapshot["cf_invert"] as? SourcePreferenceValue.Toggle)?.value ?: false,
                (snapshot["cf_grayscale"] as? SourcePreferenceValue.Toggle)?.value ?: false,
                (snapshot["cf_book"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            ),
            DesktopReaderBackground.entries.firstOrNull {
                it.name == (snapshot["background"] as? SourcePreferenceValue.Text)?.value
            } ?: DesktopReaderBackground.DEFAULT,
            (snapshot["page_numbers"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            (snapshot["vertical"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            (snapshot["crop_paged"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            (snapshot["crop_continuous"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            (snapshot["as_speed"] as? SourcePreferenceValue.FloatBits)?.bits?.let(Float::fromBits)?.coerceIn(0f, 1f)
                ?: org.skepsun.kototoro.reader.core.ReaderAutoScroll.DEFAULT_SPEED,
            (snapshot["double_cover"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            restoredTapGrid(snapshot),
        ).also { session.upscale = it.upscale; session.cropPages = it.cropActive }
    }

    /**
     * Reader actions are stored under Android's tap-grid keys (`tap_grid.CENTER`, `tap_grid.CENTER_long`); an empty
     * value is "no action" and a missing key keeps the shared default.
     */
    private fun tapGridPreferences(config: Map<TapGridArea, TapActions>): Map<String, SourcePreferenceValue> =
        TapGridArea.entries.flatMap { area ->
            listOf(false, true).map { long ->
                TAP_GRID_PREFIX + TapGridConfig.prefKey(area, long) to
                    SourcePreferenceValue.Text(TapGridConfig.action(config, area, long)?.name ?: "")
            }
        }.toMap()

    private fun restoredTapGrid(snapshot: Map<String, SourcePreferenceValue>): Map<TapGridArea, TapActions> =
        TapGridArea.entries.fold(TapGridConfig.defaults) { config, area ->
            listOf(false, true).fold(config) { current, long ->
                val stored = (snapshot[TAP_GRID_PREFIX + TapGridConfig.prefKey(area, long)] as? SourcePreferenceValue.Text)
                    ?.value ?: return@fold current
                TapGridConfig.with(current, area, long, TapAction.entries.firstOrNull { it.name == stored })
            }
        }

    /** Downloads and installs the official program a super-resolution model needs. */
    fun installUpscaler(tool: org.skepsun.kototoro.desktop.runtime.DesktopUpscaleTool) = action {
        session.superResolution.install(tool)
        mutableState.update { it.copy(message = "已安装 ${tool.title}") }
        reloadUpscaledPage()
    }

    fun installUpscalerArchive(tool: org.skepsun.kototoro.desktop.runtime.DesktopUpscaleTool, archive: Path) = action {
        session.superResolution.installArchive(tool, archive)
        mutableState.update { it.copy(message = "已安装 ${tool.title}") }
        reloadUpscaledPage()
    }

    /** An open page shown without super-resolution (program was missing) picks it up once installed. */
    private suspend fun reloadUpscaledPage() {
        if (state.value.screen == DesktopScreen.READER && state.value.readerSettings.upscale.model != null) {
            mutableState.update { it.copy(readerImages = emptyMap()) }
            pageOnIo(state.value.pageIndex)
        }
    }

    private fun readerLayout(snapshot: DesktopAppState) = DesktopReaderLayout(snapshot.pages,
        snapshot.chapter?.id ?: 0L, snapshot.readerImages, snapshot.readerSettings, snapshot.pageIndex,
        geometry = snapshot.readerGeometry)

    private suspend fun pageOnIo(index: Int, refresh: Boolean = false) {
        val snapshot = state.value
        if (index !in snapshot.pages.indices) return
        if (snapshot.readerSettings.mode == DesktopReaderMode.CONTINUOUS) {
            mutableState.update { it.copy(readerTargetPage = index, readerTargetScroll = 0f,
                readerNavigation = it.readerNavigation + 1, readerScrollReady = false, readerAtEnd = false) }
            return
        }
        mutableState.value = loadPagedOnIo(snapshot, index, refresh)
        prefetchAroundSlot(state.value)
    }

    private var prefetchJob: Job? = null
    private var prefetchFailures: Pair<Long, MutableSet<Long>> = 0L to java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Whether background page prefetching is still running (tests wait for it before counting requests). */
    internal val isPrefetching: Boolean get() = synchronized(this) { prefetchJob?.isActive == true }

    /**
     * Android's paged resource window (reader-core): the slots around the current one load in the background, two
     * ahead and two behind, nearest first, so a turn finds its pages ready. Outside the action gate: it never delays
     * a turn, and a newer window replaces it.
     */
    private fun prefetchAroundSlot(snapshot: DesktopAppState) {
        if (snapshot.screen != DesktopScreen.READER || snapshot.readerSettings.mode == DesktopReaderMode.CONTINUOUS) return
        val layout = readerLayout(snapshot)
        val frame = layout.frame ?: return
        val window = org.skepsun.kototoro.reader.core.PagedSceneResourceWindowStrategy(lookaheadSlots = 2,
            prepareAdjacentSlots = true).plan(org.skepsun.kototoro.reader.core.SceneResourceWindowRequest(layout.scene, frame))
        prefetchPages(snapshot.chapter?.id ?: return, window.requests.sortedBy { it.priority }
            .map { layout.scene.indexOf(it.pageId) }.filter { it >= 0 && it !in layout.indices })
        val slots = layout.scene.slotCount
        if (layout.slotIndex >= slots - 1 - NEXT_CHAPTER_LOOKAHEAD) preloadNextChapter(snapshot)
    }

    /** Loads [indices] of the open chapter in the background, nearest first; failures stay for the reader to retry. */
    internal fun prefetchPages(chapterId: Long, indices: List<Int>) {
        val snapshot = state.value
        if (snapshot.screen != DesktopScreen.READER || snapshot.chapter?.id != chapterId) return
        synchronized(this) {
            if (prefetchFailures.first != chapterId) prefetchFailures = chapterId to java.util.concurrent.ConcurrentHashMap.newKeySet()
            // A page whose background load failed is left to the reader: showing it loads (and reports) it again.
            val failed = prefetchFailures.second
            val pages = indices.distinct().mapNotNull { snapshot.pages.getOrNull(it) }
                .filter { it.id !in snapshot.readerImages && it.id !in snapshot.readerFailedPages && it.id !in failed }
            prefetchJob?.cancel()
            if (pages.isEmpty()) return
            prefetchJob = scope.launch(Dispatchers.IO) {
                for (page in pages) {
                    currentCoroutineContext().ensureActive()
                    val image = try { session.readerImage(page) } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { failed += page.id; continue }
                    mutableState.update { current ->
                        if (current.screen == DesktopScreen.READER && current.chapter?.id == chapterId &&
                            current.pages.any { it.id == page.id }) current.copy(readerImages = current.readerImages + (page.id to image))
                        else current
                    }
                }
            }
        }
    }

    private suspend fun loadPagedOnIo(snapshot: DesktopAppState, index: Int, refresh: Boolean = false): DesktopAppState {
        val images = snapshot.readerImages.toMutableMap()
        if (refresh) readerLayout(snapshot).indices.forEach { images.remove(snapshot.pages[it].id) }
        // Header dimensions can turn a provisional pair into solo wide pages.
        // Re-resolve until the visible set is loaded.
        var layout: DesktopReaderLayout
        while (true) {
            layout = readerLayout(snapshot.copy(pageIndex = index, readerImages = images))
            val missing = layout.indices.filter { snapshot.pages[it].id !in images }
            if (missing.isEmpty()) break
            for (position in missing) {
                val page = snapshot.pages[position]
                images[page.id] = session.readerImage(page, refresh)
            }
        }
        val anchor = layout.anchorIndex
        session.library.recordPage(requireNotNull(snapshot.content), requireNotNull(snapshot.chapter),
            anchor, snapshot.pages.size, layout.indices.last())
        return snapshot.copy(screen = DesktopScreen.READER, pageIndex = anchor, readerImages = images.toMap(), readerScroll = 0f,
                readerScrollReady = false, readerLastVisible = layout.indices.last(),
                readerFailedPages = snapshot.readerFailedPages - layout.indices.map { index -> snapshot.pages[index].id }.toSet())
    }

    fun preferences() = navigate {
        val source = requireNotNull(state.value.selectedSource) { "请先选择来源" }
        val screen = session.sources.getPreferences(source.source.name)
        mutableState.update { it.copy(screen = DesktopScreen.PREFERENCES, preferences = screen) }
    }

    fun updatePreference(node: SourcePreferenceNode, value: SourcePreferenceValue) = action {
        val screen = requireNotNull(state.value.preferences)
        val result = session.sources.updatePreference(screen.source.name, screen.revision, node.id, value)
        val message = when (result.status) {
            SourcePreferenceUpdateStatus.ACCEPTED -> "设置已保存"
            SourcePreferenceUpdateStatus.REJECTED -> "来源拒绝了这项设置"
            SourcePreferenceUpdateStatus.PERSISTENCE_FAILED -> "设置已更新到内存，磁盘保存失败"
        }
        mutableState.update { it.copy(preferences = result.screen, message = message) }
    }

    fun browser() = navigate { mutableState.update { it.copy(screen = DesktopScreen.BROWSER) } }

    fun more() = navigate { mutableState.update { it.copy(screen = DesktopScreen.MORE) } }

    fun backToDetails() = action {
        closeCast(resume = false)
        mutableState.update { it.copy(screen = DesktopScreen.DETAILS) }
    }

    private fun sourceDescriptors() = session.sourceListings()
        .sortedWith(compareBy({ it.displayName.lowercase() }, { it.source.locale }))

    private var navigation: Job? = null

    /**
     * An action that changes what the window shows. The newest one wins: a still loading (or queued) navigation is
     * cancelled, so the user can leave a slow source or page instead of waiting for it.
     */
    private fun navigate(block: suspend () -> Unit): Job = synchronized(actionState) {
        navigation?.cancel()
        action(block).also { navigation = it }
    }

    private fun action(block: suspend () -> Unit): Job {
        // Publish queued work before returning to the UI; IO dispatcher scheduling must not leave a false idle gap.
        synchronized(actionState) {
            pendingActions++
            mutableState.update { it.copy(busy = true) }
        }
        return launchAction(block).also { operation ->
            operation.invokeOnCompletion {
                synchronized(actionState) {
                    pendingActions--
                    if (pendingActions == 0) mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun launchAction(block: suspend () -> Unit): Job = scope.launch {
        continuous.cancelAndJoin()
        novelOperations.cancelAndJoin()
        videoOperations.cancelAndJoin()
        gate.withLock {
            mutableState.update { it.copy(busy = true, error = null, message = null) }
            try {
                val challenges = session.browserChallenges
                continuous.flush()
                novelOperations.flush()
                videoOperations.flush()
                if (challenges == null) block() else challenges.withRequestCancellation(block)
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                val diagnostic = if (error is SourceRemoteException && error.error.code == SourceErrorCode.RUNTIME_FAILURE) {
                    session.sourceDiagnostics.message(error.requestId)
                } else null
                mutableState.update { it.copy(error = diagnostic ?: error.message ?: error.javaClass.simpleName) }
            } catch (error: LinkageError) {
                mutableState.update { it.copy(error = "来源 API 不兼容：${error.message ?: error.javaClass.simpleName}") }
            }
        }
    }

    suspend fun shutdown() {
        session.browserChallenges?.close()
        job.cancelAndJoin()
        withContext(NonCancellable + Dispatchers.IO) {
            try { gate.withLock { continuous.flush(); novelOperations.flush(); videoOperations.flush() } }
            finally {
                try { val preview = mutableBackupPreview.value; mutableBackupPreview.value = null; preview?.close() }
                finally { session.close() }
            }
        }
    }
}
