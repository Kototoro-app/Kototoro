package org.skepsun.kototoro.reader.novel

import android.content.Intent
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import androidx.activity.viewModels
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.graphics.ColorUtils
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dagger.hilt.android.AndroidEntryPoint
import android.util.SparseArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.util.ext.isPageEbookChapter
import org.skepsun.kototoro.core.util.ext.isTextEbookChapter
import org.skepsun.kototoro.R
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.replace.ReplaceRuleRepository
import org.skepsun.kototoro.dictionary.DictionaryActivity
import org.skepsun.kototoro.core.ui.BaseComposeFullscreenActivity
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.resolveUiPresentationConfig
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.local.epub.buildEpubChapterUrl
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.util.ext.isAnimationsEnabled
import org.skepsun.kototoro.core.util.ext.isNightMode
import org.skepsun.kototoro.core.util.ext.performConfirmHapticFeedback
import org.skepsun.kototoro.core.util.ext.performRejectHapticFeedback
import org.skepsun.kototoro.core.util.ext.copyToClipboard
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

import org.skepsun.kototoro.readingrecord.data.ReadingRecordRepository
import org.skepsun.kototoro.reader.ui.ReaderControlDelegate
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.reader.ui.ReaderTvKeyDispatcher
import org.skepsun.kototoro.reader.ui.isReaderTvNavigationKey
import org.skepsun.kototoro.reader.ui.compose.EInkRefreshOverlay
import org.skepsun.kototoro.reader.ui.compose.ReaderEInkRefresh
import org.skepsun.kototoro.reader.ui.eink.EInkPageIdentity
import org.skepsun.kototoro.reader.ui.eink.EInkRefreshPolicy
import org.skepsun.kototoro.reader.novel.compose.NovelComposeReaderViewModel
import org.skepsun.kototoro.reader.novel.compose.NovelComposeImageContext
import org.skepsun.kototoro.reader.novel.compose.NovelComposeChapterContent
import org.skepsun.kototoro.reader.novel.compose.NovelReadingPosition
import org.skepsun.kototoro.reader.novel.compose.ComposeNovelReaderRoute
import org.skepsun.kototoro.reader.novel.compose.NovelReaderBottomChrome
import org.skepsun.kototoro.reader.novel.compose.NovelReaderChromeCallbacks
import org.skepsun.kototoro.reader.novel.compose.NovelReaderTopChrome
import org.skepsun.kototoro.reader.novel.compose.NovelTextSelection
import org.skepsun.kototoro.reader.novel.compose.NovelTextSelectionAction
import org.skepsun.kototoro.reader.novel.compose.NovelExcerptCardRenderer
import org.skepsun.kototoro.reader.novel.compose.NovelExcerptConfiguration
import org.skepsun.kototoro.reader.novel.compose.NovelExcerptData
import org.skepsun.kototoro.reader.novel.compose.NovelExcerptHelper
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingRepository
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingColor
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingStyle
import org.skepsun.kototoro.reader.novel.compose.findNovelTextRange
import org.skepsun.kototoro.reader.novel.compose.NovelNoteEditorSheet
import org.skepsun.kototoro.reader.novel.compose.NovelNoteDetailSheet
import org.skepsun.kototoro.reader.translate.domain.TranslationApiProviderCatalog
import org.skepsun.kototoro.reader.novel.compose.NovelTtsVoiceDialog
import org.skepsun.kototoro.reader.novel.compose.NovelTtsVoiceDialogState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.space.domain.SpaceProgressFlusher
import org.skepsun.kototoro.space.domain.SpaceSwitchAvailability
import org.skepsun.kototoro.space.domain.SpaceSwitchOrigin
import org.skepsun.kototoro.space.ui.SpaceSwitcherDelegate
import org.skepsun.kototoro.space.domain.awaitCompletion
import javax.inject.Inject
import java.io.File

/**
 * 小说阅读器 Activity。正文、阅读控件与 Space FAB 均由单一 Compose 根节点渲染。
 */
@AndroidEntryPoint
@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
class NovelReaderActivity :
    BaseComposeFullscreenActivity(),
    ReaderControlDelegate.OnInteractionListener {

    private val composeReaderViewModel: NovelComposeReaderViewModel by viewModels()
    private val snackbarHostState = SnackbarHostState()
    private val contentRoot: View
        get() = window.decorView
    private var composeNewContextMenuEnabledBeforeReader: Boolean? = null

    private fun disableNovelSystemTextToolbar() {
        if (composeNewContextMenuEnabledBeforeReader == null) {
            composeNewContextMenuEnabledBeforeReader = ComposeFoundationFlags.isNewContextMenuEnabled
        }
        ComposeFoundationFlags.isNewContextMenuEnabled = false
    }

    private fun restoreNovelSystemTextToolbar() {
        composeNewContextMenuEnabledBeforeReader?.let { wasEnabled ->
            ComposeFoundationFlags.isNewContextMenuEnabled = wasEnabled
            composeNewContextMenuEnabledBeforeReader = null
        }
    }

    @Inject
    lateinit var mangaRepositoryFactory: ContentRepository.Factory

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var tapGridSettings: org.skepsun.kototoro.reader.data.TapGridSettings

    @Inject
    lateinit var bookmarksRepository: org.skepsun.kototoro.bookmarks.domain.BookmarksRepository

    @Inject
    lateinit var historyRepository: org.skepsun.kototoro.history.data.HistoryRepository

    @Inject
    lateinit var historyUpdateUseCase: org.skepsun.kototoro.history.domain.HistoryUpdateUseCase

    @Inject
    lateinit var readingRecordRepository: ReadingRecordRepository

    @Inject
    lateinit var contentDataRepository: org.skepsun.kototoro.core.parser.ContentDataRepository

    @Inject
    lateinit var novelContentLoader: NovelContentLoader

    @Inject
    lateinit var novelTextProcessor: NovelTextProcessor

    @Inject
    lateinit var replaceRuleRepository: ReplaceRuleRepository

    @Inject
    lateinit var novelBookPreferences: NovelBookPreferences

    @Inject
    lateinit var novelMarkingRepository: NovelMarkingRepository

    @Inject
    lateinit var epubFileManager: org.skepsun.kototoro.local.epub.EpubFileManager

    @Inject
    lateinit var epubChapterMappingDao: org.skepsun.kototoro.core.db.dao.EpubChapterMappingDao

    @Inject
    lateinit var epubContentCache: org.skepsun.kototoro.local.epub.EpubContentCache

    @Inject
    lateinit var localContentRepository: org.skepsun.kototoro.local.data.LocalMangaRepository

    @Inject
    lateinit var translationProcessor: NovelTranslationProcessor

    @Inject
    lateinit var spaceSwitcherDelegate: SpaceSwitcherDelegate

    private lateinit var manga: Content
    private lateinit var repository: ContentRepository
    private var originalContent: Content? = null  // Store original for online fallback
    private lateinit var readerSettings: NovelReaderSettings
    private lateinit var epubInternalChapterLoader: EpubInternalChapterLoader

    private var chapters: List<ContentChapter> = emptyList()
    private var displayChapters: List<ContentChapter> = emptyList()
    private var replaceRulesEnabled: Boolean = true
    private var disabledReplaceRuleIds: Set<Long> = emptySet()
    private var currentChapterIndex: Int = 0
    private var chapterLoadJob: Job? = null
    private var preloadJob: Job? = null
    private var bookmarkObservationJob: Job? = null
    private var isUiVisible: Boolean = false
    private var currentPageIndex: Int = 0
    private var desiredProgressRatio: Float? = null
    private var pendingTtsAutoStart: Boolean = false
    private var isHandlingTtsCompletion: Boolean = false  // Guard against re-entrant handleTtsPageCompleted
    private var sessionStartAt: Long = 0L
    private var sessionStartState: ReaderState? = null
    private var sessionStartPercent: Float = 0f

    // Continuous Scroll mode properties
    private var lastContinuousTapHandledTime = 0L
    private var imageHeadersProvider: ((String) -> Map<String, String>?)? = null
    private var lastContinuousTapSource = ""
    private var isLoadingPrevious = false
    private var isLoadingNext = false

    // Translation state
    private var translationJob: Job? = null
    private val chapterTranslations = SparseArray<NovelChapterTranslation>()

    override val readerMode: ReaderMode?
        get() = ReaderMode.STANDARD

    override val isTvPresentation: Boolean
        get() = tvPresentationEnabled

    override val isReaderControlsVisible: Boolean
        get() = isUiVisible

    private var ttsService: org.skepsun.kototoro.reader.novel.tts.TtsService? = null
    private var isTtsBound = false
    private var ttsScrollModeChapterIndex: Int = -1
    private var readerPalette: NovelReaderPalette? = null
    private var ttsVoiceDialogState by mutableStateOf<NovelTtsVoiceDialogState?>(null)
    private val eInkRefreshPolicy = EInkRefreshPolicy()
    private var lastEInkPageIdentity: EInkPageIdentity? = null
    private var eInkRefresh by mutableStateOf<ReaderEInkRefresh?>(null)
    private var isEInkModeEnabled by mutableStateOf(false)
    private var tvPresentationEnabled = false
    private val tvKeyDispatcher = ReaderTvKeyDispatcher()
    private val controlDelegate by lazy {
        ReaderControlDelegate(resources, settings, tapGridSettings, this)
    }
    private var nextEInkRefreshId = 0L
    private var novelMarkingObservationJob: Job? = null
    private var novelBookmarkObservationJob: Job? = null
    private var noteSelection: NovelTextSelection? by mutableStateOf(null)
    private var noteMarking: NovelMarkingEntity? by mutableStateOf(null)
    private var noteDraft by mutableStateOf("")
    private var noteDetailMarking: NovelMarkingEntity? by mutableStateOf(null)
    private var novelExcerpt: NovelExcerptData? by mutableStateOf(null)
    private var activeMarkingColor by mutableStateOf(NovelMarkingColor.YELLOW.id)
    private var activeMarkingStyle by mutableStateOf(NovelMarkingStyle.UNDERLINE.id)
    private var novelAiJob: Job? = null
    private var novelAiQuestion by mutableStateOf("")
    private var novelAiAnswer by mutableStateOf<String?>(null)
    private var novelAiAskTarget by mutableStateOf<NovelAiAskTarget?>(null)
    private var novelAiAskLoading by mutableStateOf(false)
    private var novelAiRequestId = 0L

    private val ttsConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as org.skepsun.kototoro.reader.novel.tts.TtsService.TtsBinder
            ttsService = binder.getService()
            isTtsBound = true

            lifecycleScope.launch {
                ttsService?.getState()?.collect { state ->
                    composeReaderViewModel.publishTtsState(state)

                    if (state == org.skepsun.kototoro.reader.novel.tts.TtsState.PLAYING) {
                        // TODO string sync highlighting
                    } else if (state == org.skepsun.kototoro.reader.novel.tts.TtsState.IDLE) {
                        composeReaderViewModel.publishTtsHighlight(null)
                    }

                    // 当当前页朗读完成时，自动翻页并继续朗读
                    if (state == org.skepsun.kototoro.reader.novel.tts.TtsState.COMPLETED) {
                        // Guard: skip if we're already handling a completion event
                        if (!isHandlingTtsCompletion) {
                            handleTtsPageCompleted()
                        }
                    }
                }
            }

            lifecycleScope.launch {
                ttsService?.getPlayingTokenIndex()?.collectLatest { index ->
                    val range = index?.let { ttsService?.getToken(it)?.range }
                    composeReaderViewModel.publishTtsHighlight(range)
                }
            }
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            isTtsBound = false
            ttsService = null
        }
    }

    override fun onStart() {
        disableNovelSystemTextToolbar()
        super.onStart()
        val intent = Intent(this, org.skepsun.kototoro.reader.novel.tts.TtsService::class.java)
        bindService(intent, ttsConnection, android.content.Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        tvKeyDispatcher.reset()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        finishReadingSession()
        if (isFinishing) {
            ttsService?.stopTts()
        }
        if (isTtsBound) {
            unbindService(ttsConnection)
            isTtsBound = false
        }
        restoreNovelSystemTextToolbar()
    }

    override fun onDestroy() {
        restoreNovelSystemTextToolbar()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tvPresentationEnabled = resolveUiPresentationConfig(this, settings).isTv

        // Compose 1.12 enables the new Android text context menu by default. It bypasses
        // LocalTextToolbar, so the reader's own selection actions would otherwise be shown
        // together with the system action bar.
        disableNovelSystemTextToolbar()

        readerSettings = NovelReaderSettings.load(this).copy(isTranslationEnabled = false)
        settings.observeAsFlow(AppSettings.KEY_READER_CHAPTER_TITLE_BOTTOM) {
            isReaderChapterTitleAtBottom
        }.onEach { enabled ->
            if (readerSettings.chapterTitleAtBottom != enabled) {
                readerSettings = readerSettings.copy(chapterTitleAtBottom = enabled)
                composeReaderViewModel.publishSettings(readerSettings)
            }
        }.launchIn(lifecycleScope)
        isEInkModeEnabled = settings.isEInkModeEnabled
        settings.observeAsFlow(AppSettings.KEY_EINK_MODE) { isEInkModeEnabled }
            .onEach { enabled ->
                isEInkModeEnabled = enabled
                if (!enabled) resetEInkRefreshContext(clearIdentity = false)
            }
            .launchIn(lifecycleScope)
        settings.observeAsFlow(AppSettings.KEY_EINK_REFRESH) { isEInkRefreshEnabled }
            .onEach { enabled ->
                if (!enabled) resetEInkRefreshContext(clearIdentity = false)
            }
            .launchIn(lifecycleScope)

        val markingPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        activeMarkingColor = markingPrefs.getInt("novel_active_marking_color", NovelMarkingColor.YELLOW.id)
        activeMarkingStyle = markingPrefs.getInt("novel_active_marking_style", NovelMarkingStyle.UNDERLINE.id)
        composeReaderViewModel.publishActiveMarkingStyle(activeMarkingColor, activeMarkingStyle)

        // 只恢复UI状态，不恢复章节和页码（由loadChapters处理）
        savedInstanceState?.let {
            isUiVisible = it.getBoolean(KEY_UI_VISIBLE, true)
        }

        val parcelable = intent.getParcelableExtraCompat<ParcelableContent>(AppRouter.KEY_MANGA)
        val mangaSeed = runBlocking {
            contentDataRepository.resolveIntent(ContentIntent(intent), withChapters = true)
        } ?: parcelable?.manga
        if (mangaSeed == null) {
            finish()
            return
        }

        // Save original manga for online fallback（若当前是本地 URI，尝试从 index.json 恢复对应的远端信息以获得原始 URL）
        val isContentSeedLocalUrl = mangaSeed.url.let { it.startsWith("file://") || it.startsWith("zip://") || it.startsWith("cbz://") || it.startsWith("local://") }
        val maybeRemote = runCatching {
            runBlocking {
                if (isContentSeedLocalUrl) localContentRepository.getRemoteContent(mangaSeed) else null
            }
        }.getOrNull()
        originalContent = maybeRemote ?: mangaSeed

        val local = runCatching {
            runBlocking {
                localContentRepository.findSavedContent(mangaSeed, withDetails = true)
            }
        }.getOrNull()
        manga = local?.manga ?: mangaSeed

        // 如果是从历史记录进入（可能 URL 是 local 但 source 已修正）或者来源是 Unknown，
        // 尝试修正为原始来源以支持在线跳转，并确保有远程 URL 可用
        if ((manga.source.name.startsWith("LOCAL") || manga.source == org.skepsun.kototoro.core.model.UnknownContentSource)
            && originalContent != null) {
            manga = manga.copy(source = originalContent!!.source, url = originalContent!!.url)
            android.util.Log.d("NovelReaderActivity", "Fixed manga source to ${manga.source.name} and URL to ${manga.url}")
        }
        if (local != null && (manga.chapters.isNullOrEmpty())) {
            // 某些情况下索引未带章节，兜底从本地解析一遍
            runCatching {
                manga = runBlocking { localContentRepository.getDetails(manga) }
                android.util.Log.d(
                    "NovelReaderActivity",
                    "Refetched local details, chapters=${manga.chapters?.size ?: 0}",
                )
            }.onFailure {
                android.util.Log.w("NovelReaderActivity", "Failed to refetch local details", it)
            }
            // 再次兜底：直接用 LocalContentParser 解析目录/CBZ
            if (manga.chapters.isNullOrEmpty()) {
                runCatching {
                    val parser = org.skepsun.kototoro.local.data.input.LocalContentParser.getOrNull(
                        java.io.File(java.net.URI(manga.url))
                    )
                    if (parser != null) {
                        manga = runBlocking { parser.getContent(withDetails = true).manga }
                        android.util.Log.d(
                            "NovelReaderActivity",
                            "Parsed chapters via LocalContentParser fallback, count=${manga.chapters?.size ?: 0}",
                        )
                    }
                }.onFailure {
                    android.util.Log.w("NovelReaderActivity", "Fallback parse failed", it)
                }
            }
        }

        replaceRulesEnabled = novelBookPreferences.areReplaceRulesEnabled(manga)
        disabledReplaceRuleIds = novelBookPreferences.getDisabledReplaceRuleIds(manga)
        novelMarkingObservationJob?.cancel()
        novelMarkingObservationJob = novelMarkingRepository.observe(manga.id)
            .onEach(composeReaderViewModel::publishNovelMarkings)
            .launchIn(lifecycleScope)
        novelBookmarkObservationJob?.cancel()
        novelBookmarkObservationJob = bookmarksRepository.observeBookmarks(manga)
            .onEach(composeReaderViewModel::publishNovelBookmarks)
            .launchIn(lifecycleScope)
        composeReaderViewModel.publishReplaceRulesEnabled(replaceRulesEnabled)
        composeReaderViewModel.publishReplaceRules(
            rules = emptyList(),
            origin = manga.source.name,
            disabledRuleIds = disabledReplaceRuleIds,
        )

        // 进入当前小说时显式清空上一轮阅读会话中的翻译状态，
        // 但不清理长期翻译缓存。
        resetTranslationSession()

        repository = mangaRepositoryFactory.create(manga.source)
        if (local != null) {
            android.util.Log.d("NovelReaderActivity", "Using local manga for reading: ${manga.title}")
        }
        epubInternalChapterLoader = EpubInternalChapterLoader(
            context = this,
            epubFileManager = epubFileManager,
            epubChapterMappingDao = epubChapterMappingDao,
            epubContentCache = epubContentCache
        )

        android.util.Log.d("NovelReaderActivity", "=== onCreate ===")
        android.util.Log.d("NovelReaderActivity", "Content: id=${manga.id}, title=${manga.title}")
        android.util.Log.d("NovelReaderActivity", "Content has chapters: ${manga.chapters != null}, count: ${manga.chapters?.size ?: 0}")
        android.util.Log.d("NovelReaderActivity", "Repository type: ${repository.javaClass.simpleName}")

        spaceSwitcherDelegate.bind(
            activity = this,
            snackbarAnchor = contentRoot,
            origin = SpaceSwitchOrigin.NOVEL_READER,
            availabilityProvider = { SpaceSwitchAvailability.SAVE_AND_SWITCH },
            progressFlusher = SpaceProgressFlusher { flushForSpaceSwitch() },
        )
        spaceSwitcherDelegate.setControlsVisible(isUiVisible)

        // 设置标题为小说名称
        title = manga.title
        supportActionBar?.title = manga.title
        observeReplaceRules()
        setupImageHeaders()
        setupComposeContent()

        applyReaderPalette()
        applyReaderBrightness(readerSettings)

        applyReadingModeToggles()

        updateDualPageMode()
        updateFullscreenMode()
        updateReadingStatusVisibility()

        applyInitialUiVisibility()

        loadChapters()
    }

    private fun setupImageHeaders() {
        imageHeadersProvider = { imageUrl ->
            val source = manga.source
            if (source.name == "BILINOVEL") {
                mapOf(
                    "Referer" to "https://www.bilinovel.com/",
                    "Origin" to "https://www.bilinovel.com",
                    "Accept-Encoding" to "identity",
                )
            } else if (source is org.skepsun.kototoro.core.jsonsource.JsonContentSource) {
                // Extract headers from Legado JSON source config
                val headers = mutableMapOf<String, String>()

                try {
                    val config = kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }.decodeFromString<org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource>(source.entity.config)

                    // Parse header from source config
                    val headerStr = config.header
                    if (!headerStr.isNullOrBlank()) {
                        try {
                            val headerJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                                .decodeFromString<Map<String, String>>(headerStr)
                            headers.putAll(headerJson)
                        } catch (e: Exception) {
                            android.util.Log.w("NovelReaderActivity", "Failed to parse source headers: ${e.message}")
                        }
                    }

                    // Add Referer based on source URL if not already present
                    if (!headers.containsKey("Referer") && !headers.containsKey("referer")) {
                        val sourceUrl = config.bookSourceUrl
                        if (!sourceUrl.isNullOrBlank()) {
                            headers["Referer"] = sourceUrl
                        }
                    }

                    // Add User-Agent if not present
                    if (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent")) {
                        headers["User-Agent"] = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                    }

                    android.util.Log.d("NovelReaderActivity", "Image headers for $imageUrl: $headers")
                } catch (e: Exception) {
                    android.util.Log.w("NovelReaderActivity", "Failed to setup image headers: ${e.message}")
                }

                headers.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
    }

    private fun setupComposeContent() {
        val callbacks = NovelReaderChromeCallbacks(
            onNavigateBack = onBackPressedDispatcher::onBackPressed,
            onProgressSelected = ::switchPageTo,
            onPreviousChapter = { switchChapterBy(-1) },
            onNextChapter = { switchChapterBy(1) },
            onSettingsChanged = {
                composeReaderViewModel.publishSettings(it)
                applyNovelReaderSettings(it)
            },
            onChapterSelected = { index ->
                composeReaderViewModel.dismissChapters()
                onChapterSelected(index)
            },
            onSearchResultSelected = ::jumpToNovelSearchResult,
            onDismissSettings = composeReaderViewModel::dismissSettings,
            onDismissChapters = composeReaderViewModel::dismissChapters,
            onDismissTools = composeReaderViewModel::dismissTools,
            onShowSettings = { composeReaderViewModel.showSettings(readerSettings) },
            onShowChapters = ::showChaptersSheet,
            onShowReplaceRules = composeReaderViewModel::showReplaceRules,
            onShowMarkings = composeReaderViewModel::showMarkings,
            onToggleTranslation = ::toggleTranslation,
            onToggleReplaceRules = ::toggleReplaceRules,
            onDismissReplaceRules = composeReaderViewModel::dismissReplaceRules,
            onDismissMarkings = composeReaderViewModel::dismissMarkings,
            onReplaceRuleToggle = ::toggleReplaceRule,
            onEditMarkingNote = ::editNovelMarkingNote,
            onDeleteMarking = ::deleteNovelMarking,
            onJumpToMarking = ::jumpToNovelMarking,
            onOpenBookmark = ::openNovelBookmark,
            onDeleteBookmark = ::deleteNovelBookmark,
            onBookmark = ::onBookmarkClick,
            onTts = ::onTtsClick,
            onClearTranslationCache = ::onClearTranslationCacheClick,
            onTtsPrevious = { ttsService?.seekPrev() },
            onTtsPlayPause = ::onTtsPlayPauseClicked,
            onTtsNext = { ttsService?.seekNext() },
            onTtsVoice = ::showVoiceSelectionDialog,
            onTtsClose = {
                onTtsStopClicked()
                composeReaderViewModel.hideTtsControls()
            },
        )
        composeReaderViewModel.publishChrome(
            controlsVisible = isUiVisible,
            workTitle = manga.title,
        )
        setFullscreenComposeContent {
            KototoroTheme {
                val presentationConfig = LocalUiPresentationConfig.current
                val state by composeReaderViewModel.uiState.collectAsStateWithLifecycle()
                val floatingControls by settings.observeAsState(AppSettings.KEY_NOVEL_READER_CONTROLS) {
                    novelReaderControls
                }
                val showFloatingControlLabels by settings.observeAsState(AppSettings.KEY_READER_CONTROL_LABELS) {
                    isReaderControlLabelsEnabled
                }
                val readerBackdrop = if (LocalInterfaceStyle.current == InterfaceStyle.IOS && !isEInkModeEnabled) {
                    rememberLayerBackdrop { drawContent() }
                } else {
                    null
                }
                CompositionLocalProvider(
                    LocalLiquidGlassBackdrop provides readerBackdrop,
                    LocalLiquidGlassLayerBackdrop provides readerBackdrop,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                                ),
                        ) {
                        ComposeNovelReaderRoute(
                            viewModel = composeReaderViewModel,
                            imageModel = { it },
                            animationsEnabled = !isEInkModeEnabled,
                            onSettingsChanged = ::applyNovelReaderSettings,
                            onToggleTranslation = ::toggleTranslation,
                            onToggleReplaceRules = ::toggleReplaceRules,
                            onShowReplaceRules = composeReaderViewModel::showReplaceRules,
                            onShowMarkings = composeReaderViewModel::showMarkings,
                            onDismissReplaceRules = composeReaderViewModel::dismissReplaceRules,
                            onReplaceRuleToggle = ::toggleReplaceRule,
                            onDismissMarkings = composeReaderViewModel::dismissMarkings,
                            onEditMarkingNote = ::editNovelMarkingNote,
                            onDeleteMarking = ::deleteNovelMarking,
                            onBookmark = ::onBookmarkClick,
                            onTts = ::onTtsClick,
                            onClearTranslationCache = ::onClearTranslationCacheClick,
                            onChapterSelected = ::onChapterSelected,
                            onSearchResultSelected = ::jumpToNovelSearchResult,
                            onTtsPrevious = { ttsService?.seekPrev() },
                            onTtsPlayPause = ::onTtsPlayPauseClicked,
                            onTtsNext = { ttsService?.seekNext() },
                            onTtsVoice = ::showVoiceSelectionDialog,
                            onTtsClose = {
                                onTtsStopClicked()
                                composeReaderViewModel.hideTtsControls()
                            },
                            onTextSelectionChanged = composeReaderViewModel::publishTextSelection,
                            onTextSelectionAction = ::onNovelTextSelectionAction,
                            onSelectionColorChange = ::onNovelSelectionColorChange,
                            onSelectionStyleChange = ::onNovelSelectionStyleChange,
                            onMarkingClick = ::onNovelMarkingClick,
                            onMarkingAction = ::onNovelMarkingAction,
                            onMarkingChangeStyle = ::onNovelMarkingChangeStyle,
                            onOpenMarkingDetail = { noteDetailMarking = it },
                            onJumpToMarking = ::jumpToNovelMarking,
                            onDeleteBookmark = ::deleteNovelBookmark,
                            onOpenBookmark = ::openNovelBookmark,
                            excerpt = novelExcerpt,
                            onDismissExcerpt = { novelExcerpt = null },
                            onSaveExcerpt = ::saveNovelExcerpt,
                            onShareExcerpt = ::shareNovelExcerpt,
                            onRequestPreviousChapter = ::requestPreviousComposeChapter,
                            onRequestNextChapter = ::requestNextComposeChapter,
                            onVisibleChapterChanged = ::onComposeVisibleChapterChanged,
                            onVisibleProgress = ::onComposeVisibleProgress,
                            onPagedPositionChanged = { chapterId, page, pageCount ->
                                currentPageIndex = page
                                updateReadingStatus(page, pageCount)
                                observeCurrentPageBookmark(chapterId, page)
                                onEInkPagedPositionChanged(chapterId, page)
                            },
                            renderContent = true,
                            onImageClick = { path ->
                                val image = composeReaderViewModel.uiState.value.imageContext
                                openInlineImage(
                                    NovelInlineImageRequest(
                                        imagePath = path,
                                        epubFilePath = image.epubFilePath,
                                        chapterPath = image.chapterPath,
                                        headers = image.headers,
                                    ),
                                )
                            },
                            onTap = { x, y, viewport ->
                                val readerState = composeReaderViewModel.uiState.value
                                val panelVisible =
                                    readerState.settingsSheetVisible ||
                                        readerState.replaceRulesSheetVisible ||
                                        readerState.markingsSheetVisible ||
                                        readerState.chaptersSheetVisible ||
                                        readerState.toolsSheetVisible ||
                                        readerState.ttsControlsVisible
                                if (panelVisible) {
                                    composeReaderViewModel.dismissControlPanels()
                                    setUiVisible(false)
                                } else {
                                    handleContinuousTap(
                                        x = x,
                                        y = y,
                                        width = viewport.width,
                                        height = viewport.height,
                                        source = "compose",
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .then(readerBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
                        )
                        Box(modifier = Modifier.align(Alignment.TopCenter)) {
                            NovelReaderTopChrome(
                                state = state,
                                callbacks = callbacks,
                                animationsEnabled = !isEInkModeEnabled,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter),
                        ) {
                            NovelReaderBottomChrome(
                                state = state,
                                callbacks = callbacks,
                                controls = floatingControls,
                                showFloatingControlLabels = showFloatingControlLabels || presentationConfig.isTv,
                                animationsEnabled = !isEInkModeEnabled,
                            )
                        }

                        ttsVoiceDialogState?.let { NovelTtsVoiceDialog(it) }
                        noteSelection?.let { selection ->
                            NovelNoteEditorSheet(
                                title = getString(R.string.novel_selection_note_title),
                                quoteText = selection.text,
                                initialNote = noteDraft,
                                onDismiss = {
                                    noteSelection = null
                                    noteDraft = ""
                                },
                                onSave = { savedNote ->
                                    saveNovelSelectionNote(selection, savedNote)
                                    noteSelection = null
                                    noteDraft = ""
                                },
                            )
                        }
                        noteMarking?.let { marking ->
                            NovelNoteEditorSheet(
                                title = getString(
                                    if (marking.note.isNullOrBlank()) {
                                        R.string.novel_selection_note_title
                                    } else {
                                        R.string.novel_marking_edit_note_title
                                    },
                                ),
                                quoteText = marking.selectedText,
                                initialNote = noteDraft,
                                onDismiss = {
                                    noteMarking = null
                                    noteDraft = ""
                                },
                                onSave = { savedNote ->
                                    saveNovelMarkingNote(marking, savedNote.takeIf { it.isNotBlank() })
                                    noteMarking = null
                                    noteDraft = ""
                                },
                            )
                        }
                        noteDetailMarking?.let { marking ->
                            NovelNoteDetailSheet(
                                marking = marking,
                                onDismiss = { noteDetailMarking = null },
                                onEdit = {
                                    val target = marking
                                    noteDetailMarking = null
                                    editNovelMarkingNote(target)
                                },
                                onExcerpt = {
                                    val target = marking
                                    noteDetailMarking = null
                                    showNovelExcerpt(target.selectedText, target.note, target.chapterIndex)
                                },
                                onJumpToText = {
                                    val target = marking
                                    noteDetailMarking = null
                                    jumpToNovelMarking(target)
                                },
                                onDelete = {
                                    val target = marking
                                    noteDetailMarking = null
                                    deleteNovelMarking(target)
                                },
                            )
                        }
                        novelAiAskTarget?.let { target ->
                            AlertDialog(
                                onDismissRequest = ::dismissNovelAiAsk,
                                title = { Text(getString(R.string.novel_ai_ask_title)) },
                                text = {
                                    Column(
                                        modifier = Modifier.verticalScroll(rememberScrollState()),
                                    ) {
                                        Text(
                                            text = getString(R.string.novel_ai_ask_selected_label),
                                            modifier = Modifier.padding(bottom = 4.dp),
                                        )
                                        Text(
                                            text = target.excerpt,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                        if (target.chapterTitle.isNotBlank()) {
                                            Text(
                                                text = target.chapterTitle,
                                                modifier = Modifier.padding(bottom = 8.dp),
                                            )
                                        }
                                        Text(
                                            text = getString(R.string.novel_ai_ask_context_label),
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                        Text(
                                            text = getString(R.string.novel_ai_ask_suggestions),
                                            modifier = Modifier.padding(bottom = 4.dp),
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        ) {
                                            novelAiQuestionPresets.forEach { preset ->
                                                AssistChip(
                                                    onClick = {
                                                        novelAiQuestion = getString(preset.questionRes)
                                                    },
                                                    enabled = !novelAiAskLoading,
                                                    label = { Text(getString(preset.questionRes)) },
                                                )
                                            }
                                        }
                                        TextField(
                                            value = novelAiQuestion,
                                            onValueChange = { novelAiQuestion = it },
                                            placeholder = {
                                                Text(getString(R.string.novel_ai_ask_hint))
                                            },
                                            enabled = !novelAiAskLoading,
                                            minLines = 2,
                                            maxLines = 5,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        if (novelAiAskLoading) {
                                            Text(
                                                text = getString(R.string.novel_ai_ask_loading),
                                                modifier = Modifier.padding(top = 12.dp),
                                            )
                                        }
                                        novelAiAnswer?.let { answer ->
                                            Text(
                                                text = answer,
                                                modifier = Modifier.padding(top = 12.dp),
                                            )
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = novelAiQuestion.isNotBlank() && !novelAiAskLoading,
                                        onClick = { askNovelAi(target) },
                                    ) {
                                        Text(getString(R.string.novel_ai_ask_send))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = ::dismissNovelAiAsk) {
                                        Text(getString(R.string.cancel))
                                    }
                                },
                            )
                        }
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(16.dp),
                        )

                        }
                        EInkRefreshOverlay(eInkRefresh) { consumedId ->
                            if (eInkRefresh?.id == consumedId) eInkRefresh = null
                        }
                    }
                }
            }
        }
    }
    private fun openInlineImage(image: NovelInlineImageRequest) {
        val router = AppRouter(this)
        val canUseStandardViewer = image.epubFilePath.isNullOrBlank() &&
            image.headers.isEmpty() &&
            (
                image.imagePath.startsWith("http://", ignoreCase = true) ||
                    image.imagePath.startsWith("https://", ignoreCase = true) ||
                    image.imagePath.startsWith("file://", ignoreCase = true) ||
                    image.imagePath.startsWith("content://", ignoreCase = true)
                )
        if (canUseStandardViewer) {
            router.openImage(image.imagePath, manga.source)
        } else {
            router.openNovelInlineImage(
                imagePath = image.imagePath,
                source = manga.source,
                epubFilePath = image.epubFilePath,
                chapterPath = image.chapterPath,
                headers = image.headers,
            )
        }
    }

    override fun getParentActivityIntent(): Intent? {
        return AppRouter.detailsIntent(this, manga)
    }

    override fun switchPageBy(delta: Int) {
        if (readerSettings.readingMode == ReadingMode.SCROLL) {
            composeReaderViewModel.requestScrollByPage(delta)
            return
        }
        val position = composeReaderViewModel.uiState.value.position
        val targetPage = (position?.page ?: currentPageIndex) + delta
        val pageCount = position?.pageCount ?: 0
        when {
            targetPage in 0 until pageCount -> composeReaderViewModel.requestPage(targetPage)
            delta > 0 -> switchChapterBy(1)
            delta < 0 -> switchChapterBy(-1)
        }
    }

    override fun switchChapterBy(delta: Int) {
        val targetIndex = currentChapterIndex + delta
        if (targetIndex in chapters.indices) {
            val previousState = currentReaderState()
            currentChapterIndex = targetIndex
            // 如果是向下一章，从第一页开始；如果是向上一章，从最后一页开始
            currentPageIndex = if (delta > 0) 0 else -1  // -1 表示最后一页
            // 切章时取消旧的翻译任务
            translationJob?.cancel()
            translationJob = null
            val targetChapter = chapters.getOrNull(currentChapterIndex)
            if (previousState != null && targetChapter != null) {
                recordJumpPointIfNeeded(previousState, ReaderState(targetChapter.id, 0, 0), "chapter")
            }
            loadChapter(currentChapterIndex)
        } else {
            // 已经是第一章或最后一章
            contentRoot.performRejectHapticFeedback()
            val message = if (delta > 0) {
                getString(R.string.novel_last_chapter)
            } else {
                getString(R.string.novel_first_chapter)
            }
            showReaderMessage(message, 1500L)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            val uiState = composeReaderViewModel.uiState.value
            val hasSubOverlay = uiState.settingsSheetVisible ||
                uiState.replaceRulesSheetVisible ||
                uiState.markingsSheetVisible ||
                uiState.chaptersSheetVisible ||
                uiState.toolsSheetVisible ||
                uiState.ttsControlsVisible ||
                ttsVoiceDialogState != null ||
                noteSelection != null ||
                noteMarking != null ||
                noteDetailMarking != null
            if (shouldInterceptNovelReaderTvBack(isTvPresentation, isUiVisible, hasSubOverlay)) {
                if (event.action == KeyEvent.ACTION_UP) {
                    setUiVisible(false)
                }
                return true
            }
        }

        when (
            tvKeyDispatcher.dispatch(
                keyCode = event.keyCode,
                action = event.action,
                isTvPresentation = isTvPresentation,
                controlsVisible = isReaderControlsVisible,
                repeatCount = event.repeatCount,
            )
        ) {
            ReaderTvKeyDispatcher.Action.DISPATCH_TO_READER -> {
                controlDelegate.onKeyDown(event.keyCode, event)
                return true
            }
            ReaderTvKeyDispatcher.Action.CONSUME -> return true
            ReaderTvKeyDispatcher.Action.DELEGATE -> Unit
        }

        if (isTvPresentation && !isUiVisible) {
            val delta = resolveNovelReaderPageKeyDelta(event.keyCode, settings.isReaderNavigationInverted)
            if (delta != null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    switchPageBy(delta)
                }
                return true
            }
        }

        if (
            settings.isReaderVolumeButtonsEnabled &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    val delta = when (event.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> if (settings.isReaderNavigationInverted) 1 else -1
                        else -> if (settings.isReaderNavigationInverted) -1 else 1
                    }
                    switchPageBy(delta)
                    true
                }
                KeyEvent.ACTION_UP -> true
                else -> true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isTvPresentation && isReaderTvNavigationKey(keyCode)) {
            return super.onKeyDown(keyCode, event)
        }
        return controlDelegate.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return controlDelegate.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    override fun openMenu() {
        showConfigSheet()
    }

    override fun onPagesButtonClick(): Boolean {
        showChaptersSheet()
        return true
    }

    override fun onTranslateClick() {
        toggleTranslation()
    }

    private fun toggleTranslation() {
        val enabled = !readerSettings.isTranslationEnabled
        readerSettings = readerSettings.copy(isTranslationEnabled = enabled)
        readerSettings.save(this)
        composeReaderViewModel.publishSettings(readerSettings)
        if (enabled) {
            startTranslation()
        } else {
            clearTranslation()
        }
    }

    private fun toggleReplaceRules() {
        replaceRulesEnabled = !replaceRulesEnabled
        novelBookPreferences.setReplaceRulesEnabled(manga, replaceRulesEnabled)
        composeReaderViewModel.publishReplaceRulesEnabled(replaceRulesEnabled)
        showReaderMessage(
            if (replaceRulesEnabled) {
                R.string.replace_rule_book_enabled
            } else {
                R.string.replace_rule_book_disabled
            },
        )
        lifecycleScope.launch {
            displayChapters = processChapterTitles(chapters)
            loadChapter(currentChapterIndex)
        }
    }

    private fun observeReplaceRules() {
        replaceRuleRepository.observeAll()
            .onEach { rules ->
                composeReaderViewModel.publishReplaceRules(
                    rules = replaceRuleRepository.filterRulesForBook(
                        rules = rules,
                        scopeName = manga.title,
                        origin = manga.source.name,
                    ),
                    origin = manga.source.name,
                    disabledRuleIds = disabledReplaceRuleIds,
                )
            }
            .launchIn(lifecycleScope)
    }

    private fun toggleReplaceRule(rule: ReplaceRule, enabled: Boolean) {
        lifecycleScope.launch {
            novelBookPreferences.setReplaceRuleEnabled(manga, rule.id, enabled)
            disabledReplaceRuleIds = novelBookPreferences.getDisabledReplaceRuleIds(manga)
            composeReaderViewModel.publishReplaceRuleEnabled(rule.id, enabled)
            displayChapters = processChapterTitles(chapters)
            loadChapter(currentChapterIndex)
        }
    }

    private fun startTranslation() {
        translationJob?.cancel()
        val content = getCurrentChapterContent()
        android.util.Log.d("NovelReaderActivity", "startTranslation: content length=${content?.length ?: 0}")
        if (content.isNullOrBlank()) {
            showReaderMessage("暂无章节内容可翻译", 2000L)
            return
        }

        // 检查是否有可用的翻译配置
        val mode = settings.readerTranslationMode
        val onnxModelId = settings.readerTranslationOnnxModelId
        val hasOnnx = onnxModelId.isNotBlank()
        val hasApi = settings.readerTranslationApiEndpoint.isNotBlank()
        android.util.Log.d("NovelReaderActivity", "Translation config: mode=$mode, onnxModelId='$onnxModelId', hasOnnx=$hasOnnx, hasApi=$hasApi")
        if (!hasOnnx && !hasApi && mode.name != "LOCAL_ONLY") {
            showReaderMessage(
                "请先在「设置 → AI翻译」中配置翻译引擎（API 或 ONNX 本地模型）",
                3000L,
            )
            return
        }

        val sourceLang = settings.readerTranslationSourceLanguage
        val targetLang = settings.readerTranslationTargetLanguage
        val displayMode = readerSettings.translationDisplayMode
        val chapterIndex = currentChapterIndex
        val totalParagraphs = NovelParagraphSplitter.split(content)
            .count { it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank() }
        android.util.Log.d("NovelReaderActivity", "Starting translation: chapter=$chapterIndex, source=$sourceLang, target=$targetLang, mode=$displayMode")
        showNovelTranslationProgress(
            translatedCount = 0,
            totalCount = totalParagraphs,
            isComplete = false,
        )

        translationJob = lifecycleScope.launch {
            var lastNotifiedCount = 0
            try {
                translationProcessor.translateChapterFlow(
                    chapterIndex = chapterIndex,
                    content = content,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    displayMode = displayMode,
                    bookKey = "${manga.source.name}:${manga.id}",
                ).collect { translation ->
                    android.util.Log.d("NovelReaderActivity", "Translation progress: complete=${translation.isComplete}, translations=${translation.translations.size}")
                    chapterTranslations.put(translation.chapterIndex, translation)
                    applyTranslationToViews(translation)
                    val translatedCount = translation.translations.size
                    if (translation.isComplete) {
                        if (translatedCount == 0) {
                            showReaderMessage(
                                "未获得译文，请检查「设置 → AI翻译」中的引擎配置",
                                3000L,
                            )
                        } else {
                            showNovelTranslationProgress(
                                translatedCount = translatedCount,
                                totalCount = totalParagraphs,
                                isComplete = true,
                            )
                        }
                    } else if (translatedCount != lastNotifiedCount) {
                        lastNotifiedCount = translatedCount
                        showNovelTranslationProgress(
                            translatedCount = translatedCount,
                            totalCount = totalParagraphs,
                            isComplete = false,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 翻译任务被取消（切换章节、关闭翻译、退出阅读器等），这是正常行为
                android.util.Log.d("NovelReaderActivity", "Translation cancelled")
                throw e  // 重新抛出 CancellationException 以正确传播取消信号
            } catch (e: Exception) {
                android.util.Log.e("NovelReaderActivity", "Translation failed", e)
                showReaderMessage("翻译失败: ${e.message}", 2000L)
            }
        }
    }

    private fun showNovelTranslationProgress(
        translatedCount: Int,
        totalCount: Int,
        isComplete: Boolean,
    ) {
        if (totalCount <= 0) {
            return
        }
        val message = when {
            isComplete -> getString(
                R.string.novel_translation_progress_complete,
                translatedCount,
                totalCount,
            )

            translatedCount == 0 -> getString(
                R.string.novel_translation_progress_started,
                translatedCount,
                totalCount,
            )

            else -> getString(
                R.string.novel_translation_progress_update,
                translatedCount,
                totalCount,
            )
        }
        showReaderMessage(message, TRANSLATION_PROGRESS_TOAST_DURATION)
    }

    private fun clearTranslation() {
        translationJob?.cancel()
        translationJob = null
        chapterTranslations.clear()
    }

    /**
     * 进入一本新小说时，只重置当前阅读会话里的翻译状态，
     * 不清理长期翻译缓存，避免跨书残留但保留复用能力。
     */
    private fun resetTranslationSession() {
        readerSettings = readerSettings.copy(isTranslationEnabled = false)
        clearTranslation()
    }

    private fun applyTranslationToViews(translation: NovelChapterTranslation) {
        composeReaderViewModel.publishTranslation(translation)
        val isScrollMode = readerSettings.readingMode == ReadingMode.SCROLL
        if (isScrollMode) {
        } else {
            if (translation.chapterIndex == currentChapterIndex) {
            }
        }
    }

    /**
     * 获取当前章节的原始文本内容（用于翻译）
     */
    private fun getCurrentChapterContent(): String? {
        return composeReaderViewModel.uiState.value.continuousChapters
            .firstOrNull { it.chapterIndex == currentChapterIndex }
            ?.content
            ?: composeReaderViewModel.uiState.value.content
    }

    override fun scrollBy(delta: Int, smooth: Boolean): Boolean = false

    override fun toggleUiVisibility() {
        android.util.Log.d(
            NOVEL_SCROLL_TAP_LOG_TAG,
            "toggleUiVisibility current=$isUiVisible target=${!isUiVisible}",
        )
        setUiVisible(!isUiVisible)
    }

    override fun isReaderResumed(): Boolean = true

    private fun onNovelTextSelectionAction(
        selection: NovelTextSelection,
        action: NovelTextSelectionAction,
    ) {
        when (action) {
            NovelTextSelectionAction.COPY -> {
                copyToClipboard(getString(R.string.novel_selection_text), selection.text)
                clearNovelTextSelection(selection)
                showReaderMessage(R.string.novel_selection_copied)
            }
            NovelTextSelectionAction.SHARE -> {
                startActivity(ShareHelper(this).getShareTextIntent(selection.text))
                clearNovelTextSelection(selection)
            }
            NovelTextSelectionAction.DICTIONARY -> {
                openNovelDictionary(selection.text)
                clearNovelTextSelection(selection)
            }
            NovelTextSelectionAction.BOOKMARK -> {
                toggleNovelSelectionBookmark(selection)
            }
            NovelTextSelectionAction.HIGHLIGHT -> {
                toggleNovelMarking(selection)
            }
            NovelTextSelectionAction.NOTE -> {
                if (resolveNovelSelectionRange(selection) == null) {
                    showReaderMessage(R.string.novel_selection_position_unavailable)
                } else {
                    noteSelection = selection
                    noteDraft = ""
                    clearNovelTextSelection(selection)
                }
            }
            NovelTextSelectionAction.EXCERPT -> {
                showNovelExcerpt(selection.text, null, selection.chapterIndex)
                clearNovelTextSelection(selection)
            }
            NovelTextSelectionAction.ASK_AI -> {
                showNovelAiAsk(selection)
                clearNovelTextSelection(selection)
            }
            NovelTextSelectionAction.LISTEN -> {
                val startOffset = selection.renderedRange?.first
                    ?: selection.chapterText.indexOf(selection.text).coerceAtLeast(0)
                startTtsFromOffset(selection.text, selection.chapterId, startOffset)
                clearNovelTextSelection(selection)
            }
            NovelTextSelectionAction.DELETE -> Unit
        }
    }

    private fun onNovelMarkingAction(
        marking: NovelMarkingEntity,
        action: NovelTextSelectionAction,
    ) {
        when (action) {
            NovelTextSelectionAction.COPY -> {
                copyToClipboard(getString(R.string.novel_selection_text), marking.selectedText)
                clearNovelMarkingSelection()
                showReaderMessage(R.string.novel_selection_copied)
            }
            NovelTextSelectionAction.NOTE -> {
                clearNovelMarkingSelection()
                editNovelMarkingNote(marking)
            }
            NovelTextSelectionAction.EXCERPT -> {
                showNovelExcerpt(marking.selectedText, marking.note, marking.chapterIndex)
                clearNovelMarkingSelection()
            }
            NovelTextSelectionAction.ASK_AI -> {
                showNovelAiAsk(marking)
                clearNovelMarkingSelection()
            }
            NovelTextSelectionAction.LISTEN -> {
                startTtsFromOffset(marking.selectedText, marking.chapterId, marking.startOffset)
                clearNovelMarkingSelection()
            }
            NovelTextSelectionAction.SHARE -> {
                startActivity(ShareHelper(this).getShareTextIntent(marking.selectedText))
                clearNovelMarkingSelection()
            }
            NovelTextSelectionAction.DICTIONARY -> {
                openNovelDictionary(marking.selectedText)
                clearNovelMarkingSelection()
            }
            NovelTextSelectionAction.BOOKMARK -> {
                marking.toNovelSelection()?.let(::toggleNovelSelectionBookmark)
            }
            NovelTextSelectionAction.HIGHLIGHT -> {
                clearNovelMarkingSelection()
                deleteNovelMarking(marking)
            }
            NovelTextSelectionAction.DELETE -> {
                clearNovelMarkingSelection()
                deleteNovelMarking(marking)
            }
        }
    }

    private fun NovelMarkingEntity.toNovelSelection(): NovelTextSelection? {
        val state = composeReaderViewModel.uiState.value
        val chapterText = state.continuousChapters
            .firstOrNull { it.chapterId == chapterId }
            ?.content
            ?: state.content.takeIf { state.chapterId == chapterId }
        if (chapterText.isNullOrBlank()) return null
        return NovelTextSelection(
            text = selectedText,
            chapterId = chapterId,
            chapterIndex = chapterIndex,
            chapterText = chapterText,
            renderedStart = startOffset,
            renderedText = selectedText,
            clear = {},
        )
    }

    private fun toggleNovelMarking(selection: NovelTextSelection) {
        val range = resolveNovelSelectionRange(selection)
        if (range == null) {
            showReaderMessage(R.string.novel_selection_position_unavailable)
            return
        }
        lifecycleScope.launch {
            val added = novelMarkingRepository.toggle(
                mangaId = manga.id,
                chapterId = selection.chapterId,
                chapterIndex = selection.chapterIndex,
                startOffset = range.first,
                endOffset = range.last + 1,
                selectedText = selection.text,
                color = activeMarkingColor,
                style = activeMarkingStyle,
            )
            clearNovelTextSelection(selection)
            showReaderMessage(
                if (added) R.string.novel_selection_highlight_added
                else R.string.novel_selection_highlight_removed,
            )
        }
    }

    private fun saveNovelSelectionNote(selection: NovelTextSelection, note: String) {
        val range = resolveNovelSelectionRange(selection) ?: return
        lifecycleScope.launch {
            novelMarkingRepository.addNote(
                mangaId = manga.id,
                chapterId = selection.chapterId,
                chapterIndex = selection.chapterIndex,
                startOffset = range.first,
                endOffset = range.last + 1,
                selectedText = selection.text,
                note = note,
                color = activeMarkingColor,
                style = activeMarkingStyle,
            )
            showReaderMessage(R.string.novel_selection_note_saved)
        }
    }

    private fun onNovelSelectionColorChange(selection: NovelTextSelection, color: Int) {
        activeMarkingColor = color
        composeReaderViewModel.publishActiveMarkingStyle(activeMarkingColor, activeMarkingStyle)
        persistActiveMarkingPreferences()
    }

    private fun onNovelSelectionStyleChange(selection: NovelTextSelection, style: Int) {
        activeMarkingStyle = style
        composeReaderViewModel.publishActiveMarkingStyle(activeMarkingColor, activeMarkingStyle)
        persistActiveMarkingPreferences()
    }

    private fun onNovelMarkingChangeStyle(marking: NovelMarkingEntity, color: Int, style: Int) {
        composeReaderViewModel.publishNovelMarkingStyle(marking.id, color, style)
        lifecycleScope.launch {
            novelMarkingRepository.updateStyle(marking.id, color, style)
        }
    }

    private fun persistActiveMarkingPreferences() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putInt("novel_active_marking_color", activeMarkingColor)
            .putInt("novel_active_marking_style", activeMarkingStyle)
            .apply()
    }

    private fun toggleNovelSelectionBookmark(selection: NovelTextSelection) {
        val chapter = chapters.firstOrNull { it.id == selection.chapterId }
        if (chapter == null || resolveNovelSelectionRange(selection) == null) {
            showReaderMessage(R.string.novel_selection_position_unavailable)
            return
        }
        lifecycleScope.launch {
            runCatching {
                val state = composeReaderViewModel.uiState.value
                val page = state.position
                    ?.takeIf { it.chapterId == selection.chapterId }
                    ?.page
                    ?: currentPageIndex
                val existing = bookmarksRepository.observeBookmark(manga, chapter.id, page).first()
                if (existing != null) {
                    bookmarksRepository.removeBookmark(manga.id, chapter.id, page)
                    contentRoot.performConfirmHapticFeedback()
                    showReaderMessage(R.string.novel_bookmark_removed)
                } else {
                    bookmarksRepository.addBookmark(
                        org.skepsun.kototoro.bookmarks.domain.Bookmark(
                            manga = manga,
                            pageId = System.currentTimeMillis(),
                            chapterId = chapter.id,
                            page = page,
                            scroll = 0,
                            imageUrl = selection.text,
                            createdAt = java.time.Instant.now(),
                            percent = getCurrentProgressRatio(),
                        ),
                    )
                    contentRoot.performConfirmHapticFeedback()
                    showReaderMessage(R.string.novel_bookmark_added)
                }
                clearNovelTextSelection(selection)
            }.onFailure {
                android.util.Log.e("NovelReaderActivity", "Failed to toggle selection bookmark", it)
                showReaderMessage(getString(R.string.novel_bookmark_failed, it.message ?: ""), 2000L)
            }
        }
    }

    private fun onNovelMarkingClick(marking: NovelMarkingEntity, rect: androidx.compose.ui.geometry.Rect) {
        composeReaderViewModel.publishSelectedMarking(marking, rect = rect)
    }

    private fun editNovelMarkingNote(marking: NovelMarkingEntity) {
        noteMarking = marking
        noteDraft = marking.note.orEmpty()
    }

    private fun saveNovelMarkingNote(marking: NovelMarkingEntity, note: String?) {
        lifecycleScope.launch {
            novelMarkingRepository.updateNote(marking, note)
            showReaderMessage(R.string.novel_selection_note_saved)
        }
    }

    private fun deleteNovelMarking(marking: NovelMarkingEntity) {
        lifecycleScope.launch {
            novelMarkingRepository.delete(marking)
            showReaderMessage(R.string.novel_marking_deleted)
        }
    }

    private fun clearNovelMarkingSelection() {
        composeReaderViewModel.publishSelectedMarking(null)
    }

    private fun showNovelExcerpt(text: String, note: String?, chapterIndex: Int) {
        novelExcerpt = NovelExcerptData(
            selectedText = text,
            bookTitle = manga.title,
            chapterTitle = chapters.getOrNull(chapterIndex)?.title.orEmpty(),
            author = manga.authors.joinToString(", "),
            userNickname = "书友",
            note = note,
        )
    }

    private fun saveNovelExcerpt(
        data: NovelExcerptData,
        configuration: NovelExcerptConfiguration,
    ) {
        lifecycleScope.launch {
            NovelExcerptHelper.saveExcerptToGallery(this@NovelReaderActivity, data, configuration)
            novelExcerpt = null
        }
    }

    private fun shareNovelExcerpt(
        data: NovelExcerptData,
        configuration: NovelExcerptConfiguration,
    ) {
        lifecycleScope.launch {
            NovelExcerptHelper.shareExcerpt(this@NovelReaderActivity, data, configuration)
        }
    }

    private fun showNovelAiAsk(selection: NovelTextSelection) {
        val range = resolveNovelSelectionRange(selection)
        showNovelAiAsk(
            NovelAiAskTarget(
                bookTitle = manga.title,
                chapterTitle = chapters.getOrNull(selection.chapterIndex)?.title.orEmpty(),
                excerpt = selection.text,
                context = buildNovelAiContext(selection.chapterText, range),
            ),
        )
    }

    private fun showNovelAiAsk(marking: NovelMarkingEntity) {
        marking.toNovelSelection()?.let {
            showNovelAiAsk(it)
            return
        }
        showNovelAiAsk(
            NovelAiAskTarget(
                bookTitle = manga.title,
                chapterTitle = chapters.getOrNull(marking.chapterIndex)?.title.orEmpty(),
                excerpt = marking.selectedText,
                context = NovelAiContext(),
            ),
        )
    }

    private fun showNovelAiAsk(target: NovelAiAskTarget) {
        novelAiJob?.cancel()
        novelAiJob = null
        novelAiRequestId++
        novelAiAskTarget = target
        novelAiQuestion = ""
        novelAiAnswer = null
        novelAiAskLoading = false
    }

    private fun dismissNovelAiAsk() {
        novelAiJob?.cancel()
        novelAiJob = null
        novelAiRequestId++
        novelAiAskTarget = null
        novelAiQuestion = ""
        novelAiAnswer = null
        novelAiAskLoading = false
    }

    private fun askNovelAi(target: NovelAiAskTarget) {
        val question = novelAiQuestion.trim()
        if (question.isBlank() || novelAiAskLoading) return
        val configuredEndpoint = TranslationApiProviderCatalog.resolveChatEndpoint(
            settings.readerTranslationApiProviderPreset,
            settings.readerTranslationApiEndpoint,
        )
        if (configuredEndpoint.isBlank()) {
            showReaderMessage(getString(R.string.novel_ai_ask_not_configured), 2500L)
            return
        }
        novelAiJob?.cancel()
        val requestId = ++novelAiRequestId
        novelAiAskLoading = true
        novelAiJob = lifecycleScope.launch {
            try {
                val answer = translationProcessor.askBook(
                    bookTitle = target.bookTitle,
                    chapterTitle = target.chapterTitle,
                    excerpt = target.excerpt,
                    contextBefore = target.context.before,
                    contextAfter = target.context.after,
                    question = question,
                )
                if (requestId == novelAiRequestId && novelAiAskTarget == target) {
                    novelAiAnswer = answer.ifBlank {
                        getString(R.string.novel_ai_ask_failed, "empty response")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestId == novelAiRequestId && novelAiAskTarget == target) {
                    novelAiAnswer = getString(
                        R.string.novel_ai_ask_failed,
                        error.message ?: "unknown error",
                    )
                }
            } finally {
                if (requestId == novelAiRequestId) {
                    novelAiAskLoading = false
                }
            }
        }
    }

    private fun startTtsFromOffset(text: String, chapterId: Long, startOffset: Int) {
        val service = ttsService
        if (service == null) {
            showReaderMessage("TTS 尚未准备好", 1800L)
            return
        }
        val composeState = composeReaderViewModel.uiState.value
        val chapterText = composeState.continuousChapters
            .firstOrNull { it.chapterId == chapterId }?.content
            ?: composeState.content.takeIf { composeState.chapterId == chapterId }

        val (tokens, startIndex) = if (!chapterText.isNullOrBlank()) {
            val allTokens = org.skepsun.kototoro.reader.novel.tts.Tokenizer.tokenize(chapterText)
            val index = allTokens.indexOfFirst { it.range.last >= startOffset }.coerceAtLeast(0)
            allTokens to index
        } else {
            org.skepsun.kototoro.reader.novel.tts.Tokenizer.tokenize(text) to 0
        }

        if (tokens.isEmpty()) return
        composeReaderViewModel.showTtsControls()
        runCatching {
            val intent = Intent(this, org.skepsun.kototoro.reader.novel.tts.TtsService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
            service.startTts(tokens, startIndex)
        }.onFailure {
            android.util.Log.e("NovelReaderActivity", "Failed to start selected text TTS", it)
            showReaderMessage("TTS 启动失败: ${it.message}", 2000L)
        }
    }

    private fun jumpToNovelMarking(marking: NovelMarkingEntity) {
        composeReaderViewModel.dismissMarkings()
        composeReaderViewModel.dismissChapters()
        val chapterIndex = chapters.indexOfFirst { it.id == marking.chapterId }
            .takeIf { it >= 0 } ?: marking.chapterIndex
        if (chapterIndex < 0 || chapterIndex >= chapters.size) return
        val target = org.skepsun.kototoro.reader.novel.compose.NovelMarkingTarget(
            markingId = marking.id,
            chapterId = marking.chapterId,
            chapterIndex = chapterIndex,
            startOffset = marking.startOffset,
            endOffset = marking.endOffset,
            selectedText = marking.selectedText,
        )
        composeReaderViewModel.jumpToMarking(target)
        if (chapterIndex != currentChapterIndex) {
            currentChapterIndex = chapterIndex
            loadChapter(chapterIndex)
        }
    }

    /**
     * 章节面板搜索结果点击：跳转到章节内命中偏移处而非章节首页。
     * 复用标记跳转管线（pendingMarkingTarget + 临时高亮）。
     */
    private fun jumpToNovelSearchResult(
        target: org.skepsun.kototoro.reader.novel.compose.NovelMarkingTarget,
    ) {
        composeReaderViewModel.dismissChapters()
        val chapterIndex = chapters.indexOfFirst { it.id == target.chapterId }
            .takeIf { it >= 0 } ?: target.chapterIndex
        if (chapterIndex !in chapters.indices) return
        val resolved = target.copy(
            chapterId = chapters[chapterIndex].id,
            chapterIndex = chapterIndex,
        )
        composeReaderViewModel.jumpToMarking(resolved)
        if (chapterIndex != currentChapterIndex) {
            currentChapterIndex = chapterIndex
            currentPageIndex = 0
            loadChapter(chapterIndex)
        }
    }

    private fun deleteNovelBookmark(bookmark: org.skepsun.kototoro.bookmarks.domain.Bookmark) {
        lifecycleScope.launch {
            runCatching {
                bookmarksRepository.removeBookmark(bookmark)
                showReaderMessage(R.string.novel_bookmark_removed)
            }.onFailure {
                android.util.Log.e("NovelReaderActivity", "Failed to delete novel bookmark", it)
            }
        }
    }

    private fun openNovelBookmark(bookmark: org.skepsun.kototoro.bookmarks.domain.Bookmark) {
        val chapterIndex = chapters.indexOfFirst { it.id == bookmark.chapterId }
        if (chapterIndex < 0) return
        composeReaderViewModel.dismissMarkings()
        composeReaderViewModel.dismissChapters()
        currentChapterIndex = chapterIndex
        currentPageIndex = bookmark.page
        loadChapter(chapterIndex)
    }

    private fun resolveNovelSelectionRange(selection: NovelTextSelection): IntRange? {
        val renderedRange = selection.renderedRange
        findNovelTextRange(
            source = selection.chapterText,
            selected = selection.text,
            preferredStart = renderedRange?.first ?: 0,
        )?.let { return it }
        return renderedRange?.takeIf { it.last < selection.chapterText.length }
    }

    private fun clearNovelTextSelection(selection: NovelTextSelection) {
        selection.clear()
        composeReaderViewModel.publishTextSelection(null)
    }

    private fun openNovelDictionary(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        startActivity(DictionaryActivity.newIntent(this, query))
    }

    override fun onBookmarkClick() {
        val chapter = chapters.getOrNull(currentChapterIndex)
        if (chapter == null) {
            showReaderMessage(getString(R.string.novel_cannot_add_bookmark), 1500L)
            return
        }

        lifecycleScope.launch {
            try {
                val composeState = composeReaderViewModel.uiState.value
                val targetChapterId = composeState.position?.chapterId ?: chapter.id
                val targetChapter = chapters.find { it.id == targetChapterId } ?: chapter
                val currentPage = composeState.position?.page ?: currentPageIndex
                val percent = getCurrentProgressRatio()

                // 检查是否已存在书签
                val existingBookmark = bookmarksRepository.observeBookmark(
                    manga, targetChapter.id, currentPage
                ).first()

                if (existingBookmark != null) {
                    // 删除书签
                    bookmarksRepository.removeBookmark(manga.id, targetChapter.id, currentPage)
                    contentRoot.performConfirmHapticFeedback()
                    showReaderMessage(getString(R.string.novel_bookmark_removed), 1500L)
                } else {
                    // 添加书签 - 保存当前页面的文本预览
                    val pageText = composeState.currentPageText.trim()
                    val previewText = if (pageText.isNotBlank()) {
                        org.skepsun.kototoro.bookmarks.domain.extractNovelBookmarkPreview(pageText)
                            .ifBlank { pageText.take(200).trim() }
                    } else {
                        val rawContent = composeState.continuousChapters
                            .firstOrNull { it.chapterId == targetChapter.id }?.content
                            ?: composeState.content
                        val cleanContent = org.skepsun.kototoro.bookmarks.domain.extractNovelBookmarkPreview(rawContent)
                            .ifBlank { rawContent.take(200).trim() }
                        if (readerSettings.readingMode == ReadingMode.SCROLL && currentPage > 0) {
                            val paragraphs = cleanContent.split("\n").filter { it.isNotBlank() }
                            paragraphs.getOrNull(currentPage)?.take(200)?.trim()
                                ?: cleanContent.take(200).trim()
                        } else {
                            cleanContent.take(200).trim()
                        }
                    }

                    val bookmark = org.skepsun.kototoro.bookmarks.domain.Bookmark(
                        manga = manga,
                        pageId = System.currentTimeMillis(), // 使用时间戳作为 ID
                        chapterId = targetChapter.id,
                        page = currentPage,
                        scroll = 0,
                        imageUrl = previewText, // 保存文本预览
                        createdAt = java.time.Instant.now(),
                        percent = percent,
                    )
                    bookmarksRepository.addBookmark(bookmark)
                    contentRoot.performConfirmHapticFeedback()
                    showReaderMessage(getString(R.string.novel_bookmark_added), 1500L)
                }
            } catch (e: Exception) {
                android.util.Log.e("NovelReaderActivity", "Failed to toggle bookmark", e)
                showReaderMessage(getString(R.string.novel_bookmark_failed, e.message ?: ""), 2000L)
            }
        }
    }

    private fun observeCurrentPageBookmark(chapterId: Long, page: Int) {
        bookmarkObservationJob?.cancel()
        bookmarkObservationJob = lifecycleScope.launch {
            bookmarksRepository.observeBookmark(manga, chapterId, page)
                .map { it != null }
                .distinctUntilChanged()
                .collect(composeReaderViewModel::publishCurrentPageBookmarked)
        }
    }

    private fun showVoiceSelectionDialog() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val isSystem = prefs.getString("tts_engine_type", "SYSTEM") == "SYSTEM"

        if (isSystem) {
            var localTts: android.speech.tts.TextToSpeech? = null
            localTts = android.speech.tts.TextToSpeech(this) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    val voices = try { localTts?.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }

                    runOnUiThread {
                        if (voices.isNotEmpty()) {
                            val sortedVoices = voices.sortedBy { it.locale.displayName }
                            val entries = sortedVoices.map { "${it.locale.displayName} (${it.name})" }
                            val values = sortedVoices.map { it.name }

                            val currentVoice = prefs.getString("tts_system_voice", "default") ?: "default"
                            val checkedItem = values.indexOf(currentVoice).takeIf { it >= 0 } ?: 0

                            ttsVoiceDialogState = NovelTtsVoiceDialogState(
                                title = getString(R.string.tts_system_voice),
                                entries = entries,
                                selectedIndex = checkedItem,
                                onSelected = { which ->
                                    prefs.edit().putString("tts_system_voice", values[which]).apply()
                                    dismissTtsVoiceDialog()
                                    ttsService?.reloadEngine()
                                },
                                onDismiss = ::dismissTtsVoiceDialog,
                            ).withCleanup { localTts?.shutdown() }
                        } else {
                            val locales = try { localTts?.availableLanguages?.toList()?.sortedBy { it.displayName } } catch (e:Exception) { null } ?: emptyList()
                            if (locales.isNotEmpty()) {
                                    val entries = locales.map { it.displayName }
                                    val values = locales.map { it.toLanguageTag() }
                                    val currentVoice = prefs.getString("tts_system_voice", "default") ?: "default"
                                    val checkedItem = values.indexOf(currentVoice).takeIf { it >= 0 } ?: 0

                                    ttsVoiceDialogState = NovelTtsVoiceDialogState(
                                        title = getString(R.string.tts_system_voice),
                                        entries = entries,
                                        selectedIndex = checkedItem,
                                        onSelected = { which ->
                                            prefs.edit().putString("tts_system_voice", values[which]).apply()
                                            dismissTtsVoiceDialog()
                                            ttsService?.reloadEngine()
                                        },
                                        onDismiss = ::dismissTtsVoiceDialog,
                                    ).withCleanup { localTts?.shutdown() }
                            } else {
                                showReaderMessage("未检测到可用的系统音色", 2000L)
                                localTts?.shutdown()
                            }
                        }
                    }
                } else {
                    localTts?.shutdown()
                }
            }
        } else {
            val currentJson = prefs.getString("legado_tts_configs", "[]") ?: "[]"
            val type = object : com.google.gson.reflect.TypeToken<List<org.skepsun.kototoro.reader.novel.tts.model.TtsHttpConfig>>() {}.type
            val configs: List<org.skepsun.kototoro.reader.novel.tts.model.TtsHttpConfig> = try {
                com.google.gson.Gson().fromJson(currentJson, type) ?: emptyList()
            } catch (e: Exception) { emptyList() }

            if (configs.isNotEmpty()) {
                val names = configs.map { it.name }
                val values = configs.map { it.url }

                val currentVoice = prefs.getString("tts_legado_voice", "").orEmpty()
                val checkedItem = values.indexOf(currentVoice).takeIf { it >= 0 } ?: 0

                ttsVoiceDialogState = NovelTtsVoiceDialogState(
                    title = getString(R.string.tts_legado_voice),
                    entries = names,
                    selectedIndex = checkedItem,
                    onSelected = { which ->
                        prefs.edit().putString("tts_legado_voice", values[which]).apply()
                        dismissTtsVoiceDialog()
                        ttsService?.reloadEngine()
                    },
                    onDismiss = ::dismissTtsVoiceDialog,
                    onManage = {
                        dismissTtsVoiceDialog()
                        startActivity(android.content.Intent(this@NovelReaderActivity, org.skepsun.kototoro.settings.SettingsActivity::class.java))
                    },
                )
            } else {
                showReaderMessage("尚未导入任何网络音源配置，请前往设置导入", 2500L)
            }
        }
    }

    private fun NovelTtsVoiceDialogState.withCleanup(cleanup: () -> Unit): NovelTtsVoiceDialogState = copy(
        onDismiss = {
            cleanup()
            onDismiss()
        },
        onSelected = { index ->
            cleanup()
            onSelected(index)
        },
    )

    private fun dismissTtsVoiceDialog() {
        ttsVoiceDialogState = null
    }

    private fun onTtsClick() {
        composeReaderViewModel.showTtsControls()
        val state = ttsService?.getState()?.value
        if (state == org.skepsun.kototoro.reader.novel.tts.TtsState.IDLE) {
            onTtsPlayPauseClicked()
        }
    }

    private fun onClearTranslationCacheClick() {
        translationProcessor.clearCache()
        showReaderMessage("翻译缓存已清除", 1500L)
    }

    private fun startTtsFromCurrentPage() {
        if (ttsService == null) return

        var startIndex = 0

        // Safety: Extract text based on current reading mode
        val isScrollMode = readerSettings.readingMode == org.skepsun.kototoro.reader.novel.ReadingMode.SCROLL
        val text = if (!isScrollMode) {
            composeReaderViewModel.uiState.value.currentPageText
        } else {
            val composeState = composeReaderViewModel.uiState.value
            ttsScrollModeChapterIndex = composeState.chapterIndex
            composeState.content
        }

        if (text.isBlank()) return
        var tokens = org.skepsun.kototoro.reader.novel.tts.Tokenizer.tokenize(text)
        if (tokens.isEmpty()) return

        // Paged Mode relative token calibration
        if (!isScrollMode) {
            val pageStart = composeReaderViewModel.uiState.value.currentPageStart
            if (pageStart > 0) {
                tokens = tokens.map {
                    it.copy(range = IntRange(it.range.first + pageStart, it.range.last + pageStart))
                }
            }
        }

        try {
            val intent = android.content.Intent(this, org.skepsun.kototoro.reader.novel.tts.TtsService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
            ttsService?.startTts(tokens, startIndex)
        } catch (e: Exception) {
            // On Android 12+, ForegroundServiceStartNotAllowedException can be thrown.
            // Also catches SecurityException and IllegalStateException.
            android.util.Log.e("NovelReaderActivity", "Failed to start TTS foreground service", e)
            showReaderMessage("TTS启动失败: ${e.message}", 2000L)
        }
    }

    private fun onTtsPlayPauseClicked() {
        if (ttsService == null) return
        val state = ttsService?.getState()?.value

        if (state == org.skepsun.kototoro.reader.novel.tts.TtsState.PLAYING) {
            ttsService?.pause()
        } else if (state == org.skepsun.kototoro.reader.novel.tts.TtsState.PAUSED) {
            val intent = android.content.Intent(this, org.skepsun.kototoro.reader.novel.tts.TtsService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
            ttsService?.resume()
        } else {
            startTtsFromCurrentPage()
        }
    }

    private fun onTtsStopClicked() {
        ttsService?.stopTts()
    }

    /**
     * 当前页朗读完成后自动翻页并继续朗读
     */
    private fun handleTtsPageCompleted() {
        // Re-entrant guard: if already handling a completion, skip
        if (isHandlingTtsCompletion) return
        isHandlingTtsCompletion = true

        try {
            val isScrollMode = readerSettings.readingMode == org.skepsun.kototoro.reader.novel.ReadingMode.SCROLL
            if (isScrollMode) {
                // 滚动模式暂不支持自动翻页朗读
                return
            }

            // 尝试翻到下一页
            val position = composeReaderViewModel.uiState.value.position
            val hasNextPage = position != null && position.page + 1 < position.pageCount
            if (hasNextPage) {
                composeReaderViewModel.requestPage(position!!.page + 1)
                // 翻页后延迟一小段时间等待页面渲染完成，然后开始朗读新页面
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    isHandlingTtsCompletion = false
                    if (composeReaderViewModel.uiState.value.ttsControlsVisible) {
                        startTtsFromCurrentPage()
                    }
                }, 300)
                return  // Don't reset flag yet — the delayed callback will
            } else {
                // 当前页是本章最后一页，尝试切换到下一章
                val targetIndex = currentChapterIndex + 1
                if (targetIndex in chapters.indices) {
                    currentChapterIndex = targetIndex
                    currentPageIndex = 0
                    // 设置标志，在章节加载完成后自动开始朗读
                    pendingTtsAutoStart = true
                    loadChapter(currentChapterIndex)
                } else {
                    // 已经是最后一章最后一页
                    ttsService?.stopTts()
                }
            }
        } finally {
            // Reset guard unless we returned early for the delayed handler
            if (isHandlingTtsCompletion) {
                isHandlingTtsCompletion = false
            }
        }
    }

    override fun onSavePageClick() {}

    override fun onScrollTimerClick(isLongClick: Boolean) {}

    override fun toggleScreenOrientation() {}

    override fun switchPageTo(index: Int) {
        if (readerSettings.readingMode == ReadingMode.SCROLL) {
            composeReaderViewModel.requestScrollToBlock(index)
        } else {
            composeReaderViewModel.requestPage(index)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // 用户交互时的处理
    }

    /**
     * Restore reading progress from Intent or history
     * Requirements 7.5, 7.6: Restore last read chapter and page position, fallback to first chapter if not found
     *
     * 修复：改进章节ID查找逻辑，支持数据库映射的章节ID
     */
    private suspend fun restoreReadingProgress(originalChapters: List<ContentChapter>) {
        android.util.Log.d("NovelReaderActivity", "=== restoreReadingProgress() ===")

        // Get ReaderState from Intent
        val state = intent.getParcelableExtraCompat<org.skepsun.kototoro.reader.ui.ReaderState>(
            org.skepsun.kototoro.core.nav.ReaderIntent.EXTRA_STATE
        )

        // Get history
        val history = historyRepository.getOne(manga)
        data class RestoreCandidate(
            val source: String,
            val chapterId: Long,
            val page: Int,
            val scroll: Int,
        )

        suspend fun resolveLocalChapterIndex(chapterId: Long): Int {
            var targetIndex = chapters.indexOfFirst { it.id == chapterId }
            android.util.Log.d("NovelReaderActivity", "Looking for chapter ID $chapterId in ${chapters.size} expanded chapters")
            android.util.Log.d("NovelReaderActivity", "Found at index: $targetIndex")

            if (targetIndex >= 0) {
                return targetIndex
            }

            android.util.Log.d("NovelReaderActivity", "Chapter not found in expanded list, checking database mappings")
            val mapping = try {
                epubChapterMappingDao.getById(chapterId)
            } catch (e: Exception) {
                android.util.Log.e("NovelReaderActivity", "Failed to query chapter mapping", e)
                null
            }

            if (mapping != null) {
                android.util.Log.d(
                    "NovelReaderActivity",
                    "Found EPUB mapping: parentId=${mapping.parentChapterId}, index=${mapping.chapterIndex}",
                )
                val targetUrl = "#chapter/${mapping.chapterIndex}"
                targetIndex = chapters.indexOfFirst { chapter ->
                    chapter.url.contains(targetUrl) &&
                        (chapter.id == chapterId ||
                            chapter.url.contains("chapter_${mapping.parentChapterId}.epub") ||
                            chapter.url.contains("/${mapping.parentChapterId}/"))
                }
                android.util.Log.d("NovelReaderActivity", "Searching for URL pattern: $targetUrl, found at index: $targetIndex")
            }

            return targetIndex
        }

        val restoreCandidates = buildList {
            if (state != null && state.chapterId != 0L) {
                android.util.Log.d("NovelReaderActivity", "Queued intent restore candidate: ${state.chapterId}")
                add(
                    RestoreCandidate(
                        source = "intent",
                        chapterId = state.chapterId,
                        page = state.page,
                        scroll = state.scroll,
                    ),
                )
            }
            if (history != null && history.chapterId != 0L && history.chapterId != state?.chapterId) {
                android.util.Log.d("NovelReaderActivity", "Queued history restore candidate: ${history.chapterId}")
                add(
                    RestoreCandidate(
                        source = "history",
                        chapterId = history.chapterId,
                        page = history.page,
                        scroll = history.scroll,
                    ),
                )
            }
        }

        var restored = false
        for (candidate in restoreCandidates) {
            val targetIndex = resolveLocalChapterIndex(candidate.chapterId)
            if (targetIndex >= 0) {
                currentChapterIndex = targetIndex
                desiredProgressRatio = candidate.scroll.takeIf { it > 0 }?.let { it / 10000f }
                currentPageIndex = candidate.page
                android.util.Log.d(
                    "NovelReaderActivity",
                    "✅ Restored from ${candidate.source} to chapter index $targetIndex (ID: ${chapters[targetIndex].id}), page $currentPageIndex",
                )
                android.util.Log.d("NovelReaderActivity", "   Chapter title: ${chapters[targetIndex].title}")
                android.util.Log.d("NovelReaderActivity", "   Chapter URL: ${chapters[targetIndex].url.takeLast(50)}")
                restored = true
                break
            }
        }

        if (!restored && restoreCandidates.isNotEmpty()) {
            val preferredCandidate = restoreCandidates.first()
            android.util.Log.w("NovelReaderActivity", "❌ Chapter ID ${preferredCandidate.chapterId} not found in local chapters")

            var onlineChapter = originalContent?.chapters?.find { it.id == preferredCandidate.chapterId }
            if (onlineChapter == null && originalContent != null) {
                runCatching {
                    val onlineRepo = mangaRepositoryFactory.create(originalContent!!.source)
                    val details = runBlocking { onlineRepo.getDetails(originalContent!!) }
                    originalContent = details
                    onlineChapter = details.chapters?.find { it.id == preferredCandidate.chapterId }
                }.onFailure {
                    android.util.Log.w("NovelReaderActivity", "Failed to fetch online details for missing chapter", it)
                }
            }
            if (onlineChapter != null) {
                android.util.Log.d("NovelReaderActivity", "✅ Found chapter in online source: ${onlineChapter.title}")
                repository = mangaRepositoryFactory.create(originalContent!!.source)
                chapters = chapters + onlineChapter
                currentChapterIndex = chapters.size - 1
                currentPageIndex = 0

                showReaderMessage(R.string.novel_loading_online_chapter)
            } else {
                android.util.Log.w("NovelReaderActivity", "Chapter not found in online source either, falling back to first chapter")
                currentChapterIndex = 0
                currentPageIndex = 0

                if (state != null && state.chapterId != 0L) {
                    showReaderMessage(R.string.novel_chapter_not_downloaded, SnackbarDuration.Long)
                }
            }
        } else if (!restored) {
            currentChapterIndex = 0
            currentPageIndex = 0
        }

        val markingId = intent.getLongExtra(EXTRA_MARKING_ID, -1L).takeIf { it > 0 }
        val startOffset = intent.getIntExtra(EXTRA_START_OFFSET, -1).takeIf { it >= 0 }
        val endOffset = intent.getIntExtra(EXTRA_END_OFFSET, -1).takeIf { it >= 0 }
        val selectedText = intent.getStringExtra(EXTRA_SELECTED_TEXT)
        if (startOffset != null && !selectedText.isNullOrBlank()) {
            val targetChapter = chapters.getOrNull(currentChapterIndex)
            if (targetChapter != null) {
                val target = org.skepsun.kototoro.reader.novel.compose.NovelMarkingTarget(
                    markingId = markingId ?: 0L,
                    chapterId = targetChapter.id,
                    chapterIndex = currentChapterIndex,
                    startOffset = startOffset,
                    endOffset = endOffset ?: (startOffset + selectedText.length),
                    selectedText = selectedText,
                )
                composeReaderViewModel.jumpToMarking(target)
            }
        }

        // Clear Intent state to avoid reusing it
        intent.removeExtra(EXTRA_MARKING_ID)
        intent.removeExtra(EXTRA_START_OFFSET)
        intent.removeExtra(EXTRA_END_OFFSET)
        intent.removeExtra(EXTRA_SELECTED_TEXT)
        intent.removeExtra(org.skepsun.kototoro.core.nav.ReaderIntent.EXTRA_STATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val markingId = intent.getLongExtra(EXTRA_MARKING_ID, -1L).takeIf { it > 0 }
        val startOffset = intent.getIntExtra(EXTRA_START_OFFSET, -1).takeIf { it >= 0 }
        val endOffset = intent.getIntExtra(EXTRA_END_OFFSET, -1).takeIf { it >= 0 }
        val selectedText = intent.getStringExtra(EXTRA_SELECTED_TEXT)
        val state = intent.getParcelableExtraCompat<org.skepsun.kototoro.reader.ui.ReaderState>(
            org.skepsun.kototoro.core.nav.ReaderIntent.EXTRA_STATE
        )
        if (state != null && state.chapterId != 0L) {
            val targetIndex = chapters.indexOfFirst { it.id == state.chapterId }
            if (targetIndex >= 0) {
                if (startOffset != null && !selectedText.isNullOrBlank()) {
                    val target = org.skepsun.kototoro.reader.novel.compose.NovelMarkingTarget(
                        markingId = markingId ?: 0L,
                        chapterId = state.chapterId,
                        chapterIndex = targetIndex,
                        startOffset = startOffset,
                        endOffset = endOffset ?: (startOffset + selectedText.length),
                        selectedText = selectedText,
                    )
                    composeReaderViewModel.jumpToMarking(target)
                }
                if (targetIndex != currentChapterIndex) {
                    currentChapterIndex = targetIndex
                    loadChapter(targetIndex)
                }
            }
        }
        intent.removeExtra(EXTRA_MARKING_ID)
        intent.removeExtra(EXTRA_START_OFFSET)
        intent.removeExtra(EXTRA_END_OFFSET)
        intent.removeExtra(EXTRA_SELECTED_TEXT)
        intent.removeExtra(org.skepsun.kototoro.core.nav.ReaderIntent.EXTRA_STATE)
    }

    private fun showReaderMessage(
        messageRes: Int,
        duration: SnackbarDuration = SnackbarDuration.Short,
    ) {
        lifecycleScope.launch {
            snackbarHostState.showSnackbar(getString(messageRes), duration = duration)
        }
    }

    private fun loadChapters() {
        android.util.Log.d("NovelReaderActivity", "=== loadChapters() called ===")
        android.util.Log.d("NovelReaderActivity", "Current manga.chapters: ${manga.chapters?.size ?: 0} chapters")
        android.util.Log.d("NovelReaderActivity", "Content is local: ${manga.isLocal}")

        lifecycleScope.launch(org.skepsun.kototoro.core.parser.legado.RequestPriority(org.skepsun.kototoro.core.parser.legado.RequestPriority.FOREGROUND)) {
            try {
                showLoading(true)

                android.util.Log.d("NovelReaderActivity", "Content chapters null or empty: ${manga.chapters.isNullOrEmpty()}, isLocal: ${manga.isLocal}")

                // For local manga, ALWAYS reload from repository to get fresh chapter list from index
                val details = if (manga.isLocal || manga.chapters.isNullOrEmpty()) {
                    android.util.Log.d("NovelReaderActivity", "Loading chapters from repository (local=${manga.isLocal}, empty=${manga.chapters.isNullOrEmpty()})...")
                    val startTime = System.currentTimeMillis()
                    // 核心修复：如果是远程解析器，优先使用带有原始远程 URL 的 originalContent 获取详情，避免 SSL 错误
                    val result = if (repository !is org.skepsun.kototoro.local.novel.LocalNovelRepository && originalContent != null) {
                        android.util.Log.d("NovelReaderActivity", "Using originalContent for remote details fetch: ${originalContent!!.url}")
                        repository.getDetails(originalContent!!)
                    } else {
                        repository.getDetails(manga)
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    android.util.Log.d("NovelReaderActivity", "✅ Loaded from repository in ${elapsed}ms, got ${result.chapters?.size ?: 0} chapters")
                    result
                } else {
                    android.util.Log.d("NovelReaderActivity", "✅ Using chapters from manga object (${manga.chapters?.size} chapters) - SKIPPED NETWORK")
                    manga
                }

                // 如果本地启动且 originalContent 还没有目录，尝试拉取远端目录用于占位（历史入口常见）
                if (manga.isLocal && originalContent?.chapters.isNullOrEmpty() && originalContent != null) {
                    runCatching {
                        val onlineRepo = mangaRepositoryFactory.create(originalContent!!.source)
                        val remoteDetails = onlineRepo.getDetails(originalContent!!)
                        originalContent = remoteDetails
                        android.util.Log.d(
                            "NovelReaderActivity",
                            "Fetched remote details for originalContent, chapters=${remoteDetails.chapters?.size ?: 0}",
                        )
                    }.onFailure {
                        android.util.Log.w("NovelReaderActivity", "Failed to fetch remote details for originalContent", it)
                    }
                }

                // 若当前是本地且有原始远端目录，合并远端目录与本地章节，保留未下载章节的占位
                var originalChapters = details.chapters.orEmpty()
                if (manga.isLocal && originalContent?.chapters != null) {
                    val remoteChapters = originalContent?.chapters.orEmpty()
                    val localById = originalChapters.associateBy { it.id }
                    val merged = remoteChapters.map { localById[it.id] ?: it }.toMutableList()
                    // 添加仅本地存在的章节（例如本地缓存的特殊章节）
                    val remoteIds = remoteChapters.map { it.id }.toSet()
                    originalChapters.filterNot { it.id in remoteIds }.forEach { merged.add(it) }
                    originalChapters = merged
                    android.util.Log.d(
                        "NovelReaderActivity",
                        "Merged remote chapters (${remoteChapters.size}) with local overrides (${localById.size}), result=${originalChapters.size}",
                    )
                }
                android.util.Log.d("NovelReaderActivity", "Original chapters count: ${originalChapters.size}")

                // 本地 CBZ/ZIP 或无电子书迹象时直接使用原章节，避免错误展开
                // 优先用解析器填充的 ebookFormats；旧解析器回退 URL 猜测（.epub / epub://）
                val hasLikelyEbook = !manga.isLocal && originalChapters.any {
                    it.ebookFormats.isNotEmpty() || it.url.lowercase().let { url ->
                        url.contains(".epub") || url.contains("epub://")
                    }
                }
                if (hasLikelyEbook) {
                    android.util.Log.d("NovelReaderActivity", "Expanding ebook chapters...")
                    chapters = expandEpubChapters(originalChapters)
                    android.util.Log.d("NovelReaderActivity", "After expansion: ${chapters.size} chapters")
                } else {
                    android.util.Log.d("NovelReaderActivity", "Skip ebook expansion (local or no ebook hints)")
                    chapters = originalChapters
                }

                // Restore reading progress (Requirements 7.5, 7.6)
                // Priority: Intent parameters > History > First chapter
                restoreReadingProgress(originalChapters)
                displayChapters = processChapterTitles(chapters)

                if (chapters.isEmpty()) {
                    showLoading(false)
                    showError(getString(R.string.no_chapters_in_manga))
                } else {
                    loadChapter(currentChapterIndex)
                }
            } catch (e: Exception) {
                android.util.Log.e("NovelReaderActivity", "Failed to load chapters", e)
                showLoading(false)
                showError(getString(R.string.novel_load_chapters_failed, e.message ?: ""))
            }
        }
    }

    private fun loadChapter(index: Int) {
        android.util.Log.d("NovelReaderActivity", "=== loadChapter($index) called ===")
        android.util.Log.d("NovelReaderActivity", "Total chapters available: ${chapters.size}")

        val chapter = chapters.getOrNull(index)
        if (chapter == null) {
            android.util.Log.e("NovelReaderActivity", "❌ Chapter at index $index not found")
            return
        }

        // 第一时间把完整章节列表与当前索引同步给 Compose 状态，
        // 否则底栏章节切换按钮会因状态里 chapters 为空/索引为 0 而一直不可点，
        // 直到用户打开章节面板切换过章节。
        composeReaderViewModel.publishChapterLibrary(
            displayChapters.ifEmpty { chapters },
            index,
        )

        chapterLoadJob?.cancel()
        chapterLoadJob = lifecycleScope.launch(org.skepsun.kototoro.core.parser.legado.RequestPriority(org.skepsun.kototoro.core.parser.legado.RequestPriority.FOREGROUND)) {
            try {
                // Determine if we need to show the loading spinner
                val needsLoading = !chapter.url.startsWith("epub://") &&
                                  !chapter.url.contains("#chapter/") &&
                                  !novelContentLoader.isCached(chapter)

                if (needsLoading) {
                    showLoading(true)
                }

                // Check if this is an EPUB internal chapter (Requirement 6.1, 6.2, 6.3)
                if (chapter.url.contains("#chapter/") || chapter.url.startsWith("epub://")) {
                    android.util.Log.d("NovelReaderActivity", "Detected EPUB internal chapter: ${chapter.url}")

                    // Show progress indicator for large files (Requirement 11.4)

                    // Load EPUB internal chapter using the dedicated loader
                    val result = epubInternalChapterLoader.loadEpubInternalChapter(chapter)


                    if (result.isSuccess) {
                        val loadResult = result.getOrNull() ?: return@launch
                        val processedContent = processEpubChapterText(loadResult.content, chapter)
                        android.util.Log.d("NovelReaderActivity", "Successfully loaded EPUB internal chapter")
                        showLoading(false)  // Dismiss loading indicator
                        renderChapterWithEpubInfo(index, chapter, processedContent, loadResult.epubFile, loadResult.chapterHref)
                    } else {
                        val error = result.exceptionOrNull()
                            ?: IllegalStateException("Unknown EPUB loading error")
                        android.util.Log.e("NovelReaderActivity", "Failed to load EPUB internal chapter", error)
                        showLoading(false)  // Dismiss loading indicator even on error
                        // Display user-friendly error message (Requirement 6.7)
                        val errorMessage = when {
                            error.message?.contains("not found") == true -> getString(R.string.novel_epub_not_found)
                            error.message?.contains("out of bounds") == true -> getString(R.string.novel_epub_index_invalid)
                            error.message?.contains("Invalid chapter URL") == true -> getString(R.string.novel_epub_url_invalid)
                            error.message?.contains("Failed to parse") == true -> getString(R.string.novel_epub_parse_failed)
                            else -> getString(R.string.novel_epub_load_failed, error.message ?: "")
                        }
                        showReaderMessage(errorMessage, 3000L)
                    }

                    return@launch
                }

                // Use chapter's source to get correct repository (local or online)
                // This allows seamless switching between downloaded and online chapters
                val chapterRepo = mangaRepositoryFactory.create(chapter.source)

                // 1. FAST PATH: Check if already cached
                if (novelContentLoader.isCached(chapter)) {
                    android.util.Log.d("NovelReaderActivity", "✅ Cache hit for chapter, loading directly")
                    val plainText = novelContentLoader.loadChapterContent(
                        chapterRepo,
                        chapter,
                        scopeName = manga.title,
                        replaceRulesEnabled = replaceRulesEnabled,
                        disabledReplaceRuleIds = disabledReplaceRuleIds,
                    )
                    showLoading(false)
                    renderChapter(index, chapter, plainText)
                    preloadNextChapter(index + 1)
                    return@launch
                }

                // 2. SLOW PATH: Need to fetch from network
                val isLocalChapter = chapter.source is org.skepsun.kototoro.core.model.LocalNovelSource ||
                                    chapter.source is org.skepsun.kototoro.core.model.LocalMangaSource
                val isLegadoSource = chapterRepo is org.skepsun.kototoro.core.parser.legado.LegadoRepository
                android.util.Log.d("NovelReaderActivity", "Cache miss, using repository for source: ${chapter.source}, isLocal: $isLocalChapter, isLegado: $isLegadoSource")

                // For Legado sources, skip EPUB check and go directly to flow-based loading with nextChapterUrl
                // This ensures proper boundary checking to prevent infinite page loading
                var prefetchedPages: List<org.skepsun.kototoro.parsers.model.ContentPage>? = null
                if (!isLegadoSource) {
                    // Non-Legado sources: check for EPUB type
                    val pages = chapterRepo.getPages(chapter)
                    prefetchedPages = pages

                    android.util.Log.d("NovelReaderActivity", "Got ${pages.size} pages, first page preview: ${pages.firstOrNull()?.preview}, url: ${pages.firstOrNull()?.url?.take(100)}")

                    // 检查是否为文本模态电子书章节（EPUB/FB2/TXT）；页面模态（PDF/DJVU）走文件阅读路径
                    if (chapter.isTextEbookChapter(pages)) {
                        android.util.Log.d("NovelReaderActivity", "Detected text ebook chapter, loading ebook content")
                        // 尝试读取EPUB内容
                        val epubContent = loadEpubContent(index, chapter)
                        showLoading(false)

                        if (epubContent != null) {
                            // 成功读取EPUB，显示内容
                            renderChapter(index, chapter, epubContent)
                        } else {
                            // 读取失败：该分卷尚未下载，向用户正确引导「先下载、后展开」的流程
                            val volumeName = chapter.title.orEmpty()
                            val epubMessage = buildString {
                                append(getString(R.string.novel_epub_volume_not_downloaded_title))
                                append("\n\n")
                                if (volumeName.isNotBlank()) {
                                    append(getString(R.string.novel_epub_volume_name_format, volumeName))
                                    append("\n\n")
                                }
                                append(getString(R.string.novel_epub_download_guide))
                            }
                            renderChapter(index, chapter, epubMessage)
                        }
                        return@launch
                    }

                    // 页面模态电子书（PDF/DJVU）：正文是扫描页而非文本，在线阅读无法直接渲染，
                    // 引导用户下载后通过文件阅读器逐页查看
                    if (chapter.isPageEbookChapter(pages)) {
                        android.util.Log.d("NovelReaderActivity", "Detected page-mode ebook chapter (PDF/DJVU), guiding to download")
                        showLoading(false)
                        val pageBookMessage = buildString {
                            append(getString(R.string.novel_epub_volume_not_downloaded_title))
                            append("\n\n")
                            append(getString(R.string.novel_epub_download_guide))
                        }
                        renderChapter(index, chapter, pageBookMessage)
                        return@launch
                    }
                }

                android.util.Log.d("NovelReaderActivity", "Processing as regular chapter with flow-based loading")

                val nextChapterUrl = chapters.getOrNull(index + 1)?.url
                android.util.Log.d("NovelReaderActivity", "nextChapterUrl for boundary check: $nextChapterUrl")

                try {
                    val plainText = novelContentLoader.loadChapterContentFlow(
                        chapterRepo,
                        chapter,
                        prefetchedPages = prefetchedPages,
                        priority = org.skepsun.kototoro.core.parser.legado.RequestPriority.FOREGROUND,
                        nextChapterUrl = nextChapterUrl,
                        scopeName = manga.title,
                        replaceRulesEnabled = replaceRulesEnabled,
                        disabledReplaceRuleIds = disabledReplaceRuleIds,
                    ).lastOrNull().orEmpty()
                    showLoading(false)
                    renderChapter(index, chapter, plainText)
                    preloadNextChapter(index + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("NovelReaderActivity", "Error collecting novel flow", e)
                    showLoading(false)
                    // Optionally show error to user
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("NovelReaderActivity", "Failed to load chapter", e)
                showLoading(false)
                showError(getString(R.string.novel_load_chapter_failed, e.message ?: ""))
            }
        }
    }

    private suspend fun processChapterTitles(chapters: List<ContentChapter>): List<ContentChapter> {
        if (chapters.isEmpty()) return emptyList()
        val results = novelTextProcessor.processTitles(
            chapters.map { chapter ->
                NovelTextInput(
                    text = chapter.title.orEmpty(),
                    context = NovelTextContext(
                        sourceName = chapter.source.name,
                        scopeName = manga.title,
                        chapterId = chapter.id,
                        chapterTitle = chapter.title.orEmpty(),
                        replaceRulesEnabled = replaceRulesEnabled,
                        disabledReplaceRuleIds = disabledReplaceRuleIds,
                    ),
                )
            },
        )
        return chapters.mapIndexed { index, chapter ->
            chapter.copy(title = results[index].text)
        }
    }

    private fun preloadNextChapter(nextIndex: Int) {
        if (readerSettings.readingMode == ReadingMode.SCROLL) return
        preloadJob?.cancel()
        val nextChapter = chapters.getOrNull(nextIndex) ?: return

        preloadJob = lifecycleScope.launch(Dispatchers.IO + org.skepsun.kototoro.core.parser.legado.RequestPriority(org.skepsun.kototoro.core.parser.legado.RequestPriority.BACKGROUND)) {
            try {
                // Paged navigation reaches the boundary quickly, so start warming the next chapter earlier.
                kotlinx.coroutines.delay(if (readerSettings.readingMode == ReadingMode.PAGED) 350L else 2000L)

                if (novelContentLoader.isCached(nextChapter)) return@launch

                android.util.Log.d("NovelReaderActivity", "Preloading next chapter: ${nextChapter.title}")
                val chapterRepo = mangaRepositoryFactory.create(nextChapter.source)
                val nextNextChapterUrl = chapters.getOrNull(nextIndex + 1)?.url
                novelContentLoader.loadChapterContentFlow(
                    chapterRepo,
                    nextChapter,
                    priority = org.skepsun.kototoro.core.parser.legado.RequestPriority.BACKGROUND,
                    nextChapterUrl = nextNextChapterUrl,
                    scopeName = manga.title,
                    replaceRulesEnabled = replaceRulesEnabled,
                    disabledReplaceRuleIds = disabledReplaceRuleIds,
                ).conflate().collect { /* just consume and cache */ }
                android.util.Log.d("NovelReaderActivity", "Successfully preloaded: ${nextChapter.title}")
                withContext(Dispatchers.Main) {
                    if (readerSettings.readingMode == ReadingMode.PAGED && currentChapterIndex == nextIndex - 1) {
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.d("NovelReaderActivity", "Failed to preload chapter: ${nextChapter.title}", e)
            }
        }
    }

    private suspend fun loadEpubContent(chapterIndex: Int, chapter: ContentChapter): String? {
        return try {
            android.util.Log.d("NovelReaderActivity", "Loading EPUB content for: ${chapter.title}, URL: ${chapter.url}")

            // 检查URL格式
            if (chapter.url.startsWith("epub://")) {
                // 新架构：使用EpubInternalChapterLoader
                android.util.Log.d("NovelReaderActivity", "Using EpubInternalChapterLoader for new architecture")

                val result = this.epubInternalChapterLoader.loadEpubInternalChapter(chapter)

                    if (result.isSuccess) {
                        val loadResult = result.getOrNull()
                        android.util.Log.d("NovelReaderActivity", "EPUB content loaded successfully, length: ${loadResult?.content?.length}")
                        // 直接用带 href 的渲染，保证图片相对路径解析正确
                        if (loadResult != null) {
                            val processedContent = processEpubChapterText(loadResult.content, chapter)
                            renderChapterWithEpubInfo(
                                chapterIndex = chapterIndex,
                                chapter = chapter,
                                text = processedContent,
                                epubFile = loadResult.epubFile,
                                chapterHref = loadResult.chapterHref,
                            )
                        }
                        return null
                    } else {
                        val error = result.exceptionOrNull()
                        android.util.Log.e("NovelReaderActivity", "Failed to load EPUB content: ${error?.message}", error)
                        return null
                    }
            } else {
                // 旧架构：使用EpubReader直接读取
                android.util.Log.d("NovelReaderActivity", "Using EpubReader for legacy architecture")

                val chapterUri = android.net.Uri.parse(chapter.url)
                val epubReader = org.skepsun.kototoro.local.epub.EpubReaderImpl()
                val epubContent = epubReader.readEpubFromUri(chapterUri)

                if (epubContent == null) {
                    android.util.Log.e("NovelReaderActivity", "Failed to read EPUB content")
                    return null
                }

                // 合并所有章节内容
                val fullContent = buildString {
                    append("《${epubContent.title}》\n")
                    append("作者：${epubContent.author}\n")
                    append("\n")
                    append("=".repeat(40))
                    append("\n\n")

                    for (epubChapter in epubContent.chapters) {
                        append("【${epubChapter.title}】\n\n")
                        append(epubChapter.content)
                        append("\n\n")
                        append("-".repeat(40))
                        append("\n\n")
                    }
                }

                android.util.Log.d("NovelReaderActivity", "EPUB content loaded successfully, length: ${fullContent.length}")
                return fullContent
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("NovelReaderActivity", "Failed to load EPUB content", e)
            null
        }
    }

    /**
     * Render EPUB chapter with proper file info for image loading
     *
     * @param chapter The chapter being rendered
     * @param text The chapter content
     * @param epubFile The EPUB file (optional, will be looked up if not provided)
     * @param chapterHref The chapter's path in EPUB (e.g., "OEBPS/Text/content_1.html")
     */
    private fun renderChapterWithEpubInfo(
        chapterIndex: Int,
        chapter: ContentChapter,
        text: String,
        epubFile: java.io.File? = null,
        chapterHref: String? = null,
    ) {
        if (!isCurrentChapter(chapterIndex, chapter)) return
        val content = text.ifBlank { getString(R.string.chapter_is_missing) }
        val chapterTitle = displayChapterTitle(chapter)
        composeReaderViewModel.publishChapter(
            chapterId = chapter.id,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            content = content,
            settings = readerSettings,
            translation = chapterTranslations[chapterIndex],
        )
        val hasPendingMarking = composeReaderViewModel.uiState.value.pendingMarkingTarget?.chapterId == chapter.id
        if (readerSettings.readingMode == ReadingMode.PAGED && !hasPendingMarking) {
            composeReaderViewModel.requestPage(
                if (currentPageIndex < 0) Int.MAX_VALUE else currentPageIndex,
            )
        }
        publishComposeImageContext(epubFile, chapterHref)
        if (epubFile == null && chapterHref == null) {
            resolveComposeImageContext(chapterIndex, chapter)
        }
        finishComposeChapterRender(chapter)
    }

    private fun renderChapter(chapterIndex: Int, chapter: ContentChapter, text: String) {
        if (!isCurrentChapter(chapterIndex, chapter)) return
        val content = text.ifBlank { getString(R.string.chapter_is_missing) }
        val chapterTitle = displayChapterTitle(chapter)
        composeReaderViewModel.publishChapter(
            chapterId = chapter.id,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            content = content,
            settings = readerSettings,
            translation = chapterTranslations[chapterIndex],
        )
        val hasPendingMarkingInChapter = composeReaderViewModel.uiState.value.pendingMarkingTarget?.chapterId == chapter.id
        if (readerSettings.readingMode == ReadingMode.PAGED && !hasPendingMarkingInChapter) {
            composeReaderViewModel.requestPage(
                if (currentPageIndex < 0) Int.MAX_VALUE else currentPageIndex,
            )
        }
        resolveComposeImageContext(chapterIndex, chapter)
        finishComposeChapterRender(chapter)
    }

    private suspend fun processEpubChapterText(text: String, chapter: ContentChapter): String {
        val processed = novelTextProcessor.process(
            text = text,
            context = NovelTextContext(
                sourceName = chapter.source.name,
                scopeName = manga.title,
                chapterId = chapter.id,
                chapterTitle = chapter.title.orEmpty(),
                replaceRulesEnabled = replaceRulesEnabled,
                disabledReplaceRuleIds = disabledReplaceRuleIds,
            ),
        )
        if (processed.failedRuleNames.isNotEmpty()) {
            android.util.Log.w(
                "NovelReaderActivity",
                "Text rules failed for EPUB chapter=${chapter.id}: ${processed.failedRuleNames.joinToString()}",
            )
        }
        return processed.text
    }

    private fun displayChapterTitle(chapter: ContentChapter): String {
        return displayChapters.firstOrNull { it.id == chapter.id }?.title ?: chapter.title.orEmpty()
    }

    private fun isCurrentChapter(chapterIndex: Int, chapter: ContentChapter): Boolean {
        return currentChapterIndex == chapterIndex && chapters.getOrNull(chapterIndex)?.id == chapter.id
    }

    private fun resolveComposeImageContext(expectedChapterIndex: Int, chapter: ContentChapter) {
        lifecycleScope.launch(Dispatchers.IO) {
            val chapterIndex = when {
                chapter.url.startsWith("epub://") -> Regex("epub://(-?\\d+)/chapter/(\\d+)")
                    .matchEntire(chapter.url)
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()

                chapter.url.contains("#chapter/") -> Regex("#chapter/(\\d+)")
                    .find(chapter.url)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                else -> null
            }
            val contentId = Regex("epub://(-?\\d+)/chapter/\\d+")
                .matchEntire(chapter.url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: manga.id
            val mapping = chapterIndex?.let { index ->
                epubChapterMappingDao.findByContentId(contentId)
                    .sortedWith(compareBy({ it.parentChapterId }, { it.chapterIndex }))
                    .getOrNull(index)
            }
            val epubFile = mapping
                ?.epubFilePath
                ?.let { path -> java.io.File(path) }
                ?.takeIf { file -> file.exists() }
            val chapterPath = if (epubFile != null) {
                epubContentCache.get(epubFile)?.chapters?.getOrNull(chapterIndex)?.href
            } else {
                null
            }
            withContext(Dispatchers.Main) {
                if (isCurrentChapter(expectedChapterIndex, chapter)) {
                    publishComposeImageContext(epubFile, chapterPath)
                }
            }
        }
    }

    private fun finishComposeChapterRender(chapter: ContentChapter) {
        desiredProgressRatio = null
        updateNavigationButtons()
        if (settings.isReaderChapterToastEnabled) {
            showReaderMessage(chapter.title ?: getString(R.string.unnamed_chapter), 2000L)
        }
        if (pendingTtsAutoStart) {
            pendingTtsAutoStart = false
            contentRoot.postDelayed({
                if (composeReaderViewModel.uiState.value.ttsControlsVisible) {
                    startTtsFromCurrentPage()
                }
            }, 500L)
        }
        if (readerSettings.isTranslationEnabled) {
            startTranslation()
        }
    }

    /**
     * 从URL或本地文件加载EPUB内容（带缓存）
     */
    private suspend fun loadEpubContentFromUrl(url: String, chapter: ContentChapter): org.skepsun.kototoro.local.epub.EpubContent? {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("NovelReaderActivity", "Loading EPUB from URL: $url")

                // 首先尝试从本地下载的文件加载
                val localFile = findLocalEpubFile(chapter)
                if (localFile != null && localFile.exists()) {
                    android.util.Log.d("NovelReaderActivity", "Found local EPUB file: ${localFile.absolutePath}")

                    // 检查缓存 (cache is now managed by EpubReaderImpl)
                    val cachedContent = epubContentCache.get(localFile)
                    if (cachedContent != null) {
                        android.util.Log.d("NovelReaderActivity", "Using cached EPUB content for: ${localFile.absolutePath}")
                        return@withContext cachedContent
                    }

                    // 读取并缓存 (cache is automatically managed by EpubReaderImpl)
                    val epubReader = org.skepsun.kototoro.local.epub.EpubReaderImpl(epubContentCache)
                    val content = epubReader.readEpub(localFile)
                    if (content != null) {
                        android.util.Log.d("NovelReaderActivity", "Loaded and cached EPUB content for: ${localFile.absolutePath}")
                    }
                    return@withContext content
                }

                // 如果是file://协议，尝试直接读取
                if (url.startsWith("file://")) {
                    val filePath = url.substring(7)
                    val file = java.io.File(filePath)
                    if (file.exists()) {
                        android.util.Log.d("NovelReaderActivity", "Loading from file path: $filePath")

                        // 检查缓存 (cache is now managed by EpubReaderImpl)
                        val cachedContent = epubContentCache.get(file)
                        if (cachedContent != null) {
                            android.util.Log.d("NovelReaderActivity", "Using cached EPUB content for: $filePath")
                            return@withContext cachedContent
                        }

                        // 读取并缓存 (cache is automatically managed by EpubReaderImpl)
                        val epubReader = org.skepsun.kototoro.local.epub.EpubReaderImpl(epubContentCache)
                        val content = epubReader.readEpub(file)
                        if (content != null) {
                            android.util.Log.d("NovelReaderActivity", "Loaded and cached EPUB content for: $filePath")
                        }
                        return@withContext content
                    }
                }

                android.util.Log.w("NovelReaderActivity", "EPUB file not found locally")
                null
            } catch (e: Exception) {
                android.util.Log.e("NovelReaderActivity", "Failed to load EPUB from URL", e)
                null
            }
        }
    }

    /**
     * 查找本地下载的EPUB文件（可能被重命名为.cbz）
     */
    private fun findLocalEpubFile(chapter: ContentChapter): java.io.File? {
        try {
            // 获取下载目录
            val downloadDir = getExternalFilesDir(null)?.resolve("manga") ?: return null

            android.util.Log.d("NovelReaderActivity", "Searching for EPUB file in: ${downloadDir.absolutePath}")
            android.util.Log.d("NovelReaderActivity", "Content ID: ${manga.id}, Title: ${manga.title}")
            android.util.Log.d("NovelReaderActivity", "Chapter ID: ${chapter.id}, Title: ${chapter.title}")

            // 策略1: 按manga ID查找目录
            val mangaIdStr = manga.id.toString()
            val mangaDirs = listOf(
                downloadDir.resolve(mangaIdStr),
                downloadDir.resolve("_${mangaIdStr}"),
                downloadDir.resolve("__${mangaIdStr}"),
            )

            for (dir in mangaDirs) {
                if (!dir.exists() || !dir.isDirectory) continue

                val files = dir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".cbz") || file.name.endsWith(".epub"))
                }

                if (files != null && files.isNotEmpty()) {
                    val file = files.firstOrNull()
                    if (file != null) {
                        android.util.Log.d("NovelReaderActivity", "Found EPUB file in manga dir: ${file.absolutePath}")
                        return file
                    }
                }
            }

            // 策略2: 在所有子目录中查找，并通过index.json验证是否属于当前manga
            val allDirs = downloadDir.listFiles { file -> file.isDirectory } ?: emptyArray()
            android.util.Log.d("NovelReaderActivity", "Searching in ${allDirs.size} directories")

            for (dir in allDirs) {
                // 检查index.json中的manga信息
                val indexFile = dir.resolve("index.json")
                if (indexFile.exists()) {
                    try {
                        val indexContent = indexFile.readText()
                        android.util.Log.d("NovelReaderActivity", "Checking ${dir.name}/index.json")

                        // 严格匹配：必须完全匹配manga ID
                        val idPattern1 = "\"id\":${manga.id}"
                        val idPattern2 = "\"id\": ${manga.id}"
                        val idPattern3 = "\"id\" : ${manga.id}"

                        if (indexContent.contains(idPattern1) ||
                            indexContent.contains(idPattern2) ||
                            indexContent.contains(idPattern3)) {

                            android.util.Log.d("NovelReaderActivity", "Content ID matched in ${dir.name}")

                            val files = dir.listFiles { file ->
                                file.isFile && (file.name.endsWith(".cbz") || file.name.endsWith(".epub"))
                            }

                            if (files != null && files.isNotEmpty()) {
                                val file = files.firstOrNull()
                                if (file != null) {
                                    android.util.Log.d("NovelReaderActivity", "Found EPUB file by index.json: ${file.absolutePath}")
                                    return file
                                }
                            }
                        } else {
                            android.util.Log.d("NovelReaderActivity", "Content ID not matched in ${dir.name} (looking for ${manga.id})")
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("NovelReaderActivity", "Failed to read index.json in ${dir.name}", e)
                    }
                } else {
                    android.util.Log.d("NovelReaderActivity", "No index.json in ${dir.name}")
                }
            }

            android.util.Log.d("NovelReaderActivity", "No local EPUB file found for manga ${manga.id}")
        } catch (e: Exception) {
            android.util.Log.e("NovelReaderActivity", "Error finding local EPUB file", e)
        }
        return null
    }

    /**
     * 展开EPUB章节：将EPUB文件章节替换为其内部章节列表
     *
     * 修复：
     * 1. 使用卷名作为前缀，避免章节名重复
     * 2. 使用数据库映射的章节ID，确保与详情页一致
     */
    private suspend fun expandEpubChapters(originalChapters: List<ContentChapter>): List<ContentChapter> {
        // 本地 CBZ 小说直接返回，避免误判为 EPUB
        if (manga.isLocal) {
            android.util.Log.d("NovelReaderActivity", "expandEpubChapters: manga is local, skip expansion")
            return originalChapters
        }

        val expandedChapters = mutableListOf<ContentChapter>()

        android.util.Log.d("NovelReaderActivity", "expandEpubChapters: Processing ${originalChapters.size} chapters")

        for (chapter in originalChapters) {
            try {
                // 快速检查：解析器已填充格式候选，或 URL 像 EPUB 文件，才进入网络探测
                // EPUB 文件通常以.epub结尾，或者URL中包含epub关键字
                val isLikelyEpub = chapter.ebookFormats.isNotEmpty() ||
                    chapter.url.contains(".epub", ignoreCase = true) ||
                    chapter.url.contains("epub", ignoreCase = true)

                if (!isLikelyEpub) {
                    // 不像EPUB文件，直接添加，跳过网络请求
                    android.util.Log.d("NovelReaderActivity", "Chapter '${chapter.title}': Not EPUB-like URL, skipping check")
                    expandedChapters.add(chapter)
                    continue
                }

                // 可能是电子书，需要检查（解析器格式候选优先，旧解析器回退 preview 标记）
                android.util.Log.d("NovelReaderActivity", "Chapter '${chapter.title}': Checking if text ebook...")
                val pages = repository.getPages(chapter)
                android.util.Log.d("NovelReaderActivity", "Chapter '${chapter.title}': ${pages.size} pages, preview='${pages.firstOrNull()?.preview}'")

                if (chapter.isTextEbookChapter(pages)) {
                    android.util.Log.d("NovelReaderActivity", "Found text ebook chapter: ${chapter.title}, ID=${chapter.id}, expanding...")

                    // 首先尝试从数据库读取已保存的章节映射
                    val dbMappings = try {
                        epubChapterMappingDao.getByParentId(chapter.id)
                    } catch (e: Exception) {
                        android.util.Log.e("NovelReaderActivity", "Failed to query chapter mappings", e)
                        emptyList()
                    }

                    if (dbMappings.isNotEmpty()) {
                        // 使用数据库中的映射
                        android.util.Log.d("NovelReaderActivity", "Using ${dbMappings.size} chapters from database")

                        for (mapping in dbMappings.sortedBy { it.chapterIndex }) {
                            val internalChapter = ContentChapter(
                                id = mapping.internalChapterId,
                                title = mapping.chapterTitle,  // 不添加卷名前缀，详情页已经分组
                                number = chapter.number + mapping.chapterIndex,
                                volume = chapter.volume,
                                url = buildEpubChapterUrl(mapping.epubFilePath, mapping.chapterIndex),
                                scanlator = chapter.scanlator,
                                uploadDate = mapping.createdAt,
                                branch = chapter.branch,
                                source = chapter.source,
                            )
                            expandedChapters.add(internalChapter)
                        }
                        android.util.Log.d("NovelReaderActivity", "Expanded EPUB chapter from database into ${dbMappings.size} internal chapters")
                    } else {
                        // 数据库中没有映射，尝试读取EPUB内容
                        android.util.Log.d("NovelReaderActivity", "No database mappings found, reading EPUB content")
                        val epubContent = loadEpubContentFromUrl(pages[0].url, chapter)

                        if (epubContent != null && epubContent.chapters.isNotEmpty()) {
                            // 不过滤，显示所有章节
                            epubContent.chapters.forEachIndexed { chapterIndex, epubChapter ->
                                val generatedUrl = "${pages[0].url}#chapter/$chapterIndex"
                                // 使用与DownloadWorker相同的ID生成算法
                                val internalChapterId = chapter.id + (chapterIndex * 1000000L) + 1

                                val internalChapter = ContentChapter(
                                    id = internalChapterId,
                                    title = epubChapter.title,  // 不添加卷名前缀，详情页已经分组
                                    number = chapter.number + chapterIndex,
                                    volume = chapter.volume,
                                    url = generatedUrl,
                                    scanlator = chapter.scanlator,
                                    uploadDate = chapter.uploadDate,
                                    branch = chapter.branch,
                                    source = chapter.source,
                                )
                                expandedChapters.add(internalChapter)

                                // 打印章节映射信息（仅前5个和后5个）
                                if (chapterIndex < 5 || chapterIndex >= epubContent.chapters.size - 5) {
                                    android.util.Log.d("NovelReaderActivity", "  Chapter mapping: index=$chapterIndex, id=$internalChapterId, title='${internalChapter.title}', url='${internalChapter.url.takeLast(15)}'")
                                }
                            }
                            android.util.Log.d("NovelReaderActivity", "Expanded EPUB chapter into ${epubContent.chapters.size} internal chapters")
                        } else {
                            // 读取失败，保留原始章节
                            android.util.Log.d("NovelReaderActivity", "Failed to expand EPUB chapter, keeping original")
                            expandedChapters.add(chapter)
                        }
                    }
                } else {
                    // 非EPUB章节，直接添加
                    expandedChapters.add(chapter)
                }
            } catch (e: Exception) {
                android.util.Log.e("NovelReaderActivity", "Failed to expand chapter: ${chapter.title}", e)
                // 出错时保留原始章节
                expandedChapters.add(chapter)
            }
        }

        return expandedChapters
    }

    private fun updateProgress(page: Int, total: Int) {
        val ratio = getCurrentProgressRatio()
        val sliderValue = if (total > 1) {
            (ratio * (total - 1)).toInt().coerceIn(0, total - 1)
        } else 0
        // 页码显示使用当前分页（单页或双页 spread）
        composeReaderViewModel.publishProgress(
            value = sliderValue.toFloat(),
            max = (total - 1).coerceAtLeast(0).toFloat(),
            label = "${page + 1} / ${total.coerceAtLeast(1)}",
        )
    }

    private fun updateNavigationButtons() {
        composeReaderViewModel.publishChrome(workTitle = manga.title)
    }

    private fun setUiVisible(visible: Boolean) {
        if (isUiVisible == visible) return
        isUiVisible = visible
        composeReaderViewModel.publishChrome(
            controlsVisible = visible,
            workTitle = manga.title,
        )
        systemUiController.setSystemUiVisible(!readerSettings.enableFullscreen || visible)
        updateSystemBarsColors()
        spaceSwitcherDelegate.setControlsVisible(
            visible = visible,
            hideWithControlsTransition = !visible && isAnimationsEnabled,
        )
    }
    private fun showLoading(loading: Boolean) {
        composeReaderViewModel.setLoading(loading)
    }

    private fun showError(message: String) {
        showReaderMessage(message, 3000L)
    }

    private fun showReaderMessage(message: String, durationMillis: Long) {
        composeReaderViewModel.showMessage(message, durationMillis)
    }

    private fun decodeChapterHtml(url: String): String {
        if (url.startsWith("data:", ignoreCase = true)) {
            val commaIndex = url.indexOf(',')
            if (commaIndex != -1) {
                val meta = url.substring(5, commaIndex)
                val data = url.substring(commaIndex + 1)
                return if (meta.contains("base64", ignoreCase = true)) {
                    val decoded = Base64.decode(data, Base64.DEFAULT)
                    String(decoded, Charsets.UTF_8)
                } else {
                    data
                }
            }
        }
        return "<html><body>${getString(R.string.chapter_is_missing)}</body></html>"
    }

    private fun htmlToPlainText(html: String): String {
        return html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()
            .lines()
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private fun updateDualPageMode() {
        composeReaderViewModel.publishSettings(readerSettings)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CHAPTER_INDEX, currentChapterIndex)
        outState.putInt(
            KEY_PAGE_INDEX,
            composeReaderViewModel.uiState.value.position?.page ?: currentPageIndex,
        )
        outState.putBoolean(KEY_UI_VISIBLE, isUiVisible)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        tvPresentationEnabled = resolveUiPresentationConfig(this, settings).isTv
        resetEInkRefreshContext(clearIdentity = true)
        applyReaderPalette()
        // 保存当前进度（按字符比例），用于横竖屏/单双页切换后的恢复
        val position = composeReaderViewModel.uiState.value.position
        val ratio = position?.normalizedChapterProgress ?: 0f
        val currentStart = composeReaderViewModel.uiState.value.currentPageStart
        val currentEnd = composeReaderViewModel.uiState.value.currentPageEnd
        val wasDual = readerSettings.enableDualPage

        updateDualPageMode()

        val nowDual = readerSettings.enableDualPage
        // 若从单页->双页，保证旧页首字符出现；双页->单页，保证旧页尾字符出现
        if (wasDual != nowDual) {
            if (!wasDual && nowDual) {
                composeReaderViewModel.requestPage(position?.page ?: currentPageIndex)
            } else if (wasDual && !nowDual) {
                composeReaderViewModel.requestPage(position?.page ?: currentPageIndex)
            } else {
                composeReaderViewModel.requestPage(position?.page ?: currentPageIndex)
            }
        } else {
            composeReaderViewModel.requestPage(position?.page ?: currentPageIndex)
        }
        android.util.Log.d("NovelReaderActivity", "Configuration changed, saved ratio: $ratio, start=$currentStart, end=$currentEnd, wasDual=$wasDual, nowDual=$nowDual")
    }

    companion object {
        const val EXTRA_MARKING_ID = "org.skepsun.kototoro.marking_id"
        const val EXTRA_START_OFFSET = "org.skepsun.kototoro.start_offset"
        const val EXTRA_END_OFFSET = "org.skepsun.kototoro.end_offset"
        const val EXTRA_SELECTED_TEXT = "org.skepsun.kototoro.selected_text"
        private const val KEY_CHAPTER_INDEX = "chapter_index"
        private const val KEY_PAGE_INDEX = "page_index"
        private const val KEY_UI_VISIBLE = "ui_visible"
        private const val TRANSLATION_PROGRESS_TOAST_DURATION = 1800L
        private const val CONTINUOUS_TAP_DEBOUNCE_MS = 420L
        private const val NOVEL_SCROLL_TAP_LOG_TAG = "NovelScrollTap"
    }

    /**
     * 显示章节选择器
     */
    private fun showChaptersSheet() {
        android.util.Log.d("NovelReaderActivity", "showChaptersSheet: chapters.size=${chapters.size}, currentChapterIndex=$currentChapterIndex")
        if (chapters.isEmpty()) {
            showReaderMessage("暂无章节", 1500L)
            return
        }

        // 打印前5个章节的URL用于调试
        chapters.take(5).forEachIndexed { index, chapter ->
            android.util.Log.d("NovelReaderActivity", "  Chapter[$index]: title='${chapter.title}', url='${chapter.url.takeLast(15)}'")
        }

        composeReaderViewModel.showChapters(
            displayChapters.ifEmpty { chapters },
            currentChapterIndex,
        )
    }

    /**
     * 显示设置面板
     */
    private fun showConfigSheet() {
        composeReaderViewModel.showSettings(readerSettings)
    }

    private fun applyInitialUiVisibility() {
        val visible = !readerSettings.enableFullscreen
        spaceSwitcherDelegate.setControlsVisible(visible)
        isUiVisible = visible
        composeReaderViewModel.publishChrome(
            controlsVisible = visible,
            workTitle = manga.title,
        )
        systemUiController.setSystemUiVisible(!readerSettings.enableFullscreen || visible)
        updateSystemBarsColors()
    }

    private fun onChapterSelected(index: Int) {
        android.util.Log.d("NovelReaderActivity", "onChapterSelected: index=$index, currentChapterIndex=$currentChapterIndex, chapters.size=${chapters.size}")
        if (index != currentChapterIndex && index in chapters.indices) {
            val previousState = currentReaderState()
            val selectedChapter = chapters[index]
            android.util.Log.d("NovelReaderActivity", "Loading selected chapter: index=$index, title='${selectedChapter.title}', url='${selectedChapter.url}'")
            if (previousState != null) {
                finishReadingSession(allowShort = true, continueFromEnd = false)
                recordJumpPointIfNeeded(
                    previousState,
                    ReaderState(selectedChapter.id, 0, 0),
                    "chapter_list",
                    force = true,
                )
            }
            currentChapterIndex = index
            currentPageIndex = 0
            loadChapter(currentChapterIndex)
        } else {
            android.util.Log.d("NovelReaderActivity", "Chapter selection ignored: index=$index, same as current or out of bounds")
        }
    }

    /**
     * 处理手势
     */
    private fun handleTapGesture(area: org.skepsun.kototoro.reader.domain.TapGridArea) {
        val action = tapGridSettings.getTapAction(area, false)
        android.util.Log.d(
            NOVEL_SCROLL_TAP_LOG_TAG,
            "handleTapGesture area=$area action=$action ui=$isUiVisible",
        )

        when (action) {
            org.skepsun.kototoro.reader.ui.tapgrid.TapAction.PAGE_NEXT -> switchPageBy(1)
            org.skepsun.kototoro.reader.ui.tapgrid.TapAction.PAGE_PREV -> switchPageBy(-1)
            org.skepsun.kototoro.reader.ui.tapgrid.TapAction.CHAPTER_NEXT -> switchChapterBy(1)
            org.skepsun.kototoro.reader.ui.tapgrid.TapAction.CHAPTER_PREV -> switchChapterBy(-1)
            org.skepsun.kototoro.reader.ui.tapgrid.TapAction.TOGGLE_UI -> toggleUiVisibility()
            org.skepsun.kototoro.reader.ui.tapgrid.TapAction.SHOW_MENU -> openMenu()
            null -> {
                // 空动作必须保持为空；不再以切换工具栏作为隐式兜底。
            }
        }
    }

    private fun handleContinuousTap(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        eventTime: Long = SystemClock.uptimeMillis(),
        source: String = "unknown",
    ) {
        if (width <= 0 || height <= 0) {
            android.util.Log.d(
                NOVEL_SCROLL_TAP_LOG_TAG,
                "continuousTap ignored invalid size source=$source x=$x y=$y width=$width height=$height event=$eventTime",
            )
            return
        }
        val sinceLast = eventTime - lastContinuousTapHandledTime
        if (sinceLast < CONTINUOUS_TAP_DEBOUNCE_MS) {
            android.util.Log.d(
                NOVEL_SCROLL_TAP_LOG_TAG,
                "continuousTap ignored debounce source=$source lastSource=$lastContinuousTapSource " +
                    "event=$eventTime last=$lastContinuousTapHandledTime delta=$sinceLast threshold=$CONTINUOUS_TAP_DEBOUNCE_MS " +
                    "ui=$isUiVisible",
            )
            return
        }
        lastContinuousTapHandledTime = eventTime
        lastContinuousTapSource = source

        val normalizedX = x / width.toFloat()
        val normalizedY = y / height.toFloat()
        val area = when {
            normalizedY < 0.33f -> when {
                normalizedX < 0.33f -> org.skepsun.kototoro.reader.domain.TapGridArea.TOP_LEFT
                normalizedX > 0.66f -> org.skepsun.kototoro.reader.domain.TapGridArea.TOP_RIGHT
                else -> org.skepsun.kototoro.reader.domain.TapGridArea.TOP_CENTER
            }
            normalizedY > 0.66f -> when {
                normalizedX < 0.33f -> org.skepsun.kototoro.reader.domain.TapGridArea.BOTTOM_LEFT
                normalizedX > 0.66f -> org.skepsun.kototoro.reader.domain.TapGridArea.BOTTOM_RIGHT
                else -> org.skepsun.kototoro.reader.domain.TapGridArea.BOTTOM_CENTER
            }
            else -> when {
                normalizedX < 0.33f -> org.skepsun.kototoro.reader.domain.TapGridArea.CENTER_LEFT
                normalizedX > 0.66f -> org.skepsun.kototoro.reader.domain.TapGridArea.CENTER_RIGHT
                else -> org.skepsun.kototoro.reader.domain.TapGridArea.CENTER
            }
        }

        android.util.Log.d(
            NOVEL_SCROLL_TAP_LOG_TAG,
            "continuousTap accepted source=$source event=$eventTime x=$x y=$y width=$width height=$height " +
                "nx=$normalizedX ny=$normalizedY area=$area ui=$isUiVisible",
        )
        handleTapGesture(area)
    }

    private fun updateReadingStatus(page: Int, total: Int) {
        val chapter = chapters.getOrNull(currentChapterIndex) ?: return
        val uiState = org.skepsun.kototoro.reader.ui.pager.ReaderUiState(
            mangaName = manga.title,
            chapter = chapter,
            chapterIndex = currentChapterIndex,
            chaptersTotal = chapters.size,
            currentPage = page,
            totalPages = total,
            percent = getCurrentProgressRatio(),
            incognito = false // TODO: 获取无痕模式状态
        )

        // 更新历史记录使用实际页数（单页计数）
        updateHistory(page, total)
    }

    /**
     * 更新历史记录和阅读进度
     */
    private fun updateHistory(
        page: Int,
        total: Int,
        propagateFailure: Boolean = false,
    ): Job? {
        val chapter = chapters.getOrNull(currentChapterIndex) ?: return null

        return lifecycleScope.launch(CoroutineExceptionHandler { _, error ->
            android.util.Log.e("NovelReaderActivity", "History save job failed", error)
        }) {
            try {
                // 确保保存历史时包含完整目录：优先使用当前内存中的章节（已合并本地/远端），并修正来源
                val fixedSource = originalContent?.source ?: manga.source
                val fixedUrl = originalContent?.url ?: manga.url
                val baseContent = if (chapters.isNotEmpty()) {
                    manga.copy(chapters = chapters, source = fixedSource, url = fixedUrl)
                } else if (manga.chapters.isNullOrEmpty()) {
                    try {
                        repository.getDetails(manga).copy(source = fixedSource, url = fixedUrl)
                    } catch (e: Exception) {
                        android.util.Log.e("NovelReaderActivity", "Failed to get manga details for history", e)
                        manga.copy(source = fixedSource, url = fixedUrl)
                    }
                } else {
                    manga.copy(source = fixedSource, url = fixedUrl)
                }

                // 合并远端目录与当前章节（避免历史保存时只有已下载章节）
                val mergedForHistory = if (baseContent.isLocal && originalContent?.chapters != null) {
                    val remoteChapters = originalContent?.chapters.orEmpty()
                    val localById = baseContent.chapters.orEmpty().associateBy { it.id }
                    val merged = remoteChapters.map { localById[it.id] ?: it }.toMutableList()
                    val remoteIds = remoteChapters.map { it.id }.toSet()
                    baseContent.chapters.orEmpty().filterNot { it.id in remoteIds }.forEach { merged.add(it) }
                    baseContent.copy(chapters = merged, source = originalContent!!.source)
                } else {
                    baseContent
                }
                val mangaWithChapters = if (originalContent != null) {
                    originalContent!!.copy(
                        chapters = mergedForHistory.chapters,
                        source = originalContent!!.source,
                        url = originalContent!!.url,
                    )
                } else {
                    mergedForHistory
                }

                // 如果仍然没有章节信息，不保存历史
                if (mangaWithChapters.chapters.isNullOrEmpty()) {
                    android.util.Log.d("NovelReaderActivity", "Cannot save history: no chapters available")
                    return@launch
                }

                // 计算当前章节在所有章节中的进度（使用字符偏移更精确，兼容单/双页）
                val chapterProgress = getCurrentProgressRatio()

                // 计算总体阅读进度
                // 进度 = (已读完章节数 + 当前章节进度) / 总章节数
                val totalProgress = if (chapters.isNotEmpty()) {
                    (currentChapterIndex + chapterProgress) / chapters.size
                } else {
                    0f
                }.coerceIn(0f, 1f)

                android.util.Log.d("NovelReaderActivity", "Updating history: chapter=$currentChapterIndex/${chapters.size}, page=$page/$total, progress=$totalProgress")

                // 创建 ReaderState（仍沿用页码字段，历史恢复时会重新分页）
                val readerState = ReaderState(
                    chapterId = chapter.id,
                    page = page,
                    scroll = (chapterProgress * 10000).toInt()
                )
                ensureReadingSession(readerState, totalProgress)

                // 异步更新历史记录
                historyUpdateUseCase(mangaWithChapters, readerState, totalProgress)

                android.util.Log.d("NovelReaderActivity", "History update invoked successfully")
            } catch (e: Exception) {
                android.util.Log.e("NovelReaderActivity", "Failed to update history", e)
                if (propagateFailure) throw e
            }
        }
    }

    private suspend fun flushForSpaceSwitch() {
        ttsService?.stopTts()
        val position = composeReaderViewModel.uiState.value.position
        val page = position?.page ?: currentPageIndex
        val total = position?.pageCount ?: 0
        updateHistory(page, total, propagateFailure = true)?.awaitCompletion()
        finishReadingSession(allowShort = true, continueFromEnd = false)?.awaitCompletion()
    }

    private fun currentReaderState(): ReaderState? {
        val chapter = chapters.getOrNull(currentChapterIndex) ?: return null
        val position = composeReaderViewModel.uiState.value.position
            ?.takeIf { it.chapterId == chapter.id }
            ?: NovelReadingPosition(
                chapterId = chapter.id,
                page = currentPageIndex.coerceAtLeast(0),
                pageCount = 0,
                chapterProgress = getCurrentProgressRatio(),
            )
        return position.toReaderState()
    }

    private fun publishComposeImageContext(epubFile: java.io.File?, chapterPath: String?) {
        val imageHeaders = imageHeadersProvider
            ?.invoke(chapters.getOrNull(currentChapterIndex)?.url.orEmpty())
            .orEmpty()
        composeReaderViewModel.publishImageContext(
            NovelComposeImageContext(
                epubFilePath = epubFile?.absolutePath,
                chapterPath = chapterPath,
                headers = imageHeaders,
            ),
        )
    }

    private fun ensureReadingSession(state: ReaderState, percent: Float) {
        if (sessionStartState != null) return
        sessionStartAt = System.currentTimeMillis()
        sessionStartState = state
        sessionStartPercent = percent
    }

    private fun finishReadingSession(
        allowShort: Boolean = false,
        continueFromEnd: Boolean = true,
    ): Job? {
        val mangaWithChapters = manga.copy(chapters = chapters)
        if (readingRecordRepository.shouldSkip(mangaWithChapters)) return null
        val startState = sessionStartState ?: currentReaderState() ?: return null
        val endState = currentReaderState() ?: startState
        val startAt = sessionStartAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val endAt = System.currentTimeMillis()
        val startPercent = sessionStartPercent
        val endPercent = computeTotalProgress(endState)
        if (continueFromEnd) {
            sessionStartAt = endAt
            sessionStartState = endState
            sessionStartPercent = endPercent
        } else {
            sessionStartAt = 0L
            sessionStartState = null
            sessionStartPercent = 0f
        }
        return lifecycleScope.launch(
            Dispatchers.Default + CoroutineExceptionHandler { _, error ->
                android.util.Log.e("NovelReaderActivity", "Reading record save failed", error)
            },
        ) {
            readingRecordRepository.recordSession(
                manga = mangaWithChapters,
                startAt = startAt,
                endAt = endAt,
                startState = startState,
                startPercent = startPercent,
                endState = endState,
                endPercent = endPercent,
                allowShort = allowShort,
            )
        }
    }

    private fun recordJumpPointIfNeeded(
        fromState: ReaderState,
        toState: ReaderState,
        source: String,
        force: Boolean = false,
    ) {
        val mangaWithChapters = manga.copy(chapters = chapters)
        if (readingRecordRepository.shouldSkip(mangaWithChapters)) return
        if (fromState == toState || (!force && !isExplicitJump(fromState, toState))) return
        lifecycleScope.launch(Dispatchers.Default) {
            readingRecordRepository.recordJumpPoint(
                manga = mangaWithChapters,
                fromState = fromState,
                fromPercent = computeTotalProgress(fromState),
                toState = toState,
                toPercent = computeTotalProgress(toState),
                source = source,
            )
        }
    }

    private fun computeTotalProgress(state: ReaderState): Float {
        val chapterIndex = chapters.indexOfFirst { it.id == state.chapterId }
        if (chapterIndex < 0 || chapters.isEmpty()) return 0f
        val chapterProgress = (state.scroll / 10000f).coerceIn(0f, 1f)
        return ((chapterIndex + chapterProgress) / chapters.size).coerceIn(0f, 1f)
    }

    private fun isExplicitJump(fromState: ReaderState, toState: ReaderState): Boolean {
        if (fromState.chapterId == toState.chapterId) {
            return kotlin.math.abs(fromState.page - toState.page) > 1
        }
        val fromIndex = chapters.indexOfFirst { it.id == fromState.chapterId }
        val toIndex = chapters.indexOfFirst { it.id == toState.chapterId }
        if (fromIndex < 0 || toIndex < 0) return true
        return kotlin.math.abs(fromIndex - toIndex) > 1
    }

    /**
     * 基于字符偏移（翻页模式）或滚动偏移（滚动模式）的进度（0f-1f）
     */
    private fun getCurrentProgressRatio(): Float {
        val state = composeReaderViewModel.uiState.value
        if (readerSettings.readingMode == ReadingMode.SCROLL) {
            return if (state.progressMax > 0f) {
                (state.progressValue / state.progressMax).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
        return state.position?.normalizedChapterProgress ?: 0f
    }

    /**
     * 更新阅读状态可见性
     */
    private fun updateReadingStatusVisibility() {
        // 更新阅读状态背景可见性
        applyInfoBarColorScheme()
        updateNovelContentTopInset()

        // 刷新一次阅读状态
        val position = composeReaderViewModel.uiState.value.position
        updateReadingStatus(position?.page ?: 0, position?.pageCount ?: 0)
    }

    /**
     * 更新状态栏和底部导航栏颜色
     */
    private fun updateSystemBarsColors() {
        val palette = readerPalette ?: buildReaderPalette()
        val isDark = palette.isDark
        val visibleBarColor = palette.chromeBackgroundColor
        val immersiveBarColor = ColorUtils.setAlphaComponent(palette.backgroundColor, if (isDark) 242 else 248)

        // 状态栏
        if (!readerSettings.enableFullscreen) {
            window.statusBarColor = immersiveBarColor
        } else {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        }

        window.navigationBarColor = if (isUiVisible) visibleBarColor else immersiveBarColor
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = !isDark
            isAppearanceLightStatusBars = !isDark
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    }

    /**
     * 更新全屏模式
     */
    private fun updateFullscreenMode() {
        // 根据全屏设置和当前 UI 状态控制系统 UI
        val shouldShowSystemUi = !readerSettings.enableFullscreen || isUiVisible
        systemUiController.setSystemUiVisible(shouldShowSystemUi)

        // 更新系统栏颜色
        updateSystemBarsColors()
    }

    private fun applyNovelReaderSettings(settings: NovelReaderSettings) {
        settings.save(this)
        try {
            android.util.Log.d("NovelReaderActivity", "Settings changed: fontSize=${settings.fontSizeSp}")
            val previousSettings = readerSettings
            val previousDisplayMode = previousSettings.translationDisplayMode
            readerSettings = settings
            if (previousSettings != settings) {
                resetEInkRefreshContext(clearIdentity = true)
            }

            runOnUiThread {
                try {
                    applyReaderPalette()
                    applyReaderBrightness(settings)
                    applyReadingModeToggles()

                    updateDualPageMode()
                    updateFullscreenMode()
                    updateReadingStatusVisibility()

                    // 若翻译展示模式变更且翻译已启用，重启翻译使新模式立即生效
                    if (settings.isTranslationEnabled &&
                        settings.translationDisplayMode != previousDisplayMode) {
                        startTranslation()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NovelReaderActivity", "Failed to update settings", e)
                    showError("更新设置失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NovelReaderActivity", "Failed to apply settings", e)
        }
    }

    private fun onEInkPagedPositionChanged(chapterId: Long, page: Int) {
        val current = EInkPageIdentity(chapterId, page)
        val shouldRefresh = eInkRefreshPolicy.shouldRefresh(
            enabled = settings.isEInkModeEnabled && settings.isEInkRefreshEnabled,
            isPagedMode = readerSettings.readingMode == ReadingMode.PAGED,
            previous = lastEInkPageIdentity,
            current = current,
            interval = settings.eInkRefreshEveryPages,
        )
        lastEInkPageIdentity = current
        if (shouldRefresh) {
            eInkRefresh = ReaderEInkRefresh(
                id = ++nextEInkRefreshId,
                durationMillis = settings.eInkRefreshDurationMillis,
                colorArgb = settings.eInkRefreshColor.colorInt,
            )
        }
    }

    private fun resetEInkRefreshContext(clearIdentity: Boolean) {
        eInkRefreshPolicy.reset()
        eInkRefresh = null
        if (clearIdentity) lastEInkPageIdentity = null
    }

    private fun applyReadingModeToggles() {

        if (composeReaderViewModel.uiState.value.content.isBlank()) {
            // Need to reload content into the new view if it was empty
            loadChapter(currentChapterIndex)
        }
    }

    private fun preloadContinuousBoundary(index: Int, isPrevious: Boolean) {
        val chapter = chapters.getOrNull(index) ?: return

        if (isPrevious) isLoadingPrevious = true else isLoadingNext = true

        lifecycleScope.launch(Dispatchers.IO + org.skepsun.kototoro.core.parser.legado.RequestPriority(org.skepsun.kototoro.core.parser.legado.RequestPriority.BACKGROUND)) {
            try {
                // If it's an EPUB chapter, load using EpubLoader
                if (chapter.url.contains("#chapter/") || chapter.url.startsWith("epub://")) {
                    val result = epubInternalChapterLoader.loadEpubInternalChapter(chapter)
                    if (result.isSuccess) {
                        val loadResult = result.getOrNull() ?: return@launch
                        val processedContent = processEpubChapterText(loadResult.content, chapter)
                        withContext(Dispatchers.Main) {
                            val data = NovelChapterData(
                                chapterIndex = index,
                                content = processedContent,
                                epubFile = loadResult.epubFile,
                                chapterPath = loadResult.chapterHref
                            )
                            publishComposeBoundary(data)
                            if (isPrevious) isLoadingPrevious = false else isLoadingNext = false
                        }
                    } else {
                        if (isPrevious) isLoadingPrevious = false else isLoadingNext = false
                    }
                    return@launch
                }

                val chapterRepo = mangaRepositoryFactory.create(chapter.source)

                if (novelContentLoader.isCached(chapter)) {
                    val content = novelContentLoader.loadChapterContent(
                        chapterRepo,
                        chapter,
                        scopeName = manga.title,
                        replaceRulesEnabled = replaceRulesEnabled,
                        disabledReplaceRuleIds = disabledReplaceRuleIds,
                    )
                    withContext(Dispatchers.Main) {
                        val data = NovelChapterData(index, content, null, null)
                        publishComposeBoundary(data)
                        if (isPrevious) isLoadingPrevious = false else isLoadingNext = false
                    }
                    return@launch
                }

                // Fetch directly
                val contentUrl = chapters.getOrNull(index + 1)?.url
                var fullText = ""
                novelContentLoader.loadChapterContentFlow(
                    chapterRepo,
                    chapter,
                    priority = org.skepsun.kototoro.core.parser.legado.RequestPriority.BACKGROUND,
                    nextChapterUrl = contentUrl,
                    scopeName = manga.title,
                    replaceRulesEnabled = replaceRulesEnabled,
                    disabledReplaceRuleIds = disabledReplaceRuleIds,
                ).collect { text ->
                    fullText = text
                }

                withContext(Dispatchers.Main) {
                    val data = NovelChapterData(index, fullText, null, null)
                    publishComposeBoundary(data)
                    if (isPrevious) isLoadingPrevious = false else isLoadingNext = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isPrevious) isLoadingPrevious = false else isLoadingNext = false
                }
            }
        }
    }

    private fun publishComposeBoundary(data: NovelChapterData) {
        val chapter = chapters.getOrNull(data.chapterIndex) ?: return
        val chapterTitle = displayChapterTitle(chapter)
        composeReaderViewModel.publishAdjacentChapter(
            NovelComposeChapterContent(
                chapterId = chapter.id,
                chapterIndex = data.chapterIndex,
                chapterTitle = chapterTitle,
                content = data.content,
                translation = chapterTranslations[data.chapterIndex],
                imageContext = NovelComposeImageContext(
                    epubFilePath = data.epubFile?.absolutePath,
                    chapterPath = data.chapterPath,
                ),
            ),
        )
    }

    private fun requestPreviousComposeChapter() {
        if (isLoadingPrevious) return
        val firstIndex = composeReaderViewModel.uiState.value.continuousChapters
            .firstOrNull()?.chapterIndex ?: currentChapterIndex
        if (firstIndex > 0) preloadContinuousBoundary(firstIndex - 1, isPrevious = true)
    }

    private fun requestNextComposeChapter() {
        if (isLoadingNext) return
        val lastIndex = composeReaderViewModel.uiState.value.continuousChapters
            .lastOrNull()?.chapterIndex ?: currentChapterIndex
        if (lastIndex < chapters.lastIndex) preloadContinuousBoundary(lastIndex + 1, isPrevious = false)
    }

    private fun onComposeVisibleChapterChanged(index: Int) {
        if (index !in chapters.indices || index == currentChapterIndex) return
        currentChapterIndex = index
        updateNavigationButtons()
        updateHistory(0, 1)
    }

    private fun onComposeVisibleProgress(chapterIndex: Int, blockIndex: Int, blockCount: Int) {
        if (chapterIndex !in chapters.indices || blockCount <= 0) return
        val chapterProgress = ((blockIndex + 0.5f) / blockCount).coerceIn(0f, 1f)
        composeReaderViewModel.publishPosition(
            NovelReadingPosition(
                chapterId = chapters[chapterIndex].id,
                page = blockIndex,
                pageCount = blockCount,
                chapterProgress = chapterProgress,
            ),
        )
        val ratio = ((chapterIndex + chapterProgress) / chapters.size.toFloat()).coerceIn(0f, 1f)
        val sliderMax = (chapters.size * 100).coerceAtLeast(1)
        composeReaderViewModel.publishProgress(
            value = blockIndex.toFloat(),
            max = (blockCount - 1).coerceAtLeast(0).toFloat(),
            label = "${blockIndex + 1} / ${blockCount.coerceAtLeast(1)}",
        )
        updateReadingStatus(blockIndex, blockCount)
    }

    private var isToolbarFloating = true

    private fun updateToolbarFloatingStyle(isFloating: Boolean) {
        if (isToolbarFloating == isFloating) return
        isToolbarFloating = isFloating
    }

    private fun buildReaderPalette(): NovelReaderPalette {
        return novelReaderPalette(
            preset = readerSettings.themePreset,
            isDarkTheme = resources.isNightMode,
        )
    }

    private fun applyReaderPalette() {
        val palette = buildReaderPalette()
        readerPalette = palette

        contentRoot.setBackgroundColor(palette.backgroundColor)

        updateToolbarFloatingStyle(isToolbarFloating)
        updateSystemBarsColors()
    }

    private fun applyReaderBrightness(settings: NovelReaderSettings) {
        val attributes = window.attributes
        attributes.screenBrightness = settings.screenBrightness
            ?: android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
    }

    private fun applyInfoBarColorScheme() {
        // Reading status is rendered by the Compose chrome.
    }

    private fun visibleInfoBarHeight(): Int {
        return 0
    }

    private fun updateNovelContentTopInset() {
        val infoBarHeight = visibleInfoBarHeight()
    }

}
