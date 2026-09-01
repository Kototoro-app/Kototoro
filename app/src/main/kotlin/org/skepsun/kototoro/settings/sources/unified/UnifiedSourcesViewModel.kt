package org.skepsun.kototoro.settings.sources.unified

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.core.jsonsource.JsonSourceImportMetadata
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.github.VersionId
import org.skepsun.kototoro.core.lnreader.LNReaderPluginInfo
import org.skepsun.kototoro.core.lnreader.LNReaderRepository
import org.skepsun.kototoro.core.lnreader.LNReaderPluginMetadata
import org.skepsun.kototoro.core.model.jsonsource.TVBoxStoredConfig
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.network.jsonsource.JsonSourceHttpClient
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourceAvailabilityRepository
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import org.skepsun.kototoro.extensions.install.ExtensionInstallDownloadState
import org.skepsun.kototoro.extensions.install.ExtensionInstallMode
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy
import org.skepsun.kototoro.extensions.install.ExtensionInstallResult
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepo
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.InstalledExtensionSignatureValidator
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.tsundoku.TsundokuExtensionManager
import org.skepsun.kototoro.settings.sources.extensions.ExtensionBatchUpdateStateMachine
import org.skepsun.kototoro.settings.sources.extensions.isNewerThanInstalled
import org.skepsun.kototoro.settings.sources.extensions.normalizeExtensionLanguageCode
import org.skepsun.kototoro.settings.sources.extensions.normalizePackageNameForMatching
import org.skepsun.kototoro.settings.sources.extensions.toInstalledIReaderPackageName
import javax.inject.Inject

private const val TAG = "UnifiedSourcesVM"
private const val REFRESH_PACKAGES_TIMEOUT_MS = 30_000L
private const val SOURCE_TEST_TIMEOUT_MS = 45_000L
private const val SOURCE_TEST_MAX_PARALLELISM = 3

internal fun shouldRecommendMissingExtensionSource(
    sourceId: Long,
    installedSourceIds: Set<Long>,
): Boolean = sourceId !in installedSourceIds

/**
 * SavedState keys written by the Activity/UI agent for process restoration of a source
 * recovery deep link (T5.2). Read in [UnifiedSourcesViewModel.init].
 */
const val DL_SAVED_STATE_TAB = "dl_initialTab"
const val DL_SAVED_STATE_PACKAGE = "dl_package"
const val DL_SAVED_STATE_SOURCE_KEY = "dl_sourceKey"

@HiltViewModel
class UnifiedSourcesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val catalogRepository: UnifiedSourceCatalogRepository,
    private val contentSourcesRepository: ContentSourcesRepository,
    private val sourceAvailabilityRepository: SourceAvailabilityRepository,
    private val contentRepositoryFactory: ContentRepository.Factory,
    private val jsonSourceManager: JsonSourceManager,
    private val legadoHttpClient: LegadoHttpClient,
    @JsonSourceHttpClient private val okHttpClient: OkHttpClient,
    private val extensionRepoRepository: ExternalExtensionRepoRepository,
    private val installService: ExtensionInstallService,
    private val signatureValidator: InstalledExtensionSignatureValidator,
    private val settings: AppSettings,
    private val mihonExtensionManager: MihonExtensionManager,
    private val aniyomiExtensionManager: AniyomiExtensionManager,
    private val ireaderExtensionManager: IReaderExtensionManager,
    private val tsundokuExtensionManager: TsundokuExtensionManager,
    private val cloudstreamRuntimeManager: org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager,
    private val database: org.skepsun.kototoro.core.db.MangaDatabase,
    private val mirrorRepository: org.skepsun.kototoro.core.github.GitHubMirrorCatalogRepository,
    private val savedStateHandle: SavedStateHandle,
) : BaseViewModel() {

    private val availableExternalExtensions = MutableStateFlow<List<RepoAvailableExtension>>(emptyList())
    private val availableLnReaderPlugins = MutableStateFlow<List<LnReaderAvailablePlugin>>(emptyList())
    private val availableJsonPackages = MutableStateFlow<List<UnifiedSourcePackageItem>>(emptyList())
    private val installingLnReaderPackageIds = MutableStateFlow<Set<String>>(emptySet())
    private val pendingUninstallIntents = ArrayDeque<Intent>()
    private val lnReaderPackageSnapshot = combine(
        availableLnReaderPlugins,
        installingLnReaderPackageIds,
        availableJsonPackages,
    ) { plugins, installingIds, jsonPackages ->
        AvailablePackageSnapshot(plugins, installingIds, jsonPackages)
    }
    private val lnReaderRepository = LNReaderRepository(okHttpClient, jsonSourceManager, settings, mirrorRepository)
    private val batchUpdateState = ExtensionBatchUpdateStateMachine()
    private val filterState = MutableStateFlow(
        UnifiedSourcesFilterState(
            languages = settings.extensionLanguages.normalizeLanguageCodes(),
        ),
    )
    /**
     * Optimistic enabled-state overrides keyed by source id, merged into [uiState] on
     * every emission so the enable/disable switch never waits for the catalog rebuild.
     * Each entry is retired once the authoritative rebuild reports the same value.
     */
    private val _enabledOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _events = MutableSharedFlow<UnifiedSourcesEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UnifiedSourcesEvent> = _events.asSharedFlow()
    val updateAllInProgress: StateFlow<Boolean> = batchUpdateState.inProgress

    init {
        // Process restoration of a deep link: 5A writes these keys into saved state before
        // the ViewModel is recreated.
        savedStateHandle.get<String>(DL_SAVED_STATE_PACKAGE)
            ?.takeIf { it.isNotBlank() }
            ?.let(::setSearchQuery)
    }

    /**
     * Applies a source-management deep link (parsed by the UI agent's
     * `UnifiedSourcesDeepLinkParser`): package filter → search query.
     */
    fun applyDeepLink(link: UnifiedSourcesDeepLink) {
        link.packageFilter
            ?.takeIf { it.isNotBlank() }
            ?.let(::setSearchQuery)
    }

    /**
     * Heavy catalog pipeline (rebuilds on every Room invalidation / runtime change).
     * Kept separate from [uiState] so toggle feedback — which only needs the enabled
     * state — never waits for this potentially multi-second rebuild.
     */
    private val baseSourcesUiState: Flow<UnifiedSourcesUiState.Ready> = combine(
        combine(
            catalogRepository.observeState(),
            availableExternalExtensions,
            installService.downloadStates,
            filterState,
            lnReaderPackageSnapshot,
        ) { catalog, availableExtensions, downloadStates, filters, lnReaderSnapshot ->
            CatalogInputs(catalog, availableExtensions, downloadStates, filters, lnReaderSnapshot)
        },
        database.getSourceOriginsDao().observeAll(),
    ) { inputs, sourceOrigins ->
        inputs.catalog
            .withAvailableExternalPackages(inputs.availableExtensions, inputs.downloadStates)
            .withAvailableLnReaderPackages(inputs.lnReaderSnapshot.plugins, inputs.lnReaderSnapshot.installingPackageIds)
            .withAvailableJsonPackages(inputs.lnReaderSnapshot.jsonPackages)
            .toUiState(inputs.filters)
            .withMissingSourceRecommendations(sourceOrigins, inputs.availableExtensions)
    }.flowOn(Dispatchers.Default)

    private data class CatalogInputs(
        val catalog: UnifiedSourceCatalogState,
        val availableExtensions: List<RepoAvailableExtension>,
        val downloadStates: Map<String, ExtensionInstallDownloadState>,
        val filters: UnifiedSourcesFilterState,
        val lnReaderSnapshot: AvailablePackageSnapshot,
    )

    val uiState: StateFlow<UnifiedSourcesUiState> = combine(
        baseSourcesUiState,
        _enabledOverrides,
    ) { state, enabledOverrides ->
        pruneAgreedEnabledOverrides(state, enabledOverrides)
        state.withEnabledOverrides(enabledOverrides)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UnifiedSourcesUiState.Loading,
    )

    fun setSearchQuery(query: String) {
        filterState.update { it.copy(query = query) }
    }

    fun toggleKind(kind: UnifiedSourceKind) {
        filterState.update { state ->
            state.copy(kinds = state.kinds.toggle(kind))
        }
    }

    fun setKindFilter(kind: UnifiedSourceKind?) {
        filterState.update { state ->
            state.copy(kinds = kind?.let(::setOf) ?: emptySet())
        }
    }

    fun toggleContentType(contentType: ContentType) {
        filterState.update { state ->
            state.copy(contentTypes = state.contentTypes.toggle(contentType))
        }
    }

    fun setPrimaryContentTypeFilter(contentType: ContentType?) {
        setContentTypeFilter(contentType)
    }

    fun setContentTypeFilter(contentType: ContentType?) {
        filterState.update { state ->
            state.copy(contentTypes = contentType?.let(::setOf) ?: emptySet())
        }
    }

    fun toggleLocationType(locationType: UnifiedRepositoryLocationType) {
        filterState.update { state ->
            state.copy(locationTypes = state.locationTypes.toggle(locationType))
        }
    }

    fun toggleLanguage(language: String) {
        val normalized = language.normalizeLanguageCode()
        if (normalized.isBlank()) {
            return
        }
        filterState.update { state ->
            state.copy(languages = state.languages.toggle(normalized))
        }
    }

    fun setEnabledFilter(filter: UnifiedEnabledFilter) {
        filterState.update { it.copy(enabledFilter = filter) }
    }

    fun setAvailabilityFilter(filter: UnifiedAvailabilityFilter) {
        filterState.update { it.copy(availabilityFilter = filter) }
    }

    fun setTestAvailabilityFilter(filter: UnifiedTestAvailabilityFilter) {
        filterState.update { it.copy(testAvailabilityFilter = filter) }
    }

    fun setNsfwFilter(filter: UnifiedNsfwFilter) {
        filterState.update { it.copy(nsfwFilter = filter) }
    }

    fun clearLanguages() {
        filterState.update { it.copy(languages = emptySet()) }
    }

    fun applyPreferredLanguages() {
        filterState.update {
            val availableLanguages = (uiState.value as? UnifiedSourcesUiState.Ready)
                ?.availableLanguages
                .orEmpty()
                .toSet()
            it.copy(
                languages = settings.contentLanguages.normalizeLanguageCodes()
                    .filterTo(LinkedHashSet()) { language -> language in availableLanguages },
            )
        }
    }

    fun clearFilters() {
        filterState.value = UnifiedSourcesFilterState(
            availabilityFilter = UnifiedAvailabilityFilter.AVAILABLE,
        )
    }

    fun refreshInstalledSources() {
        val disabledSourcesBeforeRefresh = uiState.value.disabledSourcesForPackageRefresh()
        launchLoadingJob(Dispatchers.IO) {
            try {
                withTimeout(REFRESH_PACKAGES_TIMEOUT_MS) {
                    reloadExternalExtensionManagers()
                }
            } catch (e: TimeoutCancellationException) {
                emitMessage(appContext.getString(R.string.unified_sources_refresh_timeout))
            } finally {
                restoreDisabledSources(disabledSourcesBeforeRefresh)
            }
        }
    }

    fun refreshRepositories() {
        refreshPackageCatalog(reloadInstalledExtensions = false)
    }

    fun refreshPackages() {
        refreshPackageCatalog(reloadInstalledExtensions = true)
    }

    private fun refreshPackageCatalog(
        refreshRepositories: Boolean = true,
        showLoading: Boolean = true,
        reloadInstalledExtensions: Boolean,
    ) {
        val disabledSourcesBeforeRefresh = uiState.value.disabledSourcesForPackageRefresh()
        val refreshBlock: suspend kotlinx.coroutines.CoroutineScope.() -> Unit = {
            try {
                withTimeout(REFRESH_PACKAGES_TIMEOUT_MS) {
                    val types = externalExtensionTypes()
                    if (reloadInstalledExtensions) {
                        reloadExternalExtensionManagers()
                    }
                    if (refreshRepositories) {
                        types.forEach { type -> extensionRepoRepository.refresh(type) }
                    }
                    refreshAvailableExternalPackages(types)
                    refreshAvailableLnReaderPackages()
                    refreshAvailableJsonPackages()
                    if (showLoading) {
                        emitRefreshFailures(types)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                if (showLoading) {
                    emitMessage(appContext.getString(R.string.unified_sources_refresh_timeout))
                }
            } finally {
                restoreDisabledSources(disabledSourcesBeforeRefresh)
            }
        }
        if (showLoading) {
            launchLoadingJob(Dispatchers.IO, block = refreshBlock)
        } else {
            launchJob(Dispatchers.IO, block = refreshBlock)
        }
    }

    private suspend fun restoreDisabledSources(sources: List<ContentSource>) {
        if (sources.isNotEmpty()) {
            withContext(NonCancellable) {
                contentSourcesRepository.setSourcesEnabled(sources, false)
            }
        }
    }

    private suspend fun emitRefreshFailures(types: List<ExternalExtensionType>) {
        val failedRepositories = types
            .flatMap { type -> extensionRepoRepository.getByType(type) }
            .filter { !it.lastError.isNullOrBlank() }
            .map { it.displayName }
            .distinct()
        if (failedRepositories.isNotEmpty()) {
            emitMessage(
                appContext.getString(
                    R.string.unified_sources_refresh_partial_failed,
                    failedRepositories.take(3).joinToString(", "),
                ),
            )
        }
    }

    fun installPackage(packageId: String) {
        installPackage(packageId, ExtensionInstallMode.LOCAL_APK)
    }

    fun installPackage(packageId: String, mode: ExtensionInstallMode) {
        val item = currentPackage(packageId) ?: return
        if (item.state == UnifiedSourcePackageState.INSTALLED || item.packageName in installService.downloadStates.value) {
            return
        }
        if (item.state == UnifiedSourcePackageState.UPDATE_AVAILABLE) {
            requestPackageInstall(item, mode, enableAfterInstall = null)
            return
        }
        when (settings.getExtensionInstallPolicy(item.kind.name)) {
            ExtensionInstallPolicy.ASK_EVERY_TIME -> {
                _events.tryEmit(
                    UnifiedSourcesEvent.ConfirmPackageInstall(
                        packageId = item.id,
                        kind = item.kind,
                        name = item.name,
                        sourceCount = item.sourceCount,
                        mode = mode,
                    ),
                )
            }
            ExtensionInstallPolicy.INSTALL_ONLY -> requestPackageInstall(item, mode, enableAfterInstall = false)
            ExtensionInstallPolicy.INSTALL_AND_ENABLE -> requestPackageInstall(item, mode, enableAfterInstall = true)
        }
    }

    fun confirmPackageInstall(
        packageId: String,
        mode: ExtensionInstallMode,
        policy: ExtensionInstallPolicy,
        remember: Boolean,
    ) {
        val item = currentPackage(packageId) ?: return
        if (remember) {
            settings.setExtensionInstallPolicy(item.kind.name, policy)
        }
        requestPackageInstall(
            item = item,
            mode = mode,
            enableAfterInstall = policy == ExtensionInstallPolicy.INSTALL_AND_ENABLE,
        )
    }

    fun getInstallPolicy(kind: UnifiedSourceKind): ExtensionInstallPolicy {
        return settings.getExtensionInstallPolicy(kind.name)
    }

    fun setInstallPolicy(kind: UnifiedSourceKind, policy: ExtensionInstallPolicy) {
        settings.setExtensionInstallPolicy(kind.name, policy)
    }

    fun installPackageWithSystemInstaller(packageId: String) {
        installPackage(packageId, ExtensionInstallMode.SYSTEM)
    }

    fun cancelPackageInstall(packageId: String) {
        val item = currentPackage(packageId) ?: return
        if (item.kind == UnifiedSourceKind.LNREADER) {
            installingLnReaderPackageIds.update { it - item.id }
            return
        }
        val packageName = item.packageName ?: return
        if (batchUpdateState.shouldCancelCurrent(packageName)) {
            cancelUpdateAll()
            return
        }
        installService.cancelDownload(packageName)
    }

    fun uninstallPackage(packageId: String) {
        val item = currentPackage(packageId) ?: return
        val ready = uiState.value as? UnifiedSourcesUiState.Ready ?: return
        launchLoadingJob(Dispatchers.IO) {
            val result = removePackage(item, ready)
            if (result.reloadExternalExtensionManagers) {
                reloadExternalExtensionManagers()
            }
            if (result.removedDirectly) {
                emitMessage(appContext.getString(R.string.removal_completed))
            }
            result.uninstallIntent?.let { dispatchUninstallIntents(listOf(it)) }
        }
    }

    fun deletePackages(packageIds: Set<String>) {
        if (packageIds.isEmpty()) {
            return
        }
        val ready = uiState.value as? UnifiedSourcesUiState.Ready ?: return
        val packageItems = ready.allPackages
            .filter { it.id in packageIds }
            .distinctBy { it.id }
        if (packageItems.isEmpty()) {
            return
        }
        launchLoadingJob(Dispatchers.IO) {
            val uninstallIntents = ArrayList<Intent>(packageItems.size)
            var removedDirectly = false
            var reloadExternalManagers = false
            packageItems.forEach { item ->
                val result = removePackage(item, ready)
                removedDirectly = removedDirectly || result.removedDirectly
                reloadExternalManagers = reloadExternalManagers || result.reloadExternalExtensionManagers
                result.uninstallIntent?.let(uninstallIntents::add)
            }
            if (reloadExternalManagers) {
                reloadExternalExtensionManagers()
            }
            if (removedDirectly) {
                emitMessage(appContext.getString(R.string.removal_completed))
            }
            dispatchUninstallIntents(uninstallIntents)
        }
    }

    fun onPackagePrimaryAction(packageId: String) {
        when (val item = currentPackage(packageId)?.state) {
            UnifiedSourcePackageState.AVAILABLE,
            UnifiedSourcePackageState.UPDATE_AVAILABLE -> installPackage(packageId)

            UnifiedSourcePackageState.UNTRUSTED,
            UnifiedSourcePackageState.INCOMPATIBLE -> currentPackage(packageId)?.let {
                _events.tryEmit(UnifiedSourcesEvent.PackageStateDetails(it))
            }

            UnifiedSourcePackageState.INSTALLING,
            UnifiedSourcePackageState.INSTALLED,
            null -> Unit
        }
    }

    fun onUpdateAllPackagesAction() {
        if (updateAllInProgress.value) {
            cancelUpdateAll()
        } else {
            startUpdateAll()
        }
    }

    fun onUninstallActivityResult() {
        viewModelScope.launch {
            dispatchNextPendingUninstall()
        }
    }

    fun onInstallerActivityReturned() {
        installService.onInstallerActivityReturned()
    }

    fun importLocalJar(uri: Uri) {
        launchLoadingJob(Dispatchers.IO) {
            val fileName = resolveDisplayName(uri)
                ?.takeIf { it.isNotBlank() }
                ?: "plugin_${System.currentTimeMillis()}.jar"
            val pluginsDir = File(appContext.filesDir, "plugins").apply { mkdirs() }
            val destinationFile = File(pluginsDir, fileName)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                destinationFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalArgumentException(appContext.getString(R.string.unified_sources_cannot_open_selected_jar))
            GlobalExtensionManager.initialize(appContext)
            emitMessage(appContext.getString(R.string.unified_sources_imported_plugin, fileName))
        }
    }

    fun addRepositoryFromUrl(
        kind: UnifiedSourceKind,
        url: String,
        title: String? = null,
        enableImportedSources: Boolean = true,
    ) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        launchLoadingJob(Dispatchers.IO) {
            when (kind) {
                UnifiedSourceKind.LEGADO -> {
                    if (settings.legadoRepoUrls.containsRepositoryUrl(cleanUrl)) {
                        emitMessage(appContext.getString(R.string.unified_sources_repository_already_exists))
                        return@launchLoadingJob
                    }
                    val packageItem = fetchAvailableLegadoPackage(cleanUrl, title)
                    settings.legadoRepoUrls = settings.legadoRepoUrls + cleanUrl
                    availableJsonPackages.update { packages ->
                        (packages.filterNot { it.repositoryId == packageItem.repositoryId } + packageItem)
                            .sortedBy { it.name.lowercase() }
                    }
                    emitMessage(appContext.getString(R.string.unified_sources_repository_added))
                }
                UnifiedSourceKind.JS -> {
                    importJsonRepository(
                        kind = kind,
                        content = fetchRemoteText(cleanUrl),
                        sourceLocator = cleanUrl,
                        sourceTitle = title,
                        enableImportedSources = enableImportedSources,
                    )
                }
                UnifiedSourceKind.TVBOX -> {
                    if (settings.tvBoxRepoUrls.containsRepositoryUrl(cleanUrl)) {
                        emitMessage(appContext.getString(R.string.unified_sources_repository_already_exists))
                        return@launchLoadingJob
                    }
                    val packageItem = fetchAvailableTvBoxPackage(cleanUrl, title)
                    settings.tvBoxRepoUrls = settings.tvBoxRepoUrls + cleanUrl
                    availableJsonPackages.update { packages ->
                        (packages.filterNot { it.repositoryId == packageItem.repositoryId } + packageItem)
                            .sortedBy { it.name.lowercase() }
                    }
                    emitMessage(appContext.getString(R.string.unified_sources_repository_added))
                }
                UnifiedSourceKind.LNREADER -> addLnReaderRepository(cleanUrl)
                UnifiedSourceKind.CLOUDSTREAM,
                UnifiedSourceKind.MIHON,
                UnifiedSourceKind.ANIYOMI,
                UnifiedSourceKind.IREADER,
                UnifiedSourceKind.JAR,
                UnifiedSourceKind.TSUNDOKU -> prepareExternalRepository(kind, cleanUrl)
                UnifiedSourceKind.NATIVE -> emitMessage(appContext.getString(R.string.unified_sources_native_no_repository))
            }
        }
    }

    fun addRepositoryFromFile(kind: UnifiedSourceKind, uri: Uri, enableImportedSources: Boolean = true) {
        launchLoadingJob(Dispatchers.IO) {
            val title = resolveDisplayName(uri)
            val content = appContext.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: throw IllegalArgumentException(appContext.getString(R.string.unified_sources_cannot_open_selected_file))
            importJsonRepository(
                kind = kind,
                content = content,
                sourceLocator = uri.toString(),
                sourceTitle = title,
                enableImportedSources = enableImportedSources,
            )
        }
    }

    fun addRepositoryFromInline(
        kind: UnifiedSourceKind,
        content: String,
        title: String? = null,
        enableImportedSources: Boolean = true,
    ) {
        val cleanContent = content.trim()
        if (cleanContent.isBlank()) return
        launchLoadingJob(Dispatchers.Default) {
            val inlineLocator = title
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "inline:${kind.name.lowercase()}:${System.currentTimeMillis()}"
            importJsonRepository(
                kind = kind,
                content = cleanContent,
                sourceLocator = inlineLocator,
                sourceTitle = title,
                enableImportedSources = enableImportedSources,
            )
        }
    }

    fun refreshRepository(repositoryId: String) {
        val repository = (uiState.value as? UnifiedSourcesUiState.Ready)
            ?.allRepositories
            ?.firstOrNull { it.id == repositoryId }
            ?: return
        launchLoadingJob(Dispatchers.IO) {
            when (repository.kind) {
                UnifiedSourceKind.LEGADO -> {
                    val packageItem = fetchAvailableLegadoPackage(repository.url, repository.name)
                    availableJsonPackages.update { packages ->
                        packages.filterNot { it.repositoryId == repository.id } + packageItem
                    }
                    emitMessage(appContext.getString(R.string.unified_sources_repository_refreshed))
                }
                UnifiedSourceKind.TVBOX -> {
                    val packageItem = fetchAvailableTvBoxPackage(repository.url, repository.name)
                    availableJsonPackages.update { packages ->
                        packages.filterNot { it.repositoryId == repository.id } + packageItem
                    }
                    emitMessage(appContext.getString(R.string.unified_sources_repository_refreshed))
                }
                UnifiedSourceKind.JS -> {
                    if (repository.locationType == UnifiedRepositoryLocationType.INLINE_IMPORT ||
                        repository.locationType == UnifiedRepositoryLocationType.PRESET_ONLY
                    ) {
                        emitMessage(appContext.getString(R.string.unified_sources_repository_manual_refresh_only))
                        return@launchLoadingJob
                    }
                    importJsonRepository(
                        kind = repository.kind,
                        content = loadRepositoryText(repository.url),
                        sourceLocator = repository.url,
                        sourceTitle = repository.name,
                    )
                }
                UnifiedSourceKind.LNREADER -> {
                    refreshAvailableLnReaderPackages()
                    emitMessage(appContext.getString(R.string.unified_sources_repository_refreshed))
                }
                UnifiedSourceKind.CLOUDSTREAM,
                UnifiedSourceKind.MIHON,
                UnifiedSourceKind.ANIYOMI,
                UnifiedSourceKind.IREADER,
                UnifiedSourceKind.JAR,
                UnifiedSourceKind.TSUNDOKU -> refreshExternalRepository(repository)
                UnifiedSourceKind.NATIVE -> emitMessage(appContext.getString(R.string.unified_sources_native_no_repository))
            }
        }
    }

    fun deleteRepository(repositoryId: String) {
        val ready = uiState.value as? UnifiedSourcesUiState.Ready ?: return
        val repository = ready.allRepositories.firstOrNull { it.id == repositoryId } ?: return
        launchLoadingJob(Dispatchers.IO) {
            when (repository.kind) {
                UnifiedSourceKind.LEGADO -> {
                    if (settings.legadoRepoUrls.containsRepositoryUrl(repository.url)) {
                        settings.legadoRepoUrls = settings.legadoRepoUrls.withoutRepositoryUrl(repository.url)
                        availableJsonPackages.update { packages ->
                            packages.filterNot { it.repositoryId == repository.id }
                        }
                        emitMessage(appContext.getString(R.string.unified_sources_repository_deleted))
                    } else {
                        deleteImportedJsonRepositorySources(repository, ready)
                    }
                }
                UnifiedSourceKind.TVBOX -> {
                    if (settings.tvBoxRepoUrls.containsRepositoryUrl(repository.url)) {
                        settings.tvBoxRepoUrls = settings.tvBoxRepoUrls.withoutRepositoryUrl(repository.url)
                        availableJsonPackages.update { packages ->
                            packages.filterNot { it.repositoryId == repository.id }
                        }
                        emitMessage(appContext.getString(R.string.unified_sources_repository_deleted))
                    } else {
                        deleteImportedJsonRepositorySources(repository, ready)
                    }
                }
                UnifiedSourceKind.JS -> {
                    deleteImportedJsonRepositorySources(repository, ready)
                }
                UnifiedSourceKind.LNREADER -> {
                    settings.lnReaderRepoUrls = settings.lnReaderRepoUrls - repository.url
                    refreshAvailableLnReaderPackages()
                    emitMessage(appContext.getString(R.string.unified_sources_repository_deleted))
                }
                UnifiedSourceKind.CLOUDSTREAM,
                UnifiedSourceKind.MIHON,
                UnifiedSourceKind.ANIYOMI,
                UnifiedSourceKind.IREADER,
                UnifiedSourceKind.JAR,
                UnifiedSourceKind.TSUNDOKU -> deleteExternalRepository(repository)
                UnifiedSourceKind.NATIVE -> emitMessage(appContext.getString(R.string.unified_sources_native_no_repository))
            }
        }
    }

    fun confirmExternalRepository(repo: ExternalExtensionRepo) {
        launchLoadingJob(Dispatchers.IO) {
            when (val result = extensionRepoRepository.confirmAddRepo(repo)) {
                is ExternalExtensionRepoRepository.AddRepoResult.Success -> {
                    emitMessage(appContext.getString(R.string.extension_repo_added_message, result.repo.displayName))
                    extensionRepoRepository.refresh(repo.type)
                    refreshAvailableExternalPackages(listOf(repo.type))
                }
                is ExternalExtensionRepoRepository.AddRepoResult.DuplicateFingerprint -> emitMessage(
                    appContext.getString(
                        R.string.extension_repo_duplicate_fingerprint_message,
                        result.existingRepo.displayName,
                    ),
                )
                is ExternalExtensionRepoRepository.AddRepoResult.FetchFailed -> emitMessage(
                    result.error.getDisplayMessage(appContext.resources),
                )
                ExternalExtensionRepoRepository.AddRepoResult.InvalidUrl -> emitMessage(
                    appContext.getString(R.string.extension_repo_invalid_url_message),
                )
                ExternalExtensionRepoRepository.AddRepoResult.RepoAlreadyExists -> emitMessage(
                    appContext.getString(R.string.extension_repo_already_exists_message),
                )
            }
        }
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        setSourcesEnabled(setOf(sourceId), enabled)
    }

    /**
     * Overrides the NSFW flag for the given sources. Only sources whose flag actually
     * changes are persisted, so sources already in the target state keep their current
     * (metadata or override based) behavior.
     */
    fun setSourcesNsfw(sourceIds: Set<String>, isNsfw: Boolean) {
        if (sourceIds.isEmpty()) {
            return
        }
        val sourceItems = (uiState.value as? UnifiedSourcesUiState.Ready)
            ?.allSources
            .orEmpty()
            .filter { it.id in sourceIds }
        if (sourceItems.isEmpty()) {
            return
        }
        val changedIds = sourceItems
            .filter { it.isNsfw != isNsfw }
            .mapTo(LinkedHashSet()) { it.id }
        if (changedIds.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            settings.setSourceNsfwOverride(changedIds, isNsfw)
        }
    }

    fun setSourcesEnabled(sourceIds: Set<String>, enabled: Boolean) {
        if (sourceIds.isEmpty()) {
            return
        }
        val ready = uiState.value as? UnifiedSourcesUiState.Ready ?: return
        val sourceItems = ready.allSources.filter { it.id in sourceIds }
        if (sourceItems.isEmpty()) {
            return
        }
        // Optimistic, instant feedback: the switch stops depending on the full catalog
        // rebuild. The authoritative DB write happens in the background and overrides are
        // retired as soon as the rebuild reports the same value.
        _enabledOverrides.update { overrides ->
            overrides + sourceItems.associate { it.id to enabled }
        }
        viewModelScope.launch(Dispatchers.Default) {
            if (!enabled && settings.isAllSourcesEnabled) {
                // Materialize the sources that stay enabled WITHOUT resurrecting the one
                // being disabled, then drop the global flag.
                val rest = ready.allSources.filter { it.id !in sourceIds }
                if (rest.isNotEmpty()) {
                    contentSourcesRepository.setSourcesEnabled(rest.map { it.source }, true)
                }
                settings.isAllSourcesEnabled = false
            }
            runCatching {
                contentSourcesRepository.setSourcesEnabled(sourceItems.map { it.source }, enabled)
            }
            // Overrides are retired by [pruneAgreedEnabledOverrides] once the authoritative
            // rebuild agrees with them; never remove them here based on the merged state.
        }
    }

    /**
     * Retires override entries that the authoritative (un-overridden) base rebuild now
     * agrees with. Must compare against the base state — never the merged [uiState] — or
     * the first rebuild after a toggle would drop the override while the DB write is still
     * in flight and the switch would flicker back to the previous state. The re-emission
     * caused by this StateFlow update settles after one pass because the agreed keys are
     * no longer present.
     */
    private fun pruneAgreedEnabledOverrides(
        base: UnifiedSourcesUiState.Ready,
        overrides: Map<String, Boolean>,
    ) {
        if (overrides.isEmpty()) {
            return
        }
        val agreed = base.allSources.agreedOverrideIds(overrides)
        if (agreed.isEmpty()) {
            return
        }
        val baseById = base.allSources.associateBy { it.id }
        _enabledOverrides.update { map ->
            // Only drop when the CURRENT override also agrees with the authoritative base,
            // guarding against a re-toggle racing between the snapshot and the update.
            val drop = agreed.filterTo(mutableSetOf()) { id -> baseById[id]?.isEnabled == map[id] }
            if (drop.isEmpty()) map else map - drop
        }
    }

    fun testSources(sourceIds: Set<String>) {
        if (sourceIds.isEmpty()) {
            return
        }
        val sourceItems = (uiState.value as? UnifiedSourcesUiState.Ready)
            ?.allSources
            .orEmpty()
            .filter { it.id in sourceIds }
        if (sourceItems.isEmpty()) {
            return
        }
        launchLoadingJob(Dispatchers.IO) {
            val semaphore = Semaphore(SOURCE_TEST_MAX_PARALLELISM)
            val results = sourceItems.map { item ->
                async {
                    val outcome = testSource(item, semaphore)
                    item to outcome
                }
            }.awaitAll()
            results.forEach { (item, outcome) ->
                sourceAvailabilityRepository.setAvailability(
                    item.source,
                    if (outcome.isAvailable) ContentSourceAvailability.AVAILABLE else ContentSourceAvailability.EMPTY,
                )
            }
            logTestSummary(results)
            emitMessage(
                appContext.getString(
                    R.string.source_test_completed,
                    results.count { it.second.isAvailable },
                    results.count { !it.second.isAvailable },
                ),
            )
        }
    }

    private data class SourceTestOutcome(
        val isAvailable: Boolean,
        val failure: String?,
    )

    private suspend fun testSource(item: UnifiedSourceItem, semaphore: Semaphore): SourceTestOutcome {
        val result = runCatchingCancellable {
            semaphore.withPermit {
                withTimeoutOrNull(SOURCE_TEST_TIMEOUT_MS) {
                    val repository = contentRepositoryFactory.create(item.source)
                    repository.getList(
                        offset = 0,
                        order = repository.defaultSortOrder,
                        filter = ContentListFilter.EMPTY,
                    ).isNotEmpty()
                }
            }
        }
        val exception = result.exceptionOrNull()
        val isAvailable = result.getOrDefault(false) == true
        val failure = when {
            exception != null -> exception.javaClass.simpleName
            result.getOrNull() == null -> "timeout"
            !isAvailable -> "empty"
            else -> null
        }
        if (failure != null) {
            Log.w(
                TAG,
                "source_test_failed source=${item.source.name} type=${item.source::class.simpleName} " +
                    "failure=$failure error=${exception?.javaClass?.simpleName} " +
                    "msg=${exception?.message?.take(160)}",
            )
        }
        return SourceTestOutcome(isAvailable = isAvailable, failure = failure)
    }

    private fun logTestSummary(results: List<Pair<UnifiedSourceItem, SourceTestOutcome>>) {
        val failureCounts = results.mapNotNull { it.second.failure }.groupingBy { it }.eachCount()
        Log.w(
            TAG,
            "source_test_summary total=${results.size} available=${results.count { it.second.isAvailable }} " +
                "unavailable=${results.count { !it.second.isAvailable }} failures=$failureCounts",
        )
    }

    fun setSourcePinned(sourceId: String, pinned: Boolean) {
        val source = (uiState.value as? UnifiedSourcesUiState.Ready)
            ?.allSources
            ?.firstOrNull { it.id == sourceId }
            ?.source
            ?: return
        viewModelScope.launch(Dispatchers.Default) {
            contentSourcesRepository.setIsPinned(setOf(source), pinned)
        }
    }

    private fun currentPackage(packageId: String): UnifiedSourcePackageItem? {
        return (uiState.value as? UnifiedSourcesUiState.Ready)
            ?.allPackages
            ?.firstOrNull { it.id == packageId }
    }

    private suspend fun removePackage(
        item: UnifiedSourcePackageItem,
        ready: UnifiedSourcesUiState.Ready,
    ): PackageRemovalResult {
        if (item.state == UnifiedSourcePackageState.INSTALLING) {
            return PackageRemovalResult()
        }

        if (item.kind.isJsonBackedKind()) {
            val uiSourceIds = ready.allSources
                .filter { it.packageId == item.id }
                .map { it.id }
            val sourceIds = if (uiSourceIds.isNotEmpty()) {
                uiSourceIds
            } else {
                jsonSourceManager.observeAllJsonSources()
                    .first()
                    .filter { it.jsonPackageIdForAction() == item.id }
                    .map { it.id }
            }
            if (sourceIds.isEmpty()) {
                return PackageRemovalResult()
            }
            jsonSourceManager.deleteSourcesBatch(sourceIds)
            return PackageRemovalResult(removedDirectly = true)
        }

        val packageName = item.packageName ?: return PackageRemovalResult()
        if (item.kind == UnifiedSourceKind.JAR) {
            val pluginDir = File(appContext.filesDir, "plugins")
            val jarFile = File(pluginDir, "$packageName.jar")
            if (jarFile.exists()) {
                jarFile.delete()
            }
            appContext.getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE)
                .edit()
                .remove(packageName)
                .remove("${packageName}:repo")
                .remove("${packageName}:repoName")
                .apply()
            GlobalExtensionManager.initialize(appContext)
            return PackageRemovalResult(removedDirectly = true)
        }

        if (item.kind == UnifiedSourceKind.CLOUDSTREAM) {
            val prefs = appContext.getSharedPreferences("cloudstream_plugin_versions", Context.MODE_PRIVATE)
            val archiveName = prefs.getString("${packageName}:archive", null) ?: "$packageName.cs3"
            val pluginDir = File(File(appContext.filesDir, "cloudstream"), "plugins")
            val pluginFile = File(pluginDir, archiveName)
            if (pluginFile.exists()) {
                pluginFile.delete()
            }
            prefs.edit()
                .remove(packageName)
                .remove("${packageName}:name")
                .remove("${packageName}:lang")
                .remove("${packageName}:repo")
                .remove("${packageName}:repoName")
                .remove("${packageName}:archive")
                .remove("${packageName}:icon")
                .apply()
            cloudstreamRuntimeManager.initialize()
            return PackageRemovalResult(removedDirectly = true)
        }

        val ecosystem = item.kind.toLocalApkEcosystem()
        if (ecosystem != null && item.installLocation == UnifiedSourcePackageInstallLocation.LOCAL_APK) {
            val deleted = LocalApkExtensionSupport.deleteManagedLocalPackage(
                context = appContext,
                ecosystem = ecosystem,
                packageName = packageName,
            )
            if (deleted) {
                return PackageRemovalResult(
                    removedDirectly = true,
                    reloadExternalExtensionManagers = true,
                )
            }
        }

        return PackageRemovalResult(uninstallIntent = buildUninstallIntent(item.kind, packageName))
    }

    private fun buildUninstallIntent(kind: UnifiedSourceKind, packageName: String): Intent {
        val uninstallPkg = if (kind == UnifiedSourceKind.IREADER && packageName.startsWith("ireader-")) {
            packageName.toInstalledIReaderPackageName()
        } else {
            packageName
        }
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent.ACTION_DELETE
        } else {
            @Suppress("DEPRECATION")
            Intent.ACTION_UNINSTALL_PACKAGE
        }
        return Intent(action, Uri.fromParts("package", uninstallPkg, null))
    }

    private suspend fun dispatchUninstallIntents(intents: List<Intent>) {
        if (intents.isEmpty()) {
            return
        }
        val iterator = intents.iterator()
        if (pendingUninstallIntents.isEmpty()) {
            val first = iterator.next()
            while (iterator.hasNext()) {
                pendingUninstallIntents.addLast(iterator.next())
            }
            _events.emit(UnifiedSourcesEvent.StartUninstall(first))
            return
        }
        while (iterator.hasNext()) {
            pendingUninstallIntents.addLast(iterator.next())
        }
    }

    private suspend fun dispatchNextPendingUninstall() {
        val next = pendingUninstallIntents.removeFirstOrNull() ?: return
        _events.emit(UnifiedSourcesEvent.StartUninstall(next))
    }

    private fun requestPackageInstall(
        item: UnifiedSourcePackageItem,
        mode: ExtensionInstallMode,
        enableAfterInstall: Boolean?,
    ) {
        if (item.jsonPayload != null) {
            requestJsonPackageInstall(item, enableAfterInstall ?: true)
        } else if (item.kind == UnifiedSourceKind.LNREADER && item.lnReaderPayload != null) {
            requestLnReaderInstall(item, enableAfterInstall)
        } else {
            requestInstall(
                item = item,
                fromBatch = false,
                mode = mode,
                enableAfterInstall = enableAfterInstall,
            )
        }
    }

    private fun requestJsonPackageInstall(item: UnifiedSourcePackageItem, enableAfterInstall: Boolean) {
        val payload = item.jsonPayload ?: return
        launchLoadingJob(Dispatchers.IO) {
            importJsonRepository(
                kind = item.kind,
                content = payload.content,
                sourceLocator = payload.sourceLocator,
                sourceTitle = payload.sourceTitle,
                enableImportedSources = enableAfterInstall,
            )
        }
    }

    private fun requestInstall(
        item: UnifiedSourcePackageItem,
        fromBatch: Boolean,
        mode: ExtensionInstallMode = ExtensionInstallMode.LOCAL_APK,
        enableAfterInstall: Boolean? = null,
    ) {
        val extension = item.installPayload ?: return
        if (extension.pkgName in installService.downloadStates.value) {
            return
        }
        if (fromBatch) {
            batchUpdateState.beginInstall(extension.pkgName)
        }
        launchLoadingJob(Dispatchers.IO) {
            try {
                when (val result = installService.install(extension, mode)) {
                    is ExtensionInstallResult.RequiresInstaller -> {
                        val userAction = result.session.awaitUserAction()
                        if (userAction != null) {
                            if (fromBatch) {
                                batchUpdateState.markInstallerIntentDispatched()
                            }
                            _events.emit(UnifiedSourcesEvent.StartInstall(userAction))
                        }
                        result.session.awaitCompletion()
                        onPackageInstallCompleted(item, fromBatch, enableAfterInstall)
                    }
                    ExtensionInstallResult.Completed -> {
                        onPackageInstallCompleted(item, fromBatch, enableAfterInstall)
                    }
                }
            } catch (e: CancellationException) {
                if (!fromBatch) {
                    emitMessage(appContext.getString(R.string.canceled))
                }
                if (fromBatch) {
                    handleBatchNextAction(batchUpdateState.finishCurrentInstall())
                }
            } catch (e: Throwable) {
                _events.emit(UnifiedSourcesEvent.InstallFailed(e.getDisplayMessage(appContext.resources)))
                if (fromBatch) {
                    emitMessage(appContext.getString(R.string.extension_update_failed, item.name))
                    handleBatchNextAction(batchUpdateState.finishCurrentInstall())
                }
            }
        }
    }

    private suspend fun onPackageInstallCompleted(
        item: UnifiedSourcePackageItem,
        fromBatch: Boolean,
        enableAfterInstall: Boolean?,
    ) {
        if (item.kind.isHotReloadableExternalKind()) {
            reloadExternalExtensionManagers()
            refreshPackageCatalog(
                refreshRepositories = false,
                showLoading = false,
                reloadInstalledExtensions = false,
            )
        } else if (item.kind == UnifiedSourceKind.CLOUDSTREAM) {
            refreshPackageCatalog(
                refreshRepositories = false,
                showLoading = false,
                reloadInstalledExtensions = false,
            )
        }
        if (enableAfterInstall != null) {
            val sources = catalogRepository.getSourcesForPackage(item.id).map { it.source }
            if (sources.isNotEmpty()) {
                contentSourcesRepository.setSourcesEnabled(sources, enableAfterInstall)
            }
        }
        emitMessage(appContext.getString(R.string.unified_sources_package_installed))
        if (fromBatch) {
            handleBatchNextAction(batchUpdateState.finishCurrentInstall())
        }
    }

    private fun requestLnReaderInstall(item: UnifiedSourcePackageItem, enableAfterInstall: Boolean?) {
        val plugin = item.lnReaderPayload ?: return
        if (item.state == UnifiedSourcePackageState.INSTALLED || item.id in installingLnReaderPackageIds.value) {
            return
        }
        installingLnReaderPackageIds.update { it + item.id }
        launchLoadingJob(Dispatchers.IO) {
            try {
                val enabled = enableAfterInstall ?: (uiState.value as? UnifiedSourcesUiState.Ready)
                    ?.allSources
                    ?.firstOrNull { it.packageId == item.id }
                    ?.isEnabled
                    ?: true
                val jsContent = fetchRemoteText(plugin.url)
                jsonSourceManager.importLNReaderPlugin(
                    jsContent = jsContent,
                    metadataOverride = LNReaderPluginMetadata(
                        id = plugin.id,
                        name = plugin.name,
                        site = plugin.site,
                        version = plugin.version,
                        lang = plugin.lang,
                        icon = plugin.iconUrl,
                    ),
                    enabled = enabled,
                ).getOrThrow()
                emitMessage(appContext.getString(R.string.unified_sources_package_installed))
            } finally {
                installingLnReaderPackageIds.update { it - item.id }
            }
        }
    }

    private fun startUpdateAll() {
        val updatePackages = currentUpdatePackages()
        if (!batchUpdateState.start(updatePackages.mapNotNull { it.packageName })) {
            viewModelScope.launch { emitMessage(appContext.getString(R.string.no_extension_updates_available)) }
            return
        }
        handleBatchNextAction(batchUpdateState.nextAction())
    }

    private fun cancelUpdateAll() {
        if (!updateAllInProgress.value) {
            return
        }
        batchUpdateState.cancel(installService::cancelDownload)
        viewModelScope.launch { emitMessage(appContext.getString(R.string.extension_update_all_cancelled)) }
    }

    private fun handleBatchNextAction(action: ExtensionBatchUpdateStateMachine.NextAction) {
        when (action) {
            ExtensionBatchUpdateStateMachine.NextAction.None -> Unit
            ExtensionBatchUpdateStateMachine.NextAction.Completed -> {
                viewModelScope.launch { emitMessage(appContext.getString(R.string.extension_update_all_complete)) }
            }
            is ExtensionBatchUpdateStateMachine.NextAction.InstallNext -> {
                val item = currentUpdatePackages().firstOrNull { it.packageName == action.packageName } ?: run {
                    handleBatchNextAction(batchUpdateState.nextAction())
                    return
                }
                requestInstall(item, fromBatch = true)
            }
        }
    }

    private fun currentUpdatePackages(): List<UnifiedSourcePackageItem> {
        return (uiState.value as? UnifiedSourcesUiState.Ready)
            ?.allPackages
            .orEmpty()
            .filter { it.state == UnifiedSourcePackageState.UPDATE_AVAILABLE }
    }

    private data class PackageRemovalResult(
        val removedDirectly: Boolean = false,
        val uninstallIntent: Intent? = null,
        val reloadExternalExtensionManagers: Boolean = false,
    )

    private suspend fun prepareExternalRepository(kind: UnifiedSourceKind, url: String) {
        val type = kind.toExternalExtensionType()
            ?: throw IllegalArgumentException(
                appContext.getString(R.string.unified_sources_unsupported_repository_kind, kind.name),
            )
        when (val result = extensionRepoRepository.prepareAddRepo(type, url)) {
            is ExternalExtensionRepoRepository.PrepareAddRepoResult.Ready -> {
                _events.emit(UnifiedSourcesEvent.TrustExternalRepository(result.repo))
            }
            is ExternalExtensionRepoRepository.PrepareAddRepoResult.DuplicateFingerprint -> emitMessage(
                appContext.getString(
                    R.string.extension_repo_duplicate_fingerprint_message,
                    result.existingRepo.displayName,
                ),
            )
            is ExternalExtensionRepoRepository.PrepareAddRepoResult.FetchFailed -> emitMessage(
                result.error.getDisplayMessage(appContext.resources),
            )
            ExternalExtensionRepoRepository.PrepareAddRepoResult.InvalidUrl -> emitMessage(
                appContext.getString(R.string.extension_repo_invalid_url_message),
            )
            is ExternalExtensionRepoRepository.PrepareAddRepoResult.RepoAlreadyExists -> emitMessage(
                appContext.getString(R.string.extension_repo_already_exists_message),
            )
        }
    }

    private suspend fun addLnReaderRepository(url: String) {
        val current = settings.lnReaderRepoUrls
        if (url in current) {
            emitMessage(appContext.getString(R.string.unified_sources_repository_already_exists))
            return
        }
        settings.lnReaderRepoUrls = current + url
        refreshAvailableLnReaderPackages()
        emitMessage(appContext.getString(R.string.unified_sources_repository_added))
    }

    private suspend fun refreshExternalRepository(repository: UnifiedSourceRepositoryItem) {
        val type = repository.kind.toExternalExtensionType()
            ?: throw IllegalArgumentException(
                appContext.getString(R.string.unified_sources_unsupported_repository_kind, repository.kind.name),
            )
        val baseUrl = normalizeRepositoryUrlForAction(repository.url)
        val repo = extensionRepoRepository.getByType(type)
            .firstOrNull { normalizeRepositoryUrlForAction(it.baseUrl) == baseUrl }
        if (repo == null) {
            emitMessage(appContext.getString(R.string.unified_sources_repository_not_configured))
            return
        }
        extensionRepoRepository.refresh(repo)
        refreshAvailableExternalPackages(listOf(type))
        emitMessage(appContext.getString(R.string.unified_sources_repository_refreshed))
    }

    private suspend fun deleteExternalRepository(repository: UnifiedSourceRepositoryItem) {
        val type = repository.kind.toExternalExtensionType()
            ?: throw IllegalArgumentException(
                appContext.getString(R.string.unified_sources_unsupported_repository_kind, repository.kind.name),
            )
        val baseUrl = normalizeRepositoryUrlForAction(repository.url)
        val repo = extensionRepoRepository.getByType(type)
            .firstOrNull { normalizeRepositoryUrlForAction(it.baseUrl) == baseUrl }
        if (repo == null) {
            emitMessage(appContext.getString(R.string.unified_sources_repository_not_configured))
            return
        }
        extensionRepoRepository.delete(repo)
        refreshAvailableExternalPackages()
        emitMessage(appContext.getString(R.string.unified_sources_repository_deleted))
    }

    private suspend fun importJsonRepository(
        kind: UnifiedSourceKind,
        content: String,
        sourceLocator: String?,
        sourceTitle: String?,
        enableImportedSources: Boolean? = null,
    ) {
        val result = when (kind) {
            UnifiedSourceKind.LEGADO -> jsonSourceManager.importLegadoJson(
                jsonContent = content,
                sourceLocator = sourceLocator,
                sourceTitle = sourceTitle,
                enabled = enableImportedSources,
            )
            UnifiedSourceKind.TVBOX -> jsonSourceManager.importTvBoxJson(
                jsonContent = content,
                sourceLocator = sourceLocator,
                sourceTitle = sourceTitle,
                enabled = enableImportedSources,
            )
            UnifiedSourceKind.JS -> jsonSourceManager.importJsSource(content, enabled = enableImportedSources)
            UnifiedSourceKind.LNREADER -> jsonSourceManager.importLNReaderPlugin(content, enabled = enableImportedSources)
            else -> Result.failure(
                IllegalArgumentException(
                    appContext.getString(
                        R.string.unified_sources_cannot_import_json,
                        kind.displayNameForMessage(appContext),
                    ),
                ),
            )
        }
        result
            .onSuccess { count ->
                if (kind == UnifiedSourceKind.LNREADER &&
                    !sourceLocator.isNullOrBlank() &&
                    sourceLocator.startsWith("http", ignoreCase = true)
                ) {
                    settings.lnReaderRepoUrls = settings.lnReaderRepoUrls + sourceLocator
                }
                emitMessage(appContext.getString(R.string.unified_sources_imported_sources, count))
            }
            .onFailure { error -> emitMessage(error.getDisplayMessage(appContext.resources)) }
    }

    private suspend fun deleteImportedJsonRepositorySources(
        repository: UnifiedSourceRepositoryItem,
        ready: UnifiedSourcesUiState.Ready,
    ) {
        val sourceIds = ready.allSources
            .filter { it.repositoryId == repository.id }
            .map { it.id }
        val ids = sourceIds.ifEmpty {
            jsonSourceManager.observeAllJsonSources()
                .first()
                .filter { it.jsonRepositoryIdForAction() == repository.id }
                .map { it.id }
        }
        if (ids.isNotEmpty()) {
            jsonSourceManager.deleteSourcesBatch(ids)
        }
        emitMessage(appContext.getString(R.string.unified_sources_repository_sources_deleted))
    }

    private suspend fun loadRepositoryText(locator: String): String {
        return when (resolveRepositoryLocationTypeForAction(locator)) {
            UnifiedRepositoryLocationType.REMOTE_URL -> fetchRemoteText(locator)
            UnifiedRepositoryLocationType.LOCAL_FILE -> {
                appContext.contentResolver.openInputStream(Uri.parse(locator))
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: throw IllegalArgumentException(appContext.getString(R.string.unified_sources_cannot_open_repository_file))
            }
            UnifiedRepositoryLocationType.INLINE_IMPORT,
            UnifiedRepositoryLocationType.PRESET_ONLY -> throw IllegalArgumentException(
                appContext.getString(R.string.unified_sources_repository_cannot_refresh),
            )
        }
    }

    private suspend fun fetchRemoteText(url: String): String {
        val response = legadoHttpClient.get(url)
        return try {
            if (!response.isSuccessful) {
                throw IllegalArgumentException(appContext.getString(R.string.unified_sources_http_error, response.code))
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(appContext.getString(R.string.unified_sources_empty_response_body))
        } finally {
            response.close()
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    cursor.getString(index.takeIf { it >= 0 } ?: return@use null)
                }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private suspend fun emitMessage(message: String) {
        _events.emit(UnifiedSourcesEvent.Message(message))
    }

    private suspend fun refreshAvailableExternalPackages(types: List<ExternalExtensionType> = externalExtensionTypes()) {
        availableExternalExtensions.value = types
            .flatMap { type -> extensionRepoRepository.getCatalogExtensions(type) }
            .let { refreshed ->
                val refreshedTypes = types.toSet()
                availableExternalExtensions.value.filterNot { it.type in refreshedTypes } + refreshed
            }
    }

    private suspend fun refreshAvailableLnReaderPackages() {
        val plugins = settings.lnReaderRepoUrls.flatMap { repoUrl ->
            lnReaderRepository.fetchPluginIndex(repoUrl)
                .getOrNull()
                .orEmpty()
                .map { plugin ->
                    LnReaderAvailablePlugin(
                        plugin = plugin,
                        repoUrl = repoUrl,
                        repoName = repositoryTitleForAction(repoUrl, fallback = "LNReader"),
                    )
                }
        }
        availableLnReaderPlugins.value = plugins.withPreferredLnReaderVersions()
        fillMissingLnReaderIcons(availableLnReaderPlugins.value.map { it.plugin })
    }

    private suspend fun refreshAvailableJsonPackages() {
        val legadoPackages = settings.legadoRepoUrls.mapNotNull { repoUrl ->
            runCatching { fetchAvailableLegadoPackage(repoUrl) }.getOrNull()
        }
        val tvBoxPackages = settings.tvBoxRepoUrls.mapNotNull { repoUrl ->
            runCatching { fetchAvailableTvBoxPackage(repoUrl) }.getOrNull()
        }
        availableJsonPackages.value = (legadoPackages + tvBoxPackages).sortedBy { it.name.lowercase() }
    }

    private suspend fun fetchAvailableLegadoPackage(
        repoUrl: String,
        preferredTitle: String? = null,
    ): UnifiedSourcePackageItem {
        val content = fetchRemoteText(repoUrl)
        val sources = jsonSourceManager.inspectLegadoJson(content).getOrThrow()
        require(sources.isNotEmpty()) { appContext.getString(R.string.unified_sources_empty_response_body) }
        val repoName = preferredTitle?.trim()?.takeIf { it.isNotBlank() }
            ?: UnifiedRecommendedRepositories.all.firstOrNull { preset ->
                preset.kind == UnifiedSourceKind.LEGADO &&
                    normalizeRepositoryUrlForAction(preset.url) == normalizeRepositoryUrlForAction(repoUrl)
            }?.name
            ?: repositoryTitleForAction(repoUrl, fallback = "Legado")
        val repositoryId = repositoryIdForAction(UnifiedSourceKind.LEGADO, repoUrl)
        return UnifiedSourcePackageItem(
            id = packageIdForAction(UnifiedSourceKind.LEGADO, repositoryId),
            kind = UnifiedSourceKind.LEGADO,
            name = repoName,
            packageName = repositoryId,
            repositoryId = repositoryId,
            repositoryName = repoName,
            versionName = null,
            versionCode = null,
            language = null,
            isInstalled = false,
            isNsfw = false,
            sourceCount = sources.size,
            sourceNames = sources.map { it.bookSourceName }.distinct().sorted(),
            jsonPayload = UnifiedJsonPackagePayload(
                content = content,
                sourceLocator = repoUrl,
                sourceTitle = repoName,
            ),
        )
    }

    private suspend fun fetchAvailableTvBoxPackage(
        repoUrl: String,
        preferredTitle: String? = null,
    ): UnifiedSourcePackageItem {
        val content = if (repoUrl.isMacCmsApiUrlForAction()) repoUrl else fetchRemoteText(repoUrl)
        val sourceNames = jsonSourceManager.inspectTvBoxJson(
            jsonContent = content,
            sourceLocator = repoUrl,
            sourceTitle = preferredTitle,
        ).getOrThrow()
        val repoName = preferredTitle?.trim()?.takeIf { it.isNotBlank() }
            ?: UnifiedRecommendedRepositories.all.firstOrNull { preset ->
                preset.kind == UnifiedSourceKind.TVBOX &&
                    normalizeRepositoryUrlForAction(preset.url) == normalizeRepositoryUrlForAction(repoUrl)
            }?.name
            ?: repositoryTitleForAction(repoUrl, fallback = "TVBox")
        val repositoryId = repositoryIdForAction(UnifiedSourceKind.TVBOX, repoUrl)
        return UnifiedSourcePackageItem(
            id = packageIdForAction(UnifiedSourceKind.TVBOX, repositoryId),
            kind = UnifiedSourceKind.TVBOX,
            name = repoName,
            packageName = repositoryId,
            repositoryId = repositoryId,
            repositoryName = repoName,
            versionName = null,
            versionCode = null,
            language = null,
            isInstalled = false,
            isNsfw = false,
            sourceCount = sourceNames.size,
            sourceNames = sourceNames,
            jsonPayload = UnifiedJsonPackagePayload(
                content = content,
                sourceLocator = repoUrl,
                sourceTitle = repoName,
            ),
        )
    }

    private suspend fun fillMissingLnReaderIcons(plugins: List<LNReaderPluginInfo>) {
        val iconsById = plugins
            .asSequence()
            .filter { it.id.isNotBlank() && it.iconUrl.isNotBlank() }
            .associate { it.id to it.iconUrl }
        if (iconsById.isEmpty()) return

        val timestamp = System.currentTimeMillis()
        jsonSourceManager.observeAllJsonSources()
            .first()
            .asSequence()
            .filter { it.type == JsonSourceType.LNREADER && it.iconUrl.isNullOrBlank() }
            .mapNotNull { source ->
                val metadata = LNReaderPluginMetadata.extractFromCode(source.config, source.id)
                val iconUrl = metadata?.id?.let(iconsById::get) ?: return@mapNotNull null
                source.id to iconUrl
            }
            .forEach { (sourceId, iconUrl) ->
                jsonSourceManager.fillMissingIconUrl(sourceId, iconUrl, timestamp)
            }
    }

    private suspend fun reloadExternalExtensionManagers() {
        mihonExtensionManager.loadExtensions()
        aniyomiExtensionManager.loadExtensions()
        ireaderExtensionManager.loadExtensions()
        tsundokuExtensionManager.loadExtensions()
    }

    private fun UnifiedSourceCatalogState.withAvailableExternalPackages(
        availableExtensions: List<RepoAvailableExtension>,
        downloadStates: Map<String, ExtensionInstallDownloadState>,
    ): UnifiedSourceCatalogState {
        val externalInstalledPackages = packages
            .filter { it.kind.isExternalExtensionKind() && !it.packageName.isNullOrBlank() }
        val installedByKey = externalInstalledPackages.associateBy { item ->
            item.kind.toExternalExtensionType()?.normalizePackageNameForMatching(item.packageName.orEmpty())
        }
        val handledInstalledKeys = LinkedHashSet<String>()
        val availablePackages = availableExtensions.map { extension ->
            val installedKey = extension.type.normalizePackageNameForMatching(extension.pkgName)
            val installedPackage = installedByKey[installedKey]
            if (installedPackage != null) {
                handledInstalledKeys += installedKey
            }
            extension.toUnifiedPackageItem(
                installedPackage = installedPackage,
                downloadState = downloadStates[extension.pkgName],
            )
        }
        val installedWithoutCatalogMatch = packages.filterNot { item ->
            item.kind.isExternalExtensionKind() &&
                item.kind.toExternalExtensionType()?.normalizePackageNameForMatching(item.packageName.orEmpty()) in handledInstalledKeys
        }
        return copy(
            packages = (installedWithoutCatalogMatch + availablePackages)
                .sortedWith(packageItemComparator)
                .withUniquePackageIds(),
        )
    }

    private fun UnifiedSourceCatalogState.withAvailableLnReaderPackages(
        availablePlugins: List<LnReaderAvailablePlugin>,
        installingPackageIds: Set<String>,
    ): UnifiedSourceCatalogState {
        val installedLnReaderPackages = packages
            .filter { it.kind == UnifiedSourceKind.LNREADER && !it.packageName.isNullOrBlank() }
        val installedByPluginId = installedLnReaderPackages.associateBy { it.packageName.orEmpty() }
        val installedBySourceId = installedLnReaderPackages.associateBy { it.id.substringAfterLast(':') }
        val handledPluginIds = LinkedHashSet<String>()
        val handledInstalledIds = LinkedHashSet<String>()
        val availablePackages = availablePlugins.map { available ->
            val plugin = available.plugin
            val installedPackage = installedByPluginId[plugin.id]
                ?: installedBySourceId[lnReaderSourceId(plugin)]
            if (installedPackage != null) {
                handledPluginIds += plugin.id
                handledInstalledIds += installedPackage.id
            }
            available.toUnifiedPackageItem(
                installedPackage = installedPackage,
                isInstalling = installingPackageIds.contains(available.packageId),
            )
        }
        val installedWithoutCatalogMatch = packages.filterNot { item ->
            item.kind == UnifiedSourceKind.LNREADER && (item.packageName in handledPluginIds || item.id in handledInstalledIds)
        }
        return copy(
            packages = (installedWithoutCatalogMatch + availablePackages)
                .sortedWith(packageItemComparator)
                .withUniquePackageIds(),
        )
    }

    private fun UnifiedSourceCatalogState.withAvailableJsonPackages(
        availablePackages: List<UnifiedSourcePackageItem>,
    ): UnifiedSourceCatalogState {
        val installedById = packages
            .filter { it.kind == UnifiedSourceKind.LEGADO || it.kind == UnifiedSourceKind.TVBOX }
            .associateBy { it.id }
        val mergedPackages = availablePackages.map { available ->
            installedById[available.id]?.copy(
                repositoryId = available.repositoryId,
                repositoryName = available.repositoryName,
                jsonPayload = available.jsonPayload,
            ) ?: available
        }
        val availableIds = availablePackages.mapTo(HashSet()) { it.id }
        return copy(
            packages = (packages.filterNot { it.id in availableIds } + mergedPackages)
                .sortedWith(packageItemComparator)
                .withUniquePackageIds(),
        )
    }

    private fun RepoAvailableExtension.toUnifiedPackageItem(
        installedPackage: UnifiedSourcePackageItem?,
        downloadState: ExtensionInstallDownloadState?,
    ): UnifiedSourcePackageItem {
        val isInstalled = installedPackage != null
        val isTrusted = installedPackage == null ||
            signatureValidator.isTrusted(installedPackage.packageName.orEmpty(), signatureHash)
        val state = when {
            downloadState != null -> UnifiedSourcePackageState.INSTALLING
            isInstalled && !isTrusted -> UnifiedSourcePackageState.UNTRUSTED
            !isCompatible -> UnifiedSourcePackageState.INCOMPATIBLE
            !isInstalled -> UnifiedSourcePackageState.AVAILABLE
            isNewerThanInstalled(installedPackage.versionCode) -> UnifiedSourcePackageState.UPDATE_AVAILABLE
            else -> UnifiedSourcePackageState.INSTALLED
        }
        val kind = type.toUnifiedKindForPackage()
        return UnifiedSourcePackageItem(
            id = installedPackage?.id ?: packageIdForAction(kind, pkgName),
            kind = kind,
            name = name,
            packageName = pkgName,
            repositoryId = repositoryIdForAction(kind, repoUrl),
            repositoryName = installedPackage?.repositoryName ?: repoName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            language = lang.normalizeLanguageCode(),
            isInstalled = isInstalled,
            isNsfw = isNsfw,
            sourceCount = installedPackage?.sourceCount?.takeIf { it > 0 } ?: sourceNames.size,
            sourceNames = installedPackage?.sourceNames?.takeIf { it.isNotEmpty() } ?: sourceNames,
            iconUrl = iconUrl.takeIf { it.isNotBlank() } ?: installedPackage?.iconUrl,
            state = state,
            installedVersionName = installedPackage?.versionName,
            installProgressPercent = downloadState?.progressPercent,
            installLocation = installedPackage?.installLocation,
            installPayload = this,
        )
    }

    private fun LnReaderAvailablePlugin.toUnifiedPackageItem(
        installedPackage: UnifiedSourcePackageItem?,
        isInstalling: Boolean,
    ): UnifiedSourcePackageItem {
        val state = when {
            isInstalling -> UnifiedSourcePackageState.INSTALLING
            installedPackage == null -> UnifiedSourcePackageState.AVAILABLE
            isNewerLnReaderVersion(plugin.version, installedPackage.versionName) ->
                UnifiedSourcePackageState.UPDATE_AVAILABLE
            else -> UnifiedSourcePackageState.INSTALLED
        }
        return UnifiedSourcePackageItem(
            id = installedPackage?.id ?: packageId,
            kind = UnifiedSourceKind.LNREADER,
            name = plugin.name.ifBlank { plugin.id },
            packageName = plugin.id,
            repositoryId = repositoryIdForAction(UnifiedSourceKind.LNREADER, repoUrl),
            repositoryName = repoName,
            versionName = plugin.version.takeIf { it.isNotBlank() },
            versionCode = null,
            language = plugin.lang.normalizeLanguageCode(),
            isInstalled = installedPackage != null,
            isNsfw = false,
            sourceCount = installedPackage?.sourceCount ?: 1,
            sourceNames = installedPackage?.sourceNames ?: listOf(plugin.name.ifBlank { plugin.id }),
            iconUrl = plugin.iconUrl.takeIf { it.isNotBlank() } ?: installedPackage?.iconUrl,
            state = state,
            installedVersionName = installedPackage?.versionName,
            lnReaderPayload = plugin,
        )
    }

    private fun UnifiedSourceCatalogState.toUiState(
        filters: UnifiedSourcesFilterState,
    ): UnifiedSourcesUiState.Ready {
        val repositoriesById = repositories.associateBy { it.id }
        val enrichedPackages = packages.withUniquePackageIds().enrichWithSourceCoverage(sources)
        val packagesById = enrichedPackages.associateBy { it.id }
        val enrichedSources = sources.map { source ->
            if (source.kind == UnifiedSourceKind.JAR && source.repositoryName.isNullOrBlank()) {
                source.copy(repositoryName = source.packageId?.let(packagesById::get)?.repositoryName)
            } else {
                source
            }
        }
        val visibleRepositories = repositories.filterBy(filters)
        val visiblePackages = enrichedPackages.filterBy(filters, repositoriesById)
        val visibleSources = enrichedSources
            .filterBy(filters, repositoriesById, packagesById)
        val availableLanguages = (
            enrichedPackages.mapNotNull { it.language } + enrichedSources.mapNotNull { it.language }
        )
            .map { it.normalizeLanguageCode() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        Log.d(
            TAG,
            "language filter availableLanguages=$availableLanguages selectedLanguages=${filters.languages}",
        )

        return UnifiedSourcesUiState.Ready(
            filters = filters,
            repositories = visibleRepositories,
            packages = visiblePackages,
            sources = visibleSources,
            allRepositories = repositories,
            allPackages = enrichedPackages,
            allSources = enrichedSources,
            availableKinds = (
                repositories.map { it.kind } + enrichedPackages.map { it.kind } + enrichedSources.map { it.kind }
            )
                .distinct()
                .sortedBy { it.ordinal },
            availableContentTypes = enrichedSources.map { it.contentType }
                .distinct()
                .sortedBy { it.ordinal },
            availableLocationTypes = repositories.map { it.locationType }
                .distinct()
                .sortedBy { it.ordinal },
            availableLanguages = availableLanguages,
        )
    }

    /**
     * Builds the "Recommended" package group for the packages tab: sources recorded in
     * `source_origins` by external backup imports (kind MIHON/ANIYOMI, numeric id) whose
     * extension is not installed are matched against the store indexes by source id.
     * Matched extensions become install recommendations; the rest are surfaced together
     * with suggested repositories.
     */
    private fun UnifiedSourcesUiState.Ready.withMissingSourceRecommendations(
        sourceOrigins: List<org.skepsun.kototoro.core.db.entity.SourceOriginEntity>,
        availableExtensions: List<RepoAvailableExtension>,
    ): UnifiedSourcesUiState.Ready {
        val candidates = sourceOrigins.asSequence()
            .filter { it.kind == "MIHON" || it.kind == "ANIYOMI" }
            .mapNotNull { origin ->
                val id = origin.sourceKey.substringAfter('_', "").toLongOrNull() ?: return@mapNotNull null
                val kind = if (origin.kind == "ANIYOMI") UnifiedSourceKind.ANIYOMI else UnifiedSourceKind.MIHON
                Triple(origin, id, kind)
            }
            .toList()
        if (candidates.isEmpty()) {
            return this
        }
        val installedSourceIds = allSources.asSequence()
            .filter { it.kind == UnifiedSourceKind.MIHON || it.kind == UnifiedSourceKind.ANIYOMI }
            .mapNotNull { it.source.name.substringAfter('_', "").toLongOrNull() }
            .toSet()
        val missingCandidates = candidates.filter { (_, id, _) ->
            shouldRecommendMissingExtensionSource(id, installedSourceIds)
        }
        if (missingCandidates.isEmpty()) {
            return copy(
                recommendedPackages = emptyList(),
                missingSourcesWithoutMatch = emptyList(),
                suggestedRepositoriesForMissing = emptyList(),
            )
        }
        val candidateIds = missingCandidates.mapTo(HashSet()) { it.second }
        val labelById = HashMap<Long, String>()
        missingCandidates.forEach { (origin, id, _) ->
            labelById[id] = origin.displayName?.takeIf { it.isNotBlank() } ?: origin.sourceKey
        }
        val recommendedPackages = allPackages
            .filter { item ->
                !item.isInstalled && item.installPayload?.sourceIds.orEmpty().any { it in candidateIds }
            }
            .map { item ->
                val covered = item.installPayload?.sourceIds.orEmpty()
                    .mapNotNull(labelById::get)
                    .distinct()
                RecommendedPackageItem(item = item, coversMissingSources = covered)
            }
            .sortedWith(
                compareByDescending<RecommendedPackageItem> { it.coversMissingSources.size }
                    .thenBy { it.item.name.lowercase() },
            )
        val coveredIds = recommendedPackages
            .asSequence()
            .flatMap { it.item.installPayload?.sourceIds.orEmpty().asSequence() }
            .toSet()
        val missingSourcesWithoutMatch = missingCandidates
            .filter { (_, id, _) -> id !in coveredIds }
            .map { (origin, _, kind) ->
                MissingSourceHint(kind = kind, sourceKey = origin.sourceKey, displayName = origin.displayName)
            }
        val suggestedKinds = missingSourcesWithoutMatch.map { it.kind }.toSet()
        // Only actually configured repos count: the repository list always contains the
        // recommended presets (isConfigured=false) under the very same URL, so comparing
        // against all items would filter out every suggestion.
        val configuredUrls = repositories.filterTo(HashSet()) { it.isConfigured }.mapTo(HashSet()) { it.url }
        val suggestedRepositoriesForMissing = suggestedKinds
            .flatMap(UnifiedRecommendedRepositories::byKind)
            .filter { suggestion ->
                configuredUrls.none { configured ->
                    normalizeRepositoryUrlForAction(configured) == normalizeRepositoryUrlForAction(suggestion.url)
                }
            }
            .distinctBy { it.url }
        return copy(
            recommendedPackages = recommendedPackages,
            missingSourcesWithoutMatch = missingSourcesWithoutMatch,
            suggestedRepositoriesForMissing = suggestedRepositoriesForMissing,
        )
    }

    private fun List<UnifiedSourcePackageItem>.enrichWithSourceCoverage(
        sources: List<UnifiedSourceItem>,
    ): List<UnifiedSourcePackageItem> {
        if (isEmpty()) {
            return this
        }
        val activeSourceCountByPackageId = sources
            .asSequence()
            .mapNotNull { source -> source.packageId }
            .groupBy { it }
            .mapValues { (_, packageIds) -> packageIds.size }
        return map { item ->
            val declaredCount = item.sourceCount.coerceAtLeast(item.sourceNames.size)
            val supportsShadowedSources = item.kind == UnifiedSourceKind.JAR
            val activeCount = if (supportsShadowedSources) {
                (activeSourceCountByPackageId[item.id] ?: 0).coerceIn(0, declaredCount)
            } else {
                declaredCount
            }
            val shadowedCount = if (supportsShadowedSources) {
                (declaredCount - activeCount).coerceAtLeast(0)
            } else {
                0
            }
            item.copy(
                activeSourceCount = activeCount,
                shadowedSourceCount = shadowedCount,
            )
        }
    }

    private fun List<UnifiedSourceRepositoryItem>.filterBy(
        filters: UnifiedSourcesFilterState,
    ): List<UnifiedSourceRepositoryItem> {
        val query = filters.query.trim()
        return asSequence()
            .filter { filters.kinds.isEmpty() || it.kind in filters.kinds }
            .filter { filters.locationTypes.isEmpty() || it.locationType in filters.locationTypes }
            .filter { query.isBlank() || it.matchesQuery(query) }
            .sortedWith(compareBy({ it.kind.ordinal }, { !it.isConfigured }, { it.name.lowercase() }))
            .toList()
    }

    private fun List<UnifiedSourcePackageItem>.filterBy(
        filters: UnifiedSourcesFilterState,
        repositoriesById: Map<String, UnifiedSourceRepositoryItem>,
    ): List<UnifiedSourcePackageItem> {
        val query = filters.query.trim()
        return asSequence()
            .filter { filters.kinds.isEmpty() || it.kind in filters.kinds }
            .filter { filters.locationTypes.isEmpty() || it.repositoryLocationType(repositoriesById) in filters.locationTypes }
            .filter { filters.languages.isEmpty() || it.language.matchesLanguageFilter(filters.languages) }
            .filter {
                when (filters.nsfwFilter) {
                    UnifiedNsfwFilter.ALL -> true
                    UnifiedNsfwFilter.SFW -> !it.isNsfw
                    UnifiedNsfwFilter.NSFW -> it.isNsfw
                }
            }
            .filter { query.isBlank() || it.matchesQuery(query) }
            .sortedWith(packageItemComparator)
            .toList()
    }

    private fun List<UnifiedSourceItem>.filterBy(
        filters: UnifiedSourcesFilterState,
        repositoriesById: Map<String, UnifiedSourceRepositoryItem>,
        packagesById: Map<String, UnifiedSourcePackageItem>,
    ): List<UnifiedSourceItem> {
        val query = filters.query.trim()
        return asSequence()
            .filter { filters.kinds.isEmpty() || it.kind in filters.kinds }
            .filter { filters.contentTypes.isEmpty() || it.contentType in filters.contentTypes }
            .filter { filters.languages.isEmpty() || it.language.matchesLanguageFilter(filters.languages) }
            .filter {
                when (filters.enabledFilter) {
                    UnifiedEnabledFilter.ALL -> true
                    UnifiedEnabledFilter.ENABLED -> it.isEnabled
                    UnifiedEnabledFilter.DISABLED -> !it.isEnabled
                }
            }
            .filter {
                when (filters.availabilityFilter) {
                    UnifiedAvailabilityFilter.ALL -> true
                    UnifiedAvailabilityFilter.AVAILABLE -> it.isAvailable && !it.isBroken
                    UnifiedAvailabilityFilter.UNAVAILABLE -> !it.isAvailable || it.isBroken
                }
            }
            .filter {
                when (filters.testAvailabilityFilter) {
                    UnifiedTestAvailabilityFilter.ALL -> true
                    UnifiedTestAvailabilityFilter.UNTESTED -> it.testAvailability == ContentSourceAvailability.UNKNOWN
                    UnifiedTestAvailabilityFilter.AVAILABLE -> it.testAvailability == ContentSourceAvailability.AVAILABLE
                    UnifiedTestAvailabilityFilter.UNAVAILABLE -> it.testAvailability == ContentSourceAvailability.EMPTY
                }
            }
            .filter {
                when (filters.nsfwFilter) {
                    UnifiedNsfwFilter.ALL -> true
                    UnifiedNsfwFilter.SFW -> !it.isNsfw
                    UnifiedNsfwFilter.NSFW -> it.isNsfw
                }
            }
            .filter { filters.locationTypes.isEmpty() || it.repositoryLocationType(repositoriesById, packagesById) in filters.locationTypes }
            .filter { query.isBlank() || it.matchesQuery(query) }
            .sortedWith(compareByDescending<UnifiedSourceItem> { it.isPinned }.thenBy { it.title.lowercase() })
            .toList()
    }
}

internal fun <T> Set<T>.toggle(value: T): Set<T> {
    return if (value in this) this - value else this + value
}

private fun UnifiedSourceRepositoryItem.matchesQuery(query: String): Boolean {
    return name.contains(query, ignoreCase = true) ||
        url.contains(query, ignoreCase = true) ||
        website.contains(query, ignoreCase = true)
}

private fun UnifiedSourcePackageItem.matchesQuery(query: String): Boolean {
    return name.contains(query, ignoreCase = true) ||
        packageName.orEmpty().contains(query, ignoreCase = true) ||
        repositoryName.orEmpty().contains(query, ignoreCase = true) ||
        sourceNames.any { it.contains(query, ignoreCase = true) }
}

private fun UnifiedSourceItem.matchesQuery(query: String): Boolean {
    return title.contains(query, ignoreCase = true) ||
        id.contains(query, ignoreCase = true) ||
        packageName.orEmpty().contains(query, ignoreCase = true) ||
        repositoryName.orEmpty().contains(query, ignoreCase = true)
}

private fun UnifiedSourcePackageItem.repositoryLocationType(
    repositoriesById: Map<String, UnifiedSourceRepositoryItem>,
): UnifiedRepositoryLocationType? {
    return repositoryId?.let(repositoriesById::get)?.locationType
}

private fun UnifiedSourceItem.repositoryLocationType(
    repositoriesById: Map<String, UnifiedSourceRepositoryItem>,
    packagesById: Map<String, UnifiedSourcePackageItem>,
): UnifiedRepositoryLocationType? {
    repositoryId?.let(repositoriesById::get)?.locationType?.let { return it }
    val packageRepositoryId = packageId?.let(packagesById::get)?.repositoryId
    return packageRepositoryId?.let(repositoriesById::get)?.locationType
}

private fun String?.matchesLanguageFilter(languages: Set<String>): Boolean {
    val normalized = this?.normalizeLanguageCode().orEmpty()
    return normalized.isBlank() || normalized in languages
}

private fun Iterable<String>.normalizeLanguageCodes(): LinkedHashSet<String> {
    return mapTo(LinkedHashSet()) { it.normalizeLanguageCode() }
        .filterTo(LinkedHashSet()) { it.isNotBlank() }
}

private fun String.normalizeLanguageCode(): String {
    return normalizeExtensionLanguageCode()
}

private fun UnifiedSourceKind.toExternalExtensionType(): ExternalExtensionType? {
    return when (this) {
        UnifiedSourceKind.CLOUDSTREAM -> ExternalExtensionType.CLOUDSTREAM
        UnifiedSourceKind.MIHON -> ExternalExtensionType.MIHON
        UnifiedSourceKind.ANIYOMI -> ExternalExtensionType.ANIYOMI
        UnifiedSourceKind.IREADER -> ExternalExtensionType.IREADER
        UnifiedSourceKind.JAR -> ExternalExtensionType.JAR
        UnifiedSourceKind.TSUNDOKU -> ExternalExtensionType.TSUNDOKU
        else -> null
    }
}

private fun ExternalExtensionType.toUnifiedKindForPackage(): UnifiedSourceKind {
    return when (this) {
        ExternalExtensionType.CLOUDSTREAM -> UnifiedSourceKind.CLOUDSTREAM
        ExternalExtensionType.MIHON -> UnifiedSourceKind.MIHON
        ExternalExtensionType.ANIYOMI -> UnifiedSourceKind.ANIYOMI
        ExternalExtensionType.IREADER -> UnifiedSourceKind.IREADER
        ExternalExtensionType.JAR -> UnifiedSourceKind.JAR
        ExternalExtensionType.TSUNDOKU -> UnifiedSourceKind.TSUNDOKU
    }
}

private fun UnifiedSourceKind.isExternalExtensionKind(): Boolean {
    return when (this) {
        UnifiedSourceKind.JAR,
        UnifiedSourceKind.CLOUDSTREAM,
        UnifiedSourceKind.MIHON,
        UnifiedSourceKind.ANIYOMI,
        UnifiedSourceKind.IREADER,
        UnifiedSourceKind.TSUNDOKU -> true
        else -> false
    }
}

private fun UnifiedSourceKind.toLocalApkEcosystem(): String? {
    return when (this) {
        UnifiedSourceKind.MIHON -> "mihon"
        UnifiedSourceKind.ANIYOMI -> "aniyomi"
        UnifiedSourceKind.IREADER -> "ireader"
        UnifiedSourceKind.TSUNDOKU -> "tsundoku"
        else -> null
    }
}

private fun UnifiedSourceKind.isJsonBackedKind(): Boolean {
    return when (this) {
        UnifiedSourceKind.LEGADO,
        UnifiedSourceKind.TVBOX,
        UnifiedSourceKind.JS,
        UnifiedSourceKind.LNREADER -> true
        else -> false
    }
}

private fun JsonSourceEntity.jsonPackageIdForAction(): String? {
    return when (type) {
        JsonSourceType.LEGADO -> {
            val repositoryId = jsonRepositoryIdForAction()
            packageIdForAction(UnifiedSourceKind.LEGADO, repositoryId ?: "imported")
        }
        JsonSourceType.TVBOX -> {
            val repositoryId = jsonRepositoryIdForAction()
            packageIdForAction(UnifiedSourceKind.TVBOX, repositoryId ?: "inline")
        }
        JsonSourceType.JS -> packageIdForAction(UnifiedSourceKind.JS, id)
        JsonSourceType.LNREADER -> packageIdForAction(UnifiedSourceKind.LNREADER, id)
    }
}

private fun JsonSourceEntity.jsonRepositoryIdForAction(): String? {
    return when (type) {
        JsonSourceType.LEGADO -> {
            val locator = JsonSourceImportMetadata.parse(config)
                ?.sourceLocator
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            repositoryIdForAction(UnifiedSourceKind.LEGADO, locator)
        }
        JsonSourceType.TVBOX -> {
            val locator = runCatching { TVBoxStoredConfig.parse(config) }
                .getOrNull()
                ?.meta
                ?.sourceLocator
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            repositoryIdForAction(UnifiedSourceKind.TVBOX, locator)
        }
        JsonSourceType.JS,
        JsonSourceType.LNREADER -> null
    }
}

private fun UnifiedSourceKind.isHotReloadableExternalKind(): Boolean {
    return toLocalApkEcosystem() != null
}

private val UnifiedSourcePackageState.sortOrder: Int
    get() = when (this) {
        UnifiedSourcePackageState.UPDATE_AVAILABLE -> 0
        UnifiedSourcePackageState.UNTRUSTED -> 1
        UnifiedSourcePackageState.INCOMPATIBLE -> 2
        UnifiedSourcePackageState.INSTALLING -> 3
        UnifiedSourcePackageState.INSTALLED -> 4
        UnifiedSourcePackageState.AVAILABLE -> 5
    }

private val packageItemComparator = compareByDescending<UnifiedSourcePackageItem> { it.isInstalled }
    .thenBy { it.state.sortOrder }
    .thenBy { it.kind.ordinal }
    .thenBy { it.name.lowercase() }

private fun externalExtensionTypes(): List<ExternalExtensionType> {
    return listOf(
        ExternalExtensionType.JAR,
        ExternalExtensionType.CLOUDSTREAM,
        ExternalExtensionType.MIHON,
        ExternalExtensionType.ANIYOMI,
        ExternalExtensionType.IREADER,
        ExternalExtensionType.TSUNDOKU,
    )
}

internal fun UnifiedSourcesUiState.disabledSourcesForPackageRefresh(): List<ContentSource> {
    return (this as? UnifiedSourcesUiState.Ready)
        ?.allSources
        .orEmpty()
        .asSequence()
        .filterNot(UnifiedSourceItem::isEnabled)
        .map(UnifiedSourceItem::source)
        .distinctBy(ContentSource::name)
        .toList()
}

private fun repositoryIdForAction(kind: UnifiedSourceKind, url: String): String {
    return "repo:${kind.name}:${normalizeRepositoryUrlForAction(url)}"
}

private fun Set<String>.containsRepositoryUrl(url: String): Boolean {
    val normalizedUrl = normalizeRepositoryUrlForAction(url)
    return any { normalizeRepositoryUrlForAction(it) == normalizedUrl }
}

private fun Set<String>.withoutRepositoryUrl(url: String): Set<String> {
    val normalizedUrl = normalizeRepositoryUrlForAction(url)
    return filterNotTo(linkedSetOf()) { normalizeRepositoryUrlForAction(it) == normalizedUrl }
}

private fun packageIdForAction(kind: UnifiedSourceKind, value: String): String {
    return "package:${kind.name}:${value.trim()}"
}

private fun lnReaderPackageIdForAction(repoUrl: String, pluginId: String): String {
    return packageIdForAction(UnifiedSourceKind.LNREADER, "${normalizeRepositoryUrlForAction(repoUrl)}:${pluginId.trim()}")
}

internal data class LnReaderAvailablePlugin(
    val plugin: LNReaderPluginInfo,
    val repoUrl: String,
    val repoName: String,
) {
    val packageId: String = lnReaderPackageIdForAction(repoUrl, plugin.id)
}

internal fun List<LnReaderAvailablePlugin>.withPreferredLnReaderVersions(): List<LnReaderAvailablePlugin> {
    val preferredBySourceId = LinkedHashMap<String, LnReaderAvailablePlugin>()
    forEach { candidate ->
        val sourceId = lnReaderSourceId(candidate.plugin)
        val current = preferredBySourceId[sourceId]
        if (current == null || compareLnReaderVersions(candidate.plugin.version, current.plugin.version) > 0) {
            preferredBySourceId[sourceId] = candidate
        }
    }
    return preferredBySourceId.values.toList()
}

internal fun isNewerLnReaderVersion(candidate: String, installed: String?): Boolean {
    if (installed.isNullOrBlank() || candidate.isBlank()) return false
    return compareLnReaderVersions(candidate, installed) > 0
}

private fun compareLnReaderVersions(left: String, right: String): Int {
    return VersionId(left.normalizedLnReaderVersion()).compareTo(VersionId(right.normalizedLnReaderVersion()))
}

private fun String.normalizedLnReaderVersion(): String {
    return trim().removePrefix("v").removePrefix("V")
}

private fun lnReaderSourceId(plugin: LNReaderPluginInfo): String {
    val sourceKey = plugin.site.ifBlank { plugin.id }
    return "JSON_LNREADER_${sourceKey.hashCode().toUInt().toString(16).uppercase()}"
}

private data class AvailablePackageSnapshot(
    val plugins: List<LnReaderAvailablePlugin>,
    val installingPackageIds: Set<String>,
    val jsonPackages: List<UnifiedSourcePackageItem>,
)

private fun UnifiedSourceKind.displayNameForMessage(context: Context): String {
    return when (this) {
        UnifiedSourceKind.NATIVE -> context.getString(R.string.source_type_native)
        UnifiedSourceKind.JAR -> context.getString(R.string.source_type_jar)
        UnifiedSourceKind.CLOUDSTREAM -> context.getString(R.string.source_type_cloudstream)
        UnifiedSourceKind.MIHON -> context.getString(R.string.source_type_mihon)
        UnifiedSourceKind.ANIYOMI -> context.getString(R.string.source_type_aniyomi)
        UnifiedSourceKind.IREADER -> context.getString(R.string.source_type_ireader)
        UnifiedSourceKind.TSUNDOKU -> context.getString(R.string.source_type_tsundoku)
        UnifiedSourceKind.LEGADO -> context.getString(R.string.source_type_legado)
        UnifiedSourceKind.TVBOX -> context.getString(R.string.source_type_tvbox)
        UnifiedSourceKind.JS -> context.getString(R.string.source_type_js)
        UnifiedSourceKind.LNREADER -> context.getString(R.string.source_type_lnreader)
    }
}

private fun normalizeRepositoryUrlForAction(url: String): String {
    val trimmed = url.trim()
    val lower = trimmed.lowercase()
    if (
        lower.endsWith(".json") &&
        !lower.endsWith("/index.min.json") &&
        !lower.endsWith("/plugins.json") &&
        !lower.endsWith("/repo.json")
    ) {
        return trimmed.trimEnd('/')
    }
    return trimmed
        .trimEnd('/')
        .removeSuffix("/index.pb")
        .removeSuffix("/index.min.json")
        .removeSuffix("/plugins.json")
        .removeSuffix("/repo.json")
        .removeSuffix("/repo")
        .trimEnd('/')
}

private fun String.isMacCmsApiUrlForAction(): Boolean {
    val uri = runCatching { Uri.parse(trim()) }.getOrNull() ?: return false
    if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
        return false
    }
    val path = "/" + uri.path.orEmpty().trimStart('/').lowercase()
    return path.startsWith("/api.php/provide/vod") ||
        path.startsWith("/index.php/api/vod") ||
        path.startsWith("/api/vod") ||
        path.startsWith("/provide/vod") ||
        path.contains(".php/provide/vod")
}

private fun repositoryTitleForAction(url: String, fallback: String): String {
    val uri = runCatching { Uri.parse(url) }.getOrNull()
    val host = uri?.host?.trim().orEmpty()
    val tail = uri?.lastPathSegment?.trim().orEmpty()
    return when {
        host.isNotBlank() && tail.isNotBlank() -> "$host / $tail"
        host.isNotBlank() -> host
        tail.isNotBlank() -> tail
        else -> fallback
    }
}

private fun resolveRepositoryLocationTypeForAction(locator: String): UnifiedRepositoryLocationType {
    return when {
        locator.startsWith("content://", ignoreCase = true) -> UnifiedRepositoryLocationType.LOCAL_FILE
        locator.startsWith("file://", ignoreCase = true) -> UnifiedRepositoryLocationType.LOCAL_FILE
        locator.startsWith("http://", ignoreCase = true) -> UnifiedRepositoryLocationType.REMOTE_URL
        locator.startsWith("https://", ignoreCase = true) -> UnifiedRepositoryLocationType.REMOTE_URL
        else -> UnifiedRepositoryLocationType.INLINE_IMPORT
    }
}

/**
 * Merges optimistic enabled-state overrides into a ready state so toggle feedback does
 * not depend on the catalog rebuild. See [UnifiedSourcesViewModel._enabledOverrides].
 */
internal fun UnifiedSourcesUiState.Ready.withEnabledOverrides(
    overrides: Map<String, Boolean>,
): UnifiedSourcesUiState.Ready {
    if (overrides.isEmpty()) {
        return this
    }
    return copy(
        allSources = allSources.withEnabledOverrides(overrides),
        sources = sources.withEnabledOverrides(overrides),
    )
}

internal fun List<UnifiedSourceItem>.withEnabledOverrides(
    overrides: Map<String, Boolean>,
): List<UnifiedSourceItem> = map { item ->
    val value = overrides[item.id] ?: return@map item
    if (item.isEnabled == value) item else item.copy(isEnabled = value)
}

/**
 * Ids whose override matches the authoritative item state on this list. Used to retire
 * optimistic overrides only once the rebuild agrees with them (never before, or the
 * switch would flicker back while the DB write is still in flight).
 */
internal fun List<UnifiedSourceItem>.agreedOverrideIds(
    overrides: Map<String, Boolean>,
): Set<String> = mapNotNullTo(mutableSetOf()) { item ->
    item.id.takeIf { overrides[it] == item.isEnabled }
}
