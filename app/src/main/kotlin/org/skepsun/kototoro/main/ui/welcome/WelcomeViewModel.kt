package org.skepsun.kototoro.main.ui.welcome

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.kanade.tachiyomi.network.awaitSuccess
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.supervisorScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.lnreader.LNReaderPluginInfo
import org.skepsun.kototoro.core.lnreader.LNReaderRepository
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.ListToDetailsTransition
import org.skepsun.kototoro.core.prefs.SpaceSwitcherPosition
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.toList
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.extensions.install.ExtensionInstallDownloadState
import org.skepsun.kototoro.extensions.install.ExtensionInstallMode
import org.skepsun.kototoro.extensions.install.ExtensionInstallResult
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepo
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.JarExtensionMetadataProbe
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.extensions.repo.toInstalledPackageName
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.settings.sources.extensions.normalizeExtensionLanguageCode
import org.skepsun.kototoro.settings.sources.extensions.normalizeExtensionLanguageCodes
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepositories
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepository
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind
import org.skepsun.kototoro.tsundoku.TsundokuExtensionManager

/** Kinds whose packages (JAR/APK/Cloudstream) are installed from repos. */
private val INSTALLABLE_EXTERNAL_KINDS = listOf(
    UnifiedSourceKind.JAR,
    UnifiedSourceKind.CLOUDSTREAM,
    UnifiedSourceKind.MIHON,
    UnifiedSourceKind.ANIYOMI,
    UnifiedSourceKind.IREADER,
    UnifiedSourceKind.TSUNDOKU,
)

/** Kinds installed as APKs, where the user can pick sideload vs system install. */
private val APK_INSTALL_KINDS = setOf(
    UnifiedSourceKind.MIHON,
    UnifiedSourceKind.ANIYOMI,
    UnifiedSourceKind.IREADER,
    UnifiedSourceKind.TSUNDOKU,
)

/** JSON-based kinds whose "install" = importing the source list into the app. */
private val JSON_INSTALL_KINDS = listOf(
    UnifiedSourceKind.LEGADO,
    UnifiedSourceKind.TVBOX,
    UnifiedSourceKind.LNREADER,
)

/** Stable order the batch-install page presents kinds in. */
private val WIZARD_INSTALL_KIND_ORDER = INSTALLABLE_EXTERNAL_KINDS + JSON_INSTALL_KINDS

enum class WizardPackageState {
    QUEUED,
    DOWNLOADING,
    INSTALLING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class WizardInstallPhase {
    IDLE,
    INSTALLING,
    FINISHED,
}

enum class WizardSetupPhase {
    CONFIGURATION,
    CONFIGURING,
    BUILDING_PLAN,
    READY_TO_INSTALL,
    INSTALLING,
    FINISHED,
    SKIPPED,
}

/** What a single wizard install entry actually installs. */
sealed interface WizardInstallTarget {
    data class Extension(val extension: RepoAvailableExtension) : WizardInstallTarget
    data class JsonRepo(
        val kind: UnifiedSourceKind,
        val repoUrl: String,
        val sourceTitle: String,
    ) : WizardInstallTarget
    data class LnReaderPlugin(val plugin: LNReaderPluginInfo) : WizardInstallTarget
}

data class WizardInstallItem(
    val key: String,
    val kind: UnifiedSourceKind,
    val name: String,
    val isNsfw: Boolean,
    val state: WizardPackageState = WizardPackageState.QUEUED,
    val progressPercent: Int? = null,
    val errorMessage: String? = null,
    val target: WizardInstallTarget,
)

data class WizardInstallState(
    val phase: WizardInstallPhase = WizardInstallPhase.IDLE,
    val items: List<WizardInstallItem> = emptyList(),
    val completed: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
) {
    val total: Int get() = items.size
    val done: Int get() = completed + failed + cancelled
    val progressPercent: Int get() = if (total == 0) 0 else (done * 100) / total
}

/** Result of the repository-config step (page 2 hint after adding repos). */
data class ReposConfiguredInfo(
    val kinds: List<UnifiedSourceKind>,
)

private data class WizardCatalogSnapshot(
    val externalPackages: Map<UnifiedSourceKind, List<RepoAvailableExtension>>,
    val lnReaderPlugins: List<LNReaderPluginInfo>,
)

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: ContentSourcesRepository,
    private val settings: AppSettings,
    private val repoRepository: ExternalExtensionRepoRepository,
    private val jarMetadataProbe: JarExtensionMetadataProbe,
    private val installService: ExtensionInstallService,
    private val mihonExtensionManager: MihonExtensionManager,
    private val aniyomiExtensionManager: AniyomiExtensionManager,
    private val ireaderExtensionManager: IReaderExtensionManager,
    private val tsundokuExtensionManager: TsundokuExtensionManager,
    private val jsonSourceManager: JsonSourceManager,
    @BaseHttpClient private val okHttpClient: OkHttpClient,
    @LocalizedAppContext private val context: Context,
) : BaseViewModel() {

    private val supportedContentTypes = listOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO)

    private var updateJob: Job? = null
    private var planJob: Job? = null
    @Volatile
    private var currentInstallTask: Deferred<Unit>? = null
    private var planGeneration: Long = 0L
    private val installCancelFlag = AtomicBoolean(false)
    private var installSelectionInitialized = false
    private val handledInstallKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val discoveredSourceLocales = MutableStateFlow<Set<Locale>>(emptySet())
    private var wizardExternalRepositories: Map<UnifiedSourceKind, List<ExternalExtensionRepo>> = emptyMap()
    private var wizardJsonRepositories: Map<UnifiedSourceKind, List<UnifiedRecommendedRepository>> = emptyMap()

    private val lnReaderRepository = LNReaderRepository(okHttpClient, jsonSourceManager)

    private val _isInitializingPlugins = MutableStateFlow(false)
    val isInitializingPlugins = _isInitializingPlugins.asStateFlow()

    private val _setupPhase = MutableStateFlow(WizardSetupPhase.CONFIGURATION)
    val setupPhase = _setupPhase.asStateFlow()

    private val _reposConfiguredEvent = MutableStateFlow<ReposConfiguredInfo?>(null)
    val reposConfiguredEvent = _reposConfiguredEvent.asStateFlow()

    private val _systemInstallMode = MutableStateFlow(savedStateHandle[KEY_SYSTEM_INSTALL_MODE] ?: false)
    val systemInstallMode = _systemInstallMode.asStateFlow()

    private val _hasApkRepos = MutableStateFlow(false)
    val hasApkRepos = _hasApkRepos.asStateFlow()

    private val _includeNsfw = MutableStateFlow(savedStateHandle[KEY_INCLUDE_NSFW] ?: false)
    val includeNsfw = _includeNsfw.asStateFlow()

    private val _configuredInstallKinds = MutableStateFlow<List<UnifiedSourceKind>>(emptyList())
    val configuredInstallKinds = _configuredInstallKinds.asStateFlow()

    private val _selectedInstallKinds = MutableStateFlow<Set<UnifiedSourceKind>>(emptySet())
    val selectedInstallKinds = _selectedInstallKinds.asStateFlow()

    private val _installSkipped = MutableStateFlow(false)
    val installSkipped = _installSkipped.asStateFlow()

    private val _installPlan = MutableStateFlow<List<WizardInstallItem>>(emptyList())
    val installPlan = _installPlan.asStateFlow()

    private val _installState = MutableStateFlow(WizardInstallState())
    val installState = _installState.asStateFlow()

    private val _isInstallingPackages = MutableStateFlow(false)
    val isInstallingPackages = _isInstallingPackages.asStateFlow()

    private val _installFinishedEvent = MutableStateFlow<Boolean?>(null)
    val installFinishedEvent = _installFinishedEvent.asStateFlow()

    /** SYSTEM-mode install intents the UI must launch via the host activity. */
    private val _systemInstallRequests = MutableSharedFlow<Intent>(extraBufferCapacity = 8)
    val systemInstallRequests = _systemInstallRequests.asSharedFlow()

    // After process death, return at most to repository selection because runtime catalog/install
    // jobs cannot be reconstructed safely from a Bundle. Configuration changes keep this ViewModel.
    private val _currentPage = MutableStateFlow((savedStateHandle[KEY_CURRENT_PAGE] ?: 0).coerceIn(0, 3))
    val currentPage = _currentPage.asStateFlow()

    private val _selectedRepositoryKeys = MutableStateFlow(
        savedStateHandle.get<ArrayList<String>>(KEY_SELECTED_REPOSITORIES)?.toSet()
            ?: WelcomeDefaults.defaultRepositories(UnifiedRecommendedRepositories.all)
                .mapTo(LinkedHashSet(), ::repoKeyForWelcome),
    )
    val selectedRepositoryKeys = _selectedRepositoryKeys.asStateFlow()

    private val _spacesEnabled = MutableStateFlow(settings.isEntitySpaceEnabled)
    val spacesEnabled = _spacesEnabled.asStateFlow()
    private val _interfaceStyle = MutableStateFlow(settings.interfaceStyle)
    val interfaceStyle = _interfaceStyle.asStateFlow()
    private val _listToDetailsTransition = MutableStateFlow(settings.listToDetailsTransition)
    val listToDetailsTransition = _listToDetailsTransition.asStateFlow()
    private val _panoramaAnimationEnabled = MutableStateFlow(settings.isPanoramaCoverAnimationEnabled)
    val panoramaAnimationEnabled = _panoramaAnimationEnabled.asStateFlow()
    private val _spaceSwitcherPosition = MutableStateFlow(settings.spaceSwitcherPosition)
    val spaceSwitcherPosition = _spaceSwitcherPosition.asStateFlow()

    val locales = MutableStateFlow(
        FilterProperty<Locale>(
            availableItems = listOf(Locale.ROOT),
            selectedItems = setOf(Locale.ROOT),
            isLoading = true,
            error = null,
        ),
    )

    val types = MutableStateFlow(
        FilterProperty(
            availableItems = supportedContentTypes,
            selectedItems = supportedContentTypes.toSet(),
            isLoading = true,
            error = null,
        ),
    )

    init {
        settings.hasSeenPluginWelcome = true
        refreshState()
        launchJob(Dispatchers.Default) {
            GlobalExtensionManager.contentSources.collect {
                android.util.Log.d("KototoroInit", "contentSources collected a new plugin map! Triggering reactive chips refresh!")
                refreshState()
            }
        }
        launchJob(Dispatchers.Default) {
            GlobalExtensionManager.mangaSources.collect {
                android.util.Log.d("KototoroInit", "mangaSources collected a new plugin map! Triggering reactive chips refresh!")
                refreshState()
            }
        }
    }

    fun refreshState() {
        updateJob?.cancel()
        updateJob = launchJob(Dispatchers.Default) {
            val allSourcesSnapshot = repository.queryAllSources(includeDisabledSources = true)
            val localesGroupsSnapshot = allSourcesSnapshot.groupBy { it.getLocale() ?: Locale.ROOT }
            discoveredSourceLocales.value = localesGroupsSnapshot.keys

            types.value = types.value.copy(
                availableItems = supportedContentTypes,
                isLoading = false,
            )
            val previouslySelectedLanguages = settings.contentLanguages
            val availableLocales = WelcomeDefaults.availableLocales(
                repositoryLanguages = previouslySelectedLanguages,
                discovered = localesGroupsSnapshot.keys,
            )
            val selectedLocales = if (previouslySelectedLanguages.isNotEmpty()) {
                availableLocales.filterTo(LinkedHashSet()) { it.language in previouslySelectedLanguages }
            } else {
                val languagesMap = availableLocales.associateBy { locale -> locale.language }
                val set = HashSet<Locale>(2)
                ConfigurationCompat.getLocales(context.resources.configuration).toList()
                    .firstNotNullOfOrNull { lc -> languagesMap[lc.language] }
                    ?.let { set += it }
                set += Locale.ROOT
                set
            }
            locales.value = locales.value.copy(
                availableItems = availableLocales,
                selectedItems = selectedLocales,
                isLoading = false,
            )

            val enabledSources = repository.getEnabledSources().map { it.name }.toSet()
            val selectedTypes = allSourcesSnapshot
                .filter { it.name in enabledSources }
                .map { source ->
                    when (source.getContentType()) {
                        ContentType.HENTAI_MANGA -> ContentType.MANGA
                        ContentType.HENTAI_NOVEL -> ContentType.NOVEL
                        ContentType.HENTAI_VIDEO -> ContentType.VIDEO
                        else -> source.getContentType()
                    }
                }
                .toSet()
            if (selectedTypes.isNotEmpty()) {
                types.value = types.value.copy(selectedItems = selectedTypes)
            }

            repository.clearNewSourcesBadge()
            commit()
        }
    }

    fun initializePlugins(mirrorOriginalPosition: Int, repos: List<UnifiedRecommendedRepository>) {
        android.util.Log.d("KototoroInit", "WelcomeViewModel initializePlugins triggered! Args: mirror=$mirrorOriginalPosition, repos=$repos")
        launchJob(Dispatchers.IO) {
            _isInitializingPlugins.value = true
            _setupPhase.value = WizardSetupPhase.CONFIGURING
            wizardExternalRepositories = emptyMap()
            wizardJsonRepositories = emptyMap()
            android.util.Log.d("KototoroInit", "Coroutine launched, isInitializing=true")
            try {
                val newMirror = AppSettings.GitHubMirror.entries.getOrElse(mirrorOriginalPosition) { AppSettings.GitHubMirror.NATIVE }
                settings.gitHubMirror = newMirror
                android.util.Log.d("KototoroInit", "Proxy mirror set to $newMirror")

                val configuredKinds = LinkedHashSet<UnifiedSourceKind>()
                val selectedExternalRepositories = LinkedHashMap<UnifiedSourceKind, MutableList<ExternalExtensionRepo>>()
                val selectedJsonRepositories = LinkedHashMap<UnifiedSourceKind, MutableList<UnifiedRecommendedRepository>>()

                for (repo in repos.distinctBy { repoKeyForWelcome(it) }) {
                    android.util.Log.d("KototoroInit", "Preparing Repo type=${repo.kind} url=${repo.url}")
                    when (repo.kind) {
                        UnifiedSourceKind.LEGADO -> {
                            if (repo.url !in settings.legadoRepoUrls) {
                                settings.legadoRepoUrls = settings.legadoRepoUrls + repo.url
                            }
                            selectedJsonRepositories.getOrPut(repo.kind, ::mutableListOf) += repo
                            configuredKinds += repo.kind
                        }
                        UnifiedSourceKind.TVBOX -> {
                            if (repo.url !in settings.tvBoxRepoUrls) {
                                settings.tvBoxRepoUrls = settings.tvBoxRepoUrls + repo.url
                            }
                            selectedJsonRepositories.getOrPut(repo.kind, ::mutableListOf) += repo
                            configuredKinds += repo.kind
                        }
                        UnifiedSourceKind.LNREADER -> {
                            if (repo.url !in settings.lnReaderRepoUrls) {
                                settings.lnReaderRepoUrls = settings.lnReaderRepoUrls + repo.url
                            }
                            selectedJsonRepositories.getOrPut(repo.kind, ::mutableListOf) += repo
                            configuredKinds += repo.kind
                        }
                        else -> {
                            val type = repo.kind.toExternalType() ?: continue
                            val configuredRepo = when (val prep = repoRepository.prepareAddRepo(type, repo.url)) {
                                is ExternalExtensionRepoRepository.PrepareAddRepoResult.Ready -> {
                                    when (val result = repoRepository.confirmAddRepo(prep.repo)) {
                                        is ExternalExtensionRepoRepository.AddRepoResult.Success -> result.repo
                                        is ExternalExtensionRepoRepository.AddRepoResult.DuplicateFingerprint -> result.existingRepo
                                        ExternalExtensionRepoRepository.AddRepoResult.RepoAlreadyExists -> prep.repo
                                        is ExternalExtensionRepoRepository.AddRepoResult.FetchFailed,
                                        ExternalExtensionRepoRepository.AddRepoResult.InvalidUrl,
                                        -> null
                                    }
                                }
                                is ExternalExtensionRepoRepository.PrepareAddRepoResult.DuplicateFingerprint -> prep.existingRepo
                                is ExternalExtensionRepoRepository.PrepareAddRepoResult.RepoAlreadyExists -> prep.existingRepo
                                is ExternalExtensionRepoRepository.PrepareAddRepoResult.FetchFailed,
                                ExternalExtensionRepoRepository.PrepareAddRepoResult.InvalidUrl,
                                -> null
                            }
                            if (configuredRepo != null) {
                                selectedExternalRepositories.getOrPut(repo.kind, ::mutableListOf) += configuredRepo
                                configuredKinds += repo.kind
                                android.util.Log.d(
                                    "KototoroInit",
                                    "Repo selected for wizard: ${configuredRepo.displayName}",
                                )
                            }
                        }
                    }
                }

                wizardExternalRepositories = selectedExternalRepositories.mapValues { (_, repositories) ->
                    repositories.distinctBy { repository -> repository.type to repository.baseUrl }
                }
                wizardJsonRepositories = selectedJsonRepositories.mapValues { (_, repositories) ->
                    repositories.distinctBy(::repoKeyForWelcome)
                }
                installSelectionInitialized = false

                // Refresh only the repositories selected in this wizard run. Existing unselected
                // repositories remain configured globally but must not leak into the batch plan.
                wizardExternalRepositories.values.flatten().forEach { repository ->
                    repoRepository.refresh(repository)
                }
                GlobalExtensionManager.initialize(context)

                // Publish the configured scope and its plan atomically before opening the next page.
                _setupPhase.value = WizardSetupPhase.BUILDING_PLAN
                recomputeInstallPlan()
                android.util.Log.d("KototoroInit", "All repository configuration work finished.")

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (configuredKinds.isEmpty()) {
                        _setupPhase.value = WizardSetupPhase.CONFIGURATION
                        android.widget.Toast.makeText(
                            context,
                            context.getString(org.skepsun.kototoro.R.string.welcome_plugins_configured_none),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        _setupPhase.value = WizardSetupPhase.READY_TO_INSTALL
                        _reposConfiguredEvent.value = ReposConfiguredInfo(configuredKinds.toList())
                    }
                }
            } catch (e: Exception) {
                _setupPhase.value = WizardSetupPhase.CONFIGURATION
                android.util.Log.e("KototoroInit", "CRITICAL ERROR inside initializePlugins: ${e.message}", e)
                e.printStackTrace()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, context.getString(org.skepsun.kototoro.R.string.welcome_jar_install_failed, e.message.orEmpty()), android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                android.util.Log.d("KototoroInit", "Restoring UI interactive state")
                _isInitializingPlugins.value = false
            }
        }
    }

    fun consumeReposConfiguredEvent() {
        _reposConfiguredEvent.value = null
    }

    fun consumeInstallFinishedEvent() {
        _installFinishedEvent.value = null
    }

    fun setSystemInstallMode(enabled: Boolean) {
        _systemInstallMode.value = enabled
        savedStateHandle[KEY_SYSTEM_INSTALL_MODE] = enabled
    }

    fun setIncludeNsfw(enabled: Boolean) {
        _includeNsfw.value = enabled
        savedStateHandle[KEY_INCLUDE_NSFW] = enabled
        refreshInstallPlan()
    }

    fun setCurrentPage(page: Int) {
        val normalized = page.coerceIn(0, 5)
        _currentPage.value = normalized
        savedStateHandle[KEY_CURRENT_PAGE] = normalized
    }

    fun toggleRepository(repository: UnifiedRecommendedRepository) {
        val key = repoKeyForWelcome(repository)
        updateSelectedRepositoryKeys { selected ->
            if (key in selected) selected - key else selected + key
        }
    }

    fun selectRepositories(repositories: Collection<UnifiedRecommendedRepository>) {
        val keys = repositories.mapTo(LinkedHashSet(), ::repoKeyForWelcome)
        updateSelectedRepositoryKeys { selected -> selected + keys }
    }

    fun clearRepositories(kind: UnifiedSourceKind) {
        val prefix = "${kind.name}:"
        updateSelectedRepositoryKeys { selected -> selected.filterNotTo(LinkedHashSet()) { it.startsWith(prefix) } }
    }

    private fun updateSelectedRepositoryKeys(transform: (Set<String>) -> Set<String>) {
        val updated = transform(_selectedRepositoryKeys.value)
        _selectedRepositoryKeys.value = updated
        savedStateHandle[KEY_SELECTED_REPOSITORIES] = ArrayList(updated)
    }

    fun toggleInstallKind(kind: UnifiedSourceKind, checked: Boolean) {
        installSelectionInitialized = true
        val snapshot = _selectedInstallKinds.value
        _selectedInstallKinds.value = if (checked) {
            snapshot + kind
        } else {
            snapshot - kind
        }
        refreshInstallPlan()
    }

    fun setInstallSkipped(skipped: Boolean) {
        _installSkipped.value = skipped
        if (skipped) {
            _setupPhase.value = WizardSetupPhase.SKIPPED
        }
    }

    /**
     * Recomputes the batch-install plan from the current filters (kinds,
     * content types, languages, NSFW). Plan recomputes are serialized: the
     * previous in-flight recompute is cancelled so a stale (older filter)
     * result can never overwrite the latest one and the displayed install
     * count always matches the chip state the user just picked.
     */
    fun refreshInstallPlan() {
        if (_setupPhase.value == WizardSetupPhase.INSTALLING || _setupPhase.value == WizardSetupPhase.FINISHED) {
            return
        }
        planJob?.cancel()
        val generation = ++planGeneration
        planJob = launchJob(Dispatchers.IO) {
            _setupPhase.value = WizardSetupPhase.BUILDING_PLAN
            try {
                recomputeInstallPlan()
                if (_installPlan.value.isEmpty() && _installState.value.phase == WizardInstallPhase.IDLE) {
                    _installState.value = WizardInstallState()
                }
            } finally {
                if (generation == planGeneration && _setupPhase.value == WizardSetupPhase.BUILDING_PLAN) {
                    _setupPhase.value = WizardSetupPhase.READY_TO_INSTALL
                }
            }
        }
    }

    fun installMatchingPackages() {
        if (_isInstallingPackages.value) {
            return
        }
        val plan = _installPlan.value
        if (plan.isEmpty()) {
            return
        }
        val mode = if (_systemInstallMode.value) {
            ExtensionInstallMode.SYSTEM
        } else {
            ExtensionInstallMode.LOCAL_APK
        }
        installCancelFlag.set(false)
        _installSkipped.value = false
        startInstall(plan, mode)
    }

    fun retryFailedPackages() {
        if (_isInstallingPackages.value) return
        val retryItems = _installState.value.items.map { item ->
            if (item.state == WizardPackageState.FAILED) {
                item.copy(
                    state = WizardPackageState.QUEUED,
                    progressPercent = null,
                    errorMessage = null,
                )
            } else {
                item
            }
        }
        if (retryItems.none { item -> item.state == WizardPackageState.QUEUED }) return
        installCancelFlag.set(false)
        _installSkipped.value = false
        val mode = if (_systemInstallMode.value) ExtensionInstallMode.SYSTEM else ExtensionInstallMode.LOCAL_APK
        startInstall(retryItems, mode)
    }

    private fun startInstall(plan: List<WizardInstallItem>, mode: ExtensionInstallMode) {
        if (_isInstallingPackages.value) return
        _isInstallingPackages.value = true
        launchJob(Dispatchers.IO) {
            _setupPhase.value = WizardSetupPhase.INSTALLING
            publishInstallItems(WizardInstallPhase.INSTALLING, plan)
            val downloadCollector = launchJob {
                installService.downloadStates.collect(::applyDownloadStates)
            }
            try {
                for (item in plan) {
                    if (item.state != WizardPackageState.QUEUED) continue
                    if (installCancelFlag.get()) {
                        markRemainingCancelled()
                        break
                    }
                    markState(item.key, WizardPackageState.DOWNLOADING)
                    try {
                        supervisorScope {
                            currentInstallTask = async {
                                when (val target = item.target) {
                                    is WizardInstallTarget.Extension -> {
                                        installExtension(target.extension, mode, item.key)
                                    }
                                    is WizardInstallTarget.JsonRepo -> {
                                        importJsonRepo(target)
                                        markState(item.key, WizardPackageState.COMPLETED)
                                        handledInstallKeys += item.key
                                    }
                                    is WizardInstallTarget.LnReaderPlugin -> {
                                        lnReaderRepository.installPlugin(target.plugin).getOrThrow()
                                        markState(item.key, WizardPackageState.COMPLETED)
                                        handledInstallKeys += item.key
                                    }
                                }
                            }
                            currentInstallTask?.await()
                        }
                    } catch (e: CancellationException) {
                        if (installCancelFlag.get()) {
                            markState(item.key, WizardPackageState.CANCELLED)
                            markRemainingCancelled()
                            break
                        }
                        throw e
                    } catch (e: Throwable) {
                        android.util.Log.e("KototoroInit", "install ${item.name} failed: ${e.message}", e)
                        markState(
                            key = item.key,
                            newState = WizardPackageState.FAILED,
                            errorMessage = e.getDisplayMessage(context.resources),
                        )
                    } finally {
                        currentInstallTask = null
                    }
                }
            } finally {
                downloadCollector.cancel()
                _isInstallingPackages.value = false
            }

            try {
                reloadInstalledManagers()
                // Newly installed sources must immediately inherit the language/content choices made
                // before installation. A selected language with no matching source simply enables none.
                commit()
                refreshState()
                recomputeInstallPlan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("KototoroInit", "post-install refresh failed: ${e.message}", e)
            } finally {
                _installState.value = _installState.value.copy(phase = WizardInstallPhase.FINISHED)
                _setupPhase.value = WizardSetupPhase.FINISHED
                _installFinishedEvent.value = true
            }
        }
    }

    fun cancelInstall() {
        installCancelFlag.set(true)
        val currentTarget = _installState.value.items.firstOrNull { item ->
            item.state == WizardPackageState.DOWNLOADING || item.state == WizardPackageState.INSTALLING
        }?.target
        if (currentTarget is WizardInstallTarget.Extension) {
            installService.cancelDownload(currentTarget.extension.pkgName)
        }
        currentInstallTask?.cancel()
    }

    private suspend fun installExtension(
        extension: RepoAvailableExtension,
        mode: ExtensionInstallMode,
        itemKey: String,
    ) {
        val type = extension.type
        if (isAlreadyInstalled(type, extension)) {
            markState(itemKey, WizardPackageState.CANCELLED)
            handledInstallKeys += itemKey
            return
        }
        when (val result = installService.install(extension, mode)) {
            ExtensionInstallResult.Completed -> {
                markState(itemKey, WizardPackageState.COMPLETED)
                handledInstallKeys += itemKey
            }
            is ExtensionInstallResult.RequiresInstaller -> {
                markState(itemKey, WizardPackageState.INSTALLING)
                val action = result.session.awaitUserAction()
                if (action != null) {
                    _systemInstallRequests.tryEmit(action)
                }
                result.session.awaitCompletion()
                markState(itemKey, WizardPackageState.COMPLETED)
                handledInstallKeys += itemKey
            }
        }
    }

    private suspend fun importJsonRepo(target: WizardInstallTarget.JsonRepo) {
        val content = fetchRemoteText(target.repoUrl)
        val result = when (target.kind) {
            UnifiedSourceKind.LEGADO -> jsonSourceManager.importLegadoJson(
                jsonContent = content,
                sourceLocator = target.repoUrl,
                sourceTitle = target.sourceTitle,
                enabled = true,
            )
            UnifiedSourceKind.TVBOX -> jsonSourceManager.importTvBoxJson(
                jsonContent = content,
                sourceLocator = target.repoUrl,
                sourceTitle = target.sourceTitle,
                enabled = true,
            )
            else -> error("Unsupported JSON kind ${target.kind}")
        }
        result.getOrThrow()
    }

    private suspend fun fetchRemoteText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).awaitSuccess().use { response ->
            response.body.string()
        }
    }

    private suspend fun recomputeInstallPlan() {
        val configured = WIZARD_INSTALL_KIND_ORDER.filter { kind ->
            wizardExternalRepositories[kind].orEmpty().isNotEmpty() ||
                wizardJsonRepositories[kind].orEmpty().isNotEmpty()
        }
        _configuredInstallKinds.value = configured
        _hasApkRepos.value = configured.any { it in APK_INSTALL_KINDS }

        if (!installSelectionInitialized) {
            _selectedInstallKinds.value = configured.toSet()
            installSelectionInitialized = true
        } else {
            val configuredSet = configured.toSet()
            val kept = _selectedInstallKinds.value intersect configuredSet
            _selectedInstallKinds.value = kept
        }

        val catalog = loadCatalogSnapshot(configured)
        updateAvailableLocales(catalog)
        val plan = buildInstallPlan(catalog)
        // Never publish a plan computed by a stale (already cancelled) recompute.
        currentCoroutineContext().ensureActive()
        _installPlan.value = plan
    }

    private suspend fun loadCatalogSnapshot(configured: List<UnifiedSourceKind>): WizardCatalogSnapshot {
        val externalPackages = INSTALLABLE_EXTERNAL_KINDS
            .filter { kind -> kind in configured }
            .associateWith { kind ->
                val repositories = wizardExternalRepositories[kind].orEmpty()
                val packages = try {
                    repoRepository.getCatalogExtensions(repositories)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    emptyList()
                }
                if (kind == UnifiedSourceKind.JAR) {
                    packages.map { extension -> jarMetadataProbe.resolve(extension) }
                } else {
                    packages
                }
            }
        val lnReaderPlugins = if (UnifiedSourceKind.LNREADER in configured) {
            wizardJsonRepositories[UnifiedSourceKind.LNREADER].orEmpty().flatMap { repository ->
                val url = repository.url
                try {
                    lnReaderRepository.fetchPluginIndex(url).getOrDefault(emptyList())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        } else {
            emptyList()
        }
        return WizardCatalogSnapshot(externalPackages, lnReaderPlugins)
    }

    private fun updateAvailableLocales(catalog: WizardCatalogSnapshot) {
        val repositoryLanguages = buildSet {
            catalog.externalPackages.values.asSequence()
                .flatten()
                .flatMap { extension -> extension.normalizeExtensionLanguageCodes().asSequence() }
                .forEach(::add)
            catalog.lnReaderPlugins.asSequence()
                .map { plugin -> plugin.lang.normalizeExtensionLanguageCode() }
                .filter(String::isNotBlank)
                .forEach(::add)
            locales.value.selectedItems.mapTo(this) { locale -> locale.language }
            addAll(settings.contentLanguages)
        }
        val snapshot = locales.value
        locales.value = snapshot.copy(
            availableItems = WelcomeDefaults.availableLocales(
                repositoryLanguages = repositoryLanguages,
                discovered = discoveredSourceLocales.value,
            ),
            isLoading = false,
        )
    }

    private suspend fun buildInstallPlan(catalog: WizardCatalogSnapshot): List<WizardInstallItem> {
        val languageSelection = installLanguageSelection()
        val selected = _selectedInstallKinds.value
        val includeNsfw = _includeNsfw.value
        val selectedTypes = installTypes()
        val plan = mutableListOf<WizardInstallItem>()

        for (kind in WIZARD_INSTALL_KIND_ORDER) {
            currentCoroutineContext().ensureActive()
            if (kind !in selected) {
                continue
            }
            if (WelcomeInstallFilter.excludesKind(kind, selectedTypes)) {
                // No selected content type matches this ecosystem — filter it out.
                continue
            }
            when (kind) {
                UnifiedSourceKind.LEGADO,
                UnifiedSourceKind.TVBOX -> {
                    val urls = wizardJsonRepositories[kind].orEmpty().mapTo(LinkedHashSet()) { repository ->
                        repository.url
                    }
                    for (url in urls) {
                        val key = jsonRepoKey(kind, url)
                        if (key in handledInstallKeys) {
                            continue
                        }
                        plan += WizardInstallItem(
                            key = key,
                            kind = kind,
                            name = titleForJsonRepo(kind, url),
                            isNsfw = false,
                            target = WizardInstallTarget.JsonRepo(
                                kind = kind,
                                repoUrl = url,
                                sourceTitle = titleForJsonRepo(kind, url),
                            ),
                        )
                    }
                }
                UnifiedSourceKind.LNREADER -> {
                    for (plugin in catalog.lnReaderPlugins) {
                        val lang = plugin.lang.normalizeExtensionLanguageCode()
                        if (!WelcomeInstallFilter.matchesLanguages(
                                packageLanguages = setOf(lang).filterTo(LinkedHashSet(), String::isNotBlank),
                                packageIncludesUniversalLanguage = lang.isBlank(),
                                isPackageMetadataKnown = plugin.lang.isNotBlank(),
                                selection = languageSelection,
                            )
                        ) {
                            continue
                        }
                        val key = "LNREADER:${plugin.id}"
                        if (key in handledInstallKeys) {
                            continue
                        }
                        plan += WizardInstallItem(
                            key = key,
                            kind = UnifiedSourceKind.LNREADER,
                            name = plugin.name,
                            isNsfw = false,
                            target = WizardInstallTarget.LnReaderPlugin(plugin),
                        )
                    }
                }
                else -> {
                    val type = kind.toExternalType() ?: continue
                    val extensions = catalog.externalPackages[kind].orEmpty()
                    for (extension in extensions) {
                        if (!extension.isCompatible) {
                            continue
                        }
                        if (extension.isNsfw && !includeNsfw) {
                            continue
                        }
                        if (!WelcomeInstallFilter.matchesLanguages(
                                packageLanguages = extension.normalizeExtensionLanguageCodes(),
                                packageIncludesUniversalLanguage = extension.includesUniversalLanguage,
                                isPackageMetadataKnown = extension.isLanguageMetadataKnown,
                                selection = languageSelection,
                            )
                        ) {
                            continue
                        }
                        if (isAlreadyInstalled(type, extension)) {
                            continue
                        }
                        val key = "${kind.name}:${extension.pkgName}"
                        if (key in handledInstallKeys) {
                            continue
                        }
                        plan += WizardInstallItem(
                            key = key,
                            kind = kind,
                            name = extension.name,
                            isNsfw = extension.isNsfw,
                            target = WizardInstallTarget.Extension(extension),
                        )
                    }
                }
            }
        }
        return plan
    }

    private fun installLanguageSelection(): WelcomeInstallFilter.LanguageSelection {
        val selectedLocales = locales.value.selectedItems
        return WelcomeInstallFilter.LanguageSelection(
            languageCodes = selectedLocales.asSequence()
                .filter { locale -> locale != Locale.ROOT }
                .mapTo(LinkedHashSet()) { locale -> locale.language.lowercase(Locale.ROOT) },
            includesUniversalLanguage = Locale.ROOT in selectedLocales,
        )
    }

    /**
     * Content types selected by the user, expanded with their adult variants.
     * An empty selection means no content family is installed or enabled.
     */
    private fun installTypes(): Set<ContentType> =
        WelcomeInstallFilter.expandTypes(types.value.selectedItems)

    private fun isAlreadyInstalled(type: ExternalExtensionType, extension: RepoAvailableExtension): Boolean {
        return when (type) {
            ExternalExtensionType.JAR -> {
                jarVersionsPrefs().getLong(extension.pkgName, -1L) >= extension.versionCode
            }
            ExternalExtensionType.CLOUDSTREAM -> {
                cloudstreamVersionsPrefs().getLong(extension.pkgName, -1L) >= extension.versionCode
            }
            ExternalExtensionType.MIHON,
            ExternalExtensionType.ANIYOMI,
            ExternalExtensionType.IREADER,
            ExternalExtensionType.TSUNDOKU -> {
                val installedPackage = type.toInstalledPackageName(extension.pkgName)
                val systemInstalled = installedVersionCode(installedPackage)
                    ?.let { it >= extension.versionCode }
                    ?: false
                val localManaged = LocalApkExtensionSupport.getLocalArchivePackageInfoOrNull(
                    context = context,
                    pkgManager = context.packageManager,
                    ecosystem = type.toLocalApkEcosystemName(),
                    packageName = extension.pkgName,
                )?.let { PackageInfoCompat.getLongVersionCode(it) >= extension.versionCode }
                    ?: false
                systemInstalled || localManaged
            }
        }
    }

    private fun installedVersionCode(packageName: String): Long? {
        return runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(packageName, 0))
        }.getOrNull()
    }

    private fun jarVersionsPrefs() =
        context.getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE)

    private fun cloudstreamVersionsPrefs() =
        context.getSharedPreferences("cloudstream_plugin_versions", Context.MODE_PRIVATE)

    private fun applyDownloadStates(states: Map<String, ExtensionInstallDownloadState>) {
        if (states.isEmpty()) {
            return
        }
        _installState.update { state ->
            if (state.items.isEmpty()) {
                return@update state
            }
            state.copy(
                items = state.items.map { item ->
                    val target = item.target as? WizardInstallTarget.Extension ?: return@map item
                    val download = states[target.extension.pkgName]
                    if (download != null && item.state == WizardPackageState.DOWNLOADING) {
                        item.copy(progressPercent = download.progressPercent)
                    } else {
                        item
                    }
                },
            )
        }
    }

    private fun markState(
        key: String,
        newState: WizardPackageState,
        errorMessage: String? = null,
    ) {
        _installState.update { state ->
            val items = state.items.map { item ->
                if (item.key != key) {
                    item
                } else {
                    item.copy(
                        state = newState,
                        progressPercent = if (newState == WizardPackageState.COMPLETED) 100 else item.progressPercent,
                        errorMessage = errorMessage,
                    )
                }
            }
            state.copy(
                items = items,
                completed = items.count { it.state == WizardPackageState.COMPLETED },
                failed = items.count { it.state == WizardPackageState.FAILED },
                cancelled = items.count { it.state == WizardPackageState.CANCELLED },
            )
        }
    }

    private fun publishInstallItems(phase: WizardInstallPhase, items: List<WizardInstallItem>) {
        _installState.value = WizardInstallState(
            phase = phase,
            items = items,
            completed = items.count { item -> item.state == WizardPackageState.COMPLETED },
            failed = items.count { item -> item.state == WizardPackageState.FAILED },
            cancelled = items.count { item -> item.state == WizardPackageState.CANCELLED },
        )
    }

    private fun markRemainingCancelled() {
        _installState.update { state ->
            val items = state.items.map { item ->
                if (
                    item.state == WizardPackageState.QUEUED ||
                    item.state == WizardPackageState.DOWNLOADING ||
                    item.state == WizardPackageState.INSTALLING
                ) {
                    item.copy(state = WizardPackageState.CANCELLED)
                } else {
                    item
                }
            }
            state.copy(items = items, cancelled = items.count { it.state == WizardPackageState.CANCELLED })
        }
    }

    private suspend fun reloadInstalledManagers() {
        mihonExtensionManager.loadExtensions()
        aniyomiExtensionManager.loadExtensions()
        ireaderExtensionManager.loadExtensions()
        tsundokuExtensionManager.loadExtensions()
        // JAR and Cloudstream runtimes are (re)initialized inside ExtensionInstallService.install().
        GlobalExtensionManager.initialize(context)
    }

    fun setLocaleChecked(locale: Locale, isChecked: Boolean) {
        val snapshot = locales.value
        locales.value = snapshot.copy(
            selectedItems = if (isChecked) {
                snapshot.selectedItems + locale
            } else {
                snapshot.selectedItems - locale
            },
        )
        val prevJob = updateJob
        updateJob = launchJob(Dispatchers.Default) {
            prevJob?.join()
            commit()
        }
        refreshInstallPlan()
    }

    fun setTypeChecked(type: ContentType, isChecked: Boolean) {
        val snapshot = types.value
        types.value = snapshot.copy(
            selectedItems = if (isChecked) {
                snapshot.selectedItems + type
            } else {
                snapshot.selectedItems - type
            },
        )
        val prevJob = updateJob
        updateJob = launchJob(Dispatchers.Default) {
            prevJob?.join()
            commit()
        }
        refreshInstallPlan()
    }

    fun setSpacesEnabled(enabled: Boolean) {
        _spacesEnabled.value = enabled
        settings.isEntitySpaceEnabled = enabled
        settings.isSpaceSwitcherEnabled = enabled
    }

    fun setInterfaceStyle(value: InterfaceStyle) {
        _interfaceStyle.value = value
        settings.interfaceStyle = value
    }

    fun setListToDetailsTransition(value: ListToDetailsTransition) {
        _listToDetailsTransition.value = value
        settings.listToDetailsTransition = value
    }

    fun setPanoramaAnimationEnabled(enabled: Boolean) {
        _panoramaAnimationEnabled.value = enabled
        settings.isPanoramaCoverAnimationEnabled = enabled
    }

    fun setSpaceSwitcherPosition(position: SpaceSwitcherPosition) {
        _spaceSwitcherPosition.value = position
        settings.spaceSwitcherPosition = position
    }

    private suspend fun commit() {
        val languages = locales.value.selectedItems.mapToSet { it.language }
        val selectedTypes = types.value.selectedItems
        // Expand selected types to include adult variants
        val expandedTypes = selectedTypes.flatMapTo(HashSet()) { type ->
            when (type) {
                ContentType.MANGA -> listOf(ContentType.MANGA, ContentType.HENTAI_MANGA)
                ContentType.NOVEL -> listOf(ContentType.NOVEL, ContentType.HENTAI_NOVEL)
                ContentType.VIDEO -> listOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO)
                else -> listOf(type)
            }
        }
        val enabledSources = repository.queryAllSources(includeDisabledSources = true)
            .filterTo(HashSet()) { x ->
                val localeLang = x.getLocale()?.language ?: ""
                val mappedLang = if (x is org.skepsun.kototoro.ireader.model.IReaderMangaSource) {
                    org.skepsun.kototoro.core.model.mapIReaderLangToLocale(x.language) ?: x.language.lowercase()
                } else if (x is org.skepsun.kototoro.mihon.model.MihonMangaSource) {
                    x.language.lowercase()
                } else {
                    localeLang
                }
                val langMatches = if (mappedLang == "all" || mappedLang == "") {
                    "" in languages
                } else {
                    languages.any { it.isNotEmpty() && (mappedLang == it || mappedLang.startsWith("$it-") || it.startsWith("$mappedLang-")) }
                }
                x.getContentType() in expandedTypes && langMatches
            }
        repository.setSourcesEnabledExclusive(enabledSources)
        settings.contentLanguages = languages
    }

    private companion object {
        const val KEY_CURRENT_PAGE = "welcome.current_page"
        const val KEY_SELECTED_REPOSITORIES = "welcome.selected_repositories"
        const val KEY_INCLUDE_NSFW = "welcome.include_nsfw"
        const val KEY_SYSTEM_INSTALL_MODE = "welcome.system_install_mode"
    }
}

private fun UnifiedSourceKind.toExternalType(): ExternalExtensionType? = when (this) {
    UnifiedSourceKind.JAR -> ExternalExtensionType.JAR
    UnifiedSourceKind.MIHON -> ExternalExtensionType.MIHON
    UnifiedSourceKind.ANIYOMI -> ExternalExtensionType.ANIYOMI
    UnifiedSourceKind.IREADER -> ExternalExtensionType.IREADER
    UnifiedSourceKind.CLOUDSTREAM -> ExternalExtensionType.CLOUDSTREAM
    UnifiedSourceKind.TSUNDOKU -> ExternalExtensionType.TSUNDOKU
    UnifiedSourceKind.LEGADO,
    UnifiedSourceKind.TVBOX,
    UnifiedSourceKind.LNREADER,
    UnifiedSourceKind.JS,
    UnifiedSourceKind.NATIVE -> null
}

private fun ExternalExtensionType.toLocalApkEcosystemName(): String {
    return when (this) {
        ExternalExtensionType.MIHON -> "mihon"
        ExternalExtensionType.ANIYOMI -> "aniyomi"
        ExternalExtensionType.IREADER -> "ireader"
        ExternalExtensionType.TSUNDOKU -> "tsundoku"
        ExternalExtensionType.JAR,
        ExternalExtensionType.CLOUDSTREAM -> ""
    }
}

private fun jsonRepoKey(kind: UnifiedSourceKind, url: String): String = "JSON:${kind.name}:$url"

private fun titleForJsonRepo(kind: UnifiedSourceKind, url: String): String {
    UnifiedRecommendedRepositories.all
        .firstOrNull { it.kind == kind && it.url == url }
        ?.let { return it.name }
    return url.trimEnd('/').substringAfterLast('/').removeSuffix(".json").ifBlank {
        when (kind) {
            UnifiedSourceKind.LEGADO -> "Legado"
            UnifiedSourceKind.TVBOX -> "TVBox"
            else -> "LNReader"
        }
    }
}

private fun repoKeyForWelcome(repo: UnifiedRecommendedRepository): String = "${repo.kind.name}:${repo.url}"
