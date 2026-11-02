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
import org.skepsun.kototoro.core.extensions.SourceNsfwOverrideSet
import org.skepsun.kototoro.core.extensions.changedNsfwSources
import org.skepsun.kototoro.core.dictionary.DictionaryRule
import org.skepsun.kototoro.details.domain.RelatedCandidate
import org.skepsun.kototoro.details.domain.RelatedContentRules
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.tracker.domain.completePendingUpdates
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import org.skepsun.kototoro.stats.domain.StatsContentKind
import org.skepsun.kototoro.history.domain.recoverHistoryChapterId
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.ReaderModeDetection
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
import org.skepsun.kototoro.desktop.runtime.DesktopBookmarkGroup
import org.skepsun.kototoro.desktop.runtime.DesktopNsfwTarget
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateGroup
import org.skepsun.kototoro.desktop.runtime.desktopBookmarkShelf
import org.skepsun.kototoro.desktop.runtime.DesktopLibrarySnapshot
import org.skepsun.kototoro.desktop.runtime.DesktopRepository
import org.skepsun.kototoro.desktop.runtime.DesktopRepositoryCatalog
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateSettings
import org.skepsun.kototoro.desktop.runtime.DesktopCrashLogFile
import org.skepsun.kototoro.desktop.runtime.DesktopStorageUsage
import org.skepsun.kototoro.desktop.runtime.DesktopTriState
import org.skepsun.kototoro.desktop.runtime.DesktopNavigationSettings
import org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings
import org.skepsun.kototoro.desktop.runtime.DesktopSuggestionFilter
import org.skepsun.kototoro.desktop.runtime.desktopSuggestionFilters
import org.skepsun.kototoro.desktop.runtime.desktopReadChapterIds
import org.skepsun.kototoro.desktop.runtime.desktopReadSavedChapters
import org.skepsun.kototoro.desktop.runtime.desktopRelatedWorks
import org.skepsun.kototoro.desktop.runtime.isNsfwSource
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy
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

/** Android's two adult quick-filter chips, which only exist while adult works may be recommended. */
private val AdultChips = setOf<DesktopSuggestionFilter>(DesktopSuggestionFilter.Adult, DesktopSuggestionFilter.Safe)

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
/**
 * Android stores the reader mode of every work in the `mode` column of its `preferences` table. The desktop reader
 * prefs keep the same map as `"<contentId>=<mode>"` entries of one string set.
 */
private const val READER_MODES = "reader_modes"

internal val DetailsOrigins = setOf(DesktopScreen.HOME, DesktopScreen.FEED, DesktopScreen.EXPLORE, DesktopScreen.LIBRARY,
    DesktopScreen.HISTORY, DesktopScreen.BOOKMARKS, DesktopScreen.UPDATED, DesktopScreen.SUGGESTIONS)

/** The JVM's display locale, matched against branch names as Android matches its locale list. */
internal fun desktopChapterBranchLocales(): List<ChapterBranchLocale> =
    listOf(Locale.getDefault(Locale.Category.DISPLAY), Locale.getDefault()).distinct()
        .map { ChapterBranchLocale(it.getDisplayLanguage(it), it.getDisplayName(it)) }

enum class DesktopScreen {
    HOME, FEED, EXPLORE, LIBRARY, HISTORY, DETAILS, READER, NOVEL, VIDEO, PREFERENCES, BROWSER, DOWNLOADS, BACKUPS,
    RECOMMENDATIONS, EXTENSIONS, READER_SETTINGS, SOURCE_SETTINGS, TAG_BLACKLIST, READING_STATS, MORE, STORAGE,
    PLAYBACK_SETTINGS, PROXY_SETTINGS, CHANGELOG, CRASH_LOGS, REPLACE_RULES, DICTIONARY_RULES,
    DOWNLOAD_SETTINGS, NAV_CONFIG, DATA_CLEANUP, SERVICES, SETUP_WIZARD, SOURCE_TEST, BOOKMARKS, UPDATED, SUGGESTIONS,
}

/**
 * Android's settings sub-pages and the "more" destinations: their header carries the back arrow of Android's top bar.
 * The value is the test tag each page's own back button used to have, so the UI probes keep finding it.
 */
internal val DesktopScreen.settingsBackTag: String?
    get() = when (this) {
        DesktopScreen.SERVICES -> "services-back"
        DesktopScreen.READER_SETTINGS -> "reader-settings-back"
        DesktopScreen.SOURCE_SETTINGS -> "source-settings-back"
        DesktopScreen.STORAGE -> "storage-settings-back"
        DesktopScreen.PLAYBACK_SETTINGS -> "playback-settings-back"
        DesktopScreen.DOWNLOAD_SETTINGS -> "downloads-settings-back"
        DesktopScreen.NAV_CONFIG -> "nav-config-back"
        DesktopScreen.CHANGELOG -> "changelog-back"
        DesktopScreen.CRASH_LOGS -> "crash-logs-back"
        DesktopScreen.PROXY_SETTINGS -> "proxy-settings-back"
        DesktopScreen.DATA_CLEANUP -> "data-cleanup-back"
        DesktopScreen.REPLACE_RULES -> "replace-rules-back"
        DesktopScreen.DICTIONARY_RULES -> "dictionary-rules-back"
        DesktopScreen.TAG_BLACKLIST -> "tag-blacklist-back"
        DesktopScreen.READING_STATS -> "stats-back"
        DesktopScreen.SOURCE_TEST -> "source-test-back"
        DesktopScreen.EXTENSIONS -> "extensions-back"
        DesktopScreen.BACKUPS -> "backups-back"
        DesktopScreen.DOWNLOADS -> "downloads-back"
        DesktopScreen.RECOMMENDATIONS -> "recommendations-back"
        DesktopScreen.SETUP_WIZARD -> "setup-wizard-back"
        DesktopScreen.BROWSER -> "browser-back"
        else -> null
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
    /** Sources the user turned off on the extensions page; Android keeps them installed but stops offering them. */
    val disabledSources: Set<String> = emptySet(),
    /**
     * Android's `source_nsfw_overrides` / `source_sfw_overrides`: sources the user forced adult or SFW from the
     * selection bar, so the adult-content targets and the suggestion filter see the same flag Android does.
     */
    val sourceNsfwOverrides: SourceNsfwOverrideSet = SourceNsfwOverrideSet(),
    /**
     * Android's `jar_priority_order` as stored (one comma separated list). Windows' parser plugins are what it
     * ranks here; the settings page resolves the names against the installed plugins when it draws the row.
     */
    val jarPriorityOrder: String = "",
    /** Android's `show_broken_sources`: `@Broken` parsers stay installed, this decides if the lists offer them. */
    val showBrokenSources: Boolean = false,
    /** Android's `chapters_clear_auto`: sweep the chapters the history already passed at every app start. */
    val chaptersClearAuto: Boolean = false,
    /** Android's `extension_install_policies`, keyed by `UnifiedSourceKind` name; a kind nobody set asks every time. */
    val extensionInstallPolicies: Map<String, ExtensionInstallPolicy> = emptyMap(),
    /** Android's `ConfirmPackageInstall`: the row waiting for "install and enable" / "install only". */
    val installPrompt: DesktopInstallRequest? = null,
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
    /** The bookmarks page: every work that has saved positions, already filtered by the adult-content target. */
    val bookmarkGroups: List<org.skepsun.kototoro.desktop.runtime.DesktopBookmarkGroup> = emptyList(),
    /** Page ids picked on the bookmarks page; Android's multi-select on the same cards. */
    val bookmarkShelfSelection: Set<Long> = emptySet(),
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
    /**
     * Android's `HistoryInfo.estimatedTime` for the open details, when `reading_time` is on and the work is long
     * enough to estimate; null keeps the row off the page.
     */
    val detailsReadingTime: org.skepsun.kototoro.desktop.runtime.DesktopReadingTime? = null,
    /**
     * Android's related works for the open details: what the source answered, or the search fallback the Mihon host
     * needs. Empty while the rail loads or when `related_manga` is off, which keeps the section off the page.
     */
    val relatedWorks: List<SourceContent> = emptyList(),
    /** True while [DesktopController.details] asks the source for [relatedWorks] in the background. */
    val relatedLoading: Boolean = false,
    /** Home's history row; separate from [library], which the favourites/history pages own. */
    val homeHistory: DesktopLibrarySnapshot = DesktopLibrarySnapshot(),
    /** Android's feed read model (update logs and works with pending new chapters). */
    val feed: org.skepsun.kototoro.tracker.domain.feed.FeedSnapshot = org.skepsun.kototoro.tracker.domain.feed.FeedSnapshot.Empty,
    /** Android's suggestion and tracker settings, persisted under Android's keys. */
    val suggestionSettings: org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings = org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings(),
    val trackerSettings: org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings = org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings(),
    val sourceSettings: org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings = org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings(),
    /** Android's services switches that have a Windows meaning: statistics recording and the reading-time estimate. */
    val services: org.skepsun.kototoro.desktop.runtime.DesktopServicesSettings =
        org.skepsun.kototoro.desktop.runtime.DesktopServicesSettings(),
    val libraryDisplay: DesktopLibraryDisplay =
        DesktopLibraryDisplay(),
    /** Android's "show updated" feed menu item: whether the tracked-with-new-chapters block is drawn. */
    val feedDisplay: org.skepsun.kototoro.desktop.runtime.DesktopFeedDisplay =
        org.skepsun.kototoro.desktop.runtime.DesktopFeedDisplay(),
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
    /** Android's GitHub download mirror, which the storage-and-network page edits. */
    val githubMirror: org.skepsun.kototoro.desktop.runtime.DesktopGitHubMirrorSettings =
        org.skepsun.kototoro.desktop.runtime.DesktopGitHubMirrorSettings(),
    /** Stored suggestions, most relevant first (Android's recommendations). */
    val suggestions: List<SourceContent> = emptyList(),
    /** True while [DesktopController.refreshSuggestions] runs in the background; the window stays usable. */
    val suggestionsGenerating: Boolean = false,
    /**
     * Android's suggestions quick filter: the chips picked on the recommendations page. They narrow the page only, so
     * the home rail keeps showing everything the worker stored.
     */
    val suggestionFilters: Set<DesktopSuggestionFilter> = emptySet(),
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
    /** Android's playback settings: the player's backdrop, decoder preference and control-overlay opacities. */
    val videoSettings: DesktopVideoSettings = DesktopVideoSettings(),
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
    /** Items the last batch returned: an appended batch continues after exactly these, not after every row shown. */
    val browsePageSize: Int = 0,
    /** The source answered with an empty batch, so Android's endless list stops asking and says there is no more. */
    val browseExhausted: Boolean = false,
    val query: String = "",
    val browseOrder: String? = null,
    val dynamicFilters: SourceDynamicFilters? = null,
    val appliedFilters: List<SourceFilterChange> = emptyList(),
    /** Android's tag filter of the opened source: its tag groups (null until loaded) and the tags picked. */
    val browseTagGroups: List<org.skepsun.kototoro.core.source.SourceTagGroup>? = null,
    val browseTags: Set<org.skepsun.kototoro.core.source.SourceTag> = emptySet(),
    val filterDialogOpen: Boolean = false,
    val preferences: SourcePreferenceScreen? = null,
    val isFavourite: Boolean = false,
    /** This copy's version, the channel it came from, and the in-app update flow's progress. */
    val update: DesktopUpdateUi = DesktopUpdateUi(),
    /** The image cache's total size, the part saved chapters need, and its budget. */
    val imageCache: DesktopImageCacheReport = DesktopImageCacheReport(),
    /** Android's storage page figures; null while the walk on the data root runs. */
    val storageUsage: DesktopStorageUsage? = null,
    /** How many reader pages the on-disk index remembers; the cleanup page clears it. */
    val readerIndexCount: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /**
     * Android's recommendation notification, which Windows can only show in-app: the title of the work a finished
     * background run picked while `suggestions_notifications` is on.
     */
    val suggestionNotice: String? = null,
    /** Share text shown when Windows refused the clipboard write; null while there is nothing to fall back to. */
    val shareLinks: String? = null,
) {
    /** Android's `getEnabledSources()`: everything the user did not switch off, in the host's own order. */
    val enabledSources: List<SourceListing>
        get() = if (disabledSources.isEmpty()) sources
        else sources.filterNot { it.source.name in disabledSources }

    val image: Path? get() = if (screen == DesktopScreen.READER) pages.getOrNull(pageIndex)
        ?.let { readerImages[it.id]?.path } else null

    /**
     * Android's incognito reader records nothing, so every automatic save asks this first. Explicit commands (mark as
     * read/unread, batch downloads) stay user actions and are never gated.
     */
    val savesProgress: Boolean get() = !readerIncognito
}

/** One owned scope and immutable UI state. Source methods retain their existing cancellation/lease semantics. */
class DesktopController(
    val session: DesktopSession,
    /**
     * Android's UNMETERED work constraint reads the platform's connectivity; Windows reads its adapters. Probes hand
     * in a fixed answer so a run does not depend on the machine sitting on Wi-Fi.
     */
    private val networkAvailable: () -> Boolean = { hasUnmeteredNetwork(desktopNetworkInterfaces()) },
) {
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
    /** Android's services switches; their own store keeps `stats_on`/`reading_time` away from the other documents. */
    internal val servicesPreferences =
        org.skepsun.kototoro.desktop.runtime.DesktopServicesPreferences(
            session.storage.preferences.open("desktop_services"),
        )
    /** Background and manual runs of the same work never overlap. */
    private val backgroundMutex = Mutex()
    private val appearancePreferences = session.storage.preferences.open("desktop_appearance")
    private val mutableState = MutableStateFlow(DesktopAppState(readerSettings = restoredReaderSettings(),
        novelSettings = restoredNovelSettings(), videoEnhancement = restoredVideoEnhancement(),
        videoSettings = restoredVideoSettings(),
        appearance = DesktopAppearance.entries.firstOrNull {
            it.name == (appearancePreferences.snapshot()["theme"] as? SourcePreferenceValue.Text)?.value
        } ?: DesktopAppearance.SYSTEM,
        interfaceStyle = DesktopInterfaceStyle.entries.firstOrNull {
            it.name == (appearancePreferences.snapshot()["interface_style"] as? SourcePreferenceValue.Text)?.value
        } ?: DesktopInterfaceStyle.MATERIAL,
        language = restoredLanguage(),
        disabledSources = session.disabledSources(),
        sourceNsfwOverrides = session.sourceNsfwOverrides(),
        jarPriorityOrder = session.jarPriorityOrder(),
        showBrokenSources = session.showBrokenSources(),
        chaptersClearAuto = session.chaptersClearAuto(),
        extensionInstallPolicies = session.extensionInstallPolicies()))
    val state = mutableState.asStateFlow()
    private val continuous = DesktopScrollOperations(mutableState, session, scope, gate)
    private val novelOperations = DesktopNovelOperations(mutableState, session, scope, gate)
    private val videoOperations = DesktopVideoOperations(mutableState, session, scope, gate)
    internal val downloads = DesktopDownloads(session.downloadStore, scope,
        { session.readerRevision(it.source.name) }, session::downloadPages, session::prepareDownload,
        { session.downloadPreferences.current() })
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
    /** Android's crash-log page: the reports the crash writer left under the data root's `logs/crash_logs`. */
    private val crashLogOperations = DesktopCrashLogOperations(scope, session)
    internal val crashLogs = crashLogOperations.state
    private val replaceRuleOperations = DesktopReplaceRuleOperations(scope, session)
    internal val replaceRules = replaceRuleOperations.state
    private val dictionaryRuleOperations = DesktopDictionaryRuleOperations(scope, session)
    internal val dictionaryRules = dictionaryRuleOperations.state
    /** Android's `SourceTester`: the run behind 扩展与仓库 → 测试来源, kept out of `DesktopAppState` (§184). */
    internal val sourceTester = DesktopSourceTester(
        sources = session.sources,
        loadCover = { content -> session.covers.load(content, refresh = true) },
        probeUrl = session::probeUrl,
    )
    private var sourceTestJob: Job? = null
    /** Android's WebDAV backup destination: same shared protocol, uploaded by hand or by the timer below. */
    private val webDav = DesktopWebDavOperations(session.storage, backups)
    internal val webDavUi = webDav.ui
    private val googleDrive = DesktopGoogleDriveOperations(session.storage)
    internal val googleDriveUi = googleDrive.ui
    private var autoDriveJob: Job? = null
    private var autoBackupJob: Job? = null
    private var autoRestoreJob: Job? = null
    private var mirrorProbeJob: Job? = null
    /** The related-works request of the open details; a new details cancels the old one. */
    private var relatedJob: Job? = null

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
            libraryDisplay = DesktopLibraryDisplay.from(
                libraryPreferences.snapshot(),
            ),
            feedDisplay = org.skepsun.kototoro.desktop.runtime.DesktopFeedDisplay.from(
                libraryPreferences.snapshot(),
            ),
            ) }
        }
        // The proxy the SDK client already uses is what the page shows; changing it writes the same store.
        mutableState.update { it.copy(proxySettings = session.proxy.settings.value) }
        mutableState.update { it.copy(githubMirror = session.githubMirror.settings.value) }
        // Android's `stats_on` decides whether the tracker writes anything; the library starts on, like Android.
        servicesPreferences.settings.value.let { saved ->
            mutableState.update { it.copy(services = saved) }
            session.library.statsRecording = saved.statsEnabled
        }
        // Repositories are read in the background at start: updates are installed (when allowed) or announced.
        scope.launch { runCatching { startupUpdates() } }
        // The stamped version and the saved update toggles, before the first check or the first composed screen.
        updates.restore()
        webDav.restore()
        // Only starts a loop when the saved destination has an interval; otherwise it returns immediately.
        scope.launch {
            googleDrive.restore()
            startAutoBackup()
            startAutoRestore()
            if (googleDrive.shouldSyncOnStart() && googleDrive.sync()) refreshAfterDriveSync()
            startAutoDriveSync()
        }
        // Android's `chapters_clear_auto` runs the read-chapter sweep whenever the app is opened; the saved chapters are
        // already restored at this point, so the first sweep sees them.
        if (session.chaptersClearAuto()) runCleanupReadChapters(announceEmpty = false)
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

    /**
     * Android's support links on the about screen: the same published URLs, opened in the system browser. A copy
     * without one (a probe on a bare JVM) reports the URL instead of pretending it opened.
     */
    fun openExternalUrl(title: String, url: String) {
        if (openDesktopUrl(url)) return
        mutableState.update { it.copy(message = desktopText("about.linkFailed", title, url)) }
    }

    /** Android's changelog entry (about → 更新日志): loads this channel's published releases, once per session. */
    fun showChangelog() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.CHANGELOG) }
        updates.changelog()
    }

    fun reloadChangelog() = updates.changelog(refresh = true)

    fun backFromChangelog() = navigate { mutableState.update { it.copy(screen = DesktopScreen.MORE) } }

    /** Android's "crash logs" row on the about card: the reports the crash writer left under `logs/crash_logs`. */
    fun showCrashLogs() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.CRASH_LOGS) }
        crashLogOperations.open()
    }

    fun backFromCrashLogs() = navigate { mutableState.update { it.copy(screen = DesktopScreen.MORE) } }

    fun reloadCrashLogs() = crashLogOperations.reload()

    fun openCrashLog(log: DesktopCrashLogFile) = crashLogOperations.openDetail(log)

    fun closeCrashLogDetail() = crashLogOperations.closeDetail()

    fun deleteCrashLog(log: DesktopCrashLogFile) = crashLogOperations.delete(log)

    fun clearCrashLogs() = crashLogOperations.clear()

    fun copyCrashLog(text: String) {
        val copied = crashLogOperations.copy(text)
        mutableState.update {
            it.copy(message = if (copied) desktopText("crashLogs.copied") else desktopText("crashLogs.copyFailed"))
        }
    }

    /** Android's replace-rule settings screen, reached from the source settings page. */
    fun showReplaceRules() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.REPLACE_RULES) }
        replaceRuleOperations.open()
    }

    fun backFromReplaceRules() = navigate { mutableState.update { it.copy(screen = DesktopScreen.READER_SETTINGS) } }

    fun openReplaceRuleEditor(rule: ReplaceRule?) = replaceRuleOperations.openEditor(rule)

    fun closeReplaceRuleEditor() = replaceRuleOperations.closeEditor()

    fun saveReplaceRule(rule: ReplaceRule) {
        replaceRuleOperations.save(rule)
        mutableState.update { it.copy(message = AndroidStrings["replace_rule_saved"]) }
    }

    fun deleteReplaceRule(rule: ReplaceRule) {
        replaceRuleOperations.delete(rule)
        mutableState.update { it.copy(message = AndroidStrings["replace_rule_deleted"]) }
    }

    fun setReplaceRuleEnabled(rule: ReplaceRule, enabled: Boolean) = replaceRuleOperations.setEnabled(rule, enabled)

    /** Android's "enable/disable all": one write, because every rule changes together. */
    fun setAllReplaceRulesEnabled(enabled: Boolean) {
        replaceRuleOperations.setAllEnabled(enabled)
        val message = AndroidStrings[if (enabled) "replace_rule_all_enabled" else "replace_rule_all_disabled"]
        mutableState.update { it.copy(message = message) }
    }

    fun importReplaceRules(source: Path) = action {
        val count = replaceRuleOperations.import(source)
        val message = if (count > 0) {
            AndroidStrings["replace_rule_imported"].format(count)
        } else {
            desktopText("replaceRules.importFailed")
        }
        mutableState.update { it.copy(message = message) }
    }

    fun exportReplaceRules(target: Path) = action {
        val exported = replaceRuleOperations.export(target)
        val message = if (exported) {
            desktopText("replaceRules.exported", target.fileName.toString())
        } else {
            desktopText("replaceRules.exportFailed")
        }
        mutableState.update { it.copy(message = message) }
    }

    /** Android's dictionary-rule screen, the row right below the replace rules on the reading settings page. */
    fun showDictionaryRules() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.DICTIONARY_RULES) }
        dictionaryRuleOperations.open()
    }

    fun backFromDictionaryRules() = navigate { mutableState.update { it.copy(screen = DesktopScreen.READER_SETTINGS) } }

    fun openDictionaryRuleEditor(rule: DictionaryRule?) = dictionaryRuleOperations.openEditor(rule)

    fun closeDictionaryRuleEditor() = dictionaryRuleOperations.closeEditor()

    fun saveDictionaryRule(rule: DictionaryRule) {
        dictionaryRuleOperations.save(rule)
        mutableState.update { it.copy(message = desktopText("dictionaryRules.saved")) }
    }

    fun deleteDictionaryRule(rule: DictionaryRule) {
        dictionaryRuleOperations.delete(rule)
        mutableState.update { it.copy(message = desktopText("dictionaryRules.deleted")) }
    }

    fun setDictionaryRuleEnabled(rule: DictionaryRule, enabled: Boolean) =
        dictionaryRuleOperations.setEnabled(rule, enabled)

    fun importDictionaryRules(source: Path) = action {
        val count = dictionaryRuleOperations.import(source)
        val message = if (count > 0) {
            desktopText("dictionaryRules.imported", count)
        } else {
            desktopText("dictionaryRules.importFailed")
        }
        mutableState.update { it.copy(message = message) }
    }

    fun exportDictionaryRules(target: Path) = action {
        val exported = dictionaryRuleOperations.export(target)
        val message = if (exported) {
            desktopText("dictionaryRules.exported", target.fileName.toString())
        } else {
            desktopText("dictionaryRules.exportFailed")
        }
        mutableState.update { it.copy(message = message) }
    }

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
            previous.copy(sources = descriptors, disabledSources = session.disabledSources(),
                installedExtensions = installedVersions(), installedEntries = session.installedEntries(),
                extensionUpdates = previous.extensionUpdates.filterNot { it.extension.packageName == entry.id },
                selectedSource = if (removed) null else previous.selectedSource,
                descriptor = if (removed) null else previous.descriptor, items = if (removed) emptyList() else previous.items,
                message = desktopText("extensions.uninstalled", entry.label))
        }
    }

    /**
     * Android's selection-bar 删除: the plan folds a selection of sources into the packages that declare them, and this
     * removes those packages in one operation. A package that fails is reported and the rest still go, so one broken
     * artifact cannot pin the selection forever.
     */
    fun uninstallPackages(packageIds: Collection<String>) = action {
        val targets = state.value.installedEntries.filter { it.id in packageIds }
        if (targets.isNotEmpty()) {
            val failures = ArrayList<String>(0)
            for (entry in targets) {
                try {
                    session.uninstall(entry)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures += "${entry.label}：${error.message ?: error.javaClass.simpleName}"
                }
            }
            val descriptors = sourceDescriptors()
            mutableState.update { previous ->
                val selected = previous.selectedSource
                val removed = selected != null && descriptors.none { it.source.name == selected.source.name }
                previous.copy(
                    sources = descriptors,
                    disabledSources = session.disabledSources(),
                    sourceNsfwOverrides = session.sourceNsfwOverrides(),
                    installedExtensions = installedVersions(),
                    installedEntries = session.installedEntries(),
                    extensionUpdates = previous.extensionUpdates.filterNot { it.extension.packageName in packageIds },
                    selectedSource = if (removed) null else previous.selectedSource,
                    descriptor = if (removed) null else previous.descriptor,
                    items = if (removed) emptyList() else previous.items,
                    message = if (failures.isEmpty()) {
                        desktopText("extensions.uninstalled", targets.joinToString(", ") { it.label })
                    } else {
                        desktopText("extensions.uninstallFailed") + failures.joinToString("\n")
                    },
                )
            }
        }
    }

    fun refreshSources() = action {
        mutableState.update { it.copy(sources = sourceDescriptors(), disabledSources = session.disabledSources(),
            sourceNsfwOverrides = session.sourceNsfwOverrides(),
            installedEntries = session.installedEntries(), error = session.startupErrors.firstOrNull()) }
    }

    /**
     * Android's per-source switch. Only the offered list changes: a disabled source keeps its stored works, its
     * downloads and its settings, and switching it back on needs no reload because the runtime stays loaded.
     * A disabled source can no longer be the one being browsed, so the browse selection is dropped with it.
     */
    fun setSourceEnabled(name: String, enabled: Boolean) = setSourcesEnabled(listOf(name), enabled)

    /**
     * The dialog's bulk buttons, like Android's "enable/disable all in the current filter": one write, one state
     * update, and the same rule for the browsed source (a source that just got switched off cannot stay selected).
     */
    fun setSourcesEnabled(names: Collection<String>, enabled: Boolean) = action {
        session.setSourcesEnabled(names, enabled)
        val disabled = session.disabledSources()
        mutableState.update { previous ->
            val hidden = previous.selectedSource?.takeIf { it.source.name in disabled }
            previous.copy(disabledSources = disabled,
                selectedSource = if (hidden != null) null else previous.selectedSource,
                descriptor = if (hidden != null) null else previous.descriptor,
                items = if (hidden != null) emptyList() else previous.items,
                screen = if (hidden != null) DesktopScreen.EXTENSIONS else previous.screen)
        }
    }

    /**
     * Android's `setSourcesNsfw(ids, isNsfw)` from the selection bar's NSFW chip. Only the sources whose flag actually
     * changes are stored (`changedNsfwSources`), so marking a mixed selection does not rewrite the ones that agree;
     * the rail, the adult-content targets and the suggestion filter read the new flag from the same two sets.
     */
    fun setSourcesNsfw(names: Collection<String>, isNsfw: Boolean) = action {
        val overrides = session.sourceNsfwOverrides()
        val changed = changedNsfwSources(names, isNsfw) { name ->
            state.value.sources.firstOrNull { it.source.name == name }?.isNsfwSource(overrides) == true
        }
        if (changed.isEmpty()) return@action
        session.setSourcesNsfw(changed, isNsfw)
        mutableState.update { it.copy(sourceNsfwOverrides = session.sourceNsfwOverrides()) }
    }

    /**
     * Android's "plugin deduplication priority" row: the order decides which parser plugin serves a source that two
     * plugins both declare. The parser host reads it when it opens, so the settings page says it takes effect on the
     * next start instead of pretending a running registry can be re-ranked.
     */
    fun setJarPriorityOrder(names: List<String>) = action {
        session.setJarPriorityOrder(names)
        mutableState.update { it.copy(jarPriorityOrder = session.jarPriorityOrder()) }
    }

    /**
     * Android's `chapters_clear_auto` switch: the sweep itself is [cleanupReadChapters], this only remembers whether the
     * app should run it on every start. Turning it on does not sweep immediately — Android also waits for the next
     * launch, and a user who wants it now has the action row right above.
     */
    fun setChaptersClearAuto(clear: Boolean) = action {
        session.setChaptersClearAuto(clear)
        mutableState.update { it.copy(chaptersClearAuto = session.chaptersClearAuto()) }
    }

    /**
     * Android's `show_broken_sources` switch: the parsers a plugin marked `@Broken` appear in (or leave) every source
     * list. They stay installed either way and keep their stored works, downloads and settings, so this only
     * republishes the offered sources and drops the browse selection if it was one of the hidden ones.
     */
    fun setShowBrokenSources(show: Boolean) = action {
        session.setShowBrokenSources(show)
        val descriptors = sourceDescriptors()
        mutableState.update { previous ->
            val hidden = previous.selectedSource?.takeIf { selected ->
                descriptors.none { it.source.name == selected.source.name }
            }
            previous.copy(showBrokenSources = session.showBrokenSources(),
                sources = descriptors,
                selectedSource = if (hidden != null) null else previous.selectedSource,
                descriptor = if (hidden != null) null else previous.descriptor,
                items = if (hidden != null) emptyList() else previous.items)
        }
    }

    /**
     * A package the user picked. The install policy still decides the sources' switches, but it never asks here: the
     * user chose this file, so `ASK_EVERY_TIME` means "install it and show its sources", the question belongs to the
     * catalogue row, which is where a repository names dozens of sources at once.
     */
    fun importJar(path: Path, destination: DesktopScreen = DesktopScreen.EXPLORE) = action {
        val kind = path.installKind()
        val policy = state.value.extensionInstallPolicies.policyFor(kind)
        val extension = session.importJar(path, policy != ExtensionInstallPolicy.INSTALL_ONLY)
        publishInstallation(extension.label, desktopText("extensions.imported"), destination)
    }

    /**
     * Android's `installPackage` for a catalogue row: the stored policy answers for the user, and `ASK_EVERY_TIME`
     * puts the same question Android's `UnifiedInstallChoiceDialog` does — unless this is an update, which Android
     * installs without asking and whose sources keep the switches they already have.
     */
    fun installExtension(extension: ExtensionStoreIndex.Extension) = action {
        val catalog = requireNotNull(state.value.extensionCatalog) { desktopText("extensions.readRepositoryFirst") }
        val kind = installKind(catalog, extension)
        val request = DesktopInstallRequest.FromRepository(catalog, extension, kind, extension.sources.size)
        val updating = state.value.installedExtensions.containsKey(extension.packageName)
        val answer = state.value.extensionInstallPolicies.policyFor(kind)
        if (!updating && answer == ExtensionInstallPolicy.ASK_EVERY_TIME) {
            mutableState.update { it.copy(installPrompt = request) }
            return@action
        }
        install(request, if (updating) null else answer == ExtensionInstallPolicy.INSTALL_AND_ENABLE)
    }

    /** The answer to the install question; "remember" stores it for the kind, like Android's dialog checkbox does. */
    fun resolveInstallPrompt(enableSources: Boolean, remember: Boolean) {
        val request = state.value.installPrompt ?: return
        val policy = if (enableSources) {
            ExtensionInstallPolicy.INSTALL_AND_ENABLE
        } else {
            ExtensionInstallPolicy.INSTALL_ONLY
        }
        if (remember) setExtensionInstallPolicy(request.kind.typeName, policy)
        mutableState.update { it.copy(installPrompt = null) }
        action { install(request, enableSources) }
    }

    fun dismissInstallPrompt() = mutableState.update { it.copy(installPrompt = null) }

    /** Android's per-kind row in the install behavior sheet. */
    fun setExtensionInstallPolicy(typeName: String, policy: ExtensionInstallPolicy) = action {
        val kind = DesktopInstallKind.entries.firstOrNull { it.typeName == typeName } ?: return@action
        session.setExtensionInstallPolicy(kind, policy)
        mutableState.update { it.copy(extensionInstallPolicies = session.extensionInstallPolicies()) }
    }

    /** Android's "apply to all sources" row: one answer for every kind this host installs. */
    fun setAllExtensionInstallPolicies(policy: ExtensionInstallPolicy) = action {
        session.setAllExtensionInstallPolicies(policy)
        mutableState.update { it.copy(extensionInstallPolicies = session.extensionInstallPolicies()) }
    }

    private suspend fun install(request: DesktopInstallRequest, enableSources: Boolean?) = when (request) {
        is DesktopInstallRequest.FromRepository -> publishInstallation(
            session.installExtension(request.catalog, request.extension, enableSources).label,
            desktopText("extensions.installed"),
        )
        is DesktopInstallRequest.FromFile -> publishInstallation(
            session.importJar(request.path, enableSources).label,
            desktopText("extensions.imported"),
            DesktopScreen.EXPLORE,
        )
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
        publishJsonSources(result)
    }

    fun importExtensionFile(path: Path) = if (path.fileName.toString().endsWith(".json", ignoreCase = true)) {
        action {
            val content = withContext(Dispatchers.IO) { Files.readString(path) }
            val result = session.importJsonDocument(content, path.toUri().toString(), path.fileName.toString())
            publishJsonSources(result)
        }
    } else {
        importJar(path, DesktopScreen.EXTENSIONS)
    }

    private suspend fun publishJsonSources(result: org.skepsun.kototoro.tvbox.TvBoxImportResult) {
        require(result.sources.isNotEmpty()) {
            result.errors.firstOrNull() ?: desktopText("jsonSources.noneFound")
        }
        val stored = result.sources.map {
            org.skepsun.kototoro.desktop.runtime.DesktopJsonSource(it.id, it.name, it.config, it.iconUrl, it.stored.api)
        }
        require(session.jsonSources.add(stored)) { desktopText("jsonSources.saveFailed") }
        session.reloadJsonSources()
        mutableState.update {
            it.copy(screen = DesktopScreen.EXTENSIONS, sources = sourceDescriptors(), jsonSources = session.jsonSourceList(),
                message = desktopText("jsonSources.added", result.sources.size) +
                    if (result.skippedSpiderSites > 0) {
                        " · " + desktopText("jsonSources.skipped", result.skippedSpiderSites)
                    } else "",
                error = result.errors.firstOrNull())
        }
    }

    /** Drops an imported JSON source; the works it supplied stay in the library, as Android keeps them. */
    fun removeJsonSource(id: String) = removeJsonSources(listOf(id))

    fun removeJsonSources(ids: List<String>) = action {
        require(session.jsonSources.removeAll(ids.toSet())) { desktopText("jsonSources.saveFailed") }
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
            browseOrder = null, browsePageSize = 0, browseExhausted = false,
            dynamicFilters = null, appliedFilters = emptyList(), filterDialogOpen = false,
            browseTagGroups = null, browseTags = emptySet()) }
        loadBrowseTags(source)
        browseOnIo(0, "")
    }

    /**
     * Android's filter header reads the source's tags beside the list. Loaded off the action queue: a slow or failing
     * catalogue never holds up browsing, and a source the user already left ignores the answer.
     */
    private fun loadBrowseTags(source: SourceListing) {
        scope.launch {
            val groups = try {
                session.sources.getFilterOptions(source.source.name).effectiveTagGroups.filter { it.tags.isNotEmpty() }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
            catch (_: LinkageError) { emptyList() }
            mutableState.update {
                if (it.selectedSource?.source?.name == source.source.name) it.copy(browseTagGroups = groups) else it
            }
        }
    }

    /** Picks or drops one tag and lists again from the first batch, as Android's tag chips do. */
    fun toggleBrowseTag(tag: org.skepsun.kototoro.core.source.SourceTag) = navigate {
        val current = state.value.browseTags
        val multiple = state.value.descriptor?.filterCapabilities?.isMultipleTagsSupported != false
        val next = when {
            tag in current -> current - tag
            multiple -> current + tag
            else -> setOf(tag)
        }
        browseOnIo(0, state.value.query, state.value.browseOrder, state.value.appliedFilters, next)
    }

    fun clearBrowseTags() = navigate {
        browseOnIo(0, state.value.query, state.value.browseOrder, state.value.appliedFilters, emptySet())
    }

    fun browse(offset: Int = 0, query: String = state.value.query) = navigate { browseOnIo(offset, query) }

    /**
     * Android's endless browse list: the next batch is appended to the rows already shown instead of replacing them.
     * Called from the grid's end-of-list footer, so it queues behind whatever action is running instead of dropping the
     * request, and it stays a no-op once the source said there is nothing left.
     */
    fun browseNextPage(): Job? {
        val snapshot = state.value
        if (snapshot.screen != DesktopScreen.EXPLORE || snapshot.selectedSource == null || snapshot.browseExhausted) return null
        return action {
            // The retained list can compose its footer while details or a player replaces it. A queued page request
            // must not navigate back to the source after the user has already opened another screen.
            val current = state.value
            if (current.screen == DesktopScreen.EXPLORE && current.selectedSource == snapshot.selectedSource &&
                !current.browseExhausted) browseOnIo(current.offset + 1, current.query, append = true)
        }
    }

    /** The manual "next batch" button: same append path as the scroll trigger, so both keep the shown rows. */
    fun browseMore() = navigate {
        if (!state.value.browseExhausted) browseOnIo(state.value.offset + 1, state.value.query, append = true)
    }

    fun explore() = navigate {
        if (state.value.selectedSource == null) mutableState.update {
            it.copy(screen = DesktopScreen.EXPLORE, items = emptyList(), content = null)
        } else browseOnIo(state.value.offset, state.value.query)
    }

    /** Leaves the opened source and returns to the browse page's source grid (Android's back from a source). */
    fun exitSource() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.EXPLORE, selectedSource = null, descriptor = null,
            items = emptyList(), offset = 0, pageOffsets = listOf(0), query = "", content = null, browseOrder = null,
            browsePageSize = 0, browseExhausted = false,
            dynamicFilters = null, appliedFilters = emptyList(), filterDialogOpen = false,
            browseTagGroups = null, browseTags = emptySet()) }
    }

    fun browseUnfiltered(latest: Boolean = false) = navigate {
        browseOnIo(0, "", supportedOrder(if (latest) "UPDATED" else "POPULARITY"), emptyList(), emptySet())
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
        // The dialog's own tag controls replace the chips' picks rather than combining with them.
        browseOnIo(0, query, null, validated, emptySet())
        mutableState.update { it.copy(filterDialogOpen = false) }
    }

    private suspend fun browseOnIo(offset: Int, query: String, order: String? = state.value.browseOrder,
        changes: List<SourceFilterChange> = state.value.appliedFilters,
        tags: Set<org.skepsun.kototoro.core.source.SourceTag> = state.value.browseTags, append: Boolean = false) {
        val source = requireNotNull(state.value.selectedSource) { desktopText("extensions.selectSourceFirst") }
        val filter = if (query.isNotBlank() || changes.isNotEmpty() || tags.isNotEmpty()) {
            SourceFilter(query = query.takeIf { it.isNotBlank() }, tags = tags, dynamicFilters = changes)
        } else null
        // Page-indexed sources (Mihon) take the batch number; offset-paged ones (parsers) continue after what is shown.
        val previous = state.value
        val offsets = if (offset == 0) listOf(0) else previous.pageOffsets
        val requestOffset = if (previous.descriptor?.pagingMode == SourcePagingMode.OFFSET) {
            offsets.getOrNull(offset)
                ?: (offsets.last() + (previous.browsePageSize.takeIf { it > 0 } ?: previous.items.size))
        } else offset
        val items = session.sources.getList(source.source.name, requestOffset, order, filter)
        // A source that ignores the offset would repeat the batch it already sent; appending those rows would
        // duplicate grid keys and loop forever, so a batch with nothing new is the end of the list.
        val fresh = if (append) {
            items.filter { item -> previous.items.none { it.id == item.id && it.source.name == item.source.name } }
        } else items
        // Commit the query and controls only when the source has returned a successful result.
        mutableState.update {
            val endsHere = fresh.isEmpty()
            it.copy(screen = DesktopScreen.EXPLORE,
                items = if (append) previous.items + fresh else items,
                // An empty batch adds no page: the batch counter keeps pointing at the rows on screen.
                offset = if (append && endsHere) previous.offset else offset,
                pageOffsets = if ((append && endsHere) || offset < offsets.size) offsets else offsets + requestOffset,
                browsePageSize = items.size, browseExhausted = endsHere,
                query = query, browseOrder = order, appliedFilters = changes, browseTags = tags)
        }
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
        val readingTime = estimatedReadingTime(details, progress?.percent)
        mutableState.update { it.copy(screen = DesktopScreen.DETAILS, content = details,
            detailsOrigin = origin, detailsExpanded = false, isFavourite = favourite, chapterBranch = branch,
            detailsChapterId = chapterId, bookmarks = bookmarks, detailsReadingTime = readingTime) }
        loadRelatedWorks(details)
    }

    /**
     * Android's `RelatedContentUseCase` for the open details, off the operation gate: a related request or its search
     * fallback takes as long as the source does, and the page must stay usable while it runs. The rail is cleared first
     * so a previous work's list cannot be mistaken for this one's.
     */
    private fun loadRelatedWorks(content: SourceContent) {
        relatedJob?.cancel()
        if (!state.value.services.relatedContent) {
            mutableState.update { it.copy(relatedWorks = emptyList(), relatedLoading = false) }
            return
        }
        mutableState.update { it.copy(relatedWorks = emptyList(), relatedLoading = true) }
        relatedJob = scope.launch {
            val works = relatedWorksOnIo(content)
            mutableState.update {
                // A newer details may have opened while this ran; only write back for the work still on screen.
                if (it.content?.id != content.id || it.content.source.name != content.source.name) it
                else it.copy(relatedWorks = works, relatedLoading = false)
            }
        }
    }

    /** The source's own related list when the host can answer, Android's title search when it cannot. */
    private suspend fun relatedWorksOnIo(seed: SourceContent): List<SourceContent> {
        val related = relatedAttempt { session.sources.getRelated(seed) }.orEmpty()
        val candidates = related.takeIf { it.isNotEmpty() } ?: relatedSearchOnIo(seed)
        return desktopRelatedWorks(seed, candidates)
    }

    /**
     * `RelatedContentSearchFallback` over the shared source search: the work's titles, the results that name the work,
     * and the narrowest result set (Android's rule). A Mihon extension has no related API on this host, so this is the
     * only route to related works for most Windows sources.
     */
    private suspend fun relatedSearchOnIo(seed: SourceContent): List<SourceContent> {
        val queries = RelatedContentRules.searchQueries(seed.title, seed.altTitles)
        if (queries.isEmpty()) {
            return emptyList()
        }
        val sets = queries.mapNotNull { query ->
            relatedAttempt {
                session.sources.getList(seed.source.name, 0, null, SourceFilter(query = query))
                    .filter { candidate ->
                        RelatedContentRules.isSearchCandidate(
                            query,
                            RelatedCandidate(candidate.title, candidate.altTitles),
                        )
                    }
            }?.takeIf { it.isNotEmpty() }
        }
        return RelatedContentRules.smallestResult(sets)
    }

    /** A related request that failed (unsupported, offline, damaged source) means "nothing to show", not an error. */
    private suspend fun <T> relatedAttempt(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    /**
     * Android's `ReadingTimeUseCase` as the details page shows it: the work's measured page time (or Android's ten
     * second default when statistics are off or the work was never read), twenty pages a chapter, minus the progress
     * of an unfinished read. Nothing is computed when the `reading_time` switch is off.
     */
    private suspend fun estimatedReadingTime(
        details: SourceContent,
        percent: Float?,
    ): org.skepsun.kototoro.desktop.runtime.DesktopReadingTime? {
        val services = state.value.services
        if (!services.readingTimeEstimation) return null
        val chapterCount = details.chapters.orEmpty().size
        if (chapterCount <= 0) return null
        val stats = runCatching {
            session.library.contentReadingStats(details.id, TimeZone.currentSystemDefault())
        }.getOrNull()
        val secondsPerPage = if (!services.statsEnabled || stats == null || stats.units <= 0) {
            0
        } else {
            (stats.totalDuration / 1000 / stats.units).toInt()
        }
        return org.skepsun.kototoro.desktop.runtime.desktopReadingTime(
            chapterCount = chapterCount,
            percent = percent?.takeIf { it in 0f..1f },
            secondsPerPage = secondsPerPage,
        )
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
                    state.value.enabledSources.isEmpty() -> desktopText("message.installSourceFirst")
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
        mutableState.update {
            it.copy(
                suggestionSettings = settings,
                // Android offers the two adult chips only while adult works may be recommended, so a switched-on
                // filter must not keep selecting works the generator no longer stores.
                suggestionFilters = if (settings.excludeNsfw) it.suggestionFilters - AdultChips else it.suggestionFilters,
            )
        }
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
        val settings = state.value.suggestionSettings
        // Android's UNMETERED constraint: with "only using Wi-Fi" on, a mobile link is not a reason to spend data.
        // The run is not recorded, so the next wake retries instead of waiting a whole interval.
        if (settings.wifiOnly && !networkAvailable()) return 0
        // A switched-off source must not answer, exactly like Android's enabled-only source list feeding the worker —
        // unless "include disabled sources" asks for candidates from every installed source.
        val disabled = if (settings.includeDisabledSources) emptySet() else state.value.disabledSources
        val sources = session.sourceListings().filterNot { it.source.name in disabled }
        val stored = session.suggestions.refresh(sources, settings, session.sourceNsfwOverrides())
        recordRun(LAST_SUGGESTIONS_RUN)
        val items = session.suggestions.suggestions()
        // A rebuild can stop offering a chip (a tag no longer in the list, both adult macros after the nsfw switch),
        // and Android recomputes its quick-filter options on every reload; drop what the new list cannot filter.
        val offered = desktopSuggestionFilters(items, includeAdultChips = !settings.excludeNsfw).toSet()
        mutableState.update { it.copy(suggestions = items, suggestionFilters = it.suggestionFilters intersect offered) }
        if (stored > 0) announceSuggestion(settings, items)
        return stored
    }

    /**
     * Android's `suggestion_manga` notification, which the worker posts after storing results: one recommended work,
     * chosen at random, with its title. Windows has no tray notifications, so the headline goes to the home rail's
     * recommendation card and stays until dismissed; the stored suggestions already carry the tags and filters the
     * worker applied, so nothing extra is fetched here.
     */
    private fun announceSuggestion(settings: DesktopSuggestionSettings, items: List<SourceContent>) {
        if (!settings.notifications) return
        val pick = items.randomOrNull() ?: return
        mutableState.update { it.copy(suggestionNotice = AndroidStrings.format("suggestion_manga", pick.title)) }
    }

    internal fun dismissSuggestionNotice() {
        mutableState.update { it.copy(suggestionNotice = null) }
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

    /**
     * Android's updates destination (`UpdatesNavKey`): the tracked works the last check found new chapters for. It sits
     * beside the subscriptions page instead of replacing it — Android draws the same carousel there too, behind its
     * "show updated" switch — and reads the snapshot the subscriptions page already reloaded.
     */
    fun showUpdates() = navigate { loadFeedOnIo(DesktopScreen.UPDATED) }

    /**
     * The updates page's "mark all read" (the header) and "remove" (a row), which on Android is one action:
     * `UpdatesViewModel.remove` → `TrackingRepository.clearUpdates` → `completePendingUpdates` per work. It drains the
     * tracks' new-chapter counters and leaves history, progress and the timeline's log rows alone, so a later check
     * starts from the chapter the counter stopped at instead of resurrecting reminders for chapters already opened.
     */
    fun markUpdatesRead(groups: List<DesktopUpdateGroup>) = action {
        val database = session.storage.database
        val owners = groups.flatMapTo(LinkedHashSet()) { it.ownerIds }
        val now = System.currentTimeMillis()
        for (ownerId in owners) database.completePendingUpdates(ownerId, now)
        loadFeedOnIo()
    }

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

    /** Opening a timeline entry acknowledges its reminders; the header acknowledges the work's reminders. */
    fun openTracked(mangaId: Long, logIds: List<Long> = emptyList()) = navigate {
        if (logIds.isEmpty()) session.tracker.markSeen(mangaId) else {
            logIds.forEach { session.storage.database.getTrackLogsDao().markAsRead(it) }
        }
        val content = requireNotNull(session.library.find(mangaId)) { desktopText("error.workNotFound") }
        detailsOnIo(content)
        loadFeedOnIo()
    }

    fun acknowledgeTracked(mangaId: Long, logIds: List<Long>) = action {
        val dao = session.storage.database.getTrackLogsDao()
        logIds.forEach { id -> if (dao.find(id)?.mangaId == mangaId) dao.markAsRead(id) }
        loadFeedOnIo()
    }

    /**
     * Android's "show updated" menu item. The timeline keeps its rows; only the block of tracked works with new
     * chapters above it goes away, which is what `KEY_FEED_HEADER` controls there.
     */
    internal fun setShowFeedUpdated(show: Boolean) {
        val display = org.skepsun.kototoro.desktop.runtime.DesktopFeedDisplay(showUpdated = show)
        mutableState.update { it.copy(feedDisplay = display) }
        if (!libraryPreferences.edit(SourcePreferenceEdit(changes = display.toPreferences()))) {
            mutableState.update { it.copy(error = desktopText("feed.displaySaveFailed")) }
        }
    }

    /**
     * Android's "clear feed" menu action: the update history goes away, and with it the chapter counters that the
     * dialog can leave behind on purpose (`text_clear_updates_feed_prompt` asks whether to).
     *
     * Clearing the counters is what `completePendingUpdates` does per track, so a later check starts from a clean
     * slate instead of resurrecting the reminders of chapters that are still unread.
     */
    fun clearFeed(clearPendingCounters: Boolean) = action {
        val database = session.storage.database
        database.getTrackLogsDao().clear()
        if (clearPendingCounters) {
            val now = System.currentTimeMillis()
            for (mangaId in database.getTracksDao().findAllIds()) database.completePendingUpdates(mangaId, now)
        }
        loadFeedOnIo()
        mutableState.update { it.copy(message = desktopText("feed.cleared")) }
    }

    /** Android's "continue reading" on a feed entry: mark it read and resume from history. */
    fun continueTracked(mangaId: Long) = navigate {
        session.tracker.markSeen(mangaId)
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
        // Closing the page ends its related search too; nothing on screen would show the answer.
        relatedJob?.cancel()
        val origin = state.value.detailsOrigin ?: DesktopScreen.EXPLORE
        val library = if (origin == DesktopScreen.LIBRARY || origin == DesktopScreen.HISTORY) {
            session.library.snapshot(origin == DesktopScreen.HISTORY)
        } else state.value.library
        // Reading from home, the feed or the updates page changes their rows (history, cleared counters).
        val homeHistory = if (origin == DesktopScreen.HOME) session.library.snapshot(history = true) else state.value.homeHistory
        val feed = if (origin == DesktopScreen.HOME || origin == DesktopScreen.FEED ||
            origin == DesktopScreen.UPDATED) {
            session.feed.observe(session.storage.database).first()
        } else state.value.feed
        val feedContents = if (feed !== state.value.feed) feedContentsOnIo(feed) else state.value.feedContents
        mutableState.update { it.copy(screen = origin, content = null, detailsOrigin = null,
            detailsExpanded = false, library = library, homeHistory = homeHistory, feed = feed, feedContents = feedContents,
            relatedWorks = emptyList(), relatedLoading = false) }
        // The shelf behind the details changed while it was covered: a bookmark read or deleted in there.
        if (origin == DesktopScreen.BOOKMARKS) reloadBookmarkShelf()
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
        val parsed = DesktopNovelHtml.parse(body.html, content.publicUrl.takeIf { it.startsWith("http") }.orEmpty())
        require(parsed.isNotEmpty()) { desktopText("error.noChapterText") }
        // Android's scope values for a rule are the book's title and the source name; the same two here.
        val scopeValues = listOf(content.title, content.source.name)
        val bookRules = session.replaceRules.forBook(content.title, content.source.name)
        val blocks = DesktopNovelTextRules.blocks(parsed,
            DesktopNovelTextRules.forTarget(bookRules, ReplaceRule.Scope.CONTENT, scopeValues))
        val chapterTitles = DesktopNovelTextRules.titles(chapters.map { it.title.orEmpty() },
            DesktopNovelTextRules.forTarget(bookRules, ReplaceRule.Scope.TITLE, scopeValues))
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
                chapterTitles = chapters.mapIndexedNotNull { index, value ->
                    chapterTitles[index].takeIf { it != value.title.orEmpty() }?.let { value.id to it }
                }.toMap(),
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
        val targets = desktopMigrationSources(state.value.enabledSources, content)
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

    /**
     * Android's suggestions destination (`NavItem.SUGGESTIONS`): the stored recommendations as a full list. The home
     * rail reads the same table, so entering the page only reloads it — nothing is generated unless the page's refresh
     * asks for it, exactly like Android's screen next to its worker.
     */
    fun showSuggestions() = navigate {
        val items = session.suggestions.suggestions()
        mutableState.update { it.copy(screen = DesktopScreen.SUGGESTIONS, suggestions = items) }
    }

    /** Android's quick filter on the suggestions list: the same chip again drops it, several chips narrow together. */
    internal fun toggleSuggestionFilter(filter: DesktopSuggestionFilter) = mutableState.update {
        it.copy(suggestionFilters = if (filter in it.suggestionFilters) it.suggestionFilters - filter
        else it.suggestionFilters + filter)
    }

    /** Android's "服务" page: the entry for recommendations and statistics that 更多 used to hold as two rows. */
    fun showServices() = navigate { mutableState.update { it.copy(screen = DesktopScreen.SERVICES) } }

    /**
     * Android's services switches. `stats_on` also reaches the library at once: the reader's tracker writes nothing
     * while it is off, and what was already recorded stays on the statistics page.
     */
    fun servicesSettings(value: org.skepsun.kototoro.desktop.runtime.DesktopServicesSettings) {
        val previous = state.value.services
        val saved = servicesPreferences.save(value)
        session.library.statsRecording = value.statsEnabled
        mutableState.update {
            it.copy(
                services = value,
                error = if (saved) it.error else desktopText("services.saveFailed"),
            )
        }
        // `related_manga` is read when details opens, so an open page follows the switch instead of waiting for a reopen.
        if (previous.relatedContent != value.relatedContent) {
            state.value.content?.let(::loadRelatedWorks)
                ?: mutableState.update { it.copy(relatedWorks = emptyList(), relatedLoading = false) }
        }
    }

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

    /** Android's setup wizard, which 来源设置 opens from its overview group. */
    fun showSetupWizard() = navigate { mutableState.update { it.copy(screen = DesktopScreen.SETUP_WIZARD) } }

    /**
     * Android's "test all sources" page, reached from 扩展与仓库. Entering it forgets the previous run so a second visit
     * starts clean instead of showing verdicts from a source that has since been uninstalled.
     */
    fun showSourceTest() = navigate {
        sourceTester.reset()
        mutableState.update { it.copy(screen = DesktopScreen.SOURCE_TEST) }
    }

    /**
     * Starts a run over the sources the browse rail offers (Android tests the enabled ones, which is
     * [DesktopAppState.enabledSources]), or over exactly the names [sources] lists when the switch dialog's selection
     * asked for a few of them — Android's `testSources(selectedSourceIds)`. A selection can include sources the user
     * switched off, so the pool is the installed list, not the offered one. It is *not* an [action]: the run can take
     * minutes and must stay cancellable from its own page while the rest of the window keeps rendering, so its
     * progress lives in [DesktopSourceTester.progress] and the page collects it directly, the way the GitHub mirror
     * sweep works.
     */
    fun startSourceTest(sources: Collection<String>? = null) {
        if (sourceTestJob?.isActive == true) return
        val state = mutableState.value
        val names = sources?.toSet()
        val chosen = if (names == null) state.enabledSources else state.sources.filter { it.source.name in names }
        val targets = chosen.map { SourceTestTarget(it.source.name, it.displayName) }
        if (targets.isEmpty()) return
        sourceTestJob = scope.launch { sourceTester.testAll(targets) }
    }

    fun cancelSourceTest() {
        sourceTestJob?.cancel()
    }

    /** Android's "export": the run as JSON on the clipboard, with the dialog fallback when the clipboard refuses. */
    fun copySourceTestResults() {
        val text = sourceTester.progress.value.toExportJson()
        if (DesktopClipboard.write(text)) {
            mutableState.update { it.copy(message = desktopText("sourceTest.copied")) }
        } else {
            mutableState.update { it.copy(message = desktopText("sourceTest.copyFailed")) }
        }
    }

    /** Android's `disableFailed`: the FAIL rows join `disabled_sources`, so they leave the rail until re-enabled. */
    fun disableFailedSources() = action {
        val failed = sourceTester.progress.value.failedSources.map { it.sourceName }
        require(failed.isNotEmpty()) { desktopText("sourceTest.nothingFailed") }
        session.setSourcesEnabled(failed, false)
        val disabled = session.disabledSources()
        mutableState.update { previous ->
            val hidden = previous.selectedSource?.takeIf { it.source.name in disabled }
            previous.copy(disabledSources = disabled,
                selectedSource = if (hidden != null) null else previous.selectedSource,
                descriptor = if (hidden != null) null else previous.descriptor,
                items = if (hidden != null) emptyList() else previous.items,
                message = desktopText("sourceTest.disabledFailed", failed.size))
        }
    }

    /** Android's playback settings screen: the player's backdrop, decoder mode and control-overlay opacities. */
    fun showPlaybackSettings() = navigate { mutableState.update { it.copy(screen = DesktopScreen.PLAYBACK_SETTINGS) } }

    /** Android's download settings; the queue below reads the same stored value, so a change needs no restart. */
    internal val downloadSettings = session.downloadPreferences.settings

    fun showDownloadSettings() = navigate { mutableState.update { it.copy(screen = DesktopScreen.DOWNLOAD_SETTINGS) } }

    /** Writes the speed/retry preferences; the next chapter and the next page read them. */
    fun saveDownloadSettings(settings: org.skepsun.kototoro.desktop.runtime.DesktopDownloadSettings) = action {
        if (!session.downloadPreferences.save(settings)) {
            mutableState.update { it.copy(error = desktopText("error.downloadSettingsInMemory")) }
        }
    }

    /** Android's nav configuration screen: the rail reads [navSettings] and always appends 更多 after it. */
    internal val navSettings = session.navigationPreferences.settings

    fun showNavigationConfig() = navigate { mutableState.update { it.copy(screen = DesktopScreen.NAV_CONFIG) } }

    fun saveNavigationSettings(settings: DesktopNavigationSettings) = action {
        if (!session.navigationPreferences.save(settings)) {
            mutableState.update { it.copy(error = desktopText("error.navSettingsInMemory")) }
        }
    }

    /** Writes the playback preferences the player reads; the same four keys Android's own screen writes. */
    fun videoSettings(settings: DesktopVideoSettings) = action {
        val saved = videoPreferences.edit(SourcePreferenceEdit(changes = mapOf(
            "video_background" to SourcePreferenceValue.Text(settings.background.name),
            "video_decoder_mode" to SourcePreferenceValue.Text(settings.decoderMode.name),
            "video_controls_alpha" to SourcePreferenceValue.Integer(settings.controlsAlpha),
            "video_gradient_alpha" to SourcePreferenceValue.Integer(settings.gradientAlpha),
        )))
        mutableState.update { it.copy(videoSettings = settings) }
        if (!saved) mutableState.update { it.copy(error = desktopText("error.videoSettingsInMemory")) }
    }

    private fun restoredVideoSettings(): DesktopVideoSettings {
        val snapshot = videoPreferences.snapshot()
        return DesktopVideoSettings(
            background = DesktopReaderBackground.entries.firstOrNull {
                it.name == (snapshot["video_background"] as? SourcePreferenceValue.Text)?.value
            } ?: DesktopReaderBackground.DEFAULT,
            decoderMode = DesktopVideoDecoderMode.entries.firstOrNull {
                it.name == (snapshot["video_decoder_mode"] as? SourcePreferenceValue.Text)?.value
            } ?: DesktopVideoDecoderMode.HARDWARE_PREFERRED,
            controlsAlpha = ((snapshot["video_controls_alpha"] as? SourcePreferenceValue.Integer)?.value ?: 90)
                .coerceIn(DesktopVideoSettings.CONTROLS_ALPHA_RANGE),
            gradientAlpha = ((snapshot["video_gradient_alpha"] as? SourcePreferenceValue.Integer)?.value ?: 70)
                .coerceIn(DesktopVideoSettings.GRADIENT_ALPHA_RANGE),
        )
    }

    /**
     * Android's storage and network hub. The figures are measured on entry, and the image cache report comes with them
     * because the page names the part of the images directory that saved chapters hold.
     */
    fun showStorage() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.STORAGE, storageUsage = null) }
        loadImageCacheOnIo()
        loadStorageUsageOnIo()
    }

    /** The proxy page's back button; the figures the hub already measured stay, so no walk is repeated. */
    fun backToStorage() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.STORAGE, proxyTest = DesktopProxyTestState()) }
    }

    /**
     * Android's "data removal" page, reached from the storage hub the way Android reaches it. The page names figures,
     * so the entry re-reads them: the image report and the timeline can both have moved since the hub was opened.
     */
    fun showDataCleanup() = navigate {
        mutableState.update { it.copy(screen = DesktopScreen.DATA_CLEANUP) }
        loadImageCacheOnIo()
        mutableState.update { it.copy(readerIndexCount = session.readerIndexCount()) }
        loadFeedOnIo()
    }

    /** Android's "clear pages cache": the reader index forgets the pages, saved chapters keep their blobs. */
    fun clearReaderPageCache() = action {
        val cleared = runCatching { session.clearReaderIndex() }.getOrNull()
        mutableState.update {
            if (cleared == null) {
                it.copy(error = desktopText("cleanup.pagesClearFailed"))
            } else {
                val message = desktopText("cleanup.pagesCleared", cleared)
                it.copy(readerIndexCount = session.readerIndexCount(), message = message)
            }
        }
    }

    /**
     * Android's `delete_read_chapters`: for every work with saved chapters, drop the ones the reading history has
     * already passed. "Read" is history-relative (see [desktopReadChapterIds]), so a work whose history row is gone
     * keeps everything, and the deletion removes the chapter records only — the page blobs they referenced turn into
     * unreferenced metadata that the image budget reclaims on its next sweep, the same hand-off the download page's
     * batch removal uses.
     */
    fun cleanupReadChapters() = runCleanupReadChapters(announceEmpty = true)

    private fun runCleanupReadChapters(announceEmpty: Boolean) = action {
        var removed = 0
        val forgotten = mutableListOf<String>()
        for ((contentId, records) in session.downloadStore.records.value.groupBy { it.contentId }) {
            val currentChapterId = runCatching { session.library.progress(contentId)?.chapterId }.getOrNull()
                ?: continue
            val saved = runCatching { session.library.find(contentId)?.chapters }.getOrNull().orEmpty()
            val read = desktopReadSavedChapters(saved, records.map { it.chapter }, currentChapterId)
            for (chapter in read) {
                val key = DesktopDownloadStore.key(contentId, chapter)
                downloads.pause(key)
                downloads.settle(key)
                if (session.downloadStore.delete(contentId, chapter, session.storage.preferences)) {
                    removed++
                    forgotten += key
                }
            }
        }
        if (forgotten.isNotEmpty()) {
            downloads.forget(forgotten)
            mutableDownloadSelection.update { DesktopDownloadSelection.retain(it, downloads.state.value.map { row -> row.key }) }
        }
        mutableState.update {
            when {
                removed > 0 -> it.copy(message = desktopText("cleanup.readChaptersDeleted", removed))
                announceEmpty -> it.copy(message = AndroidStrings["no_chapters_deleted"])
                else -> it
            }
        }
    }

    internal suspend fun loadStorageUsageOnIo() {
        val usage = runCatching { session.storageUsage() }.getOrNull() ?: return
        mutableState.update { it.copy(storageUsage = usage) }
    }

    /** Android's proxy page, reached from the storage hub the way Android reaches it. */
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

    /**
     * Saves the GitHub download mirror of the storage-and-network page. The rewriter reads the live value, so the next
     * release asset, extension package or Cloudstream list already uses the new mirror.
     */
    internal fun updateGitHubMirror(value: org.skepsun.kototoro.desktop.runtime.DesktopGitHubMirrorSettings) {
        val persisted = session.githubMirror.save(value)
        mutableState.update {
            it.copy(githubMirror = value,
                message = if (persisted) it.message else desktopText("jsonSources.saveFailed"))
        }
    }

    /**
     * Android's mirror speed sweep, from the same page: every mirror fetches a tiny GitHub file and the dialog shows the
     * milliseconds it took. Its own job so the page can cancel it; the runtime keeps the rows that already answered.
     */
    fun probeGitHubMirrors() {
        if (mirrorProbeJob?.isActive == true) return
        mirrorProbeJob = scope.launch { runCatching { session.githubMirror.probe() } }
    }

    /** Stops a running sweep; the runtime settles on its idle state and the answered rows stay visible. */
    fun cancelGitHubMirrorProbe() {
        mirrorProbeJob?.cancel()
        mirrorProbeJob = null
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

    /**
     * Android's `saveSourceOrder`: the hand-picked sequence is stored and the sort order switches to it, so saving the
     * editor is also what makes the new order visible. Sources the rail cannot offer (disabled, uninstalled) are
     * dropped from the sequence, otherwise they would keep a rank nothing renders.
     */
    internal fun saveSourceOrder(names: List<String>) {
        val offered = sourceDescriptors().mapTo(HashSet()) { it.source.name }
        sourceSettings(
            mutableState.value.sourceSettings.copy(
                sort = org.skepsun.kototoro.desktop.runtime.DesktopSourceSort.CUSTOM,
                order = names.filter { it in offered }.distinct(),
            ),
        )
    }

    internal fun sourceSettings(settings: org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings) {
        mutableState.update { it.copy(sourceSettings = settings) }
        if (!backgroundPreferences.edit(SourcePreferenceEdit(changes = settings.toPreferences()))) {
            mutableState.update { it.copy(error = desktopText("settings.sourcesSaveFailed")) }
        }
    }
    /** Android's display options: the shelves render either the poster grid or one work per row. */
    internal fun libraryDisplay(display: DesktopLibraryDisplay) {
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

    internal fun googleDriveSignIn() = scope.launch {
        if (googleDrive.signIn()) startAutoDriveSync()
    }

    internal fun googleDriveSignOut() = scope.launch {
        autoDriveJob?.cancelAndJoin()
        if (googleDrive.disconnect()) {
            startAutoBackup()
            startAutoRestore()
        }
    }

    internal fun googleDriveSettings(
        enabled: Boolean = googleDriveUi.value.enabled,
        interval: Int = googleDriveUi.value.intervalMinutes,
        onStart: Boolean = googleDriveUi.value.syncOnStart,
    ) = scope.launch {
        gate.withLock {
            if (googleDrive.settings(enabled, interval, onStart)) {
                if (enabled) {
                    autoBackupJob?.cancelAndJoin()
                    autoRestoreJob?.cancelAndJoin()
                } else {
                    startAutoBackup()
                    startAutoRestore()
                }
                startAutoDriveSync()
            }
        }
    }

    internal fun googleDriveSyncNow() = scope.launch {
        if (googleDriveUi.value.syncing) return@launch
        if (googleDrive.sync()) refreshAfterDriveSync()
        startAutoDriveSync()
    }

    private suspend fun refreshAfterDriveSync() = gate.withLock {
        when (mutableState.value.screen) {
            DesktopScreen.LIBRARY, DesktopScreen.HISTORY -> reloadLibrarySnapshot()
            DesktopScreen.FEED, DesktopScreen.UPDATED -> loadFeedOnIo()
            DesktopScreen.HOME -> {
                val history = session.library.snapshot(history = true)
                loadFeedOnIo()
                mutableState.update { it.copy(homeHistory = history) }
            }
            else -> Unit
        }
    }

    private fun startAutoDriveSync() {
        autoDriveJob?.cancel()
        autoDriveJob = scope.launch {
            while (isActive) {
                val wait = googleDrive.nextSyncDelay() ?: return@launch
                if (wait > 0) { delay(wait); continue }
                if (googleDrive.sync()) refreshAfterDriveSync()
                // Also covers another manual run holding the sync lock.
                delay(60_000)
            }
        }
    }

    internal fun webDavSettings(value: DesktopWebDavSettings) {
        webDav.settings(value)
        // The interval lives in the settings, so a changed value must take effect without a restart.
        startAutoBackup()
        // Turning "auto restore on startup" on is a user asking for it now, not only on the next start.
        startAutoRestore()
    }

    /**
     * Android's auto restore runs from a foreground service started at boot, which Windows has no equivalent of, so
     * the check happens when the app opens the session. It is a one-shot job: the calendar-day guard lives in the
     * operations, and a failed attempt is allowed to be retried on the next start.
     */
    private fun startAutoRestore() {
        autoRestoreJob?.cancel()
        if (googleDriveUi.value.enabled) return
        autoRestoreJob = scope.launch {
            when (val result = webDav.autoRestore()) {
                is DesktopAutoRestore.Restored -> {
                    // The merge wrote rows straight into the library, so the same reload the manual restore uses.
                    mutableState.update {
                        it.copy(
                            items = emptyList(),
                            content = null,
                            message = desktopText("webdav.autoRestoredAndUploaded", result.name, result.merged),
                        )
                    }
                }
                // A failed check already left its message in `webDavUi.error`, where the page shows it.
                DesktopAutoRestore.Failed, DesktopAutoRestore.Disabled,
                DesktopAutoRestore.NotDue, DesktopAutoRestore.Nothing,
                -> Unit
            }
        }
    }

    /**
     * The timer Android's periodic worker replaces: sleep until the saved interval has passed, upload once, repeat.
     * A disabled timer simply ends the loop; saving settings starts a new one.
     */
    private fun startAutoBackup() {
        autoBackupJob?.cancel()
        if (googleDriveUi.value.enabled) return
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
        if (googleDriveUi.value.enabled) return@action
        webDav.probe()
        mutableState.update { it.copy(message = desktopText("webdav.reachable")) }
    }

    internal fun webDavRefresh() = action { if (!googleDriveUi.value.enabled) webDav.refresh() }

    internal fun webDavUpload() = action {
        if (googleDriveUi.value.enabled) return@action
        val result = webDav.upload()
        val uploaded = if (result.removed.isEmpty()) {
            desktopText("webdav.uploaded", result.name, result.size / 1024)
        } else {
            desktopText("webdav.uploadedTrimmed", result.name, result.size / 1024, result.removed.size)
        }
        // Android reports the kept copy separately, because the upload is what the button promised.
        val message = if (result.local != null) "$uploaded ${desktopText("webdav.localKept", result.local)}" else uploaded
        mutableState.update { it.copy(message = message) }
        // A manual upload moves the timer's baseline, so a sleeping loop must be recomputed against it.
        startAutoBackup()
    }

    /**
     * Android's `backup_periodical_directory`: the folder the local copies go in, `null` for the data root's `backups`.
     * Android refuses a tree it cannot write, so a path that is not a directory is rejected here too instead of being
     * saved and silently ignored by every backup.
     */
    internal fun webDavOutputDirectory(directory: Path?) {
        if (directory != null && !Files.isDirectory(directory)) {
            mutableState.update { it.copy(message = desktopText("webdav.outputInvalid", directory)) }
            return
        }
        webDavSettings(webDavUi.value.settings.copy(outputDirectory = directory?.toString().orEmpty()))
    }

    /** Android's periodical page points at its output directory; on Windows that is the folder the copies live in. */
    internal fun webDavOpenLocalDirectory() {
        val directory = webDavUi.value.localDirectory ?: session.storage.paths.backups
        runCatching {
            Files.createDirectories(directory)
            java.awt.Desktop.getDesktop().open(directory.toFile())
        }.onFailure {
            mutableState.update { state -> state.copy(message = desktopText("webdav.localOpenFailed", directory)) }
        }
    }

    /** The remote file is downloaded, then previewed through the same archive/merge flow a local file uses. */
    internal fun webDavRestore(file: WebDavBackupFile) = action {
        if (googleDriveUi.value.enabled) return@action
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
        if (googleDriveUi.value.enabled) return@action
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
        val settings = state.value.readerSettings
        val mode = resolvedReaderMode(settings, content.id, pages)
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
            readerSettings = settings.copy(mode = mode),
            readerTargetPage = index, readerTargetScroll = if (startAtEnd) Float.MAX_VALUE else restoredScroll,
            readerLastVisible = index, readerAtEnd = false, readerNavigation = it.readerNavigation + 1, readerScrollReady = false,
            readerVisiblePages = emptyList(), readerLoading = emptySet(), readerFailedPages = emptyMap(),
            bookmarks = session.library.bookmarks(content.id)) }
        val loaded = if (candidate.readerSettings.mode.isContinuous) {
            val image = session.readerImage(pages[index])
            candidate.copy(readerImages = mapOf(pages[index].id to image))
        } else loadPagedOnIo(candidate, index)
        mutableState.value = loaded
        prefetchAroundSlot(loaded)
    }

    /**
     * Android's `DetectReaderModeUseCase`: a mode remembered for this work wins; otherwise, while autodetect is on, the
     * page at 30% of the chapter decides whether the work is a webtoon. Android caches that verdict too, so a later
     * manual choice for the same work is what the next open uses.
     */
    private suspend fun resolvedReaderMode(
        settings: DesktopReaderSettings,
        contentId: Long,
        pages: List<SourcePage>,
    ): DesktopReaderMode {
        rememberedReaderMode(contentId)?.let { return it }
        val mode = settings.mode
        if (!settings.readerModeDetection || mode.isContinuous) return mode
        val probe = pages.getOrNull(ReaderModeDetection.probePageIndex(pages.size)) ?: return mode
        // The page is fetched exactly as the reader would fetch it, so the check costs one image and warms the index.
        val image = runCatching { session.readerImage(probe) }.getOrNull() ?: return mode
        val resolved = if (ReaderModeDetection.isWebtoonPage(image.width, image.height)) {
            DesktopReaderMode.CONTINUOUS
        } else {
            mode
        }
        rememberReaderMode(contentId, resolved)
        return resolved
    }

    private fun rememberedReaderMode(contentId: Long): DesktopReaderMode? {
        val key = contentId.toString() + "="
        val value = rememberedReaderModes().firstOrNull { it.startsWith(key) }?.substring(key.length) ?: return null
        return DesktopReaderMode.entries.firstOrNull { it.name == value }
    }

    private suspend fun rememberReaderMode(contentId: Long, mode: DesktopReaderMode) {
        val key = contentId.toString() + "="
        val modes = rememberedReaderModes().filterNot { it.startsWith(key) } + (key + mode.name)
        readerPreferences.edit(SourcePreferenceEdit(changes = mapOf(
            READER_MODES to SourcePreferenceValue.TextSet(modes.toSet()),
        )))
    }

    private fun rememberedReaderModes(): List<String> =
        (readerPreferences.snapshot()[READER_MODES] as? SourcePreferenceValue.TextSet)?.values?.sorted().orEmpty()

    fun page(index: Int) = action { pageOnIo(index) }

    fun toggleBookmark() = action {
        val snapshot = state.value
        if (snapshot.screen != DesktopScreen.READER) return@action
        val content = requireNotNull(snapshot.content)
        val chapter = requireNotNull(snapshot.chapter)
        if (snapshot.readerSettings.mode.isContinuous && !snapshot.readerScrollReady) return@action
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
     * Android's bookmarks destination (`BookmarksNavKey`). The shelf is loaded on entry, so a bookmark saved in the
     * reader or deleted on the details page is already reflected and no observer has to be torn down on exit.
     */
    fun showBookmarks() = navigate {
        mutableState.update {
            it.copy(screen = DesktopScreen.BOOKMARKS, bookmarkShelfSelection = emptySet(), content = null,
                detailsOrigin = null)
        }
        reloadBookmarkShelf()
    }

    private suspend fun reloadBookmarkShelf() {
        val groups = session.library.bookmarkGroups()
        val shelf = desktopBookmarkShelf(groups, state.value.sourceSettings.hidesNsfw(DesktopNsfwTarget.BOOKMARKS))
        mutableState.update { previous ->
            val ids = shelf.flatMapTo(HashSet()) { group -> group.bookmarks.map { it.pageId } }
            previous.copy(bookmarkGroups = shelf,
                // A card that was deleted elsewhere must not stay picked and reintroduce a missing id later.
                bookmarkShelfSelection = previous.bookmarkShelfSelection.intersect(ids))
        }
    }

    fun selectBookmarkShelf(pageId: Long) = mutableState.update { previous ->
        val selection = if (pageId in previous.bookmarkShelfSelection) {
            previous.bookmarkShelfSelection - pageId
        } else {
            previous.bookmarkShelfSelection + pageId
        }
        previous.copy(bookmarkShelfSelection = selection)
    }

    fun selectAllBookmarkShelf(selected: Boolean) = mutableState.update { previous ->
        previous.copy(bookmarkShelfSelection = if (selected) {
            previous.bookmarkGroups.flatMapTo(LinkedHashSet()) { group -> group.bookmarks.map { it.pageId } }
        } else {
            emptySet()
        })
    }

    /** Drops every picked card in one transaction and reports Android's `bookmarks_removed`. */
    fun removeSelectedBookmarkShelf() = action {
        val pageIds = state.value.bookmarkShelfSelection
        if (pageIds.isEmpty()) return@action
        val removed = session.library.removeBookmarks(pageIds)
        reloadBookmarkShelf()
        mutableState.update {
            it.copy(bookmarkShelfSelection = emptySet(), message = AndroidStrings["bookmarks_removed"])
        }
        if (removed <= 0) mutableState.update { it.copy(error = desktopText("error.bookmarkMissing")) }
    }

    /**
     * A saved position opens the reader at itself. Android's shelf builds the intent with `.incognito()` — revisiting a
     * saved position must not rewrite the work's progress — so Windows starts the same incognito session and says so.
     * The chapters come from the stored row, so a work whose source is gone still reopens here.
     */
    fun openShelfBookmark(group: DesktopBookmarkGroup, bookmark: DesktopBookmark) = navigate {
        require(bookmark.contentId == group.content.id) { desktopText("error.bookmarkMissing") }
        val content = session.library.find(group.content.id)
            ?: error(desktopText("error.workNotFound"))
        val chapter = content.chapters?.firstOrNull { it.id == bookmark.chapterId }
            ?: error(desktopText("error.bookmarkChapterMissing"))
        val favourite = session.library.isFavourite(content.id)
        val bookmarks = session.library.bookmarks(content.id)
        val progress = session.library.progress(content.id)
        mutableState.update {
            it.copy(content = content, isFavourite = favourite, bookmarks = bookmarks,
                detailsOrigin = DesktopScreen.BOOKMARKS, detailsChapterId = progress?.chapterId,
                detailsExpanded = false, relatedWorks = emptyList(), relatedLoading = false)
        }
        startReading(DesktopReadingRequest.Bookmark(chapter, bookmark), incognito = true)
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
        val continuousMode = snapshot.readerSettings.mode.isContinuous
        val boundary = if (continuousMode) snapshot.readerScrollReady &&
            (if (forward) snapshot.readerAtEnd else snapshot.pageIndex == 0 && snapshot.readerScroll == 0f)
            else readerLayout(snapshot).turnIndex(forward) == null
        val target = if (continuousMode) (snapshot.pageIndex + if (forward) 1 else -1).takeIf {
            !boundary && it in snapshot.pages.indices
        } else readerLayout(snapshot).turnIndex(forward)
        if (target != null) pageOnIo(target)
        // Android's reader always reads on into the adjacent chapter at a boundary; there is no switch for it.
        else if (boundary) {
            snapshot.adjacentChapter(forward)?.let { readOnIo(it, startAtEnd = !forward) }
        }
    }

    fun changeChapter(forward: Boolean) = action {
        state.value.adjacentChapter(forward)?.let { readOnIo(it) }
    }

    internal fun continuousChapter(forward: Boolean, chapterId: Long, navigation: Long) = action {
        val snapshot = state.value
        if (snapshot.screen != DesktopScreen.READER || snapshot.chapter?.id != chapterId ||
            snapshot.readerNavigation != navigation ||
            !snapshot.readerSettings.mode.isContinuous || !snapshot.readerScrollReady) return@action
        val boundary = if (forward) snapshot.readerAtEnd else snapshot.pageIndex == 0 && snapshot.readerScroll == 0f
        if (boundary) snapshot.adjacentChapter(forward)?.let { readOnIo(it, startAtEnd = !forward) }
    }

    fun reloadPage(): Job {
        val snapshot = state.value
        return if (snapshot.readerSettings.mode.isContinuous && snapshot.chapter != null) {
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
            "reader_chapter_title_bottom" to SourcePreferenceValue.Toggle(settings.chapterTitleAtBottom),
            "reader_reduce_offscreen_quality" to SourcePreferenceValue.Toggle(settings.reducePagePreloading),
            "vertical" to SourcePreferenceValue.Toggle(settings.vertical),
            "crop_paged" to SourcePreferenceValue.Toggle(settings.cropPaged),
            "crop_continuous" to SourcePreferenceValue.Toggle(settings.cropContinuous),
            "webtoon_gaps" to SourcePreferenceValue.Toggle(settings.webtoonGaps),
            "reader_continuous_horizontal_reversed" to
                SourcePreferenceValue.Toggle(settings.continuousHorizontalReversed),
            "reader_mode_detect" to SourcePreferenceValue.Toggle(settings.readerModeDetection),
            "as_speed" to SourcePreferenceValue.FloatBits(settings.autoScrollSpeed.toRawBits()),
            "double_cover" to SourcePreferenceValue.Toggle(settings.doublePageCover),
        ) + tapGridPreferences(settings.tapGrid)))
        session.upscale = settings.upscale
        session.cropPages = settings.cropActive
        // Android's ReaderViewModel.switchMode saves the mode for the work being read, so a manual choice sticks.
        if (state.value.screen == DesktopScreen.READER && previous.mode != settings.mode) {
            state.value.content?.let { rememberReaderMode(it.id, settings.mode) }
        }
        // Loaded pages were made for the previous super-resolution or crop choice; they are loaded (from cache) again.
        val reload = previous.upscale != settings.upscale || previous.cropActive != settings.cropActive
        if (reload) cancelPrefetch()
        mutableState.update { it.copy(readerSettings = settings,
            readerImages = if (reload) emptyMap() else it.readerImages) }
        if (state.value.screen == DesktopScreen.READER &&
            previous.copy(animation = settings.animation,
                colorFilter = settings.colorFilter, background = settings.background, pageNumbers = settings.pageNumbers,
                autoScrollSpeed = settings.autoScrollSpeed, tapGrid = settings.tapGrid) != settings) {
            pageOnIo(state.value.pageIndex)
        }
        // The preload window follows the switch at once, so turning it on drops the pages already queued around us
        // (the children of the old plan keep loading; the next window is the reduced one).
        if (state.value.screen == DesktopScreen.READER && previous.reducePagePreloading != settings.reducePagePreloading) {
            cancelPrefetch()
            prefetchAroundSlot(state.value)
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
            (snapshot["reader_chapter_title_bottom"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            (snapshot["reader_reduce_offscreen_quality"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            (snapshot["vertical"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            (snapshot["crop_paged"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            (snapshot["crop_continuous"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            (snapshot["webtoon_gaps"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            (snapshot["as_speed"] as? SourcePreferenceValue.FloatBits)?.bits?.let(Float::fromBits)?.coerceIn(0f, 1f)
                ?: org.skepsun.kototoro.reader.core.ReaderAutoScroll.DEFAULT_SPEED,
            (snapshot["double_cover"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            restoredTapGrid(snapshot),
            // Android's own key for the horizontal strip's reading direction.
            (snapshot["reader_continuous_horizontal_reversed"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            // Android's autodetect switch; AppSettings.isReaderModeDetectionEnabled defaults it to on.
            (snapshot["reader_mode_detect"] as? SourcePreferenceValue.Toggle)?.value ?: true,
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
        if (snapshot.readerSettings.mode.isContinuous) {
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
     * a turn, and a newer window re-plans the queue of the running worker. Android's "reduce page preloading" switch
     * keeps a single slot ahead instead and stops the next chapter from being fetched while it is still far away.
     */
    private fun prefetchAroundSlot(snapshot: DesktopAppState) {
        if (snapshot.screen != DesktopScreen.READER || snapshot.readerSettings.mode.isContinuous) return
        val layout = readerLayout(snapshot)
        val frame = layout.frame ?: return
        val reduce = snapshot.readerSettings.reducePagePreloading
        val window = org.skepsun.kototoro.reader.core.PagedSceneResourceWindowStrategy(lookaheadSlots = if (reduce) 1 else 2,
            prepareAdjacentSlots = !reduce).plan(org.skepsun.kototoro.reader.core.SceneResourceWindowRequest(layout.scene, frame))
        prefetchPages(snapshot.chapter?.id ?: return, window.requests.sortedBy { it.priority }
            .map { layout.scene.indexOf(it.pageId) }.filter { it >= 0 && it !in layout.indices })
        val slots = layout.scene.slotCount
        if (!reduce && layout.slotIndex >= slots - 1 - NEXT_CHAPTER_LOOKAHEAD) preloadNextChapter(snapshot)
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

    /**
     * Android's unified list opens a source's own settings from its row; 已安装 expands a package into its sources,
     * so those rows need the same destination without a detour through the browse rail.
     */
    fun sourceSettings(name: String) = navigate {
        val listing = state.value.sources.firstOrNull { it.source.name == name }
        val screen = session.sources.getPreferences(name)
        mutableState.update { it.copy(screen = DesktopScreen.PREFERENCES, preferences = screen,
                selectedSource = listing ?: it.selectedSource) }
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

    /** The header's back arrow on a settings sub-page: to the page it was opened from (see [settingsBackTag]). */
    fun settingsBack(): Job = when (state.value.screen) {
        DesktopScreen.PROXY_SETTINGS, DesktopScreen.DATA_CLEANUP -> backToStorage()
        DesktopScreen.REPLACE_RULES -> backFromReplaceRules()
        DesktopScreen.DICTIONARY_RULES -> backFromDictionaryRules()
        DesktopScreen.TAG_BLACKLIST, DesktopScreen.SETUP_WIZARD -> showSourceSettings()
        DesktopScreen.READING_STATS, DesktopScreen.RECOMMENDATIONS -> showServices()
        DesktopScreen.SOURCE_TEST -> showExtensions()
        else -> more()
    }

    /** Android's "captcha required" notification, tapped: verify [site] now, in the window if it has to be. */
    fun verifyBlockedSite(site: String) = action {
        session.verifySite(site)
        session.browserChallenges?.dismissBlocked(site)
        mutableState.update { it.copy(message = desktopText("challenge.verified")) }
    }

    fun more() = navigate {
        // The statistics screen's per-work sheet is an overlay of that screen, never of the "more" page.
        mutableState.update { it.copy(screen = DesktopScreen.MORE, contentStats = null) }
    }

    fun backToDetails() = action {
        closeCast(resume = false)
        mutableState.update { it.copy(screen = DesktopScreen.DETAILS,
            // A detected or remembered mode belongs to the work that was open; the next one starts from the preference.
            readerSettings = if (it.screen == DesktopScreen.READER) {
                it.readerSettings.copy(mode = restoredReaderSettings().mode)
            } else {
                it.readerSettings
            },
            detailsChapterId = when (it.screen) {
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
                if (challenges == null) block() else challenges.withInteraction(block)
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
                try { googleDrive.close(); webDav.close() } finally {
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
