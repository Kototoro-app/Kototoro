package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.history.domain.recoverHistoryChapterId
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.core.IntSize
import java.nio.file.Path
import org.skepsun.kototoro.desktop.runtime.DesktopNovelHtml
import org.skepsun.kototoro.desktop.runtime.DesktopBackupArchive
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryBackup
import org.skepsun.kototoro.desktop.runtime.DesktopBookmark
import org.skepsun.kototoro.desktop.runtime.DesktopLibrarySnapshot
import org.skepsun.kototoro.desktop.runtime.DesktopRepository
import org.skepsun.kototoro.desktop.runtime.DesktopRepositoryCatalog
import org.skepsun.kototoro.extensions.repo.ExtensionStoreIndex

enum class DesktopScreen { EXPLORE, LIBRARY, HISTORY, DETAILS, READER, NOVEL, VIDEO, PREFERENCES, BROWSER, DOWNLOADS, BACKUPS, EXTENSIONS, MORE }

data class DesktopAppState(
    val screen: DesktopScreen = DesktopScreen.EXPLORE,
    val sources: List<SourceListing> = emptyList(),
    val repositories: List<DesktopRepository> = emptyList(),
    val extensionCatalog: DesktopRepositoryCatalog? = null,
    val installedExtensions: Map<String, Long> = emptyMap(),
    val installedEntries: List<DesktopInstalledEntry> = emptyList(),
    val extensionUpdates: List<DesktopExtensionUpdate> = emptyList(),
    val autoUpdateExtensions: Boolean = false,
    val selectedSource: SourceListing? = null,
    val descriptor: SourceDescriptor? = null,
    val items: List<SourceContent> = emptyList(),
    val library: DesktopLibrarySnapshot = DesktopLibrarySnapshot(),
    val librarySelection: DesktopLibrarySelection = DesktopLibrarySelection(),
    val historySelection: DesktopLibrarySelection = DesktopLibrarySelection(),
    val bookmarks: List<DesktopBookmark> = emptyList(),
    val content: SourceContent? = null,
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
    private val mutableState = MutableStateFlow(DesktopAppState(readerSettings = restoredReaderSettings(),
        novelSettings = restoredNovelSettings(), videoEnhancement = restoredVideoEnhancement()))
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

    fun showExtensions() = action {
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

    private fun installedVersions() = session.registry.installed().associate { it.metadata.packageName to it.metadata.versionCode }

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

    fun selectSource(source: SourceListing) = action {
        val descriptor = session.sources.describe(source.source.name)
        mutableState.update { it.copy(screen = DesktopScreen.EXPLORE, selectedSource = source,
            descriptor = descriptor, items = emptyList(), offset = 0, pageOffsets = listOf(0), query = "", content = null,
            browseOrder = null, dynamicFilters = null, appliedFilters = emptyList(), filterDialogOpen = false) }
        browseOnIo(0, "")
    }

    fun browse(offset: Int = 0, query: String = state.value.query) = action { browseOnIo(offset, query) }

    fun explore() = action {
        if (state.value.selectedSource == null) mutableState.update {
            it.copy(screen = DesktopScreen.EXPLORE, items = emptyList(), content = null)
        } else browseOnIo(state.value.offset, state.value.query)
    }

    fun browseUnfiltered(latest: Boolean = false) = action {
        browseOnIo(0, "", supportedOrder(if (latest) "UPDATED" else "POPULARITY"), emptyList())
    }

    /** Lists in the given sort order; [order] must be one of the source's declared orders. */
    fun browseOrdered(order: String) = action {
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

    fun applyFilters(query: String, changes: List<SourceFilterChange>) = action {
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

    fun library(history: Boolean = false) = action {
        val snapshot = session.library.snapshot(history)
        mutableState.update { it.copy(screen = if (history) DesktopScreen.HISTORY else DesktopScreen.LIBRARY,
            items = snapshot.entries.map { row -> row.content }, library = snapshot, content = null) }
    }

    internal fun librarySelection(selection: DesktopLibrarySelection) {
        mutableState.update {
            if (it.screen == DesktopScreen.HISTORY) it.copy(historySelection = selection)
            else if (it.screen == DesktopScreen.LIBRARY) it.copy(librarySelection = selection) else it
        }
    }

    fun details(content: SourceContent) = action {
        val details = session.sources.getDetails(content, SourceDetailsFetchMode.FORCE_REFRESH)
        session.library.save(details)
        val favourite = session.library.isFavourite(details.id)
        mutableState.update { it.copy(screen = DesktopScreen.DETAILS, content = details,
            isFavourite = favourite) }
    }

    fun addFavourite() = action {
        session.library.addFavourite(requireNotNull(state.value.content))
        mutableState.update { it.copy(isFavourite = true, message = "已加入收藏") }
    }

    fun read(chapter: SourceChapter? = null) = action {
        when (DesktopReaderKind.of(state.value.content)) {
            DesktopReaderKind.NOVEL -> readNovelOnIo(chapter)
            DesktopReaderKind.VIDEO -> watchOnIo(chapter)
            DesktopReaderKind.PAGES -> readOnIo(chapter)
        }
    }

    private suspend fun readNovelOnIo(chapter: SourceChapter? = null) {
        val content = requireNotNull(state.value.content)
        val chapters = requireNotNull(content.chapters) { "没有可阅读章节" }
        val progress = session.library.progress(content.id)
        val restoredId = progress?.let { recoverHistoryChapterId(false, it.chapterId, it.parentChapterId,
            it.percent, chapters.map { c -> c.id }) ?: it.chapterId }
        val selected = chapter ?: chapters.firstOrNull { it.id == restoredId } ?: chapters.first()
        val next = SourceChapterNavigation.adjacent(chapters, selected.id, true)?.url
        val body = session.sources.getChapterContent(selected, next) ?: error("此章节没有可显示的文本")
        val blocks = DesktopNovelHtml.parse(body.html, content.publicUrl.takeIf { it.startsWith("http") }.orEmpty())
        require(blocks.isNotEmpty()) { "此章节没有可显示的文本" }
        val start = if (chapter == null && selected.id == restoredId) (progress?.page ?: 0).coerceIn(blocks.indices) else 0
        novelOperations.forget()
        mutableState.update { it.copy(screen = DesktopScreen.NOVEL, chapter = selected, pages = emptyList(),
            readerImages = emptyMap(), novel = DesktopNovel(selected, blocks, body.images.associateBy { image -> image.url }, start)) }
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
        val selected = chapter ?: chapters.firstOrNull { it.id == progress?.chapterId } ?: chapters.first()
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

    fun showDownloads() = action { mutableState.update { it.copy(screen = DesktopScreen.DOWNLOADS) } }

    fun showBackups() = action { mutableState.update { it.copy(screen = DesktopScreen.BACKUPS) } }

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
        val selected = chapter ?: chapters.firstOrNull { it.id == restoredId } ?: chapters.first()
        val pages = session.chapterPages(content.id, selected)
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

    internal fun continuousProgress(chapterId: Long, first: Int, offset: Float, last: Int, atEnd: Boolean) =
        continuous.report(chapterId, first, offset, last, atEnd)

    fun readerSettings(settings: DesktopReaderSettings) = action {
        val previous = state.value.readerSettings
        val saved = readerPreferences.edit(SourcePreferenceEdit(changes = mapOf(
            "mode" to SourcePreferenceValue.Text(settings.mode.name),
            "right_to_left" to SourcePreferenceValue.Toggle(settings.rightToLeft),
            "fit_mode" to SourcePreferenceValue.Text(settings.fitMode.name),
            "automatic_chapter" to SourcePreferenceValue.Toggle(settings.automaticChapter),
            "upscale_model" to SourcePreferenceValue.Text(settings.upscale.model?.name ?: ""),
            "upscale_noise" to SourcePreferenceValue.Integer(settings.upscale.noise),
        )))
        session.upscale = settings.upscale
        // Loaded pages were made for the previous super-resolution choice; they are fetched (from cache) again.
        mutableState.update { it.copy(readerSettings = settings,
            readerImages = if (previous.upscale != settings.upscale) emptyMap() else it.readerImages) }
        if (state.value.screen == DesktopScreen.READER && previous.copy(automaticChapter = settings.automaticChapter) != settings) {
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
            )).also { session.upscale = it.upscale }
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

    fun preferences() = action {
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

    fun browser() = action { mutableState.update { it.copy(screen = DesktopScreen.BROWSER) } }

    fun more() = action { mutableState.update { it.copy(screen = DesktopScreen.MORE) } }

    fun backToDetails() = action {
        closeCast(resume = false)
        mutableState.update { it.copy(screen = DesktopScreen.DETAILS) }
    }

    private fun sourceDescriptors() = session.sourceListings()
        .sortedWith(compareBy({ it.displayName.lowercase() }, { it.source.locale }))

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
