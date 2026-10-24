package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.desktop.compat.MihonDesktopPlatform
import org.skepsun.kototoro.cloudstream.desktop.CloudstreamPluginRegistry
import org.skepsun.kototoro.cloudstream.desktop.CloudstreamSourceRuntime
import org.skepsun.kototoro.desktop.runtime.DesktopCloudstreamRecord
import org.skepsun.kototoro.desktop.runtime.DesktopExtensionFiles
import org.skepsun.kototoro.desktop.runtime.DesktopManagedCloudstreamPlugin
import org.skepsun.kototoro.desktop.runtime.DesktopExtensionKind
import org.skepsun.kototoro.desktop.runtime.DesktopManagedJar
import org.skepsun.kototoro.desktop.runtime.DesktopManagedParserPlugin
import org.skepsun.kototoro.desktop.runtime.DesktopParserPlatform
import org.skepsun.kototoro.desktop.runtime.DesktopParserRecord
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import org.skepsun.kototoro.desktop.runtime.DesktopStorageUsage
import org.skepsun.kototoro.desktop.runtime.desktopStorageUsage
import org.skepsun.kototoro.desktop.runtime.DesktopLibrary
import org.skepsun.kototoro.desktop.runtime.DesktopRepositories
import org.skepsun.kototoro.desktop.runtime.DesktopRepositoryCatalog
import org.skepsun.kototoro.desktop.runtime.persistRepositoryPreference
import org.skepsun.kototoro.extensions.repo.ExtensionStoreIndex
import org.skepsun.kototoro.parserhost.ParserPluginArchitecture
import org.skepsun.kototoro.parserhost.ParserPluginRegistry
import org.skepsun.kototoro.parserhost.ParserSourceInfo
import org.skepsun.kototoro.parserhost.ParserSourceRuntime
import org.skepsun.kototoro.source.host.*
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import org.skepsun.kototoro.reader.core.IntSize
import java.util.concurrent.atomic.AtomicLong

/** Desktop owns platform/storage lifetime; all source calls use the existing shared JSON contract. */
class DesktopSession private constructor(
    val storage: DesktopRuntime,
    private val platform: MihonDesktopPlatform,
    val registry: MihonJarRegistry,
    private val parserPlatform: DesktopParserPlatform,
    val parsers: ParserPluginRegistry,
    /** Android's proxy settings; it routes every request of the shared SDK client. */
    val proxy: org.skepsun.kototoro.desktop.runtime.DesktopProxy,
) : Closeable {
    private val ids = AtomicLong()
    private val installed = storage.preferences.open("desktop_extensions")
    internal val repositories = DesktopRepositories(storage.paths.root, storage.preferences.open("desktop_repositories"))
    internal val sourceDiagnostics = DesktopSourceDiagnostics(storage.paths.root)
    private val mihonRuntime = MihonSourceRuntime(registry, storage.images, platform.preferenceContext())
    private val aniyomiRuntime = AniyomiSourceRuntime(registry, storage.images, platform.preferenceContext())
    private val parserRuntime = ParserSourceRuntime(parsers, parserPlatform, storage.images, ebookAssets = storage.images.directory)
    /** Cloudstream plugins run in the same compatibility runtime and HTTP client (cookies, Cloudflare solver). */
    internal val cloudstream = CloudstreamPluginRegistry(storage.paths.root.resolve("cache/cloudstream"),
        platform.preferenceContext(), platform.sharedHttpClient())
    private val cloudstreamRuntime = CloudstreamSourceRuntime(cloudstream, storage.images)
    /** Android's imported JSON sources (TVBox / Apple CMS links); the shared importer skips spider sites. */
    internal val jsonSources = org.skepsun.kototoro.desktop.runtime.DesktopJsonSources(
        storage.preferences.open("desktop_json_sources"),
    )
    private val jsonClient = platform.sharedHttpClient()
    internal val jsonHttp = org.skepsun.kototoro.tvbox.TvBoxHttp { url, headers ->
        withContext(Dispatchers.IO) {
            val request = okhttp3.Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            jsonClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} $url")
                response.body?.string().orEmpty()
            }
        }
    }
    @Volatile private var jsonSourceCache = jsonSources.all()
    private val tvboxRuntime = org.skepsun.kototoro.tvbox.MacCmsSourceRuntime(jsonHttp) {
        jsonSourceCache.map {
            org.skepsun.kototoro.tvbox.TvBoxSource(it.id, it.name, it.config, it.iconUrl)
        }
    }

    /** Sites the JSON runtime serves right now; the runtime reads the same list. */
    internal fun jsonSourceList(): List<org.skepsun.kototoro.desktop.runtime.DesktopJsonSource> = jsonSourceCache

    /**
     * A changed proxy must not reuse a connection the previous route opened, so the desktop page drops the pool right
     * after saving. The client itself keeps working; only its idle connections go away.
     */
    internal fun resetHttpConnections() {
        platform.sharedHttpClient().connectionPool.evictAll()
    }

    /**
     * Android's proxy "test connection" row: a plain HTTP GET through the shared client, so whatever the page saved is
     * what gets exercised (including SOCKS and credentials). Returns the round-trip latency in milliseconds.
     */
    internal suspend fun testProxyConnection(): Long = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        jsonClient.newCall(okhttp3.Request.Builder().url(PROXY_TEST_URL).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} ${response.message}" }
            (System.nanoTime() - startedAt) / 1_000_000
        }
    }

    /** Re-reads the stored documents after an import or a removal; read failures stay in [startupErrors]. */
    internal suspend fun reloadJsonSources() {
        jsonSourceCache = jsonSources.all()
        startupErrors.clear()
        startupErrors += jsonSources.errors
        tvboxRuntime.invalidate()
    }

    /** Follows the shared TVBox import rules: multi-repository links, CMS APIs and documents all land here. */
    internal suspend fun importJsonSources(url: String, title: String?) =
        org.skepsun.kototoro.tvbox.TvBoxImport.importFromUrl(jsonHttp, url, title)
    val sources: SourceRuntime = SourceProtocolClient(
        SourceEndpoint(
            RoutedSourceRuntime(listOf(
                RoutedSourceRuntime.Route(mihonRuntime::owns, mihonRuntime),
                RoutedSourceRuntime.Route(aniyomiRuntime::owns, aniyomiRuntime),
                RoutedSourceRuntime.Route(parsers::owns, parserRuntime),
                RoutedSourceRuntime.Route(cloudstreamRuntime::owns, cloudstreamRuntime),
                RoutedSourceRuntime.Route(org.skepsun.kototoro.tvbox.MacCmsSourceRuntime::owns, tvboxRuntime),
            )),
            sourceDiagnostics::record,
        ),
    ) { "desktop-${ids.incrementAndGet()}" }
    val library = DesktopLibrary(storage.database) { name ->
        registry.installed().flatMap { it.sources }.firstOrNull { it.source.name == name }?.source
            ?: parsers.sources().firstOrNull { it.source.name == name }?.source
            ?: cloudstream.sources().firstOrNull { it.name == name }?.let(CloudstreamSourceRuntime::sourceRef)
            ?: jsonSourceCache.firstOrNull { it.id == name }
                ?.let { SourceRef(it.id, "", org.skepsun.kototoro.tvbox.MacCmsSourceRuntime.CONTENT_TYPE) }
    }
    /** New-chapter tracking with Android's rules; details refresh through the normal source protocol. */
    val tracker = org.skepsun.kototoro.desktop.runtime.DesktopTracker(
        database = storage.database,
        library = library,
        fetchDetails = { content -> sources.getDetails(content, SourceDetailsFetchMode.FORCE_REFRESH) },
        preferredBranch = { content ->
            org.skepsun.kototoro.core.ui.chapters.resolvePreferredChapterBranch(content.chapters.orEmpty(),
                SourceChapter::branch, SourceChapter::id, null, desktopChapterBranchLocales())
        },
    )

    /** Suggestions with Android's worker rules, from the installed sources, stored in the shared table. */
    val suggestions = org.skepsun.kototoro.desktop.runtime.DesktopSuggestions(library, sources)

    /** Android's feed read model over the shared tracker tables, classified by the installed ecosystems. */
    val feed = org.skepsun.kototoro.tracker.domain.feed.FeedSnapshotAssembler(
        contentGroupOf = { name, nsfw -> desktopContentGroup(listingOf(name)?.source?.contentType, nsfw) },
        originGroupOf = { name -> listingOf(name)?.ecosystem?.originGroup() ?: org.skepsun.kototoro.core.jsonsource.OriginGroup.EXTERNAL },
    )

    private fun listingOf(name: String): SourceListing? = sourceListings().firstOrNull { it.source.name == name }

    val startupErrors = mutableListOf<String>()
    val browserChallenges get() = platform.browserChallenges
    private val readerImages = DesktopReaderImages(storage.paths.images, storage.preferences.open("desktop_reader_images")) {
        sources.fetchImage(it)
    }
    internal val downloadStore = DesktopDownloadStore(storage.preferences)
    /** CBZ export reads the same verified blobs as the reader; no second copy of the images lives in the store. */
    internal val downloadExporter = DesktopDownloadExporter(storage.paths.images)
    /** Automatic sweep of the unreachable page blocks; the controller runs it when the queue goes idle. */
    internal val downloadMaintenance = DesktopDownloadMaintenance(downloadStore, storage.preferences,
        storage.preferences.open("desktop_download_maintenance"))

    /** Blobs a saved chapter still needs; the image budget never evicts these, offline reading would break. */
    internal fun downloadedImageBlobs(): Set<String> = downloadStore.records.value
        .flatMap { record -> record.pages.mapNotNull { page -> page.artifact?.relativePath } }
        .toSet()

    internal fun imageCacheReport(budgetMb: Int): DesktopImageCacheReport =
        DesktopImageBudget.inspect(storage.paths.images, downloadedImageBlobs(), budgetMb)

    /** Android's storage page on this data root; the walk is IO, so the page never runs it on the UI thread. */
    internal suspend fun storageUsage(): DesktopStorageUsage =
        withContext(Dispatchers.IO) { desktopStorageUsage(storage.paths) }

    internal suspend fun enforceImageBudget(budgetMb: Int, clear: Boolean = false): DesktopImageCacheReport =
        DesktopImageBudget.enforce(storage.paths.images, downloadedImageBlobs(), budgetMb, clear)

    init { startupErrors.addAll(downloadStore.errors); startupErrors.addAll(repositories.errors) }

    /** NCNN super-resolution of reader pages; the controller keeps [upscale] in step with the reader settings. */
    val superResolution = org.skepsun.kototoro.desktop.runtime.DesktopSuperResolution(storage.paths.root, shaderUpscaler = {
        // Anime4K pages render offscreen through the same libmpv and shaders as video enhancement.
        org.skepsun.kototoro.desktop.player.MpvLocator.find(storage.paths.root)?.let { library ->
            val enhancer = org.skepsun.kototoro.desktop.player.MpvImageEnhancer(library,
                org.skepsun.kototoro.desktop.player.MpvShaderLibrary(storage.paths.root.resolve("cache/mpv-shaders")))
            org.skepsun.kototoro.desktop.runtime.DesktopShaderUpscaler { input, output, width, height, model ->
                enhancer.enhance(input, output, width, height,
                    org.skepsun.kototoro.desktop.player.Anime4KImagePreset.valueOf(model.name.removePrefix("ANIME4K_")))
            }
        }
    })
    @Volatile internal var upscale = org.skepsun.kototoro.desktop.runtime.DesktopUpscaleSetting()
    /** Android's "crop pages" for the open reading mode; the controller keeps it in step with the reader settings. */
    @Volatile internal var cropPages = false
    private val mutableUpscaleError = MutableStateFlow<String?>(null)
    internal val upscaleError = mutableUpscaleError.asStateFlow()

    internal suspend fun readerImage(page: SourcePage, refresh: Boolean = false): DesktopReaderImage {
        if (!refresh) downloadStore.page(page)?.artifact?.let { artifact ->
            return cropped(upscaled(readerImages.openArtifact(page, artifact)
                ?: throw IOException(desktopText("error.localDownloadMissing"))))
        }
        return cropped(upscaled(readerImages.load(page, readerRevision(page.source.name), refresh)))
    }

    /** Plain white margins removed, as Android crops pages; long tiled pages keep their full geometry. */
    private suspend fun cropped(image: DesktopReaderImage): DesktopReaderImage {
        if (!cropPages || image.tiled) return image
        val bounds = try { withContext(DesktopDispatchers.decode) { DesktopImageDecoder.contentBounds(image.path, image.width, image.height) } }
            catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (_: Exception) { null }
        return image.copy(crop = bounds)
    }

    /**
     * The page as the reader shows it: upscaled when a model is chosen and installed, else the original. A failing
     * program never costs the page; its message is kept for the reader settings to show.
     */
    private suspend fun upscaled(image: DesktopReaderImage): DesktopReaderImage {
        val setting = upscale
        if (setting.model == null) return image
        return try {
            val output = superResolution.upscale(image.path, image.width, image.height, setting) ?: return image
            mutableUpscaleError.value = null
            withContext(Dispatchers.IO) { DesktopImageDecoder.header(output) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableUpscaleError.value = error.message ?: error.javaClass.simpleName
            image
        }
    }

    internal suspend fun readerGeometry(pages: List<SourcePage>): Map<Long, IntSize> {
        val pinned = pages.mapNotNull { page -> downloadStore.page(page)?.let {
            page.id to IntSize(it.width, it.height)
        } }.toMap()
        val remaining = pages.filter { it.id !in pinned }
        return if (remaining.isEmpty()) pinned else readerImages.geometry(remaining,
            readerRevision(remaining.first().source.name)) + pinned
    }

    internal fun readerRevision(sourceName: String): String = registry.installed().firstOrNull { installed ->
        installed.sources.any { it.source.name == sourceName }
    }?.metadata?.sha256 ?: parsers.pluginFor(sourceName)?.metadata?.sha256 ?: cloudstream.pluginFor(sourceName)?.sha256
        ?: throw SourceInvalidArgumentException()

    /** An illustration inside a novel chapter, fetched like a page: same client, cache index and challenge handling. */
    internal suspend fun novelImage(chapter: SourceChapter, image: SourceContentImage): DesktopReaderImage {
        val identity = java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256")
            .digest(image.url.toByteArray(Charsets.UTF_8))).long
        val page = SourcePage(identity, image.url, null, chapter.source, image.headers.takeIf { it.isNotEmpty() })
        return downloadRequest { readerImages.load(page, readerRevision(chapter.source.name)) }
    }

    /** Every installed source of every ecosystem, as the UI lists them. */
    fun sourceListings(): List<SourceListing> =
        registry.installed().flatMap { extension -> extension.sources.map { mihonListing(it, extension.metadata.ecosystem) } } +
            parsers.sources().map(::parserListing) +
            cloudstream.sources().map(::cloudstreamListing) +
            jsonSourceCache.map(::jsonListing)
    internal suspend fun chapterPages(contentId: Long, chapter: SourceChapter): List<SourcePage> =
        downloadStore.find(contentId, chapter)?.takeIf { it.isComplete }?.pages?.map { it.page }
            ?: sources.getPages(chapter, null)

    /** The page a bookmark points at, for its card in the details bookmark list; null when the page is gone. */
    internal suspend fun bookmarkImage(contentId: Long, chapter: SourceChapter,
        pageIndex: Int): DesktopReaderImage? = chapterPages(contentId, chapter).getOrNull(pageIndex)
        ?.let { readerImage(it) }

    internal suspend fun downloadPages(chapter: SourceChapter) = downloadRequest { sources.getPages(chapter, null) }

    internal suspend fun prepareDownload(page: SourcePage, revision: String, known: SourceImageArtifact?) =
        downloadRequest { readerImages.prepareDownload(page, revision, known) }

    private suspend fun <T> downloadRequest(block: suspend () -> T): T {
        val challenges = browserChallenges
        return if (challenges == null) block() else challenges.withRequestCancellation(block)
    }
    internal val covers = DesktopCovers(storage.paths.images, storage.preferences.open("desktop_covers")) {
        content, large ->
        try {
            downloadRequest { sources.fetchCover(content, large) }
        } catch (error: SourceRemoteException) {
            // JSON/TVBox sources have no cover operation: they only name a URL, which Android also loads directly.
            if (error.error.code == SourceErrorCode.UNSUPPORTED_OPERATION) downloadedCover(content, large) else throw error
        }
    }

    /** Plain GET of a cover URL into the image directory, for sources whose protocol has no cover operation. */
    private suspend fun downloadedCover(content: SourceContent, large: Boolean): SourceCoverArtifact {
        val url = (if (large) content.largeCoverUrl ?: content.coverUrl else content.coverUrl)
            ?.takeIf(String::isNotBlank) ?: throw SourceInvalidArgumentException()

        val (bytes, type) = withContext(Dispatchers.IO) {
            val request = okhttp3.Request.Builder().url(url).get().build()
            jsonClient.newCall(request).execute().use { response ->

                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Cover response has no body")
                val mime = body.contentType()?.toString()?.substringBefore(';')
                    ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                body.bytes() to mime
            }
        }

        currentCoroutineContext().ensureActive()
        val sha256 = imageKey(bytes)
        val relativePath = "$sha256.img"
        val target = storage.paths.images.resolve(relativePath)
        withContext(Dispatchers.IO) {
            if (!java.nio.file.Files.isRegularFile(target) ||
                java.nio.file.Files.size(target) != bytes.size.toLong()
            ) {
                java.nio.file.Files.createDirectories(target.parent)
                java.nio.file.Files.write(target, bytes)
            }
        }

        return SourceCoverArtifact(content.source, content.id, relativePath, sha256, bytes.size.toLong(), type)
    }
    internal val browser = MihonDesktopPlatform.defaultBridgeExecutable()?.let {
        DesktopBrowser(it, storage.paths.root.resolve("compat/browser-debug"), platform::cookieBridge)
    }

    /** Imports a Mihon extension or a parser plugin; the jar's own contents tell which. */
    suspend fun importJar(path: Path): InstalledExtension = withContext(Dispatchers.IO) {
        when (DesktopExtensionFiles.kind(path)) {
            // Mihon manga, Tsundoku novels and Aniyomi anime share the jar/APK pipeline; the manifest names the runtime.
            DesktopExtensionKind.MIHON, DesktopExtensionKind.ANIYOMI -> installManaged(repositories.import(path))
            DesktopExtensionKind.PARSER -> installParser(repositories.importParserPlugin(path))
            DesktopExtensionKind.CLOUDSTREAM -> installCloudstream(repositories.importCloudstreamPlugin(path))
        }
    }

    internal suspend fun installExtension(
        catalog: DesktopRepositoryCatalog,
        extension: ExtensionStoreIndex.Extension,
    ): InstalledExtension = if (catalog.isParserPlugin(extension)) {
        installParser(repositories.downloadParserPlugin(catalog, extension))
    } else if (catalog.isCloudstreamPlugin(extension)) {
        installCloudstream(repositories.downloadCloudstreamPlugin(extension.resources.apkUrl, extension.packageName))
    } else {
        installManaged(repositories.download(catalog, extension))
    }

    private suspend fun installManaged(managed: DesktopManagedJar): InstalledExtension = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val local = registry.installed().firstOrNull { it.metadata.packageName == managed.identity.packageName }
        require(local == null || local.metadata.versionCode <= managed.identity.versionCode) { desktopText("error.olderExtension") }
        val extension = registry.replace(managed.path, managed.identity) {
            context.ensureActive()
            val jar = SourceHostJar(managed.path.toString(), managed.identity)
            persistRepositoryPreference(installed, managed.identity.packageName,
                SourcePreferenceValue.Text(SourceProtocolJson.encodeToString(jar)))
        }
        InstalledExtension(extension.metadata.displayName, extension.sources.map { mihonListing(it, extension.metadata.ecosystem) })
    }

    private suspend fun installParser(managed: DesktopManagedParserPlugin): InstalledExtension = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val plugin = parsers.replace(managed.path, managed.id, managed.sha256) {
            context.ensureActive()
            val record = DesktopParserRecord(managed.id, managed.path.toString(), managed.sha256)
            persistRepositoryPreference(installed, PARSER_KEY_PREFIX + managed.id,
                SourcePreferenceValue.Text(SourceProtocolJson.encodeToString(record)))
        }
        InstalledExtension(desktopText("extensions.installedSummary", plugin.id, plugin.sources.size),
            plugin.sources.map(::parserListing))
    }

    /** Loads the plugin, then records it; a plugin whose record cannot be written is unloaded again. */
    internal suspend fun installCloudstream(managed: DesktopManagedCloudstreamPlugin): InstalledExtension =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val plugin = cloudstream.load(managed.path, managed.id, managed.sha256)
            try {
                val record = DesktopCloudstreamRecord(managed.id, managed.path.toString(), managed.sha256)
                persistRepositoryPreference(installed, CLOUDSTREAM_KEY_PREFIX + managed.id,
                    SourcePreferenceValue.Text(SourceProtocolJson.encodeToString(record)))
            } catch (error: Throwable) {
                cloudstream.unload(managed.id)
                throw error
            }
            InstalledExtension(desktopText("extensions.installedSummary", plugin.manifest.name ?: plugin.id, plugin.sources.size),
                plugin.sources.map(::cloudstreamListing))
        }

    /** Every installed extension and parser plugin, as the extension manager lists them. */
    fun installedEntries(): List<DesktopInstalledEntry> =
        registry.installed().map { extension ->
            val metadata = extension.metadata
            DesktopInstalledEntry(DesktopInstalledKind.EXTENSION, metadata.packageName, metadata.displayName,
                metadata.ecosystem, metadata.versionName, metadata.versionCode, extension.sources.size)
        } + parsers.installed().map { plugin ->
            DesktopInstalledEntry(DesktopInstalledKind.PARSER, plugin.id, plugin.id,
                plugin.sources.firstOrNull()?.let { parserListing(it).ecosystem } ?: SourceEcosystem.KOTOTORO,
                plugin.metadata.sha256.take(8), null, plugin.sources.size)
        } + cloudstream.installed().map { plugin ->
            DesktopInstalledEntry(DesktopInstalledKind.CLOUDSTREAM, plugin.id, plugin.manifest.name ?: plugin.id,
                SourceEcosystem.CLOUDSTREAM, "v${plugin.manifest.version}", plugin.manifest.version.toLong(), plugin.sources.size)
        }

    /**
     * Removes an extension: its install record first (so a crash never resurrects half of it), then its sources;
     * calls already running finish on the retiring loader. Unreferenced artifacts are deleted afterwards.
     */
    suspend fun uninstall(entry: DesktopInstalledEntry) = withContext(Dispatchers.IO) {
        val key = when (entry.kind) {
            DesktopInstalledKind.PARSER -> PARSER_KEY_PREFIX + entry.id
            DesktopInstalledKind.CLOUDSTREAM -> CLOUDSTREAM_KEY_PREFIX + entry.id
            DesktopInstalledKind.EXTENSION -> entry.id
        }
        require(key in installed.snapshot()) { desktopText("error.extensionRecordMissing") }
        if (!installed.edit(SourcePreferenceEdit(changes = mapOf(key to null)))) throw IOException(desktopText("error.extensionRecordDelete"))
        val unloaded = when (entry.kind) {
            DesktopInstalledKind.PARSER -> parsers.unload(entry.id)
            DesktopInstalledKind.CLOUDSTREAM -> cloudstream.unload(entry.id)
            DesktopInstalledKind.EXTENSION -> registry.unload(entry.id)
        }
        check(unloaded) { desktopText("error.extensionNotLoaded") }
        cleanupArtifacts()
    }

    /** Artifacts of earlier versions and removed extensions; the files records still point at are kept. */
    fun cleanupArtifacts(): Int {
        val referenced = installed.snapshot().mapNotNull { (key, value) ->
            val text = (value as? SourcePreferenceValue.Text)?.value ?: return@mapNotNull null
            runCatching {
                if (key.startsWith(PARSER_KEY_PREFIX)) Path.of(SourceProtocolJson.decodeFromString<DesktopParserRecord>(text).path)
                else if (key.startsWith(CLOUDSTREAM_KEY_PREFIX)) {
                    Path.of(SourceProtocolJson.decodeFromString<DesktopCloudstreamRecord>(text).path)
                } else Path.of(SourceProtocolJson.decodeFromString<SourceHostJar>(text).path)
            }.getOrNull()
        }.toSet()
        return repositories.cleanupArtifacts(referenced)
    }

    /**
     * Newer versions of installed extensions in the saved repositories (the highest offered wins). Repositories that
     * cannot be read are reported, not fatal.
     */
    suspend fun checkUpdates(): DesktopUpdateCheck {
        val installedVersions = installedVersions()
        val updates = linkedMapOf<String, DesktopExtensionUpdate>()
        val failures = mutableListOf<String>()
        for (repository in repositories.saved()) {
            val catalog = try { repositories.fetch(repository.indexUrl) } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { failures += "${repository.name}：${error.message ?: error.javaClass.simpleName}"; continue }
            for (extension in catalog.extensions) {
                val current = installedVersions[extension.packageName] ?: continue
                if (extension.versionCode <= current) continue
                val known = updates[extension.packageName]
                if (known == null || known.extension.versionCode < extension.versionCode) {
                    updates[extension.packageName] = DesktopExtensionUpdate(catalog, extension, current)
                }
            }
        }
        return DesktopUpdateCheck(updates.values.toList(), failures)
    }

    internal suspend fun applyUpdate(update: DesktopExtensionUpdate) = installExtension(update.catalog, update.extension)

    /** Installed versions by package name (Cloudstream: plugin id and manifest version), for repository listings. */
    fun installedVersions(): Map<String, Long> =
        registry.installed().associate { it.metadata.packageName to it.metadata.versionCode } +
            cloudstream.installed().associate { it.id to it.manifest.version.toLong() }

    private fun restoreJars() {
        for ((key, value) in installed.snapshot()) {
            try {
                val text = (value as SourcePreferenceValue.Text).value
                if (key.startsWith(PARSER_KEY_PREFIX)) {
                    val record = SourceProtocolJson.decodeFromString<DesktopParserRecord>(text)
                    parsers.load(Path.of(record.path), record.id, record.sha256)
                } else if (key.startsWith(CLOUDSTREAM_KEY_PREFIX)) {
                    val record = SourceProtocolJson.decodeFromString<DesktopCloudstreamRecord>(text)
                    cloudstream.load(Path.of(record.path), record.id, record.sha256)
                } else {
                    val jar = SourceProtocolJson.decodeFromString<SourceHostJar>(text)
                    registry.load(Path.of(jar.path), jar.identity)
                }
            } catch (error: Exception) {
                startupErrors += desktopText("error.extensionRestoreFailed", error.message ?: error.javaClass.simpleName)
            }
        }
    }

    override fun close() {
        var failure: Throwable? = null
        for (owner in listOfNotNull(covers, browser, repositories, parsers, cloudstream, registry, platform, storage)) {
            try { owner.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        suspend fun open(root: Path): DesktopSession {
            var created: DesktopSession? = null
            try {
                return withContext(Dispatchers.IO) { openOnIo(root).also { created = it } }
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { created?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                }
                throw error
            }
        }

        private suspend fun openOnIo(root: Path): DesktopSession {
            // The runtime reports catalog keys with English fallbacks; from here they follow the interface language.
            org.skepsun.kototoro.desktop.runtime.DesktopRuntimeStrings.install(DesktopStrings::localized)
            val storage = DesktopRuntime.open(root)
            // The proxy the settings page edits becomes the SDK client's route before any source is constructed.
            val proxy = org.skepsun.kototoro.desktop.runtime.DesktopProxy(storage.preferences.open("desktop_network"))
            val platform = MihonDesktopPlatform(storage.paths.root.resolve("compat"), enableBrowserChallenges = true,
                proxySelector = proxy.selector, proxyAuthenticator = proxy.authenticator)
            var registry: MihonJarRegistry? = null
            var parsers: ParserPluginRegistry? = null
            try {
                registry = MihonJarRegistry(platform.initialize(storage.preferences))
                // Parser plugins reuse the platform's HTTP client: one cookie store and challenge flow for all ecosystems.
                val parserPlatform = DesktopParserPlatform(platform.sharedHttpClient(), storage.preferences)
                parsers = ParserPluginRegistry(parserPlatform)
                return DesktopSession(storage, platform, registry, parserPlatform, parsers, proxy).also {
                    it.restoreJars()
                    // Superseded and removed versions are deleted once nothing has them open.
                    runCatching { it.cleanupArtifacts() }
                }
            } catch (error: Throwable) {
                for (owner in listOfNotNull(parsers, registry, platform, storage)) {
                    try { owner.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                }
                throw error
            }
        }

    }
}

enum class DesktopInstalledKind { EXTENSION, PARSER, CLOUDSTREAM }

/** One installed extension (Mihon / Tsundoku / Aniyomi) or parser plugin, for the extension manager. */
data class DesktopInstalledEntry(
    val kind: DesktopInstalledKind,
    val id: String,
    val label: String,
    val ecosystem: SourceEcosystem,
    val version: String,
    val versionCode: Long?,
    val sources: Int,
)

data class DesktopExtensionUpdate(
    val catalog: DesktopRepositoryCatalog,
    val extension: ExtensionStoreIndex.Extension,
    val installedVersion: Long,
)

data class DesktopUpdateCheck(val updates: List<DesktopExtensionUpdate>, val failures: List<String>)

/** What an import or install produced: a label for messages and the sources it added, ready to select. */
class InstalledExtension(val label: String, val sources: List<SourceListing>)

private const val PARSER_KEY_PREFIX = "parser:"
private const val CLOUDSTREAM_KEY_PREFIX = "cloudstream:"
/** The same plain-HTTP target Android's proxy page tests against. */
private const val PROXY_TEST_URL = "http://neverssl.com"

private fun cloudstreamListing(source: org.skepsun.kototoro.cloudstream.model.CloudstreamSource) =
    SourceListing(CloudstreamSourceRuntime.sourceRef(source), source.displayName, SourceEcosystem.CLOUDSTREAM, supportsLatest = false)

private fun jsonListing(source: org.skepsun.kototoro.desktop.runtime.DesktopJsonSource) = SourceListing(
    SourceRef(source.id, "", org.skepsun.kototoro.tvbox.MacCmsSourceRuntime.CONTENT_TYPE),
    source.name, SourceEcosystem.TVBOX, supportsLatest = false,
)

private fun mihonListing(source: MihonSourceDescriptor, ecosystem: SourceEcosystem) =
    SourceListing(source.source, source.displayName, ecosystem, source.supportsLatest, source.sourceId)

private fun parserListing(info: ParserSourceInfo) = SourceListing(info.source, info.title, when (info.architecture) {
    ParserPluginArchitecture.KOTOTORO -> SourceEcosystem.KOTOTORO
    ParserPluginArchitecture.KOTATSU -> SourceEcosystem.KOTATSU
    ParserPluginArchitecture.TSUKI -> SourceEcosystem.UMA
}, supportsLatest = true)
