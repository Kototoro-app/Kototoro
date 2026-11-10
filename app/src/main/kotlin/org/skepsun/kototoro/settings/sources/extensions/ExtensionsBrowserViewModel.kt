package org.skepsun.kototoro.settings.sources.extensions

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.require
import org.skepsun.kototoro.extensions.install.ExtensionInstallMode
import org.skepsun.kototoro.extensions.install.ExtensionInstallResult
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.InstalledExtensionSignatureValidator
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.mihon.MihonExtensionManager
import javax.inject.Inject

@HiltViewModel
class ExtensionsBrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val settings: AppSettings,
    private val repoRepository: ExternalExtensionRepoRepository,
    private val installService: ExtensionInstallService,
    private val signatureValidator: InstalledExtensionSignatureValidator,
    private val mihonExtensionManager: MihonExtensionManager,
    private val aniyomiExtensionManager: AniyomiExtensionManager,
    private val ireaderExtensionManager: org.skepsun.kototoro.ireader.IReaderExtensionManager,
    private val cloudstreamRuntimeManager: org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager,
    private val packageRemovalCleanup: org.skepsun.kototoro.extensions.install.PackageRemovalCleanup,
    private val catalogRepository: org.skepsun.kototoro.settings.sources.unified.UnifiedSourceCatalogRepository,
    private val tsundokuExtensionManager: org.skepsun.kototoro.tsundoku.TsundokuExtensionManager,
) : BaseViewModel() {

    val type: ExternalExtensionType = enumValueOf(savedStateHandle.require<String>(ARG_EXTENSION_TYPE))

    private val availableExtensions = MutableStateFlow<List<RepoAvailableExtension>>(emptyList())
    private val searchQuery = MutableStateFlow("")
    private val collapsedLanguageGroups = MutableStateFlow<Set<ExtensionsLanguageGroupKey>>(emptySet())
    private val batchUpdateState = ExtensionBatchUpdateStateMachine()

    val currentSearchQuery: StateFlow<String> = searchQuery
    val updateAllInProgress: StateFlow<Boolean> = batchUpdateState.inProgress
    val currentCollapsedLanguageGroups: StateFlow<Set<ExtensionsLanguageGroupKey>> = collapsedLanguageGroups

    private val selectedExtensionLanguages: StateFlow<Set<String>> = settings.observeAsFlow(
        AppSettings.KEY_EXTENSION_LANGUAGES,
    ) { extensionLanguages }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settings.extensionLanguages)

    private val isStrictExtensionLanguageFilter: StateFlow<Boolean> = settings.observeAsFlow(
        AppSettings.KEY_STRICT_EXTENSION_LANGUAGE_FILTER,
    ) { isStrictExtensionLanguageFilter }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settings.isStrictExtensionLanguageFilter)

    val repoCount: StateFlow<Int> = repoRepository.observeByType(type)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val installedExtensions: StateFlow<List<InstalledExtensionEntry>> = observeInstalledExtensionEntries(
        type = type,
        mihonExtensionManager = mihonExtensionManager,
        aniyomiExtensionManager = aniyomiExtensionManager,
        ireaderExtensionManager = ireaderExtensionManager,
        context = appContext,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @Volatile private var trustCacheInstalled: List<InstalledExtensionEntry>? = null
    private val trustCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun isTrustedCached(packageName: String, expectedFingerprint: String): Boolean {
        val key = "$packageName:$expectedFingerprint"
        return trustCache[key] ?: signatureValidator.isTrusted(packageName, expectedFingerprint).also { trustCache[key] = it }
    }

    private val browserInputsBase: StateFlow<BrowserInputs> = combine(
        installedExtensions,
        availableExtensions,
        combine(
            installService.downloadStates.distinctUntilChanged { a, b ->
                a.size == b.size && a.all { (k, v) -> b.containsKey(k) && b[k]?.progressPercent == v.progressPercent }
            },
            installService.installing,
        ) { downloads, installing -> downloads to installing },
        selectedExtensionLanguages,
        isStrictExtensionLanguageFilter,
    ) { installed, available, downloadInputs, selectedLanguages, isStrict ->
        BrowserInputs(
            installed = installed,
            available = available,
            downloads = downloadInputs.first,
            installing = downloadInputs.second,
            selectedLanguages = selectedLanguages,
            isStrictLanguageFilter = isStrict,
            collapsedGroups = emptySet(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        BrowserInputs(
            installed = emptyList(),
            available = emptyList(),
            downloads = emptyMap(),
            installing = emptySet(),
            selectedLanguages = selectedExtensionLanguages.value,
            isStrictLanguageFilter = isStrictExtensionLanguageFilter.value,
            collapsedGroups = emptySet(),
        ),
    )

    private val browserInputs: StateFlow<BrowserInputs> = combine(
        browserInputsBase,
        collapsedLanguageGroups,
    ) { base, collapsedGroups ->
        base.copy(collapsedGroups = collapsedGroups)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        browserInputsBase.value.copy(collapsedGroups = collapsedLanguageGroups.value),
    )

    val items: StateFlow<List<ExtensionsBrowserListItem>> = combine(
        browserInputs,
        searchQuery,
    ) { inputs, query ->
        if (inputs.installed !== trustCacheInstalled) {
            trustCache.clear()
            trustCacheInstalled = inputs.installed
        }
        buildExtensionsBrowserItems(
            type = type,
            installed = inputs.installed,
            available = inputs.available,
            downloadStates = inputs.downloads,
            installing = inputs.installing,
            selectedExtensionLanguages = inputs.selectedLanguages,
            collapsedLanguageGroups = inputs.collapsedGroups,
            query = query,
            isTrustedPackage = ::isTrustedCached,
            isStrictLanguageFilter = inputs.isStrictLanguageFilter,
        )
    }.flowOn(Dispatchers.Default).distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val sectionCounts: StateFlow<Map<ExtensionsBrowserSection, Int>> = items.map { list ->
        val counts = HashMap<ExtensionsBrowserSection, Int>(4)
        for (item in list) {
            if (item is ExtensionsBrowserListItem.SectionHeader) counts.putIfAbsent(item.section, item.count)
        }
        counts
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val installedCount: StateFlow<Int> = sectionCounts.map { it[ExtensionsBrowserSection.INSTALLED] ?: 0 }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val updateCount: StateFlow<Int> = sectionCounts.map { it[ExtensionsBrowserSection.UPDATES] ?: 0 }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val availableCount: StateFlow<Int> = sectionCounts.map { it[ExtensionsBrowserSection.AVAILABLE] ?: 0 }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val availableLanguageCodes: StateFlow<List<String>> = combine(installedExtensions, availableExtensions) { installed, available ->
        (installed.asSequence().map { it.lang.normalizeExtensionLanguageCode() } + available.asSequence().map { it.normalizeExtensionLanguageCode() })
            .filter { it.isNotBlank() || it == "" }
            .distinct()
            .sorted()
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val onInstallIntent = MutableEventFlow<Intent>()
    val onUninstallIntent = MutableEventFlow<Intent>()
    val onStateDetails = MutableEventFlow<ExtensionsBrowserListItem.Entry>()
    val onMessage = MutableEventFlow<String>()

    init {
        refresh()
    }

    fun refresh() {
        launchLoadingJob(Dispatchers.IO) {
            repoRepository.refresh(type)
            availableExtensions.value = repoRepository.getCatalogExtensions(type)
        }
    }

    fun install(item: ExtensionsBrowserListItem.Entry) {
        if (item.state == ExtensionsBrowserEntryState.INSTALLED || item.extension.pkgName in installService.downloadStates.value || item.extension.pkgName in installService.installing.value) {
            return
        }
        requestInstall(item, fromBatch = false)
    }

    fun onPrimaryAction(item: ExtensionsBrowserListItem.Entry) {
        when (item.state) {
            ExtensionsBrowserEntryState.AVAILABLE,
            ExtensionsBrowserEntryState.UPDATE_AVAILABLE -> install(item)

            ExtensionsBrowserEntryState.UNTRUSTED,
            ExtensionsBrowserEntryState.INCOMPATIBLE -> onStateDetails.call(item)

            ExtensionsBrowserEntryState.INSTALLING,
            ExtensionsBrowserEntryState.INSTALLED -> Unit
        }
    }

    fun cancelInstall(item: ExtensionsBrowserListItem.Entry) {
        if (batchUpdateState.shouldCancelCurrent(item.pkgName)) {
            cancelUpdateAll()
            return
        }
        installService.cancelDownload(item.pkgName)
    }

    fun uninstall(item: ExtensionsBrowserListItem.Entry) {
        if (item.state == ExtensionsBrowserEntryState.INSTALLING) {
            return
        }

        if (item.extension.type == ExternalExtensionType.JAR || item.extension.type == ExternalExtensionType.CLOUDSTREAM) {
            launchJob(Dispatchers.IO) { uninstallFileBased(item) }
            return
        }

        val uninstallPkg = if (item.extension.type == ExternalExtensionType.IREADER && item.pkgName.startsWith("ireader-")) {
            item.pkgName.toInstalledIReaderPackageName()
        } else item.pkgName

        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent.ACTION_DELETE
        } else {
            @Suppress("DEPRECATION")
            Intent.ACTION_UNINSTALL_PACKAGE
        }
        launchJob(Dispatchers.IO) {
            val sourceNames = catalogRepository.sourceNamesForPackage(
                item.extension.type,
                item.pkgName,
                item.extension.type.normalizePackageNameForMatching(item.pkgName),
            )
            pendingUninstalls[uninstallPkg] = PendingUninstall(item.pkgName, sourceNames)
            onUninstallIntent.call(Intent(action, Uri.fromParts("package", uninstallPkg, null)))
        }
    }

    private class PendingUninstall(val packageName: String, val sourceNames: List<String>)

    private val pendingUninstalls = java.util.concurrent.ConcurrentHashMap<String, PendingUninstall>()

    /** Call when the system uninstaller returns: packages that are really gone get the usual removal cleanup. */
    fun onUninstallActivityResult() {
        launchJob(Dispatchers.IO) {
            val pm = appContext.packageManager
            val gone = pendingUninstalls.filterKeys { pkg ->
                try {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                    false
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    true
                }
            }
            if (gone.isEmpty()) return@launchJob
            gone.forEach { (pkg, info) ->
                pendingUninstalls.remove(pkg)
                packageRemovalCleanup.afterPackageRemoved(info.packageName, info.sourceNames)
            }
            mihonExtensionManager.loadExtensions()
            aniyomiExtensionManager.loadExtensions()
            ireaderExtensionManager.loadExtensions()
            tsundokuExtensionManager.loadExtensions()
        }
    }

    private suspend fun uninstallFileBased(item: ExtensionsBrowserListItem.Entry) {
        if (item.extension.type == ExternalExtensionType.JAR) {
            val pluginDir = java.io.File(appContext.filesDir, "plugins")
            val jarFile = java.io.File(pluginDir, "${item.pkgName}.jar")
            if (jarFile.exists() && !jarFile.delete()) {
                errorEvent.call(java.io.IOException(appContext.getString(R.string.extension_file_delete_failed, jarFile.name)))
                return
            }
            appContext.getSharedPreferences("jar_plugin_versions", android.content.Context.MODE_PRIVATE)
                .edit()
                .remove(item.pkgName)
                .remove("${item.pkgName}:repo")
                .remove("${item.pkgName}:repoName")
                .apply()
            val jarName = item.pkgName.removeSuffix(".jar")
            val jarSourceNames = org.skepsun.kototoro.core.extensions.GlobalExtensionManager.contentSources.value
                .filter { it.jarName.removeSuffix(".jar") == jarName }.map { it.name } +
                org.skepsun.kototoro.core.extensions.GlobalExtensionManager.mangaSources.value
                    .filter { it.jarName.removeSuffix(".jar") == jarName }.map { it.name }
            org.skepsun.kototoro.core.extensions.GlobalExtensionManager.initialize(appContext)
            packageRemovalCleanup.afterPackageRemoved(item.pkgName, jarSourceNames)
            return
        }
        val prefs = appContext.getSharedPreferences("cloudstream_plugin_versions", android.content.Context.MODE_PRIVATE)
        val archiveName = prefs.getString("${item.pkgName}:archive", null) ?: "${item.pkgName}.cs3"
        val pluginDir = java.io.File(java.io.File(appContext.filesDir, "cloudstream"), "plugins")
        val pluginFile = java.io.File(pluginDir, archiveName)
        if (pluginFile.exists() && !pluginFile.delete()) {
            errorEvent.call(java.io.IOException(appContext.getString(R.string.extension_file_delete_failed, pluginFile.name)))
            return
        }
        prefs.edit()
            .remove(item.pkgName)
            .remove("${item.pkgName}:name")
            .remove("${item.pkgName}:lang")
            .remove("${item.pkgName}:repo")
            .remove("${item.pkgName}:repoName")
            .remove("${item.pkgName}:archive")
            .remove("${item.pkgName}:icon")
            .apply()
        val csSourceNames = cloudstreamRuntimeManager.sources.value
            .filter { it.pluginPackageName == item.pkgName }.map { it.name }
        cloudstreamRuntimeManager.initialize()
        packageRemovalCleanup.afterPackageRemoved(item.pkgName, csSourceNames)
    }

    fun setSearchQuery(query: String?) {
        searchQuery.value = query?.trim().orEmpty()
    }

    fun setSelectedExtensionLanguages(languages: Set<String>) {
        settings.extensionLanguages = languages
    }

    fun toggleLanguageGroup(item: ExtensionsBrowserListItem.LanguageHeader) {
        val key = ExtensionsLanguageGroupKey(item.section, item.language)
        collapsedLanguageGroups.value = collapsedLanguageGroups.value.toMutableSet().apply {
            if (!add(key)) {
                remove(key)
            }
        }
    }

    fun onUpdateAllAction() {
        if (updateAllInProgress.value) {
            cancelUpdateAll()
        } else {
            startUpdateAll()
        }
    }

    fun getSourcesForPackage(pkgName: String): List<org.skepsun.kototoro.parsers.model.ContentSource> {
        return when (type) {
            ExternalExtensionType.MIHON -> mihonExtensionManager.getMihonMangaSources().filter { it.pkgName == pkgName }
            ExternalExtensionType.ANIYOMI -> aniyomiExtensionManager.getAniyomiAnimeSources().filter { it.pkgName == pkgName }
            ExternalExtensionType.IREADER -> {
                val lookupPkg = pkgName.toInstalledIReaderPackageName()
                ireaderExtensionManager.getIReaderMangaSources().filter { it.pkgName == lookupPkg || it.pkgName == pkgName }
            }
            ExternalExtensionType.JAR,
            ExternalExtensionType.CLOUDSTREAM,
            ExternalExtensionType.TSUNDOKU -> emptyList()
        }
    }

    private fun requestInstall(item: ExtensionsBrowserListItem.Entry, fromBatch: Boolean) {
        if (item.extension.pkgName in installService.downloadStates.value || item.extension.pkgName in installService.installing.value) {
            return
        }
        if (fromBatch) {
            batchUpdateState.beginInstall(item.pkgName)
        }
        launchLoadingJob(Dispatchers.IO) {
            try {
                when (val result = installService.install(item.extension, preferredInstallMode(item.extension))) {
                    is ExtensionInstallResult.RequiresInstaller -> {
                        val userAction = result.session.awaitUserAction()
                        if (userAction != null) {
                            if (fromBatch) {
                                batchUpdateState.markInstallerIntentDispatched()
                            }
                            onInstallIntent.call(userAction)
                        }
                        result.session.awaitCompletion()
                        clearInstalledAvailability(item.extension)
                        onMessage.call(appContext.getString(R.string.unified_sources_package_installed))
                        if (fromBatch) {
                            handleBatchNextAction(batchUpdateState.finishCurrentInstall())
                        }
                    }
                    ExtensionInstallResult.Completed -> {
                        reloadInstalledManager(item.extension)
                        clearInstalledAvailability(item.extension)
                        onMessage.call(appContext.getString(R.string.unified_sources_package_installed))
                        if (fromBatch) {
                            handleBatchNextAction(batchUpdateState.finishCurrentInstall())
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (!fromBatch) {
                    onMessage.call(appContext.getString(R.string.canceled))
                }
                if (fromBatch) {
                    handleBatchNextAction(batchUpdateState.finishCurrentInstall())
                }
            } catch (e: Throwable) {
                errorEvent.call(e)
                if (fromBatch) {
                    onMessage.call(appContext.getString(R.string.extension_update_failed, item.name))
                    handleBatchNextAction(batchUpdateState.finishCurrentInstall())
                }
            }
        }
    }

    /**
     * Same rule as the unified screen: a package that the system installed is updated through the system installer
     * (no second managed copy); everything else is a managed LOCAL_APK install.
     */
    private fun preferredInstallMode(extension: RepoAvailableExtension): ExtensionInstallMode {
        val installedName = extension.type.normalizePackageNameForMatching(extension.pkgName)
        val isSystemInstalled = try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(installedName, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
        return if (isSystemInstalled) ExtensionInstallMode.SYSTEM else ExtensionInstallMode.LOCAL_APK
    }

    private suspend fun clearInstalledAvailability(extension: RepoAvailableExtension) {
        val pkg = extension.pkgName
        catalogRepository.clearAvailabilityForPackage(extension.type, pkg, extension.type.normalizePackageNameForMatching(pkg))
    }

    private suspend fun reloadInstalledManager(extension: RepoAvailableExtension) {
        when (extension.type) {
            ExternalExtensionType.MIHON -> mihonExtensionManager.loadExtensions()
            ExternalExtensionType.ANIYOMI -> aniyomiExtensionManager.loadExtensions()
            ExternalExtensionType.IREADER -> ireaderExtensionManager.loadExtensions()
            ExternalExtensionType.TSUNDOKU -> tsundokuExtensionManager.loadExtensions()
            else -> Unit
        }
    }

    private fun startUpdateAll() {
        val updatePackages = currentUpdateEntries().map { it.pkgName }
        if (!batchUpdateState.start(updatePackages)) {
            onMessage.call(appContext.getString(R.string.no_extension_updates_available))
            return
        }
        handleBatchNextAction(batchUpdateState.nextAction())
    }

    private fun cancelUpdateAll() {
        if (!updateAllInProgress.value) {
            return
        }
        batchUpdateState.cancel(installService::cancelDownload)
        onMessage.call(appContext.getString(R.string.extension_update_all_cancelled))
    }

    private fun handleBatchNextAction(action: ExtensionBatchUpdateStateMachine.NextAction) {
        when (action) {
            ExtensionBatchUpdateStateMachine.NextAction.None -> Unit
            ExtensionBatchUpdateStateMachine.NextAction.Completed -> {
                onMessage.call(appContext.getString(R.string.extension_update_all_complete))
            }
            is ExtensionBatchUpdateStateMachine.NextAction.InstallNext -> {
                val item = currentUpdateEntries().firstOrNull { it.pkgName == action.packageName } ?: run {
                    handleBatchNextAction(batchUpdateState.nextAction())
                    return
                }
                requestInstall(item, fromBatch = true)
            }
        }
    }

    private fun currentUpdateEntries(): List<ExtensionsBrowserListItem.Entry> {
        return buildExtensionsBrowserItems(
            type = type,
            installed = browserInputs.value.installed,
            available = browserInputs.value.available,
            downloadStates = browserInputs.value.downloads,
            installing = browserInputs.value.installing,
            selectedExtensionLanguages = browserInputs.value.selectedLanguages,
            collapsedLanguageGroups = browserInputs.value.collapsedGroups,
            query = "",
            isTrustedPackage = ::isTrustedCached,
            isStrictLanguageFilter = browserInputs.value.isStrictLanguageFilter,
        ).filterIsInstance<ExtensionsBrowserListItem.Entry>()
            .filter { it.state == ExtensionsBrowserEntryState.UPDATE_AVAILABLE }
    }

    private data class BrowserInputs(
        val installed: List<InstalledExtensionEntry>,
        val available: List<RepoAvailableExtension>,
        val downloads: Map<String, org.skepsun.kototoro.extensions.install.ExtensionInstallDownloadState>,
        val installing: Set<String>,
        val selectedLanguages: Set<String>,
        val isStrictLanguageFilter: Boolean,
        val collapsedGroups: Set<ExtensionsLanguageGroupKey>,
    )
}
