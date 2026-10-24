package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import org.skepsun.kototoro.core.library.LibraryContentStats
import org.skepsun.kototoro.core.library.LibraryReadingStats
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import org.skepsun.kototoro.stats.domain.StatsContentKind
import org.skepsun.kototoro.history.domain.recoverHistoryChapterId
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.core.IntSize
import java.nio.file.Files
import java.nio.file.Path
import org.skepsun.kototoro.desktop.runtime.DesktopNovelHtml
import org.skepsun.kototoro.desktop.runtime.NovelBlock
import org.skepsun.kototoro.desktop.runtime.bookmarkText
import org.skepsun.kototoro.bookmarks.domain.parseNovelBookmarkPreview
import org.skepsun.kototoro.bookmarks.domain.novelBookmarkChapterProgress
import org.skepsun.kototoro.bookmarks.domain.resolveNovelBookmarkPosition
import org.skepsun.kototoro.bookmarks.domain.NovelBookmarkTextIndex
import org.skepsun.kototoro.backups.webdav.WebDavBackupFile
import org.skepsun.kototoro.desktop.runtime.DesktopBackupArchive
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryBackup
import org.skepsun.kototoro.desktop.runtime.DesktopBookmark
import org.skepsun.kototoro.desktop.runtime.DesktopLibrarySnapshot
import org.skepsun.kototoro.desktop.runtime.DesktopRepository
import org.skepsun.kototoro.desktop.runtime.DesktopRepositoryCatalog
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateSettings
import org.skepsun.kototoro.desktop.runtime.DesktopTriState
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
private const val LAST_APP_UPDATE_CHECK = "last_app_update_check"
/** Page groups (paged) or pages (continuous) before a chapter's end at which the next chapter is prepared. */
private const val NEXT_CHAPTER_LOOKAHEAD = 2
/** First pages of the next chapter loaded with it. */
private const val NEXT_CHAPTER_PAGES = 2
/** After a failed automatic upload, how long the timer waits before it tries the same server again. */
private const val AUTO_BACKUP_RETRY_MILLIS = 10L * 60 * 1000
/** The total image budget lives in the settings store Android keeps for Windows-only storage choices. */
private const val IMAGE_BUDGET = "image_budget_mb"
/** A sweep lists and stats the whole image directory, so the periodic loop runs it at most this often. */
private const val IMAGE_SWEEP_INTERVAL_MILLIS = DesktopImageBudget.SWEEP_INTERVAL_MILLIS

internal val DetailsOrigins = setOf(DesktopScreen.HOME, DesktopScreen.FEED, DesktopScreen.EXPLORE, DesktopScreen.LIBRARY,
    DesktopScreen.HISTORY)

/** The JVM's display locale, matched against branch names as Android matches its locale list. */
internal fun desktopChapterBranchLocales(): List<ChapterBranchLocale> =
    listOf(Locale.getDefault(Locale.Category.DISPLAY), Locale.getDefault()).distinct()
        .map { ChapterBranchLocale(it.getDisplayLanguage(it), it.getDisplayName(it)) }

enum class DesktopScreen {
    HOME, FEED, EXPLORE, LIBRARY, HISTORY, DETAILS, READER, NOVEL, VIDEO, PREFERENCES, BROWSER, DOWNLOADS, BACKUPS,
    RECOMMENDATIONS, EXTENSIONS, READER_SETTINGS, SOURCE_SETTINGS, TAG_BLACKLIST, READING_STATS, MORE, PROXY_SETTINGS,
}

/** The three screens that own an open chapter, where Android's reading statistics are counted. */
internal val DesktopScreen.isReading: Boolean
    get() = this == DesktopScreen.READER || this == DesktopScreen.NOVEL || this == DesktopScreen.VIDEO

/**
 * What a reader entry asked for, kept while the adult-work question is open so the answer can carry on with it. Each
 * top-level entry fills its own shape: a plain read, a bookmark jump, or a downloaded chapter.
 */
sealed interface DesktopReadingRequest {
    data class Chapter(val chapter: SourceChapter?) : DesktopReadingRequest
    data class Bookmark(val chapter: SourceChapter, val bookmark: DesktopBookmark) : DesktopReadingRequest
    data class Download(val chapter: SourceChapter) : DesktopReadingRequest
}

/** Android's `IncognitoModeDialog` pending state: the question and the "don't ask again" checkbox. */
data class DesktopIncognitoPrompt(
    val request: DesktopReadingRequest,
    val dontAskAgain: Boolean = false,
)

data class DesktopAppState(
    val screen: DesktopScreen = DesktopScreen.EXPLORE,
    val appearance: DesktopAppearance = DesktopAppearance.SYSTEM,
    val interfaceStyle: DesktopInterfaceStyle = DesktopInterfaceStyle.MATERIAL,
    /** The saved interface language tag; null follows the system language. */
    val language: String? = null,
    val sources: List<SourceListing> = emptyList(),
    val repositories: List<DesktopRepository> = emptyList(),
    val extensionCatalog: DesktopRepositoryCatalog? = null,
    val installedExtensions: Map<String, Long> = emptyMap(),
    val installedEntries: List<DesktopInstalledEntry> = emptyList(),
    val jsonSources: List<org.skepsun.kototoro.desktop.runtime.DesktopJsonSource> = emptyList(),
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
    /** Open change-source dialog (Android's MIGRATE) and the search state it carries. */
    val migration: DesktopMigrationState? = null,
    val bookmarks: List<DesktopBookmark> = emptyList(),
    val content: SourceContent? = null,
    /** List that owns the open details; retained while reading so returning does not lose the list context. */
    val detailsOrigin: DesktopScreen? = null,
    /** Android's incognito reader: while set, nothing this session reports reaches the history. */
    val readerIncognito: Boolean = false,
    /** The adult-work question Android shows in the reader; Windows asks before opening it. */
    val incognitoPrompt: DesktopIncognitoPrompt? = null,
    val detailsExpanded: Boolean = false,
    /** Chapter branch (translation) shown in the details chapter list. */
    val chapterBranch: String? = null,
    /** Current chapter projected from this work's history; never inherited from a different open work. */
    val detailsChapterId: Long? = null,
    /** Home's history row; separate from [library], which the favourites/history pages own. */
    val homeHistory: DesktopLibrarySnapshot = DesktopLibrarySnapshot(),
    /** Android's feed read model (update logs and works with pending new chapters). */
    val feed: org.skepsun.kototoro.tracker.domain.feed.FeedSnapshot = org.skepsun.kototoro.tracker.domain.feed.FeedSnapshot.Empty,
    /** Android's suggestion and tracker settings, persisted under Android's keys. */
    val suggestionSettings: org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings = org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings(),
    val trackerSettings: org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings = org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings(),
    val sourceSettings: org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings = org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings(),
    val libraryDisplay: org.skepsun.kototoro.desktop.runtime.DesktopLibraryDisplay =
        org.skepsun.kototoro.desktop.runtime.DesktopLibraryDisplay(),
    /** Android's "阅读统计" screen: the selected period and kind, and the dashboard derived for them. */
    val readingStats: LibraryReadingStats? = null,
    val readingStatsPeriod: ReadingStatsPeriod = ReadingStatsPeriod.WEEK,
    val readingStatsKind: StatsContentKind = StatsContentKind.ALL,
    /** Android's per-work statistics sheet; null when it is closed. */
    val contentStats: DesktopContentStats? = null,
    /** Android's proxy page: the saved connection and the state of its "test connection" row. */
    val proxySettings: org.skepsun.kototoro.desktop.runtime.DesktopProxySettings =
        org.skepsun.kototoro.desktop.runtime.DesktopProxySettings(),
    val proxyTest: DesktopProxyTestState = DesktopProxyTestState(),
    /** Stored suggestions, most relevant first (Android's recommendations). */
    val suggestions: List<SourceContent> = emptyList(),
    /** True while [DesktopController.refreshSuggestions] runs in the background; the window stays usable. */
    val suggestionsGenerating: Boolean = false,
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
    /** This copy's version, the channel it came from, and the in-app update flow's progress. */
    val update: DesktopUpdateUi = DesktopUpdateUi(),
    /** The image cache's total size, the part saved chapters need, and its budget. */
    val imageCache: DesktopImageCacheReport = DesktopImageCacheReport(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** Share text shown when Windows refused the clipboard write; null while there is nothing to fall back to. */
    val shareLinks: String? = null,
) {
    val image: Path? get() = if (screen == DesktopScreen.READER) pages.getOrNull(pageIndex)
        ?.let { readerImages[it.id]?.path } else null

    /**
     * Android's incognito reader records nothing, so every automatic save asks this first. Explicit commands (mark as
     * read/unread, batch downloads) stay user actions and are never gated.
     */
    val savesProgress: Boolean get() = !readerIncognito
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
    private val libraryPreferences = session.storage.preferences.open("desktop_library")
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
        } ?: DesktopInterfaceStyle.MATERIAL,
        language = restoredLanguage()))
    val state = mutableState.asStateFlow()
    private val continuous = DesktopScrollOperations(mutableState, session, scope, gate)
    private val novelOperations = DesktopNovelOperations(mutableState, session, scope, gate)
    private val videoOperations = DesktopVideoOperations(mutableState, session, scope, gate)
    internal val downloads = DesktopDownloads(session.downloadStore, scope,
        { session.readerRevision(it.source.name) }, session::downloadPages, session::prepareDownload)
    private val mutableCleanup = MutableStateFlow<DesktopDownloadCleanupPlan?>(null)
    internal val cleanupPreview = mutableCleanup.asStateFlow()
    private val mutableAutoCleanup = MutableStateFlow(session.downloadMaintenance.automatic)
    internal val autoCleanupDownloads = mutableAutoCleanup.asStateFlow()
    /** Android's total image budget; the page shows what the reader cache holds and what saving protects. */
    private val storagePreferences = session.storage.preferences.open("desktop_storage")
    private var lastImageSweepAt = 0L
    /** Chapter keys the download page has selected; kept here so a batch survives leaving and reopening the page. */
    private val mutableDownloadSelection = MutableStateFlow<Set<String>>(emptySet())
    internal val downloadSelection = mutableDownloadSelection.asStateFlow()
    private val backups = DesktopLibraryBackup(session.storage.database)
    private val mutableBackupPreview = MutableStateFlow<DesktopBackupArchive?>(null)
    internal val backupPreview = mutableBackupPreview.asStateFlow()
    /** Android's in-app update flow: check the published releases, download, and swap the image on restart. */
    internal val updates = DesktopUpdateOperations(mutableState, session, scope)
    /** Android's WebDAV backup destination: same shared protocol, uploaded by hand or by the timer below. */
    private val webDav = DesktopWebDavOperations(session.storage, backups)
    internal val webDavUi = webDav.ui
    private var autoBackupJob: Job? = null

    init {
        // The saved language applies before the first screen is composed; `--lang` outranks it for this run.
        if (!DesktopStrings.isPinned) DesktopStrings.select(mutableState.value.language)
        refreshSources()
        mutableState.update { it.copy(jsonSources = session.jsonSourceList()) }
        mutableState.update { it.copy(autoUpdateExtensions =
            (updatePreferences.snapshot()["auto_update"] as? SourcePreferenceValue.Toggle)?.value ?: false) }
        backgroundPreferences.snapshot().let { saved ->
            mutableState.update { it.copy(
                suggestionSettings = org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings.from(saved),
                trackerSettings = org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings.from(saved),
            sourceSettings = org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings.from(saved),
            libraryDisplay = org.skepsun.kototoro.desktop.runtime.DesktopLibraryDisplay.from(
                libraryPreferences.snapshot(),
            ),
            ) }
        }
        // The proxy the SDK client already uses is what the page shows; changing it writes the same store.
        mutableState.update { it.copy(proxySettings = session.proxy.settings.value) }
        // Repositories are read in the background at start: updates are installed (when allowed) or announced.
        scope.launch { runCatching { startupUpdates() } }
        // The stamped version and the saved update toggles, before the first check or the first composed screen.
        updates.restore()
        webDav.restore()
        // Only starts a loop when the saved destination has an interval; otherwise it returns immediately.
        startAutoBackup()
        // Sweeps unreachable page blocks at start and whenever the queue goes idle, so the metadata half of
        // the download storage does not grow for weeks just because nobody clicked "reclaim".
        scope.launch(DesktopDispatchers.background) {
            downloads.state.map { rows -> rows.any { it.status.isActive } }.distinctUntilChanged()
                .collect { active -> if (!active) reclaimDownloadsInBackground() }
        }
    }

    private suspend fun reclaimDownloadsInBackground() {
        val result = runCatching { session.downloadMaintenance.reclaim() }.getOrNull() ?: return
        if (result.removed == 0) return
        mutableState.update { it.copy(message = desktopText("downloads.cleanupAuto", result.removed, result.bytes / 1024)) }
    }

    internal fun setAutoDownloadCleanup(enabled: Boolean) {
        session.downloadMaintenance.automatic = enabled
        mutableAutoCleanup.value = session.downloadMaintenance.automatic
    }

    private suspend fun startupUpdates() {
        if (session.repositories.saved().isEmpty() || session.registry.installed().isEmpty()) return
        val check = session.checkUpdates()
        mutableState.update { it.copy(extensionUpdates = check.updates) }
        if (check.updates.isNotEmpty() && state.value.autoUpdateExtensions) applyUpdates(check.updates).join()
        else if (check.updates.isNotEmpty()) mutableState.update { it.copy(message = desktopText("extensions.updatesAvailableHint", check.updates.size)) }
    }

    fun setAutoUpdateExtensions(enabled: Boolean) {
        mutableState.update { it.copy(autoUpdateExtensions = enabled) }
        if (!updatePreferences.edit(SourcePreferenceEdit(changes = mapOf("auto_update" to SourcePreferenceValue.Toggle(enabled))))) {
            mutableState.update { it.copy(error = desktopText("extensions.autoUpdateSaveFailed")) }
        }
    }

    /** Android's "check for updates" button: runs the same check the periodic loop does, but right now. */
    fun checkAppUpdate() {
        updates.check()
        recordRun(LAST_APP_UPDATE_CHECK)
    }

    fun downloadAppUpdate() = updates.download()

    /** Swaps the downloaded package and closes the window; [onClose] is the window's own quit path. */
    fun installAppUpdate(onClose: () -> Unit) = updates.install(onClose)

    fun discardAppUpdate() = action { updates.discard() }

    fun appUpdateSettings(value: DesktopUpdateSettings) = updates.settings(value)

    internal fun appearance(value: DesktopAppearance) = action {
        check(appearancePreferences.edit(SourcePreferenceEdit(changes = mapOf(
            "theme" to SourcePreferenceValue.Text(value.name),
        )))) { desktopText("settings.appearanceSaveFailed") }
        mutableState.update { it.copy(appearance = value) }
    }

    /** Shows the UI in [value] (null: follow the system language) and remembers the choice for the next start. */
    fun language(value: String?) = action {
        val tag = value?.takeIf(String::isNotBlank)
        val saved = appearancePreferences.edit(SourcePreferenceEdit(changes = mapOf(
            "language" to SourcePreferenceValue.Text(tag.orEmpty()),
        )))
        if (saved) {
            DesktopStrings.select(tag)
            mutableState.update { it.copy(language = tag) }
        } else mutableState.update { it.copy(error = desktopText("error.languageSaveFailed")) }
    }

    private fun restoredLanguage(): String? =
        (appearancePreferences.snapshot()["language"] as? SourcePreferenceValue.Text)?.value?.takeIf(String::isNotBlank)

    internal fun interfaceStyle(value: DesktopInterfaceStyle) = action {
        check(appearancePreferences.edit(SourcePreferenceEdit(changes = mapOf(
            "interface_style" to SourcePreferenceValue.Text(value.name),
        )))) { desktopText("settings.interfaceStyleSaveFailed") }
        mutableState.update { it.copy(interfaceStyle = value) }
    }

    fun checkExtensionUpdates() = action {
        val check = session.checkUpdates()
        mutableState.update { it.copy(extensionUpdates = check.updates, installedEntries = session.installedEntries(),
            message = if (check.updates.isEmpty()) desktopText("extensions.allUpToDate") else desktopText("extensions.updatesAvailable", check.updates.size),
            error = check.failures.takeIf { failures -> failures.isNotEmpty() }?.joinToString("；", desktopText("extensions.repositoryReadFailed"))) }
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
            message = desktopText("extensions.updated", done),
            error = failures.takeIf { it.isNotEmpty() }?.joinToString("\n", desktopText("extensions.updateFailed"))) }
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
                message = desktopText("extensions.uninstalled", entry.label))
        }
    }

    fun refreshSources() = action {
        mutableState.update { it.copy(sources = sourceDescriptors(), error = session.startupErrors.firstOrNull()) }
    }

    fun importJar(path: Path) = action {
        val extension = session.importJar(path)
        publishInstallation(extension.label, desktopText("extensions.imported"), DesktopScreen.EXPLORE)
    }

    fun showExtensions() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.EXTENSIONS, repositories = session.repositories.saved(),
            installedExtensions = installedVersions(), installedEntries = session.installedEntries()) }
    }

    /**
     * Android's "add JSON source": one link may be a MacCMS API, a TVBox document or a multi-repository list, so the
     * shared importer decides how many sites it holds. Spider sites and unreadable children are reported, not stored.
     */
    fun importJsonSource(address: String, title: String? = null) = action {
        val url = address.trim()
        require(url.isNotEmpty()) { desktopText("jsonSources.addressRequired") }
        mutableState.update { it.copy(screen = DesktopScreen.EXTENSIONS) }
        val result = session.importJsonSources(url, title?.trim()?.takeIf(String::isNotEmpty))
        require(result.sources.isNotEmpty()) {
            result.errors.firstOrNull() ?: desktopText("jsonSources.noneFound")
        }
        val stored = result.sources.map {
            org.skepsun.kototoro.desktop.runtime.DesktopJsonSource(it.id, it.name, it.config, it.iconUrl, it.stored.api)
        }
        require(session.jsonSources.add(stored)) { desktopText("jsonSources.saveFailed") }
        session.reloadJsonSources()
        mutableState.update {
            it.copy(sources = sourceDescriptors(), jsonSources = session.jsonSourceList(),
                message = desktopText("jsonSources.added", result.sources.size) +
                    if (result.skippedSpiderSites > 0) {
                        " · " + desktopText("jsonSources.skipped", result.skippedSpiderSites)
                    } else "",
                error = result.errors.firstOrNull())
        }
    }

    /** Drops an imported JSON source; the works it supplied stay in the library, as Android keeps them. */
    fun removeJsonSource(id: String) = action {
        require(session.jsonSources.remove(id)) { desktopText("jsonSources.saveFailed") }
        session.reloadJsonSources()
        mutableState.update {
            it.copy(sources = sourceDescriptors(), jsonSources = session.jsonSourceList(),
                message = desktopText("jsonSources.removed"))
        }
    }

    fun fetchRepository(address: String) = action {
        val catalog = session.repositories.fetch(address)
        mutableState.update { it.copy(screen = DesktopScreen.EXTENSIONS, extensionCatalog = catalog,
            repositories = session.repositories.saved(), installedExtensions = installedVersions(),
            message = desktopText("extensions.catalogRead", catalog.repository.name, catalog.extensions.size)) }
    }

    /** Drops a saved repository; its extensions stay installed, matching Android's "forget the source" behavior. */
    fun removeRepository(repository: DesktopRepository) = action {
        require(session.repositories.remove(repository.indexUrl)) { desktopText("extensions.repositoryMissing") }
        mutableState.update {
            it.copy(repositories = session.repositories.saved(),
                extensionCatalog = it.extensionCatalog?.takeIf { catalog ->
                    catalog.repository.indexUrl != repository.indexUrl
                },
                message = desktopText("extensions.repositoryRemoved", repository.name))
        }
    }

    fun installExtension(extension: ExtensionStoreIndex.Extension) = action {
        val catalog = requireNotNull(state.value.extensionCatalog) { desktopText("extensions.readRepositoryFirst") }
        publishInstallation(session.installExtension(catalog, extension).label, desktopText("extensions.installed"))
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
        require(order in state.value.descriptor?.sortOrders.orEmpty()) { desktopText("error.sortUnsupported") }
        browseOnIo(0, state.value.query, order, state.value.appliedFilters)
    }

    /** Parser sources do not all have a POPULARITY order; fall back to the source's default rather than failing. */
    private fun supportedOrder(wanted: String): String? {
        val descriptor = state.value.descriptor ?: return wanted
        return if (wanted in descriptor.sortOrders) wanted else descriptor.defaultSortOrder
    }

    fun filters() = action {
        val source = requireNotNull(state.value.selectedSource) { desktopText("extensions.selectSourceFirst") }
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
        val source = requireNotNull(state.value.selectedSource) { desktopText("extensions.selectSourceFirst") }
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

    /**
     * Android's favourites-category screen. Editing keeps the user on the grid: the snapshot is reloaded so
     * the rail, the memberships of every card and the "all" slice stay consistent with the database.
     */
    fun createCategory(title: String) = action {
        session.library.createCategory(title)
        reloadLibrarySnapshot()
        mutableState.update { it.copy(message = desktopText("library.categoryCreated")) }
    }

    fun renameCategory(id: Long, title: String) = action {
        session.library.renameCategory(id, title)
        reloadLibrarySnapshot()
    }

    fun deleteCategory(id: Long) = action {
        session.library.removeCategories(listOf(id))
        // A grid filtered by the deleted category would show nothing at all, so fall back to "all".
        mutableState.update {
            val selection = if (it.librarySelection.categoryId == id) it.librarySelection.copy(categoryId = null)
            else it.librarySelection
            it.copy(librarySelection = selection)
        }
        reloadLibrarySnapshot()
        mutableState.update { it.copy(message = desktopText("library.categoryDeleted")) }
    }

    /**
     * Android's batch category dialog: the checked set becomes the membership of every selected work, so a box that
     * is unticked removes the work from that category. [ids] are content ids from the grid.
     */
    fun setFavouriteCategories(ids: Collection<Long>, included: Set<Long>) = action {
        val contents = session.library.findMany(ids)
        val excluded = session.library.categories().mapTo(HashSet()) { it.id } - included
        session.library.updateCategories(contents, included = included, excluded = excluded)
        reloadLibrarySnapshot()
        mutableState.update { it.copy(message = desktopText("library.categoriesUpdated", contents.size)) }
    }

    /** Batch "remove from favourites" and the history equivalent; both keep the works themselves. */
    fun removeFavourites(ids: Collection<Long>) = action {
        for (id in ids) session.library.removeFavourite(id)
        reloadLibrarySnapshot()
        mutableState.update { it.copy(message = desktopText("library.favouritesRemoved", ids.size)) }
    }

    fun removeHistoryRecords(ids: Collection<Long>) = action {
        for (id in ids) session.library.removeHistory(id)
        reloadLibrarySnapshot()
        mutableState.update { it.copy(message = desktopText("library.historyRemoved", ids.size)) }
    }

    /**
     * Android's batch "mark as completed": the reading position of every selected work is moved onto its last chapter,
     * so the stored progress becomes the end of the work and any earlier position is replaced. Works that hold no
     * chapters are skipped, because there is nothing to point at.
     */
    fun markLibraryCompleted(ids: Collection<Long>) = action {
        val contents = session.library.findMany(ids, withChapters = true)
        var marked = 0
        for (content in contents) {
            val last = content.chapters.orEmpty().lastOrNull() ?: continue
            // One page of the last chapter lands the shared percent exactly at the end, as the details batch mark does.
            session.library.recordPage(content, last, page = 0, pageCount = 1)
            marked++
        }
        require(marked > 0) { desktopText("library.completeNone") }
        reloadLibrarySnapshot()
        mutableState.update { it.copy(message = desktopText("library.completed", marked)) }
    }

    /** Android's batch pin: the flag lives on the favourites rows, so it survives a reload and leads every order. */
    /**
     * Android's selection "share". Windows has no share sheet, so the same text goes to the clipboard and the
     * message reports how many links landed there; when the clipboard refuses the write the text opens in a
     * dialog instead, so a locked session never silently loses the links.
     */
    fun shareLibrarySelection(ids: Collection<Long>) = action {
        val selected = state.value.library.entries.filter { it.content.id in ids }
        val share = desktopShareText(selected.map { it.content })
        require(!share.isEmpty) { desktopText("library.shareEmpty") }
        if (DesktopClipboard.write(share.text)) {
            mutableState.update { it.copy(message = desktopText("library.shared", share.count)) }
        } else {
            mutableState.update { it.copy(shareLinks = share.text, message = desktopText("library.shareFailed")) }
        }
    }

    internal fun dismissShareLinks() {
        mutableState.update { it.copy(shareLinks = null) }
    }

    fun setLibraryPinned(ids: Collection<Long>, isPinned: Boolean) = action {
        require(ids.isNotEmpty()) { desktopText("library.pinNone") }
        session.library.setPinned(ids, isPinned)
        reloadLibrarySnapshot()
        mutableState.update {
            val message = if (isPinned) desktopText("library.pinnedCount", ids.size)
            else desktopText("library.unpinnedCount", ids.size)
            it.copy(message = message)
        }
    }

    fun moveCategory(id: Long, up: Boolean) = action {
        val ids = session.library.categories().map { it.id }
        val from = ids.indexOf(id)
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in ids.indices) return@action
        session.library.reorderCategories(ids.toMutableList().apply { add(to, removeAt(from)) })
        reloadLibrarySnapshot()
    }

    private suspend fun reloadLibrarySnapshot() {
        val snapshot = session.library.snapshot(history = state.value.screen == DesktopScreen.HISTORY)
        mutableState.update { it.copy(items = snapshot.entries.map { row -> row.content }, library = snapshot) }
    }

    fun details(content: SourceContent) = navigate { detailsOnIo(content) }

    private suspend fun detailsOnIo(content: SourceContent) {
        val previous = state.value
        val origin = if (previous.screen == DesktopScreen.DETAILS) previous.detailsOrigin
            else previous.screen.takeIf { it in DetailsOrigins }
        val details = session.sources.getDetails(content, SourceDetailsFetchMode.FORCE_REFRESH)
        session.library.save(details)
        val favourite = session.library.isFavourite(details.id)
        val progress = session.library.progress(details.id)
        val chapterId = progress?.let { recoverHistoryChapterId(false, it.chapterId, it.parentChapterId,
            it.percent, details.chapters.orEmpty().map(SourceChapter::id)) ?: it.chapterId }
        val bookmarks = session.library.bookmarks(details.id)
        val branch = resolvePreferredChapterBranch(details.chapters.orEmpty(), SourceChapter::branch,
            SourceChapter::id, chapterId, desktopChapterBranchLocales())
        mutableState.update { it.copy(screen = DesktopScreen.DETAILS, content = details,
            detailsOrigin = origin, detailsExpanded = false, isFavourite = favourite, chapterBranch = branch,
            detailsChapterId = chapterId, bookmarks = bookmarks) }
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

    /**
     * Rebuilds recommendations in the background, as Android's suggestions worker does periodically.
     *
     * Asking every installed source for candidates takes as long as the network does, so it must not go through
     * [action]: that sets [DesktopState.busy] and holds the operation gate, which left the whole window stuck on
     * "recommendations are being fetched" until the last source answered. Progress is [suggestionsGenerating], and
     * [backgroundMutex] keeps it from running beside the periodic worker.
     */
    fun refreshSuggestions() {
        if (state.value.suggestionsGenerating) return
        mutableState.update { it.copy(suggestionsGenerating = true, message = desktopText("message.generatingSuggestions")) }
        scope.launch {
            try {
                val stored = backgroundMutex.withLock { runSuggestionsOnIo() }
                mutableState.update { it.copy(message = when {
                    stored > 0 -> desktopText("message.suggestionsGenerated", stored)
                    state.value.sources.isEmpty() -> desktopText("message.installSourceFirst")
                    else -> desktopText("message.noSuggestions")
                }) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkageError) {
                mutableState.update { it.copy(error = desktopText("error.sourceApiIncompatible", error.message ?: error.javaClass.simpleName)) }
            } catch (error: Exception) {
                val diagnostic = if (error is SourceRemoteException && error.error.code == SourceErrorCode.RUNTIME_FAILURE) {
                    session.sourceDiagnostics.message(error.requestId)
                } else null
                mutableState.update { it.copy(error = diagnostic ?: error.message ?: error.javaClass.simpleName) }
            } finally {
                mutableState.update { it.copy(suggestionsGenerating = false) }
            }
        }
    }

    /** Android shows suggestions only once enabled; enabling also generates them, like the worker's first run. */
    fun enableSuggestions() {
        suggestionSettings(state.value.suggestionSettings.copy(enabled = true))
        refreshSuggestions()
    }

    internal fun suggestionSettings(settings: org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings) {
        mutableState.update { it.copy(suggestionSettings = settings) }
        if (!backgroundPreferences.edit(SourcePreferenceEdit(changes = settings.toPreferences()))) {
            mutableState.update { it.copy(error = desktopText("settings.suggestionsSaveFailed")) }
        }
    }

    internal fun trackerSettings(settings: org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings) {
        mutableState.update { it.copy(trackerSettings = settings) }
        if (!backgroundPreferences.edit(SourcePreferenceEdit(changes = settings.toPreferences()))) {
            mutableState.update { it.copy(error = desktopText("settings.trackerSaveFailed")) }
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
     * Runs Android's periodic jobs (tracker, suggestions) and the application-update check while the app is open,
     * outside the UI action queue so the window stays responsive. Each loop sleeps until its own next job is due
     * ([nextWakeDelay]) instead of polling on a fixed tick, and the update check has its own loop: a slow repository
     * or a package download must never delay the tracker, nor the other way round. Only the real app starts it;
     * probes drive the work explicitly.
     */
    fun startBackgroundWork(initialDelayMillis: Long = 60_000L) {
        scope.launch(DesktopDispatchers.background) {
            delay(initialDelayMillis)
            while (isActive) {
                runCatching { runDueBackgroundWork() }
                // The image budget rides on the same loop: it wakes at least every MAXIMUM_WAKE_MS and throttles itself.
                runCatching { enforceImageBudgetIfDue() }
                delay(nextWakeDelay(System.currentTimeMillis(), backgroundSchedules()) ?: MAXIMUM_WAKE_MS)
            }
        }
        scope.launch(DesktopDispatchers.background) {
            delay(initialDelayMillis)
            while (isActive) {
                runCatching { runAppUpdateCheck() }
                delay(nextWakeDelay(System.currentTimeMillis(), listOf(appUpdateSchedule(lastRun(
                    LAST_APP_UPDATE_CHECK), state.value.update.settings))) ?: MAXIMUM_WAKE_MS)
            }
        }
    }

    private fun backgroundSchedules(): List<DesktopBackgroundSchedule> = desktopBackgroundSchedules(
        lastTrackerRun = lastRun(LAST_TRACKER_RUN),
        lastSuggestionsRun = lastRun(LAST_SUGGESTIONS_RUN),
        tracker = state.value.trackerSettings,
        suggestions = state.value.suggestionSettings,
    )

    /**
     * Checks for an application update when its interval elapsed. The run is recorded before the check starts, so a
     * restart does not re-check immediately and a network failure waits for the next interval instead of retrying in
     * a loop; the "check now" button is the way to retry sooner.
     */
    internal fun runAppUpdateCheck(now: Long = System.currentTimeMillis()): Boolean {
        val schedule = appUpdateSchedule(lastRun(LAST_APP_UPDATE_CHECK), state.value.update.settings)
        if (DesktopBackgroundTask.APP_UPDATE !in dueTasks(now, listOf(schedule))) return false
        recordRun(LAST_APP_UPDATE_CHECK)
        updates.checkInBackground()
        return true
    }

    internal suspend fun runDueBackgroundWork(now: Long = System.currentTimeMillis()): Set<DesktopBackgroundTask> {
        val due = dueTasks(now, backgroundSchedules())
        if (DesktopBackgroundTask.TRACKER in due) {
            val report = backgroundMutex.withLock { runTrackerOnIo() }
            val feed = session.feed.observe(session.storage.database).first()
            val contents = feedContentsOnIo(feed)
            mutableState.update { it.copy(feed = feed, feedContents = contents,
                message = if (report.withUpdates > 0) {
                desktopText("message.backgroundCheck", report.withUpdates, report.newChapters)
            } else it.message) }
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
            runTrackerOnIo { done, total -> mutableState.update { it.copy(message = desktopText("message.checkingUpdates", done, total)) } }
        }
        loadFeedOnIo()
        mutableState.update { it.copy(message = when {
            report.checked == 0 -> desktopText("message.nothingToCheck")
            report.withUpdates == 0 -> if (report.failed > 0) {
                desktopText("message.checkedNoNewFailed", report.checked, report.failed)
            } else desktopText("message.checkedNoNew", report.checked)
            else -> if (report.failed > 0) {
                desktopText("message.checkedNewFailed", report.withUpdates, report.newChapters, report.failed)
            } else desktopText("message.checkedNew", report.withUpdates, report.newChapters)
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
        val content = requireNotNull(session.library.find(mangaId)) { desktopText("error.workNotFound") }
        detailsOnIo(content)
        loadFeedOnIo()
    }

    /** Android's "continue reading" on a feed entry: mark it read and resume from history. */
    fun continueTracked(mangaId: Long) = navigate {
        session.tracker.markRead(mangaId)
        val content = requireNotNull(session.library.find(mangaId)) { desktopText("error.workNotFound") }
        detailsOnIo(content)
        startReading(DesktopReadingRequest.Chapter(null))
    }

    /** Android's "random" quick action: open a random favourite. */
    fun randomFavourite() = navigate {
        val favourite = session.library.favourites().randomOrNull() ?: error(desktopText("error.favouritesEmpty"))
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
        mutableState.update { it.copy(isFavourite = true, message = desktopText("message.addedToFavourites")) }
    }

    /** Android's details heart toggles, so a work can leave the shelf from the same button that added it. */
    fun toggleFavourite() = action {
        val content = requireNotNull(state.value.content) { desktopText("error.workNotFound") }
        if (state.value.isFavourite) {
            session.library.removeFavourite(content.id)
            mutableState.update {
                it.copy(isFavourite = false, message = desktopText("message.removedFromFavourites"))
            }
        } else {
            session.library.addFavourite(content)
            mutableState.update { it.copy(isFavourite = true, message = desktopText("message.addedToFavourites")) }
        }
    }

    fun read(chapter: SourceChapter? = null) = navigate { startReading(DesktopReadingRequest.Chapter(chapter)) }

    /** Android's per-session "无痕模式" action: this session records nothing, whatever the stored choice says. */
    fun readIncognito(chapter: SourceChapter? = null) =
        navigate { startReading(DesktopReadingRequest.Chapter(chapter), incognito = true) }

    /**
     * Answers the adult-work question. Android stores "don't ask again" as the same tri-state the settings page edits,
     * so the two entry points cannot disagree; 取消 leaves everything as it was and stays on the details page.
     */
    fun resolveIncognitoPrompt(incognito: Boolean) {
        val prompt = mutableState.value.incognitoPrompt ?: return
        mutableState.update { it.copy(incognitoPrompt = null) }
        if (prompt.dontAskAgain) {
            setIncognitoForNsfw(
                if (incognito) DesktopTriState.ENABLED else DesktopTriState.DISABLED,
            )
        }
        navigate { startReading(prompt.request, incognito) }
    }

    fun dismissIncognitoPrompt() = mutableState.update { it.copy(incognitoPrompt = null) }

    fun setIncognitoPromptDontAskAgain(enabled: Boolean) = mutableState.update {
        it.copy(incognitoPrompt = it.incognitoPrompt?.copy(dontAskAgain = enabled))
    }

    /**
     * Every top-level reader entry: Android resolves incognito inside the reader, Windows raises the same question
     * before opening it. [incognito] forces the answer for one session; null means the stored choice decides.
     */
    private suspend fun startReading(request: DesktopReadingRequest, incognito: Boolean? = null) {
        val content = requireNotNull(state.value.content) { desktopText("error.workNotFound") }
        val settings = state.value.sourceSettings
        val mode = incognito ?: when {
            settings.requiresIncognito(content) -> true
            settings.asksForIncognito(content) -> {
                mutableState.update { it.copy(incognitoPrompt = DesktopIncognitoPrompt(request)) }
                return
            }

            else -> false
        }
        // Set before opening: the reader's own opening save must already see the incognito session.
        mutableState.update {
            it.copy(readerIncognito = mode,
                message = if (mode) desktopText("reader.incognito") else it.message)
        }
        when (request) {
            is DesktopReadingRequest.Chapter -> when (DesktopReaderKind.of(content)) {
                DesktopReaderKind.NOVEL -> readNovelOnIo(request.chapter)
                DesktopReaderKind.VIDEO -> watchOnIo(request.chapter)
                DesktopReaderKind.PAGES -> readOnIo(request.chapter)
            }

            is DesktopReadingRequest.Bookmark -> when (DesktopReaderKind.of(content)) {
                DesktopReaderKind.NOVEL -> readNovelOnIo(request.chapter, request.bookmark)
                DesktopReaderKind.VIDEO -> watchOnIo(request.chapter)
                DesktopReaderKind.PAGES -> readOnIo(request.chapter, bookmark = request.bookmark)
            }

            is DesktopReadingRequest.Download -> readOnIo(request.chapter)
        }
    }

    private suspend fun readNovelOnIo(chapter: SourceChapter? = null, bookmark: DesktopBookmark? = null) {
        val content = requireNotNull(state.value.content)
        val chapters = requireNotNull(content.chapters) { desktopText("error.noChapters") }
        val progress = session.library.progress(content.id)
        val restoredId = progress?.let { recoverHistoryChapterId(false, it.chapterId, it.parentChapterId,
            it.percent, chapters.map { c -> c.id }) ?: it.chapterId }
        val selected = chapter ?: chapters.firstOrNull { it.id == restoredId } ?: firstChapterOfBranch(chapters)
        val next = SourceChapterNavigation.adjacent(chapters, selected.id, true)?.url
        val body = session.sources.getChapterContent(selected, next) ?: error(desktopText("error.noChapterText"))
        val blocks = DesktopNovelHtml.parse(body.html, content.publicUrl.takeIf { it.startsWith("http") }.orEmpty())
        require(blocks.isNotEmpty()) { desktopText("error.noChapterText") }
        val start = if (bookmark != null) {
            require(bookmark.contentId == content.id && bookmark.chapterId == selected.id)
            novelBookmarkIndex(content, selected, blocks, bookmark)
        } else if (chapter == null && selected.id == restoredId) (progress?.page ?: 0).coerceIn(blocks.indices) else 0
        val bookmarks = session.library.bookmarks(content.id)
        novelOperations.forget()
        beginReadingStop()
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
        val chapters = requireNotNull(content.chapters) { desktopText("error.noEpisodes") }
        val progress = session.library.progress(content.id)
        val selected = chapter ?: chapters.firstOrNull { it.id == progress?.chapterId } ?: firstChapterOfBranch(chapters)
        val next = SourceChapterNavigation.adjacent(chapters, selected.id, true)?.url
        val streams = session.sources.getPages(selected, next).filter { it.url.isNotBlank() }
        require(streams.isNotEmpty()) { desktopText("error.noVideo") }
        val start = if (progress != null && progress.chapterId == selected.id) progress.page.toDouble() else 0.0
        videoOperations.forget()
        beginReadingStop()
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
        if (!saved) mutableState.update { it.copy(error = desktopText("error.enhancementInMemory")) }
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
        val video = requireNotNull(snapshot.video) { desktopText("error.videoNotPlaying") }
        snapshot.cast?.let { previous -> mutableState.update { it.copy(cast = null) }; withContext(Dispatchers.IO) { previous.close() } }
        val title = listOfNotNull(snapshot.content?.title, video.chapter.title).joinToString(" · ")
        val cast = withContext(Dispatchers.IO) { DesktopCast.start(device, video.stream, title, video.position) }
        mutableState.update { it.copy(cast = cast, message = desktopText("message.castingTo", device.name)) }
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
        require(session.library.bookmarks(bookmark.contentId).contains(bookmark)) { desktopText("error.bookmarkMissing") }
        val chapter = snapshot.content.chapters?.firstOrNull { it.id == bookmark.chapterId }
            ?: error(desktopText("error.bookmarkChapterMissing"))
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
            desktopText("error.bookmarkChanged")
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
            "paged" to SourcePreferenceValue.Toggle(settings.paged),
        )))
        if (!saved) mutableState.update { it.copy(error = desktopText("error.readerSettingsInMemory")) }
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
            paged = (snapshot["paged"] as? SourcePreferenceValue.Toggle)?.value ?: defaults.paged,
        )
    }

    /** Inline images of the open chapter go through the same source client, cache and challenge handling as pages. */
    internal suspend fun novelImage(url: String): DesktopReaderImage {
        val novel = requireNotNull(state.value.novel) { desktopText("error.novelNotOpen") }
        return session.novelImage(novel.chapter, novel.images[url] ?: SourceContentImage(url))
    }

    fun downloadChapter(chapter: SourceChapter) = action {
        val content = requireNotNull(state.value.content)
        require(content.chapters.orEmpty().any { it == chapter }) { desktopText("error.chapterNotOfWork") }
        downloads.enqueue(content.id, chapter, content.title)
    }

    /**
     * The whole-work scopes go through one action so a menu click cannot interleave with navigation,
     * and ownership is checked once instead of per chapter. Chapters that are already active or
     * complete are filtered out by [desktopDownloadTargets] before this runs.
     */
    fun downloadChapters(chapters: List<SourceChapter>) = action {
        val content = requireNotNull(state.value.content)
        val owned = content.chapters.orEmpty()
        val targets = chapters.filter { candidate -> owned.any { it == candidate } }
        require(targets.isNotEmpty()) { desktopText("downloads.nothingToQueue") }
        targets.forEach { downloads.enqueue(content.id, it, content.title) }
        mutableState.update { it.copy(message = desktopText("downloads.batchQueued", targets.size)) }
    }

    /**
     * Android's batch SAVE on the shelves: the details menu's chapter scopes applied to every selected work with
     * its own progress and its own saved chapters. The chapter lists come from the stored rows instead of a fresh
     * fetch, so the batch also works offline and cannot fail halfway through a source outage; works that hold no
     * chapters (or hold them for another reader kind) simply drop out, and an empty batch is reported as such.
     */
    internal fun downloadLibrarySelection(ids: Collection<Long>, option: DesktopDownloadOption) = action {
        val records = session.downloadStore.records.value
        val works = session.library.findMany(ids, withChapters = true)
            .filter { DesktopReaderKind.of(it) == DesktopReaderKind.PAGES }
            .map { content ->
                DesktopShelfDownloadWork(
                    contentId = content.id,
                    title = content.title,
                    chapters = content.chapters.orEmpty(),
                    currentChapterId = session.library.progress(content.id)?.chapterId,
                    downloaded = records.filter { it.contentId == content.id && it.isComplete }
                        .map { it.chapter },
                )
            }
        val queues = desktopShelfDownloadTargets(works, option)
        require(queues.isNotEmpty()) { desktopText("downloads.nothingToQueue") }
        var queued = 0
        for (queue in queues) {
            queue.chapters.forEach { chapter ->
                downloads.enqueue(queue.work.contentId, chapter, queue.work.title)
            }
            queued += queue.chapters.size
        }
        mutableState.update { it.copy(message = desktopText("downloads.batchQueued", queued)) }
    }

    /**
     * Android's MIGRATE on the favourites shelf, for one work at a time: the dialog searches the title on another
     * source, and only the picked candidate is refetched (with its chapters) and handed to the shared migration plan
     * together with the work it replaces. Works are migrated singly because every candidate has to be recognised by
     * eye, so batching them here would guess where Android shows a per-work list.
     */
    internal fun beginMigration(id: Long) = action {
        val content = requireNotNull(session.library.find(id)) { desktopText("error.workNotFound") }
        val targets = desktopMigrationSources(state.value.sources, content)
        require(targets.isNotEmpty()) { desktopText("library.migrateNoSources") }
        mutableState.update {
            it.copy(migration = DesktopMigrationState(contentId = content.id, title = content.title,
                sources = targets, sourceName = targets.first().source.name, query = content.title))
        }
    }

    fun closeMigration() {
        mutableState.update { it.copy(migration = null) }
    }

    fun migrationSource(name: String) {
        mutableState.update { current ->
            current.migration?.let { migration ->
                current.copy(migration = migration.copy(sourceName = name, candidates = emptyList(), searched = false))
            } ?: current
        }
    }

    fun migrationQuery(query: String) {
        mutableState.update { current ->
            current.migration?.let { current.copy(migration = it.copy(query = query)) } ?: current
        }
    }

    fun migrationKeepOriginal(keep: Boolean) {
        mutableState.update { current ->
            current.migration?.let { current.copy(migration = it.copy(keepOriginal = keep)) } ?: current
        }
    }

    /** Android's candidate search: the query goes to the chosen source, and only matching works become candidates. */
    fun searchMigration() = action {
        val migration = requireNotNull(state.value.migration) { desktopText("error.workNotFound") }
        val target = requireNotNull(migration.sourceName) { desktopText("library.migrateNoSources") }
        val content = requireNotNull(session.library.find(migration.contentId)) { desktopText("error.workNotFound") }
        mutableState.update { it.copy(migration = migration.copy(searching = true, searched = false)) }
        val items = session.sources.getList(target, 0, null,
            SourceFilter(query = migration.query.takeIf { it.isNotBlank() }))
        val candidates = desktopMigrationCandidates(items, target, content)
        mutableState.update { current ->
            current.copy(migration = current.migration?.copy(searching = false, searched = true,
                candidates = candidates))
        }
    }

    /** The shared migration plan, committed with the target's refreshed details; no download or bookmark moves. */
    fun migrateContent(target: SourceContent) = action {
        val migration = requireNotNull(state.value.migration) { desktopText("error.workNotFound") }
        val content = requireNotNull(session.library.find(migration.contentId)) { desktopText("error.workNotFound") }
        val details = session.sources.getDetails(target, SourceDetailsFetchMode.FORCE_REFRESH)
        session.library.migrate(content, details, keepOriginal = migration.keepOriginal)
        val label = migration.sourceLabel.ifEmpty { target.source.name }
        mutableState.update { it.copy(migration = null, message = desktopText("library.migrated", label)) }
        reloadLibrarySnapshot()
    }

    /** Android's chapter long-press "read": history moves onto the chapter, leaving later chapters unread. */
    fun markChapterRead(chapter: SourceChapter) = markChapterProgress(chapter, read = true)

    /** The "unread" counterpart; the first chapter of a branch clears the record instead. */
    fun markChapterUnread(chapter: SourceChapter) = markChapterProgress(chapter, read = false)

    private fun markChapterProgress(chapter: SourceChapter, read: Boolean) = action {
        val content = requireNotNull(state.value.content) { desktopText("error.workNotFound") }
        val target = if (read) {
            requireNotNull(desktopMarkReadTarget(content, chapter)) { desktopText("details.chapterMissing") }
        } else {
            when (val mark = desktopUnreadMark(content, chapter)) {
                is DesktopUnreadMark.Move -> mark.chapter
                DesktopUnreadMark.Missing -> error(desktopText("details.chapterMissing"))
                DesktopUnreadMark.Clear -> null
            }
        }
        if (target == null) {
            session.library.removeHistory(content.id)
        } else {
            // One page of one chapter: percent lands at that chapter's end, as Android's mark-as-read does.
            session.library.recordPage(content, target, page = 0, pageCount = 1)
        }
        val progress = session.library.progress(content.id)
        val message = if (read) desktopText("details.markedRead", desktopChapterTitle(chapter))
            else desktopText("details.markedUnread", desktopChapterTitle(chapter))
        mutableState.update { it.copy(detailsChapterId = progress?.chapterId, message = message) }
    }

    /** Batch "mark as read": history lands on the last chapter of the given reading order, like Android's batch action. */
    fun markChaptersRead(chapters: List<SourceChapter>) = action {
        val content = requireNotNull(state.value.content) { desktopText("error.workNotFound") }
        require(chapters.isNotEmpty()) { desktopText("details.chapterMissing") }
        val targets = chapters.mapNotNull { desktopMarkReadTarget(content, it) }
        require(targets.isNotEmpty()) { desktopText("details.chapterMissing") }
        session.library.recordPage(content, targets.last(), page = 0, pageCount = 1)
        val progress = session.library.progress(content.id)
        mutableState.update {
            it.copy(detailsChapterId = progress?.chapterId, message = desktopText("details.markedReadCount", targets.size))
        }
    }

    /** Drops every selected chapter's completed download, leaving shared image blocks to the reclaim view. */
    fun deleteChapterDownloads(chapters: List<SourceChapter>) = action {
        val content = requireNotNull(state.value.content) { desktopText("error.workNotFound") }
        val keys = chapters.mapTo(HashSet()) { DesktopDownloadStore.key(content.id, it) }
        val rows = downloads.state.value.filter { it.key in keys }
        require(rows.isNotEmpty()) { desktopText("downloads.chapterMissing") }
        var removed = 0
        for (row in rows) {
            downloads.pause(row.key)
            downloads.settle(row.key)
            if (session.downloadStore.delete(content.id, row.record.chapter, session.storage.preferences)) {
                removed++
            }
        }
        downloads.forget(rows.map { it.key })
        mutableDownloadSelection.update { DesktopDownloadSelection.retain(it, downloads.state.value.map { state -> state.key }) }
        mutableState.update { it.copy(message = desktopText("downloads.batchRemoved", removed)) }
    }

    /** Drops one chapter's completed download; the index entry and its manifest go, shared image files stay. */
    fun deleteChapterDownload(chapter: SourceChapter) = action {
        val content = requireNotNull(state.value.content) { desktopText("error.workNotFound") }
        val key = DesktopDownloadStore.key(content.id, chapter)
        val row = downloads.state.value.firstOrNull { it.key == key }
        requireNotNull(row) { desktopText("downloads.chapterMissing") }
        downloads.pause(key)
        downloads.settle(key)
        val removed = session.downloadStore.delete(content.id, chapter, session.storage.preferences)
        downloads.forget(listOf(key))
        mutableDownloadSelection.update { DesktopDownloadSelection.retain(it, downloads.state.value.map { state -> state.key }) }
        val message = if (removed) desktopText("downloads.chapterRemoved", desktopChapterTitle(chapter))
            else desktopText("downloads.chapterMissing")
        mutableState.update { it.copy(message = message) }
    }

    /** Exports one completed chapter as a CBZ so it can leave the app; the stored chapter stays readable. */
    internal fun exportDownload(record: SourceChapterDownload, directory: Path) = action {
        val file = session.downloadExporter.export(record, directory)
        mutableState.update { it.copy(message = desktopText("downloads.exported", file.fileName)) }
    }

    /**
     * Android puts every downloaded chapter where the user can reach it; on the desktop that is one CBZ per chapter
     * in the chosen folder. Incomplete chapters are skipped, and a damaged one does not abort the rest of the batch.
     */
    internal fun exportAllDownloads(directory: Path) = action {
        val result = session.downloadExporter.exportAll(downloads.state.value.map { it.record }, directory)
        mutableState.update { it.copy(message = if (result.failed > 0)
            desktopText("downloads.exportAllPartial", result.written, result.failed)
        else desktopText("downloads.exportAllDone", result.written)) }
    }

    fun showDownloads() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.DOWNLOADS) }
        loadImageCacheOnIo()
    }

    /** The budget the user picked; zero means unlimited, which is Android's "no limit" choice. */
    internal fun imageBudgetMb(): Int =
        (storagePreferences.snapshot()[IMAGE_BUDGET] as? SourcePreferenceValue.Integer)?.value
            ?: DesktopImageBudget.DEFAULT_MB

    internal suspend fun loadImageCacheOnIo() {
        val report = runCatching { session.imageCacheReport(imageBudgetMb()) }.getOrNull() ?: return
        mutableState.update { it.copy(imageCache = report) }
    }

    /** Re-reads the figures when the queue settles: finishing a chapter publishes blobs the page has not counted yet. */
    fun refreshImageCache() = action { loadImageCacheOnIo() }

    /**
     * Applies a new total image budget now instead of waiting for the next sweep: the user just changed the number and
     * expects the directory to match it, and the report the page shows comes from the same run.
     */
    fun setImageBudget(megabytes: Int) = action {
        require(megabytes == DesktopImageBudget.UNLIMITED_MB || megabytes in DesktopImageBudget.choicesMb)
        storagePreferences.edit(SourcePreferenceEdit(changes = mapOf(
            IMAGE_BUDGET to SourcePreferenceValue.Integer(megabytes))))
        enforceImageBudgetOnIo()
    }

    /** Frees every unprotected blob; saved chapters keep theirs, so offline reading still works afterwards. */
    fun clearImageCache() = action {
        val report = session.enforceImageBudget(imageBudgetMb(), clear = true)
        mutableState.update { it.copy(imageCache = report, message =
            if (report.deletedFiles > 0) desktopText("downloads.imageCacheCleared", report.deletedFiles,
                desktopCacheSize(report.deletedBytes))
            else desktopText("downloads.imageCacheClearKept")) }
    }

    private suspend fun enforceImageBudgetOnIo() {
        lastImageSweepAt = System.currentTimeMillis()
        val report = runCatching { session.enforceImageBudget(imageBudgetMb()) }.getOrNull() ?: return
        mutableState.update { it.copy(imageCache = report) }
    }

    /**
     * The budget is enforced by the periodic loop, not per page: a sweep lists the whole directory, so running it after
     * every image would cost more than the images do. [DesktopImageBudget.GRACE_MILLIS] keeps a fresh page safe when a
     * reading session creates blobs faster than the interval.
     */
    internal suspend fun enforceImageBudgetIfDue(now: Long = System.currentTimeMillis()): Boolean {
        if (now - lastImageSweepAt < IMAGE_SWEEP_INTERVAL_MILLIS) return false
        lastImageSweepAt = now
        return runCatching { session.enforceImageBudget(imageBudgetMb()) }.getOrNull()?.let { report ->
            mutableState.update { it.copy(imageCache = report) }
            report.deletedFiles > 0
        } ?: false
    }

    fun showBackups() = navigate { mutableState.update { it.copy(screen = DesktopScreen.BACKUPS) } }

    fun showRecommendations() = navigate { mutableState.update { it.copy(screen = DesktopScreen.RECOMMENDATIONS) } }

    /** Android's "阅读统计" screen: the shared derivation over this library's sessions and stored works. */
    fun showReadingStats() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.READING_STATS, contentStats = null) }
        loadReadingStats()
    }

    /** Android's period menu on the statistics screen. */
    fun readingStatsPeriod(period: ReadingStatsPeriod) = navigate {
        mutableState.update { it.copy(readingStatsPeriod = period) }
        loadReadingStats()
    }

    /** Android's kind selector next to the period menu: all works, or only manga, novels or videos. */
    fun readingStatsKind(kind: StatsContentKind) = navigate {
        mutableState.update { it.copy(readingStatsKind = kind) }
        loadReadingStats()
    }

    /**
     * Android's `showStatisticSheet`: picking a work in the statistics list opens its own statistics, not its details.
     * The sheet opens at once with empty numbers and fills in when the snapshot arrives, the way Android's sheet does.
     */
    fun showContentStats(content: SourceContent) {
        mutableState.update { it.copy(contentStats = DesktopContentStats(content, LibraryContentStats())) }
        scope.launch {
            val stats = try {
                session.library.contentReadingStats(content.id, TimeZone.currentSystemDefault())
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutableState.update { it.copy(contentStats = null, error = error.message ?: error.toString()) }
                return@launch
            }
            mutableState.update { state ->
                val open = state.contentStats
                if (open?.content?.id != content.id) state else state.copy(contentStats = open.copy(stats = stats))
            }
        }
    }

    /** Android's sheet closing, or its "details" button: the sheet never outlives the statistics screen. */
    fun closeContentStats() = mutableState.update { it.copy(contentStats = null) }

    /** Android's "clear statistics" action: both tables are emptied, reading history stays. */
    internal fun clearReadingStats() = action {
        session.library.clearReadingStatistics()
        loadReadingStats()
        mutableState.update { it.copy(message = AndroidStrings["stats_cleared"]) }
    }

    private suspend fun loadReadingStats() {
        val (period, kind) = mutableState.value.let { it.readingStatsPeriod to it.readingStatsKind }
        val stats = session.library.readingStatistics(period, TimeZone.currentSystemDefault(), kind = kind)
        mutableState.update { it.copy(readingStats = stats) }
    }

    /** Android's reader settings screen; it edits the same preferences an open chapter reads. */
    fun showReaderSettings() = navigate { mutableState.update { it.copy(screen = DesktopScreen.READER_SETTINGS) } }

    /** Android's source settings screen: rail order, layout and the adult-content targets. */
    fun showSourceSettings() = navigate { mutableState.update { it.copy(screen = DesktopScreen.SOURCE_SETTINGS) } }

    /** Android's proxy page, reached from "more" the way Android reaches it from storage and network. */
    fun showProxySettings() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.PROXY_SETTINGS, proxyTest = DesktopProxyTestState()) }
    }

    /**
     * Saves one change of the proxy page. The value goes to the same store the SDK client's selector reads, and the
     * connection pool is dropped so the next request really uses the new route.
     */
    internal fun updateProxySettings(value: org.skepsun.kototoro.desktop.runtime.DesktopProxySettings) {
        val persisted = session.proxy.save(value)
        mutableState.update {
            it.copy(proxySettings = value,
                proxyTest = DesktopProxyTestState(),
                message = if (persisted) it.message else desktopText("jsonSources.saveFailed"))
        }
        session.resetHttpConnections()
    }

    /** Android's "test connection" row: the shared client asks the target through whatever the page saved. */
    fun testProxyConnection() {
        if (mutableState.value.proxyTest.running) return
        mutableState.update { it.copy(proxyTest = DesktopProxyTestState(running = true)) }
        scope.launch {
            val result = runCatching { session.testProxyConnection() }
            mutableState.update { state ->
                // A page that was left meanwhile keeps no stale result.
                if (state.screen != DesktopScreen.PROXY_SETTINGS) state
                else state.copy(proxyTest = DesktopProxyTestState(
                    running = false,
                    message = result.fold(
                        onSuccess = { AndroidStrings["connection_ok"] },
                        onFailure = { it.message ?: it::class.simpleName.orEmpty() },
                    ),
                    success = result.isSuccess,
                ))
            }
        }
    }

    internal fun closeProxyTest() = mutableState.update { it.copy(proxyTest = DesktopProxyTestState()) }

    /** Android's global tag blacklist, reached from the source settings page like the Android preference. */
    fun showTagBlacklist() = navigate { mutableState.update { it.copy(screen = DesktopScreen.TAG_BLACKLIST) } }

    /** Android's "clear" action on the blacklist screen: the stored selection becomes empty, matching nothing. */
    internal fun clearTagBlacklist() = updateTagBlacklist(emptySet())

    internal fun toggleTagBlacklist(key: String) {
        val current = mutableState.value.sourceSettings.tagBlacklist
        updateTagBlacklist(if (key in current) current - key else current + key)
    }

    private fun updateTagBlacklist(selection: Set<String>) =
        sourceSettings(mutableState.value.sourceSettings.copy(tagBlacklist = selection))

    /** Android's `tags_warnings` switch: the warn-list stays, only the tint on details chips follows it. */
    internal fun setTagsWarnings(enabled: Boolean) =
        sourceSettings(mutableState.value.sourceSettings.copy(tagsWarnings = enabled))

    /** Android's `incognito_nsfw` choice, picked in the adult section or answered by "don't ask again". */
    internal fun setIncognitoForNsfw(value: org.skepsun.kototoro.desktop.runtime.DesktopTriState) =
        sourceSettings(mutableState.value.sourceSettings.copy(incognitoForNsfw = value))

    internal fun sourceSettings(settings: org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings) {
        mutableState.update { it.copy(sourceSettings = settings) }
        if (!backgroundPreferences.edit(SourcePreferenceEdit(changes = settings.toPreferences()))) {
            mutableState.update { it.copy(error = desktopText("settings.sourcesSaveFailed")) }
        }
    }
    /** Android's display options: the shelves render either the poster grid or one work per row. */
    internal fun libraryDisplay(display: org.skepsun.kototoro.desktop.runtime.DesktopLibraryDisplay) {
        mutableState.update { it.copy(libraryDisplay = display) }
        if (!libraryPreferences.edit(SourcePreferenceEdit(changes = display.toPreferences()))) {
            mutableState.update { it.copy(error = desktopText("library.displaySaveFailed")) }
        }
    }

    internal fun exportLibraryBackup(path: Path) = action {
        backups.export(path)
        mutableState.update { it.copy(message = desktopText("backups.saved", path.fileName)) }
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
        require(mutableBackupPreview.value === preview) { desktopText("backups.expired") }
        mutableBackupPreview.value = null
        val processed = preview.use { backups.restore(it) }
        mutableState.update { it.copy(items = emptyList(), content = null,
            message = desktopText("backups.merged", processed)) }
    }

    internal fun webDavSettings(value: DesktopWebDavSettings) {
        webDav.settings(value)
        // The interval lives in the settings, so a changed value must take effect without a restart.
        startAutoBackup()
    }

    /**
     * The timer Android's periodic worker replaces: sleep until the saved interval has passed, upload once, repeat.
     * A disabled timer simply ends the loop; saving settings starts a new one.
     */
    private fun startAutoBackup() {
        autoBackupJob?.cancel()
        autoBackupJob = scope.launch {
            while (isActive) {
                val delayMillis = webDav.autoBackupDelay() ?: return@launch
                if (delayMillis > 0L) {
                    delay(delayMillis)
                    continue
                }
                if (webDav.autoBackup() == DesktopAutoBackup.Failed) {
                    // A dead server must not turn the timer into a retry loop against it.
                    delay(AUTO_BACKUP_RETRY_MILLIS)
                }
            }
        }
    }

    internal fun webDavProbe() = action {
        webDav.probe()
        mutableState.update { it.copy(message = desktopText("webdav.reachable")) }
    }

    internal fun webDavRefresh() = action { webDav.refresh() }

    internal fun webDavUpload() = action {
        val result = webDav.upload()
        val message = if (result.removed.isEmpty()) {
            desktopText("webdav.uploaded", result.name, result.size / 1024)
        } else {
            desktopText("webdav.uploadedTrimmed", result.name, result.size / 1024, result.removed.size)
        }
        mutableState.update { it.copy(message = message) }
        // A manual upload moves the timer's baseline, so a sleeping loop must be recomputed against it.
        startAutoBackup()
    }

    /** The remote file is downloaded, then previewed through the same archive/merge flow a local file uses. */
    internal fun webDavRestore(file: WebDavBackupFile) = action {
        val path = webDav.download(file)
        val preview = try {
            backups.preview(path)
        } finally {
            Files.deleteIfExists(path)
        }
        val previous = mutableBackupPreview.value
        mutableBackupPreview.value = preview
        previous?.close()
        mutableState.update {
            it.copy(screen = DesktopScreen.BACKUPS, message = desktopText("webdav.preview", file.name))
        }
    }

    internal fun webDavDelete(file: WebDavBackupFile) = action {
        webDav.delete(file)
        mutableState.update { it.copy(message = desktopText("webdav.deleted", file.name)) }
    }

    internal fun previewDownloadCleanup() = action {
        val context = currentCoroutineContext()
        mutableCleanup.value = session.downloadStore.previewCleanup(session.storage.preferences) { context.ensureActive() }
    }

    internal fun dismissDownloadCleanup() { mutableCleanup.value = null }

    internal fun cleanupDownloads(plan: DesktopDownloadCleanupPlan) = action {
        require(mutableCleanup.value === plan) { desktopText("error.reclaimExpired") }
        mutableCleanup.value = null
        val context = currentCoroutineContext()
        val result = session.downloadStore.cleanup(plan, session.storage.preferences) { context.ensureActive() }
        mutableState.update { it.copy(message = if (result.skipped == 0) {
            desktopText("downloads.cleanupDone", result.removed, result.bytes / 1024)
        } else desktopText("downloads.cleanupDoneSkipped", result.removed, result.bytes / 1024, result.skipped)) }
    }

    internal fun resumeDownload(download: SourceChapterDownload) = action {
        downloads.enqueue(download.contentId, download.chapter)
    }

    internal fun toggleDownloadSelection(key: String) {
        mutableDownloadSelection.update { DesktopDownloadSelection.toggle(it, key) }
    }

    internal fun selectAllDownloads() {
        mutableDownloadSelection.value = DesktopDownloadSelection.all(downloads.state.value.map { it.key })
    }

    internal fun clearDownloadSelection() { mutableDownloadSelection.value = emptySet() }

    /** Batch pause: every waiting or running chapter stops, finished ones are left alone. */
    internal fun pauseSelectedDownloads() = action {
        val rows = selectedDownloads(DesktopDownloadBatch::pausable)
        rows.forEach { downloads.pause(it.key) }
        mutableState.update { it.copy(message = desktopText("downloads.batchPaused", rows.size)) }
    }

    /** Batch continue and batch retry differ only in which rows they take, so the queue logic stays in one place. */
    internal fun resumeSelectedDownloads() = action {
        val rows = selectedDownloads(DesktopDownloadBatch::resumable)
        rows.forEach { downloads.enqueue(it.record.contentId, it.record.chapter, it.record.contentTitle) }
        mutableState.update { it.copy(message = desktopText("downloads.batchQueued", rows.size)) }
    }

    internal fun retrySelectedDownloads() = action {
        val rows = selectedDownloads(DesktopDownloadBatch::retryable)
        rows.forEach { downloads.enqueue(it.record.contentId, it.record.chapter, it.record.contentTitle) }
        mutableState.update { it.copy(message = desktopText("downloads.batchQueued", rows.size)) }
    }

    /**
     * Batch remove forgets the saved chapters, not the images: the blocks they referenced become unreferenced and the
     * existing cleanup pass offers them back, so a removal cannot silently leave files nobody can find again.
     */
    internal fun removeSelectedDownloads() = action {
        val rows = selectedDownloads { states ->
            DesktopDownloadBatch.removable(states, mutableDownloadSelection.value)
        }
        var removed = 0
        for (row in rows) {
            downloads.pause(row.key)
            downloads.settle(row.key)
            if (session.downloadStore.delete(row.record.contentId, row.record.chapter, session.storage.preferences)) {
                removed++
            }
        }
        downloads.forget(rows.map { it.key })
        mutableDownloadSelection.update { DesktopDownloadSelection.retain(it, downloads.state.value.map { row -> row.key }) }
        mutableState.update { it.copy(message = desktopText("downloads.batchRemoved", removed)) }
    }

    private fun selectedDownloads(pick: (List<DesktopDownloadState>) -> List<DesktopDownloadState>) =
        pick(downloads.state.value.filter { it.key in mutableDownloadSelection.value })

    internal fun readDownload(download: SourceChapterDownload) = action {
        val content = requireNotNull(session.library.find(download.contentId)) { desktopText("error.localWorkMissing") }
        require(session.downloadStore.find(download.contentId, download.chapter)?.isComplete == true) { desktopText("error.downloadIncomplete") }
        mutableState.update { it.copy(content = content) }
        startReading(DesktopReadingRequest.Download(download.chapter))
    }

    private suspend fun readOnIo(chapter: SourceChapter? = null, startAtEnd: Boolean = false,
        bookmark: DesktopBookmark? = null) {
        val content = requireNotNull(state.value.content)
        val chapters = requireNotNull(content.chapters) { desktopText("error.noChapters") }
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
        require(pages.isNotEmpty()) { desktopText("error.noPages") }
        val bookmarkedIndex = bookmark?.let { saved ->
            require(saved.contentId == content.id && saved.chapterId == selected.id)
            pages.indexOfFirst { it.id == saved.pageId }.takeIf { it >= 0 }
                ?: saved.page.takeIf { it in pages.indices }
                ?: error(desktopText("error.bookmarkPageMissing"))
        }
        val index = bookmarkedIndex ?: if (startAtEnd) pages.lastIndex else if (chapter == null && selected.id == restoredId) {
            progress.page.coerceIn(pages.indices)
        } else 0
        val restoredScroll = bookmark?.scroll?.coerceAtLeast(0)?.toFloat() ?: if (chapter == null && selected.id == restoredId)
            progress.scroll.takeIf { it.isFinite() && it >= 0f } ?: 0f else 0f
        // Before the first page is recorded, so even the opening write belongs to the stop.
        beginReadingStop()
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
        require(snapshot.bookmarks.contains(bookmark)) { desktopText("error.bookmarkMissing") }
        val chapter = snapshot.content.chapters?.firstOrNull { it.id == bookmark.chapterId }
            ?: error(desktopText("error.bookmarkChapterMissing"))
        readOnIo(chapter, bookmark = bookmark)
    }

    /** Android's bookmark list also opens from the details tabs, before the reader was ever opened. */
    fun openDetailsBookmark(bookmark: DesktopBookmark) = navigate {
        val content = requireNotNull(state.value.content)
        require(content.id == bookmark.contentId) { desktopText("error.bookmarkMissing") }
        val chapter = content.chapters?.firstOrNull { it.id == bookmark.chapterId }
            ?: error(desktopText("error.bookmarkChapterMissing"))
        startReading(DesktopReadingRequest.Bookmark(chapter, bookmark))
    }

    fun removeBookmark(bookmark: DesktopBookmark) = action {
        val content = requireNotNull(state.value.content)
        require(content.id == bookmark.contentId) { desktopText("error.bookmarkMissing") }
        session.library.removeBookmark(content, bookmark)
        val bookmarks = session.library.bookmarks(content.id)
        mutableState.update { it.copy(bookmarks = bookmarks, novel = it.novel?.let { novel ->
            novel.copy(bookmarkBlocks = novelBookmarkBlocks(content, novel.chapter, novel.blocks, bookmarks))
        }) }
    }

    fun clearBookmarks() = action {
        val content = requireNotNull(state.value.content)
        val contentId = content.id
        // One transaction per bookmark keeps a stale card from deleting a replacement.
        for (bookmark in session.library.bookmarks(contentId)) {
            runCatching { session.library.removeBookmark(content, bookmark) }
        }
        val bookmarks = session.library.bookmarks(contentId)
        mutableState.update { it.copy(bookmarks = bookmarks, novel = it.novel?.let { novel ->
            novel.copy(bookmarkBlocks = novelBookmarkBlocks(content, novel.chapter, novel.blocks, bookmarks))
        }) }
    }

    /**
     * Android deletes several bookmarks from one long-press menu; on the desktop the same set comes from a
     * selection on the details grid. Cards of other works or already gone are skipped instead of failing.
     */
    fun removeBookmarks(pageIds: Set<Long>) = action {
        if (pageIds.isEmpty()) return@action
        val content = requireNotNull(state.value.content)
        val contentId = content.id
        val targets = session.library.bookmarks(contentId).filter { it.pageId in pageIds }
        for (bookmark in targets) {
            runCatching { session.library.removeBookmark(content, bookmark) }
        }
        val bookmarks = session.library.bookmarks(contentId)
        mutableState.update { it.copy(bookmarks = bookmarks, message = desktopText("details.bookmarksDeleted", targets.size),
            novel = it.novel?.let { novel ->
                novel.copy(bookmarkBlocks = novelBookmarkBlocks(content, novel.chapter, novel.blocks, bookmarks))
            }) }
    }

    /**
     * Android's chapter bookmark command in the details batch bar. Removing takes every bookmark of the
     * selected chapters; adding only touches chapters that hold none, so a saved page is never widened.
     */
    fun toggleChapterBookmarks(chapters: List<SourceChapter>, remove: Boolean) = action {
        val content = requireNotNull(state.value.content)
        val contentId = content.id
        val stored = session.library.bookmarks(contentId)
        if (remove) {
            val chapterIds = chapters.mapTo(HashSet()) { it.id }
            for (bookmark in stored.filter { it.chapterId in chapterIds }) {
                runCatching { session.library.removeBookmark(content, bookmark) }
            }
        } else {
            val covered = stored.mapTo(HashSet()) { it.chapterId }
            for (chapter in chapters) {
                if (chapter.id !in covered) runCatching { session.library.addChapterBookmark(content, chapter) }
            }
        }
        val bookmarks = session.library.bookmarks(contentId)
        mutableState.update { it.copy(bookmarks = bookmarks, novel = it.novel?.let { novel ->
            novel.copy(bookmarkBlocks = novelBookmarkBlocks(content, novel.chapter, novel.blocks, bookmarks))
        }) }
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
        if (reload) cancelPrefetch()
        mutableState.update { it.copy(readerSettings = settings,
            readerImages = if (reload) emptyMap() else it.readerImages) }
        if (state.value.screen == DesktopScreen.READER &&
            previous.copy(automaticChapter = settings.automaticChapter, animation = settings.animation,
                colorFilter = settings.colorFilter, background = settings.background, pageNumbers = settings.pageNumbers,
                autoScrollSpeed = settings.autoScrollSpeed, tapGrid = settings.tapGrid) != settings) {
            pageOnIo(state.value.pageIndex)
        }
        if (!saved) mutableState.update { it.copy(error = desktopText("error.readerSettingsInMemory")) }
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
        mutableState.update { it.copy(message = desktopText("extensions.installedTool", tool.title)) }
        reloadUpscaledPage()
    }

    fun installUpscalerArchive(tool: org.skepsun.kototoro.desktop.runtime.DesktopUpscaleTool, archive: Path) = action {
        session.superResolution.installArchive(tool, archive)
        mutableState.update { it.copy(message = desktopText("extensions.installedTool", tool.title)) }
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

    // The prefetcher is one long-lived worker with a queue instead of a job that is cancelled and restarted per turn:
    // a turn only re-plans the queue, so a page already being decoded finishes and is kept, and nothing is asked for
    // twice. All four fields are guarded by [prefetchLock].
    private val prefetchLock = Any()
    private val prefetchPending = LinkedHashSet<Long>()
    private val prefetchInFlight = HashSet<Long>()
    private var prefetchLoaded: Pair<Long, MutableSet<Long>> = 0L to HashSet()
    private var prefetchWorker: Job? = null
    private var prefetchFailures: Pair<Long, MutableSet<Long>> = 0L to java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Whether background page prefetching is still running (tests wait for it before counting requests). */
    internal val isPrefetching: Boolean get() = synchronized(prefetchLock) {
        prefetchWorker?.isActive == true || prefetchPending.isNotEmpty()
    }

    /** Drops queued work and stops the worker; the reader reloads its own pages (new upscale or crop choice). */
    private fun cancelPrefetch() = synchronized(prefetchLock) {
        prefetchPending.clear()
        prefetchInFlight.clear()
        prefetchLoaded = prefetchLoaded.first to HashSet()
        prefetchWorker?.cancel()
        prefetchWorker = null
    }

    /**
     * Android's paged resource window (reader-core): the slots around the current one load in the background, two
     * ahead and two behind, nearest first, so a turn finds its pages ready. Outside the action gate: it never delays
     * a turn, and a newer window re-plans the queue of the running worker.
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
        synchronized(prefetchLock) {
            if (prefetchFailures.first != chapterId) {
                prefetchFailures = chapterId to java.util.concurrent.ConcurrentHashMap.newKeySet()
                prefetchLoaded = chapterId to HashSet()
                prefetchPending.clear()
            }
            val failed = prefetchFailures.second
            val loaded = prefetchLoaded.second
            // A page whose background load failed is left to the reader: showing it loads (and reports) it again.
            val pages = indices.distinct().mapNotNull { snapshot.pages.getOrNull(it) }
                .filter { it.id !in snapshot.readerImages && it.id !in snapshot.readerFailedPages && it.id !in failed }
            // Nearest first: this plan replaces the queue order but keeps pages another turn already asked for.
            prefetchPending.clear()
            pages.forEach { page ->
                if (page.id !in loaded && page.id !in prefetchInFlight) prefetchPending.add(page.id)
            }
            if (prefetchPending.isNotEmpty()) ensurePrefetchWorkerLocked(chapterId)
        }
    }

    /** Starts the worker if it is idle; the caller holds [prefetchLock]. */
    private fun ensurePrefetchWorkerLocked(chapterId: Long) {
        if (prefetchWorker?.isActive == true) return
        prefetchWorker = scope.launch(DesktopDispatchers.decode) {
            try {
                while (true) {
                    val id = synchronized(prefetchLock) {
                        val next = prefetchPending.firstOrNull() ?: return@launch
                        prefetchPending.remove(next)
                        prefetchInFlight.add(next)
                        next
                    }
                    try {
                        val page = state.value.pages.firstOrNull { it.id == id } ?: continue
                        // Another path (the reader itself) may have loaded it while it waited in the queue.
                        if (page.id !in state.value.readerImages) {
                            val image = try {
                                session.readerImage(page)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                prefetchFailures.second.add(page.id)
                                continue
                            }
                            mutableState.update { current ->
                                if (current.screen == DesktopScreen.READER && current.chapter?.id == chapterId &&
                                    current.pages.any { it.id == page.id }) {
                                    current.copy(readerImages = current.readerImages + (page.id to image))
                                } else current
                            }
                        }
                    } finally {
                        synchronized(prefetchLock) {
                            prefetchInFlight.remove(id)
                            // Switching chapter replaces the records; an id of the old chapter is not carried over.
                            if (prefetchLoaded.first == chapterId) prefetchLoaded.second.add(id)
                        }
                    }
                }
            } finally {
                synchronized(prefetchLock) {
                    prefetchWorker = null
                    // A plan may have arrived while the loop was deciding to stop.
                    if (prefetchPending.isNotEmpty()) ensurePrefetchWorkerLocked(chapterId)
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
        // Android's incognito session keeps the position on screen only.
        if (snapshot.savesProgress) {
            session.library.recordPage(requireNotNull(snapshot.content), requireNotNull(snapshot.chapter),
                anchor, snapshot.pages.size, layout.indices.last())
        }
        return snapshot.copy(screen = DesktopScreen.READER, pageIndex = anchor, readerImages = images.toMap(), readerScroll = 0f,
                readerScrollReady = false, readerLastVisible = layout.indices.last(),
                readerFailedPages = snapshot.readerFailedPages - layout.indices.map { index -> snapshot.pages[index].id }.toSet())
    }

    fun preferences() = navigate {
        val source = requireNotNull(state.value.selectedSource) { desktopText("extensions.selectSourceFirst") }
        val screen = session.sources.getPreferences(source.source.name)
        mutableState.update { it.copy(screen = DesktopScreen.PREFERENCES, preferences = screen) }
    }

    fun updatePreference(node: SourcePreferenceNode, value: SourcePreferenceValue) = action {
        val screen = requireNotNull(state.value.preferences)
        node.actionUrl?.let { url ->
            val browser = requireNotNull(session.browser) { desktopText("error.webView2Required") }
            browser.openUrl(url)
            browser.showWindow()
            mutableState.update { it.copy(screen = DesktopScreen.BROWSER) }
            return@action
        }
        val result = session.sources.updatePreference(screen.source.name, screen.revision, node.id, value)
        val message = when (result.status) {
            SourcePreferenceUpdateStatus.ACCEPTED -> desktopText("settings.saved")
            SourcePreferenceUpdateStatus.REJECTED -> desktopText("settings.rejected")
            SourcePreferenceUpdateStatus.PERSISTENCE_FAILED -> desktopText("settings.persistedInMemory")
        }
        mutableState.update { it.copy(preferences = result.screen, message = message) }
    }

    fun browser() = navigate { mutableState.update { it.copy(screen = DesktopScreen.BROWSER) } }

    fun more() = navigate {
        // The statistics screen's per-work sheet is an overlay of that screen, never of the "more" page.
        mutableState.update { it.copy(screen = DesktopScreen.MORE, contentStats = null) }
    }

    fun backToDetails() = action {
        closeCast(resume = false)
        mutableState.update { it.copy(screen = DesktopScreen.DETAILS, detailsChapterId = when (it.screen) {
            DesktopScreen.READER -> it.chapter?.id
            DesktopScreen.NOVEL -> it.novel?.chapter?.id
            DesktopScreen.VIDEO -> it.video?.chapter?.id
            else -> it.detailsChapterId
        }) }
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
            val wasReading = mutableState.value.screen.isReading
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
                mutableState.update { it.copy(error = desktopText("error.sourceApiIncompatible", error.message ?: error.javaClass.simpleName)) }
            } finally {
                // Closing the reading stop must not replace the action's own failure, or crash the caller's scope, when
                // the statistics write fails; report it the way every other action failure is reported instead.
                try {
                    settleReadingSession(wasReading)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableState.update { it.copy(error = error.message ?: error.javaClass.simpleName) }
                }
            }
        }
    }

    /**
     * Android's reader view model owns the statistics collector; this host has no such lifetime, so the entrance of a
     * reader screen opens the stop and leaving one closes it. An incognito reader is never counted, exactly like the
     * progress writes it already skips.
     */
    private suspend fun beginReadingStop() {
        val state = mutableState.value
        if (state.savesProgress && !state.screen.isReading) session.library.beginReadingSession()
    }

    /** Called from the action that leaves a reader screen, so the stop lands before the next screen's work starts. */
    private suspend fun settleReadingSession(wasReading: Boolean) {
        if (wasReading && !mutableState.value.screen.isReading) session.library.finishReadingSession()
    }

    suspend fun shutdown() {
        // A downloaded package the user chose to apply on exit is swapped after this session's files are closed.
        runCatching { updates.installOnExitIfRequested() }
        session.browserChallenges?.close()
        job.cancelAndJoin()
        withContext(NonCancellable + Dispatchers.IO) {
            try { gate.withLock { continuous.flush(); novelOperations.flush(); videoOperations.flush() } }
            finally {
                // Leaving the app is a reader stop too: Android's onPause closes the statistics session.
                runCatching { session.library.finishReadingSession() }
                try { webDav.close() } finally {
                    try {
                        val preview = mutableBackupPreview.value
                        mutableBackupPreview.value = null
                        preview?.close()
                    } finally {
                        session.close()
                    }
                }
            }
        }
    }
}
