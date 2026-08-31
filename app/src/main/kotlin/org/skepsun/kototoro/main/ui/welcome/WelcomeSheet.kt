package org.skepsun.kototoro.main.ui.welcome

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.github.GitHubMirrorCatalogMeta
import org.skepsun.kototoro.core.github.GitHubMirrorProbeResult
import org.skepsun.kototoro.core.github.GitHubMirrorProbeState
import org.skepsun.kototoro.core.github.GitHubMirrorSyncState
import org.skepsun.kototoro.core.github.latencyLabel
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.GitHubMirrorEntry
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.ListToDetailsTransition
import org.skepsun.kototoro.core.prefs.SpaceSwitcherPosition
import org.skepsun.kototoro.core.prefs.displayName
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer
import org.skepsun.kototoro.core.ui.glass.GlassTuningController
import org.skepsun.kototoro.core.ui.glass.rememberGlassTuning
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.settings.compose.glass.GlassPreset
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepositories
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepository
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind
import org.skepsun.kototoro.settings.sources.unified.labelResId
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Stable setup-wizard page order. Permissions and the look-and-feel
 * (appearance / spaces) come before repository configuration so the user
 * picks those up first; repository config, batch install and the done summary
 * follow.
 */
private const val WIZARD_PAGE_INTRO = 0
private const val WIZARD_PAGE_PERMISSIONS = 1
private const val WIZARD_PAGE_APPEARANCE = 2
private const val WIZARD_PAGE_SOURCES = 3
private const val WIZARD_PAGE_BATCH_INSTALL = 4
private const val WIZARD_PAGE_DONE = 5
private const val WIZARD_PAGE_COUNT = 6

/** Maximum content width so the wizard stays readable on tablets / landscape. */
private val WIZARD_CONTENT_MAX_WIDTH = 680.dp

/** Stable order the wizard presents source types in. */
private val WELCOME_REPO_KIND_ORDER = listOf(
    UnifiedSourceKind.JAR,
    UnifiedSourceKind.MIHON,
    UnifiedSourceKind.ANIYOMI,
    UnifiedSourceKind.IREADER,
    UnifiedSourceKind.CLOUDSTREAM,
    UnifiedSourceKind.TSUNDOKU,
    UnifiedSourceKind.LEGADO,
    UnifiedSourceKind.TVBOX,
    UnifiedSourceKind.LNREADER,
)

private fun welcomeRepoKinds(repos: List<UnifiedRecommendedRepository>): List<UnifiedSourceKind> =
    WELCOME_REPO_KIND_ORDER.filter { kind -> repos.any { it.kind == kind } }

private fun repoKey(repo: UnifiedRecommendedRepository): String = "${repo.kind.name}:${repo.url}"

@Composable
internal fun WelcomeRoute(
    onDismissRequest: () -> Unit,
    onRestoreBackup: (Uri) -> Unit,
    onOpenDocumentUnsupported: () -> Unit = {},
    onStartSystemInstall: (android.content.Intent) -> Unit = {},
    onOpenExtensionManagement: () -> Unit = {},
    viewModel: WelcomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val backupSelectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(onRestoreBackup)
    }
    val mirrorEntries = viewModel.mirrorEntries.collectAsStateWithLifecycle().value
    val mirrorSyncState = viewModel.mirrorSyncState.collectAsStateWithLifecycle().value
    val mirrorCatalogMeta = viewModel.mirrorCatalogMeta.collectAsStateWithLifecycle().value
    val mirrorProbeState = viewModel.mirrorProbeState.collectAsStateWithLifecycle().value
    val mirrorProbeResults = viewModel.mirrorProbeResults.collectAsStateWithLifecycle().value

    // SYSTEM-mode APK installs must be launched by the host activity.
    LaunchedEffect(Unit) {
        viewModel.systemInstallRequests.collect { intent ->
            onStartSystemInstall(intent)
        }
    }

    // Custom Dialog + AnchoredDraggableState sheet (mihon pattern): only the
    // top drag handle can move the panel (pull down to dismiss); the content
    // area just scrolls and can never drag or dismiss the wizard.
    WelcomeBottomSheet(onDismissRequest = onDismissRequest) {
        KototoroTheme {
            WelcomeContent(
                viewModel = viewModel,
                mirrorEntries = mirrorEntries,
                mirrorSyncState = mirrorSyncState,
                mirrorCatalogMeta = mirrorCatalogMeta,
                mirrorProbeState = mirrorProbeState,
                mirrorProbeResults = mirrorProbeResults,
                onRefreshMirrors = viewModel::refreshMirrorCatalog,
                onProbeMirrors = viewModel::probeMirrors,
                onCancelMirrorProbes = viewModel::cancelMirrorProbes,
                onRestoreBackup = {
                    if (!backupSelectLauncher.tryLaunch(arrayOf("*/*"))) {
                        onOpenDocumentUnsupported()
                    }
                },
                onOpenExtensionManagement = onOpenExtensionManagement,
                onDone = onDismissRequest,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeContent(
    viewModel: WelcomeViewModel,
    mirrorEntries: List<GitHubMirrorEntry>,
    mirrorSyncState: GitHubMirrorSyncState,
    mirrorCatalogMeta: GitHubMirrorCatalogMeta,
    mirrorProbeState: GitHubMirrorProbeState,
    mirrorProbeResults: Map<String, GitHubMirrorProbeResult>,
    onRefreshMirrors: () -> Unit,
    onProbeMirrors: () -> Unit,
    onCancelMirrorProbes: () -> Unit,
    onRestoreBackup: () -> Unit,
    onOpenExtensionManagement: () -> Unit = {},
    onDone: () -> Unit,
) {
    val locales by viewModel.locales.collectAsStateWithLifecycle()
    val types by viewModel.types.collectAsStateWithLifecycle()
    val spacesEnabled by viewModel.spacesEnabled.collectAsStateWithLifecycle()
    val interfaceStyle by viewModel.interfaceStyle.collectAsStateWithLifecycle()
    val listToDetailsTransition by viewModel.listToDetailsTransition.collectAsStateWithLifecycle()
    val panoramaAnimationEnabled by viewModel.panoramaAnimationEnabled.collectAsStateWithLifecycle()
    val spaceSwitcherPosition by viewModel.spaceSwitcherPosition.collectAsStateWithLifecycle()
    val isInitializing by viewModel.isInitializingPlugins.collectAsStateWithLifecycle()
    val repoFetchStatuses by viewModel.repoFetchStatuses.collectAsStateWithLifecycle()
    val setupPhase by viewModel.setupPhase.collectAsStateWithLifecycle()
    val reposConfiguredEvent by viewModel.reposConfiguredEvent.collectAsStateWithLifecycle()
    val systemInstallMode by viewModel.systemInstallMode.collectAsStateWithLifecycle()
    val hasApkRepos by viewModel.hasApkRepos.collectAsStateWithLifecycle()
    val installPlan by viewModel.installPlan.collectAsStateWithLifecycle()
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val isInstallingPackages by viewModel.isInstallingPackages.collectAsStateWithLifecycle()
    val installFinishedEvent by viewModel.installFinishedEvent.collectAsStateWithLifecycle()
    val includeNsfw by viewModel.includeNsfw.collectAsStateWithLifecycle()
    val configuredInstallKinds by viewModel.configuredInstallKinds.collectAsStateWithLifecycle()
    val selectedInstallKinds by viewModel.selectedInstallKinds.collectAsStateWithLifecycle()
    val installSkipped by viewModel.installSkipped.collectAsStateWithLifecycle()
    val savedCurrentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val selectedRepositoryKeys by viewModel.selectedRepositoryKeys.collectAsStateWithLifecycle()
    // Glass finish preset state, shared with Settings → Appearance → glass tuner.
    val appContext = LocalContext.current.applicationContext
    val glassSettings = remember(appContext) { AppSettings(appContext) }
    val glassTuning = rememberGlassTuning(glassSettings)
    val glassTuningController = remember { GlassTuningController(glassSettings) }
    val activeGlassPreset = GlassPreset.entries.firstOrNull { it.matches(glassTuning) }
    val pagerState = rememberPagerState(initialPage = savedCurrentPage, pageCount = { WIZARD_PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val recommendedRepos = remember { UnifiedRecommendedRepositories.all }
    val selectedRepos = remember(recommendedRepos, selectedRepositoryKeys) {
        recommendedRepos.filter { repository -> repoKey(repository) in selectedRepositoryKeys }
    }
    var expandedKinds by remember { mutableStateOf(setOf(UnifiedSourceKind.JAR)) }
    var selectedMirrorId by rememberSaveable {
        mutableStateOf(glassSettings.gitHubMirrorId)
    }
    var showDisclaimer by rememberSaveable { mutableStateOf(false) }
    val navigationLocked = setupPhase == WizardSetupPhase.CONFIGURING ||
        setupPhase == WizardSetupPhase.BUILDING_PLAN ||
        setupPhase == WizardSetupPhase.INSTALLING
    val isBuildingInstallPlan = setupPhase == WizardSetupPhase.BUILDING_PLAN
    val installActionableKinds = selectedInstallKinds.count { kind ->
        kind == UnifiedSourceKind.MIHON ||
            kind == UnifiedSourceKind.ANIYOMI ||
            kind == UnifiedSourceKind.IREADER ||
            kind == UnifiedSourceKind.TSUNDOKU
    } > 0
    val showInstallMode = hasApkRepos && installActionableKinds
    val pendingInstallCount = installPlan.count { it.kind in selectedInstallKinds }
    val doneOutcome = when {
        installSkipped -> WizardDoneOutcome.SKIPPED
        installState.failed > 0 -> WizardDoneOutcome.PARTIAL
        else -> WizardDoneOutcome.SUCCESS
    }

    BackHandler(enabled = pagerState.currentPage > 0 || navigationLocked) {
        if (!navigationLocked && pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        }
    }

    // Recompute the install plan whenever the user is on the batch-install page,
    // so the counts reflect the latest configured repos and selected languages.
    LaunchedEffect(pagerState.currentPage, locales.selectedItems) {
        viewModel.setCurrentPage(pagerState.currentPage)
        if (pagerState.currentPage == WIZARD_PAGE_BATCH_INSTALL && !isInstallingPackages && !isInitializing) {
            viewModel.refreshInstallPlan()
        }
    }

    LaunchedEffect(reposConfiguredEvent) {
        // Navigate FIRST, then consume: clearing the event is a state change that
        // restarts this LaunchedEffect, which would otherwise cancel the in-flight
        // page animation mid-scroll (the wizard would appear to "do nothing").
        if (reposConfiguredEvent != null) {
            pagerState.animateScrollToPage(WIZARD_PAGE_BATCH_INSTALL)
            viewModel.consumeReposConfiguredEvent()
        }
    }

    LaunchedEffect(installFinishedEvent) {
        if (installFinishedEvent == true) {
            pagerState.animateScrollToPage(WIZARD_PAGE_DONE)
            viewModel.consumeInstallFinishedEvent()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isWideLayout = maxWidth > WIZARD_CONTENT_MAX_WIDTH
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = WIZARD_CONTENT_MAX_WIDTH)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 128.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    when (page) {
                        WIZARD_PAGE_INTRO -> WelcomeIntroStep()

                        WIZARD_PAGE_PERMISSIONS -> WelcomePermissionsStep()

                        WIZARD_PAGE_APPEARANCE -> {
                            WelcomeAppearanceStep(
                                interfaceStyle = interfaceStyle,
                                listToDetailsTransition = listToDetailsTransition,
                                panoramaAnimationEnabled = panoramaAnimationEnabled,
                                glassPreset = activeGlassPreset,
                                onGlassPresetChange = { preset ->
                                    glassTuningController.applyPreset(preset.config, preset.roleOverrides)
                                },
                                onInterfaceStyleChange = viewModel::setInterfaceStyle,
                                onListToDetailsTransitionChange = viewModel::setListToDetailsTransition,
                                onPanoramaAnimationChange = viewModel::setPanoramaAnimationEnabled,
                            )
                            WelcomeSpacesStep(
                                spacesEnabled = spacesEnabled,
                                onSpacesEnabledChange = viewModel::setSpacesEnabled,
                                spaceSwitcherPosition = spaceSwitcherPosition,
                                onSpaceSwitcherPositionChange = viewModel::setSpaceSwitcherPosition,
                            )
                        }

                        WIZARD_PAGE_SOURCES -> WelcomeSourcesStep(
                            recommendedRepos = recommendedRepos,
                            selectedRepositoryKeys = selectedRepositoryKeys,
                            expandedKinds = expandedKinds,
                            onKindToggled = { kind ->
                                expandedKinds = if (kind in expandedKinds) {
                                    expandedKinds - kind
                                } else {
                                    expandedKinds + kind
                                }
                            },
                            mirrorEntries = mirrorEntries,
                            selectedMirrorId = selectedMirrorId,
                            onMirrorSelected = { selectedMirrorId = it },
                            mirrorSyncState = mirrorSyncState,
                            mirrorCatalogMeta = mirrorCatalogMeta,
                            mirrorProbeState = mirrorProbeState,
                            mirrorProbeResults = mirrorProbeResults,
                            onRefreshMirrors = onRefreshMirrors,
                            onProbeMirrors = onProbeMirrors,
                            onCancelMirrorProbes = onCancelMirrorProbes,
                            repoFetchStatuses = repoFetchStatuses,
                            onCancelConfiguration = viewModel::cancelWizardConfiguration,
                            onRepositoryToggled = viewModel::toggleRepository,
                            onRepositoriesSelected = viewModel::selectRepositories,
                            onRepositoriesCleared = viewModel::clearRepositories,
                            isInitializing = isInitializing,
                            onRestoreBackup = onRestoreBackup,
                            twoColumn = isWideLayout,
                        )

                        WIZARD_PAGE_BATCH_INSTALL -> WelcomeBatchInstallStep(
                            configuredKinds = configuredInstallKinds,
                            selectedKinds = selectedInstallKinds,
                            onKindToggle = viewModel::toggleInstallKind,
                            locales = locales,
                            types = types,
                            onLocaleToggle = viewModel::setLocaleChecked,
                            onLocalesSelectAll = viewModel::selectAllLocales,
                            onLocalesClearAll = viewModel::clearAllLocales,
                            onTypeToggle = viewModel::setTypeChecked,
                            includeNsfw = includeNsfw,
                            onIncludeNsfwChange = viewModel::setIncludeNsfw,
                            showInstallMode = showInstallMode,
                            systemInstallMode = systemInstallMode,
                            onSystemInstallModeChange = viewModel::setSystemInstallMode,
                            installPlan = installPlan,
                            installState = installState,
                            isInstallingPackages = isInstallingPackages,
                            isBuildingInstallPlan = isBuildingInstallPlan,
                            onCancelInstall = viewModel::cancelInstall,
                            onSkip = {
                                viewModel.setInstallSkipped(true)
                                scope.launch { pagerState.animateScrollToPage(WIZARD_PAGE_DONE) }
                            },
                        )

                        WIZARD_PAGE_DONE -> WelcomeDoneStep(
                            installState = installState,
                            outcome = doneOutcome,
                            onOpenExtensionManagement = onOpenExtensionManagement,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassBottomBarContainer(
                modifier = Modifier.wrapContentWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        },
                        enabled = pagerState.currentPage > 0 && !navigationLocked,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(pagerState.pageCount) { index ->
                            Box(modifier = Modifier.size(width = 24.dp, height = 8.dp), contentAlignment = Alignment.Center) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.size(width = if (index == pagerState.currentPage) 24.dp else 8.dp, height = 8.dp),
                                ) {}
                            }
                        }
                    }
                    WizardPrimaryButton(
                        page = pagerState.currentPage,
                        setupPhase = setupPhase,
                        navigationLocked = navigationLocked,
                        isInstallingPackages = isInstallingPackages,
                        selectedRepoCount = selectedRepositoryKeys.size,
                        pendingInstallCount = pendingInstallCount,
                        doneOutcome = doneOutcome,
                        onNext = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                        onConfigure = { showDisclaimer = true },
                        onInstall = viewModel::installMatchingPackages,
                        onCancelInstall = viewModel::cancelInstall,
                        onRetryFailed = {
                            viewModel.retryFailedPackages()
                            scope.launch { pagerState.animateScrollToPage(WIZARD_PAGE_BATCH_INSTALL) }
                        },
                        onDone = onDone,
                    )
                }
            }
        }
    }
    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(stringResource(R.string.welcome_plugins_title)) },
            text = { Text(stringResource(R.string.welcome_plugins_disclaimer)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisclaimer = false
                    viewModel.initializePlugins(selectedMirrorId, selectedRepos.toList())
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisclaimer = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

private enum class WizardDoneOutcome {
    SUCCESS,
    PARTIAL,
    SKIPPED,
}

/**
 * The single primary action of the wizard. Its meaning follows the current
 * page so the content area never carries a competing main button.
 */
@Composable
private fun WizardPrimaryButton(
    page: Int,
    setupPhase: WizardSetupPhase,
    navigationLocked: Boolean,
    isInstallingPackages: Boolean,
    selectedRepoCount: Int,
    pendingInstallCount: Int,
    doneOutcome: WizardDoneOutcome,
    onNext: () -> Unit,
    onConfigure: () -> Unit,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onRetryFailed: () -> Unit,
    onDone: () -> Unit,
) {
    val action: WizardPrimaryAction? = when (page) {
        WIZARD_PAGE_SOURCES -> WizardPrimaryAction(
            label = stringResource(R.string.welcome_action_configure_repos),
            iconPainter = rememberSafePainter(R.drawable.ic_download),
            enabled = selectedRepoCount > 0 && setupPhase != WizardSetupPhase.CONFIGURING,
            onClick = onConfigure,
        )
        WIZARD_PAGE_BATCH_INSTALL -> when {
            isInstallingPackages -> WizardPrimaryAction(
                label = stringResource(R.string.welcome_action_cancel_install),
                enabled = true,
                onClick = onCancelInstall,
                tonal = true,
            )
            setupPhase == WizardSetupPhase.FINISHED || setupPhase == WizardSetupPhase.SKIPPED -> WizardPrimaryAction(
                label = stringResource(R.string.next),
                iconVector = Icons.AutoMirrored.Filled.ArrowForward,
                enabled = !navigationLocked,
                onClick = onNext,
            )
            setupPhase == WizardSetupPhase.READY_TO_INSTALL && pendingInstallCount > 0 -> WizardPrimaryAction(
                label = stringResource(R.string.welcome_action_install_count, pendingInstallCount),
                iconPainter = rememberSafePainter(R.drawable.ic_download),
                enabled = true,
                onClick = onInstall,
            )
            else -> null
        }
        WIZARD_PAGE_DONE -> when (doneOutcome) {
            WizardDoneOutcome.PARTIAL -> WizardPrimaryAction(
                label = stringResource(R.string.welcome_action_retry_failed),
                enabled = true,
                onClick = onRetryFailed,
            )
            else -> WizardPrimaryAction(
                label = stringResource(R.string.done),
                iconVector = Icons.Default.Done,
                enabled = true,
                onClick = onDone,
            )
        }
        else -> WizardPrimaryAction(
            label = stringResource(R.string.next),
            iconVector = Icons.AutoMirrored.Filled.ArrowForward,
            enabled = !navigationLocked,
            onClick = onNext,
        )
    }
    // A partial failure must never trap the user: "retry failed" stays the primary
    // action, but "done" is always offered next to it as the way to end the wizard.
    val secondaryAction: WizardPrimaryAction? = if (page == WIZARD_PAGE_DONE && doneOutcome == WizardDoneOutcome.PARTIAL) {
        WizardPrimaryAction(
            label = stringResource(R.string.done),
            iconVector = Icons.Default.Done,
            enabled = true,
            onClick = onDone,
        )
    } else {
        null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (secondaryAction != null) {
            TextButton(
                onClick = secondaryAction.onClick,
                enabled = secondaryAction.enabled,
            ) {
                WizardPrimaryButtonContent(secondaryAction.label, secondaryAction.iconVector, secondaryAction.iconPainter)
            }
        }
        if (action != null) {
            AnimatedContent(
                targetState = action.label,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "wizardPrimaryAction",
            ) { animatedLabel ->
                val onClick = if (animatedLabel == action.label) action.onClick else ({})
                if (action.tonal) {
                    FilledTonalButton(
                        onClick = onClick,
                        enabled = action.enabled,
                        modifier = Modifier.height(52.dp),
                    ) {
                        WizardPrimaryButtonContent(animatedLabel, action.iconVector, action.iconPainter)
                    }
                } else {
                    Button(
                        onClick = onClick,
                        enabled = action.enabled,
                        modifier = Modifier.height(52.dp),
                    ) {
                        WizardPrimaryButtonContent(animatedLabel, action.iconVector, action.iconPainter)
                    }
                }
            }
        }
    }
}

private data class WizardPrimaryAction(
    val label: String,
    val iconVector: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val iconPainter: Painter? = null,
    val enabled: Boolean,
    val tonal: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun WizardPrimaryButtonContent(
    label: String,
    iconVector: androidx.compose.ui.graphics.vector.ImageVector?,
    iconPainter: Painter?,
) {
    Text(label)
    if (iconVector != null) {
        Spacer(Modifier.width(8.dp))
        Icon(iconVector, contentDescription = null, modifier = Modifier.size(18.dp))
    } else if (iconPainter != null) {
        Spacer(Modifier.width(8.dp))
        Icon(iconPainter, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun WelcomeSourcesStep(
    recommendedRepos: List<UnifiedRecommendedRepository>,
    selectedRepositoryKeys: Set<String>,
    expandedKinds: Set<UnifiedSourceKind>,
    onKindToggled: (UnifiedSourceKind) -> Unit,
    mirrorEntries: List<GitHubMirrorEntry>,
    selectedMirrorId: String,
    onMirrorSelected: (String) -> Unit,
    mirrorSyncState: GitHubMirrorSyncState,
    mirrorCatalogMeta: GitHubMirrorCatalogMeta,
    mirrorProbeState: GitHubMirrorProbeState,
    mirrorProbeResults: Map<String, GitHubMirrorProbeResult>,
    onRefreshMirrors: () -> Unit,
    onProbeMirrors: () -> Unit,
    onCancelMirrorProbes: () -> Unit,
    repoFetchStatuses: Map<String, WizardRepoFetchStatus>,
    onCancelConfiguration: () -> Unit,
    onRepositoryToggled: (UnifiedRecommendedRepository) -> Unit,
    onRepositoriesSelected: (Collection<UnifiedRecommendedRepository>) -> Unit,
    onRepositoriesCleared: (UnifiedSourceKind) -> Unit,
    isInitializing: Boolean,
    onRestoreBackup: () -> Unit,
    twoColumn: Boolean,
) {
    WizardPageHeader(
        step = WIZARD_PAGE_SOURCES + 1,
        totalSteps = WIZARD_PAGE_COUNT,
        title = stringResource(R.string.welcome_plugins_title),
        summary = stringResource(R.string.welcome_plugins_summary),
        icon = rememberSafePainter(R.drawable.ic_extension),
    )
    if (isInitializing) {
        WizardSectionCard {
            WizardInlineStatus(text = stringResource(R.string.welcome_status_reading_repos))
            repoFetchStatuses.values.forEach { status ->
                WizardRepoFetchRow(status = status)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancelConfiguration) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
    welcomeRepoKinds(recommendedRepos).forEach { kind ->
        val kindRepos = recommendedRepos.filter { it.kind == kind }
        val selectedCount = kindRepos.count { repoKey(it) in selectedRepositoryKeys }
        RepoKindGroupCard(
            title = stringResource(kind.labelResId()),
            selectedCount = selectedCount,
            totalCount = kindRepos.size,
            expanded = kind in expandedKinds,
            onToggleExpanded = { onKindToggled(kind) },
            enabled = !isInitializing,
            actions = {
                TextButton(onClick = { onRepositoriesSelected(kindRepos) }, enabled = !isInitializing) {
                    Text(stringResource(R.string.select_all))
                }
                TextButton(onClick = { onRepositoriesCleared(kind) }, enabled = !isInitializing) {
                    Text(stringResource(R.string.clear))
                }
            },
        ) {
            if (twoColumn) {
                val mid = (kindRepos.size + 1) / 2
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        kindRepos.take(mid).forEach { repo ->
                            RepoCheckRow(
                                title = repo.name,
                                subtitle = repo.note,
                                selected = repoKey(repo) in selectedRepositoryKeys,
                                enabled = !isInitializing,
                                onToggle = { onRepositoryToggled(repo) },
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        kindRepos.drop(mid).forEach { repo ->
                            RepoCheckRow(
                                title = repo.name,
                                subtitle = repo.note,
                                selected = repoKey(repo) in selectedRepositoryKeys,
                                enabled = !isInitializing,
                                onToggle = { onRepositoryToggled(repo) },
                            )
                        }
                    }
                }
            } else {
                kindRepos.forEach { repo ->
                    RepoCheckRow(
                        title = repo.name,
                        subtitle = repo.note,
                        selected = repoKey(repo) in selectedRepositoryKeys,
                        enabled = !isInitializing,
                        onToggle = { onRepositoryToggled(repo) },
                    )
                }
            }
        }
    }
    WizardSectionCard(
        title = stringResource(R.string.pref_github_mirror),
        summary = stringResource(R.string.welcome_mirror_summary),
    ) {
        MirrorDropdown(
            entries = mirrorEntries,
            selectedId = selectedMirrorId,
            onSelected = onMirrorSelected,
            enabled = !isInitializing,
            probeResults = mirrorProbeResults,
        )
        MirrorSyncRow(
            entries = mirrorEntries,
            syncState = mirrorSyncState,
            meta = mirrorCatalogMeta,
            probeState = mirrorProbeState,
            onRefresh = onRefreshMirrors,
            onProbe = onProbeMirrors,
            onCancelProbe = onCancelMirrorProbes,
            enabled = !isInitializing,
        )
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onRestoreBackup, enabled = !isInitializing) {
            Icon(rememberSafePainter(R.drawable.ic_backup_restore), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.restore_backup))
        }
    }
}

@Composable
private fun ColumnScope.WelcomeBatchInstallStep(
    configuredKinds: List<UnifiedSourceKind>,
    selectedKinds: Set<UnifiedSourceKind>,
    onKindToggle: (UnifiedSourceKind, Boolean) -> Unit,
    locales: FilterProperty<Locale>,
    types: FilterProperty<ContentType>,
    onLocaleToggle: (Locale, Boolean) -> Unit,
    onLocalesSelectAll: () -> Unit,
    onLocalesClearAll: () -> Unit,
    onTypeToggle: (ContentType, Boolean) -> Unit,
    includeNsfw: Boolean,
    onIncludeNsfwChange: (Boolean) -> Unit,
    showInstallMode: Boolean,
    systemInstallMode: Boolean,
    onSystemInstallModeChange: (Boolean) -> Unit,
    installPlan: List<WizardInstallItem>,
    installState: WizardInstallState,
    isInstallingPackages: Boolean,
    isBuildingInstallPlan: Boolean,
    onCancelInstall: () -> Unit,
    onSkip: () -> Unit,
) {
    val controlsEnabled = !isInstallingPackages && !isBuildingInstallPlan
    WizardPageHeader(
        step = WIZARD_PAGE_BATCH_INSTALL + 1,
        totalSteps = WIZARD_PAGE_COUNT,
        title = stringResource(R.string.welcome_install_step_title),
        summary = stringResource(R.string.welcome_install_step_summary),
        icon = rememberSafePainter(R.drawable.ic_download),
    )

    // While the plan is being rebuilt the current content stays visible but
    // is dimmed and non-interactive, so the page does not jump or collapse.
    Column(
        modifier = Modifier
            .alpha(if (isBuildingInstallPlan) 0.5f else 1f)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        WizardSectionCard(
            title = stringResource(R.string.welcome_install_kinds_title),
            summary = stringResource(R.string.welcome_install_kinds_summary),
        ) {
            if (configuredKinds.isEmpty()) {
                Text(
                    text = stringResource(R.string.welcome_install_kinds_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    configuredKinds.forEach { kind ->
                        val count = installPlan.count { it.kind == kind }
                        val label = if (count > 0) {
                            "${stringResource(kind.labelResId())} · $count"
                        } else {
                            stringResource(kind.labelResId())
                        }
                        WizardFilterChip(
                            selected = kind in selectedKinds,
                            enabled = controlsEnabled,
                            onClick = { onKindToggle(kind, kind !in selectedKinds) },
                            label = {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        WizardSectionCard(
            title = stringResource(R.string.welcome_source_formats_title),
            summary = stringResource(R.string.welcome_source_formats_summary),
        ) {
            ContentTypeChips(types = types, enabled = controlsEnabled, onTypeToggle = onTypeToggle)
        }

        WizardSectionCard(
            title = stringResource(R.string.languages),
            summary = stringResource(R.string.welcome_preferences_summary),
        ) {
            WizardLanguageSelector(
                locales = locales,
                enabled = controlsEnabled,
                onLocaleToggle = onLocaleToggle,
                onSelectAll = onLocalesSelectAll,
                onClearAll = onLocalesClearAll,
            )
            if (locales.isLoading || types.isLoading) {
                WizardInlineStatus(text = stringResource(R.string.welcome_status_analyzing))
            }
        }

        WizardSectionCard {
            WelcomeSwitchRow(
                title = stringResource(R.string.welcome_install_nsfw_title),
                summary = stringResource(R.string.welcome_install_nsfw_summary),
                checked = includeNsfw,
                onCheckedChange = onIncludeNsfwChange,
                enabled = controlsEnabled,
            )
        }

        if (showInstallMode) {
            WizardSectionCard(
                title = stringResource(R.string.welcome_install_mode_title),
                summary = stringResource(R.string.welcome_install_mode_summary),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizardFilterChip(
                        selected = !systemInstallMode,
                        enabled = controlsEnabled,
                        onClick = { onSystemInstallModeChange(false) },
                        label = { Text(stringResource(R.string.welcome_install_mode_sideload)) },
                    )
                    WizardFilterChip(
                        selected = systemInstallMode,
                        enabled = controlsEnabled,
                        onClick = { onSystemInstallModeChange(true) },
                        label = { Text(stringResource(R.string.welcome_install_mode_system)) },
                    )
                }
            }
        }

        WizardSectionCard(
            title = stringResource(R.string.welcome_install_section_title),
            summary = stringResource(R.string.welcome_install_section_summary),
        ) {
            when {
                isBuildingInstallPlan -> WizardInlineStatus(
                    text = stringResource(R.string.welcome_status_building_plan),
                )
                isInstallingPackages || installState.phase == WizardInstallPhase.FINISHED -> {
                    WizardInstallProgressPanel(
                        installState = installState,
                        isInstalling = isInstallingPackages,
                        onCancel = onCancelInstall,
                    )
                }
                else -> WizardInstallPlanSummary(
                    installPlan = installPlan,
                    selectedKinds = selectedKinds,
                    locales = locales,
                    types = types,
                )
            }
        }
    }

    if (installState.phase != WizardInstallPhase.FINISHED) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(
                onClick = onSkip,
                enabled = controlsEnabled,
            ) {
                Text(stringResource(R.string.welcome_install_skip))
            }
        }
    }
}

@Composable
private fun WizardInstallPlanSummary(
    installPlan: List<WizardInstallItem>,
    selectedKinds: Set<UnifiedSourceKind>,
    locales: FilterProperty<Locale>,
    types: FilterProperty<ContentType>,
) {
    val actionable = installPlan.filter { it.kind in selectedKinds }
    if (actionable.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.welcome_install_plan_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val kindSummary = actionable
        .groupBy { it.kind }
        .map { (kind, items) -> "${stringResource(kind.labelResId())} ${items.size}" }
        .joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AnimatedContent(
            targetState = actionable.size,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "installCount",
        ) { animatedCount ->
            Text(
                text = stringResource(R.string.welcome_action_install_count, animatedCount),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = kindSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.welcome_install_plan_conditions,
                locales.selectedItems.size,
                types.selectedItems.size,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WelcomeIntroStep() {
    WizardPageHeader(
        step = WIZARD_PAGE_INTRO + 1,
        totalSteps = WIZARD_PAGE_COUNT,
        title = stringResource(R.string.welcome_intro_app_title),
        summary = stringResource(R.string.welcome_intro_body),
        icon = rememberSafePainter(R.drawable.ic_welcome),
        prominent = true,
    )

    WizardSectionCard(title = stringResource(R.string.welcome_intro_concepts_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IntroConceptItem(
                title = stringResource(R.string.welcome_intro_repo_label),
                summary = stringResource(R.string.welcome_intro_repo_body),
            )
            IntroConceptItem(
                title = stringResource(R.string.welcome_intro_extension_label),
                summary = stringResource(R.string.welcome_intro_extension_body),
            )
            IntroConceptItem(
                title = stringResource(R.string.welcome_intro_source_label),
                summary = stringResource(R.string.welcome_intro_source_body),
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.welcome_intro_disclaimer),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun IntroConceptItem(title: String, summary: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WelcomeDoneStep(
    installState: WizardInstallState,
    outcome: WizardDoneOutcome,
    onOpenExtensionManagement: () -> Unit,
) {
    val (headerTitle, containerColor, contentColor) = when (outcome) {
        WizardDoneOutcome.SUCCESS -> Triple(
            stringResource(R.string.welcome_done_success_title),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        WizardDoneOutcome.PARTIAL -> Triple(
            stringResource(R.string.welcome_done_partial_title),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        WizardDoneOutcome.SKIPPED -> Triple(
            stringResource(R.string.welcome_done_skipped_title),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    val headerIcon = when (outcome) {
        WizardDoneOutcome.PARTIAL -> rememberSafePainter(R.drawable.ic_error_small)
        else -> rememberSafePainter(R.drawable.ic_check)
    }
    WizardPageHeader(
        step = WIZARD_PAGE_DONE + 1,
        totalSteps = WIZARD_PAGE_COUNT,
        title = headerTitle,
        summary = stringResource(R.string.welcome_done_summary),
        icon = headerIcon,
        prominent = true,
        containerColor = containerColor,
        contentColor = contentColor,
    )

    WizardSectionCard {
        Text(
            text = when (outcome) {
                WizardDoneOutcome.SUCCESS -> stringResource(
                    R.string.welcome_done_success_detail,
                    installState.completed,
                )
                WizardDoneOutcome.PARTIAL -> stringResource(
                    R.string.welcome_done_partial_detail,
                    installState.completed,
                    installState.failed,
                    installState.cancelled,
                )
                WizardDoneOutcome.SKIPPED -> stringResource(R.string.welcome_done_skipped)
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.welcome_done_json_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = onOpenExtensionManagement,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = rememberSafePainter(R.drawable.ic_settings),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.welcome_open_extension_management))
        }
    }

    WizardSectionCard(
        title = stringResource(R.string.welcome_done_advice_title),
        summary = stringResource(R.string.welcome_done_advice_summary),
    ) {
        Text(
            text = stringResource(R.string.welcome_done_advice_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WelcomePermissionsStep() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionRefreshToken by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefreshToken++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    WizardPageHeader(
        step = WIZARD_PAGE_PERMISSIONS + 1,
        totalSteps = WIZARD_PAGE_COUNT,
        title = stringResource(R.string.welcome_permissions_title),
        summary = stringResource(R.string.welcome_permissions_summary),
        icon = rememberSafePainter(R.drawable.ic_notification),
    )

    // Notifications
    val notificationGranted = remember(permissionRefreshToken) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    WizardSectionCard {
        WelcomePermissionRow(
            icon = rememberSafePainter(R.drawable.ic_notification),
            title = stringResource(R.string.welcome_permissions_notifications_title),
            summary = stringResource(R.string.welcome_permissions_notifications_summary),
            granted = notificationGranted,
            actionLabel = stringResource(R.string.welcome_permissions_grant),
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }

    // Query all packages (declared in the manifest as a normal permission; granted at install time)
    WizardSectionCard {
        WelcomePermissionRow(
            icon = rememberSafePainter(R.drawable.ic_source_builtin),
            title = stringResource(R.string.welcome_permissions_packages_title),
            summary = stringResource(R.string.welcome_permissions_packages_summary),
            granted = true,
            actionLabel = null,
            onAction = null,
        )
    }

    // Battery optimization / background survival
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoringBatteryOptimizations = remember(permissionRefreshToken) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    WizardSectionCard {
        WelcomePermissionRow(
            icon = rememberSafePainter(R.drawable.ic_battery_outline),
            title = stringResource(R.string.welcome_permissions_battery_title),
            summary = stringResource(R.string.welcome_permissions_battery_summary),
            granted = ignoringBatteryOptimizations,
            actionLabel = stringResource(R.string.welcome_permissions_battery_action),
            onAction = {
                runCatching {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }.onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.welcome_permissions_battery_failed, it.message.orEmpty()),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }

    Text(
        text = stringResource(R.string.welcome_permissions_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WelcomePermissionRow(
    icon: Painter,
    title: String,
    summary: String,
    granted: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            Text(
                text = stringResource(R.string.welcome_permissions_granted),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun WizardInstallProgressPanel(
    installState: WizardInstallState,
    isInstalling: Boolean,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.welcome_install_progress, installState.done, installState.total),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            WizardStatChip(
                text = stringResource(R.string.welcome_install_state_completed) + " ${installState.completed}",
                color = MaterialTheme.colorScheme.primary,
            )
            if (installState.failed > 0) {
                WizardStatChip(
                    text = stringResource(R.string.welcome_install_state_failed) + " ${installState.failed}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (installState.cancelled > 0) {
                WizardStatChip(
                    text = stringResource(R.string.welcome_install_state_cancelled) + " ${installState.cancelled}",
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        WizardInlineStatus(
            text = stringResource(R.string.welcome_install_progress, installState.done, installState.total),
            progress = { installState.progressPercent / 100f },
        )
        if (installState.items.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                installState.items.forEach { item ->
                    WizardInstallItemRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun WizardInstallItemRow(item: WizardInstallItem) {
    var errorExpanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WizardPackageStatusIconAnimated(state = item.state)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.state == WizardPackageState.DOWNLOADING && item.progressPercent != null) {
                    WizardInlineStatus(
                        text = "${item.progressPercent}%",
                        progress = { item.progressPercent / 100f },
                    )
                }
            }
            if (item.errorMessage != null) {
                IconButton(onClick = { errorExpanded = !errorExpanded }) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = stringResource(item.state.labelResId()),
                style = MaterialTheme.typography.labelSmall,
                color = item.state.color(),
            )
        }
        if (errorExpanded && item.errorMessage != null) {
            Text(
                text = item.errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 28.dp, top = 2.dp),
            )
        }
    }
}

internal fun WizardPackageState.labelResId(): Int {
    return when (this) {
        WizardPackageState.QUEUED -> R.string.welcome_install_state_queued
        WizardPackageState.DOWNLOADING -> R.string.welcome_install_state_downloading
        WizardPackageState.INSTALLING -> R.string.welcome_install_state_installing
        WizardPackageState.COMPLETED -> R.string.welcome_install_state_completed
        WizardPackageState.FAILED -> R.string.welcome_install_state_failed
        WizardPackageState.CANCELLED -> R.string.welcome_install_state_cancelled
    }
}

@Composable
private fun WizardPackageState.color(): Color {
    return when (this) {
        WizardPackageState.COMPLETED -> MaterialTheme.colorScheme.primary
        WizardPackageState.FAILED -> MaterialTheme.colorScheme.error
        WizardPackageState.CANCELLED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun WelcomeSpacesStep(
    spacesEnabled: Boolean,
    onSpacesEnabledChange: (Boolean) -> Unit,
    spaceSwitcherPosition: SpaceSwitcherPosition,
    onSpaceSwitcherPositionChange: (SpaceSwitcherPosition) -> Unit,
) {
    WizardSectionCard(
        title = stringResource(R.string.welcome_spaces_title),
        summary = stringResource(R.string.welcome_spaces_summary),
    ) {
        WelcomeSwitchRow(
            title = stringResource(R.string.spaces_enabled),
            summary = stringResource(R.string.spaces_enabled_summary),
            checked = spacesEnabled,
            onCheckedChange = onSpacesEnabledChange,
            enabled = true,
        )
        if (spacesEnabled) {
            Text(
                text = stringResource(R.string.space_switcher_position),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SpaceSwitcherPosition.entries.forEach { position ->
                    WizardFilterChip(
                        selected = position == spaceSwitcherPosition,
                        enabled = true,
                        onClick = { onSpaceSwitcherPositionChange(position) },
                        label = { Text(stringResource(position.labelResId())) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeAppearanceStep(
    interfaceStyle: InterfaceStyle,
    listToDetailsTransition: ListToDetailsTransition,
    panoramaAnimationEnabled: Boolean,
    glassPreset: GlassPreset?,
    onGlassPresetChange: (GlassPreset) -> Unit,
    onInterfaceStyleChange: (InterfaceStyle) -> Unit,
    onListToDetailsTransitionChange: (ListToDetailsTransition) -> Unit,
    onPanoramaAnimationChange: (Boolean) -> Unit,
) {
    WizardPageHeader(
        step = WIZARD_PAGE_APPEARANCE + 1,
        totalSteps = WIZARD_PAGE_COUNT,
        title = stringResource(R.string.welcome_appearance_title),
        summary = stringResource(R.string.welcome_appearance_summary),
        icon = rememberSafePainter(R.drawable.ic_palette),
    )
    WizardSectionCard(title = stringResource(R.string.interface_style)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InterfaceStyle.selectableEntries.forEach { style ->
                WizardFilterChip(
                    selected = style == interfaceStyle,
                    enabled = true,
                    onClick = { onInterfaceStyleChange(style) },
                    label = { Text(stringResource(style.titleResId)) },
                )
            }
        }
        if (interfaceStyle == InterfaceStyle.IOS) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.welcome_glass_preset_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.welcome_glass_preset_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPreset.entries.forEach { preset ->
                        WizardFilterChip(
                            selected = preset == glassPreset,
                            enabled = true,
                            onClick = { onGlassPresetChange(preset) },
                            label = { Text(stringResource(preset.titleRes)) },
                        )
                    }
                }
            }
        }
    }
    WizardSectionCard(
        title = stringResource(R.string.pref_list_to_details_transition),
        summary = stringResource(R.string.pref_list_to_details_transition_summary),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListToDetailsTransition.entries.forEach { option ->
                WizardFilterChip(
                    selected = option == listToDetailsTransition,
                    enabled = true,
                    onClick = { onListToDetailsTransitionChange(option) },
                    label = { Text(stringResource(option.labelRes())) },
                )
            }
        }
        WelcomeSwitchRow(
            title = stringResource(R.string.pref_panorama_animation),
            summary = stringResource(R.string.pref_panorama_animation_summary),
            checked = panoramaAnimationEnabled,
            onCheckedChange = onPanoramaAnimationChange,
            enabled = true,
        )
    }
}

@Composable
private fun WelcomeSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

private fun SpaceSwitcherPosition.labelResId(): Int = when (this) {
    SpaceSwitcherPosition.TOP_RIGHT -> R.string.space_switcher_position_top_right
    SpaceSwitcherPosition.CENTER_RIGHT -> R.string.space_switcher_position_center_right
    SpaceSwitcherPosition.TOP_LEFT -> R.string.space_switcher_position_top_left
    SpaceSwitcherPosition.CENTER_LEFT -> R.string.space_switcher_position_center_left
}

private fun ListToDetailsTransition.labelRes(): Int = when (this) {
    ListToDetailsTransition.HERO_EXPAND -> R.string.pref_list_to_details_transition_hero
    ListToDetailsTransition.LEGACY_SLIDE -> R.string.pref_list_to_details_transition_legacy
}

@Composable
private fun MirrorDropdown(
    entries: List<GitHubMirrorEntry>,
    selectedId: String,
    onSelected: (String) -> Unit,
    enabled: Boolean,
    probeResults: Map<String, GitHubMirrorProbeResult> = emptyMap(),
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = entries.firstOrNull { it.id == selectedId }?.displayName(context)
        ?: stringResource(R.string.pref_github_mirror)
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            enabled = enabled && entries.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${stringResource(R.string.pref_github_mirror)}: $selectedLabel",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Text(entry.displayName(context) + probeResults[entry.id].latencyLabel(context))
                    },
                    onClick = {
                        onSelected(entry.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MirrorSyncRow(
    entries: List<GitHubMirrorEntry>,
    syncState: GitHubMirrorSyncState,
    meta: GitHubMirrorCatalogMeta,
    probeState: GitHubMirrorProbeState,
    onRefresh: () -> Unit,
    onProbe: () -> Unit,
    onCancelProbe: () -> Unit,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val refreshing = syncState is GitHubMirrorSyncState.Refreshing
    val probing = probeState is GitHubMirrorProbeState.Running
    val caption = when {
        refreshing -> stringResource(R.string.mirror_sync_in_progress)
        probing -> {
            val running = probeState as GitHubMirrorProbeState.Running
            stringResource(R.string.mirror_probe_running, running.completed, running.total)
        }
        probeState is GitHubMirrorProbeState.Finished && probeState.total > 0 -> when {
            probeState.available == 0 -> stringResource(R.string.mirror_probe_none_available)
            else -> {
                val fastestName = probeState.fastestId
                    ?.let { id -> entries.firstOrNull { it.id == id } }
                    ?.displayName(context)
                    ?: probeState.fastestId.orEmpty()
                stringResource(
                    R.string.mirror_probe_finished,
                    fastestName,
                    probeState.fastestMillis ?: 0L,
                    probeState.available,
                    probeState.total,
                )
            }
        }
        syncState is GitHubMirrorSyncState.Success -> context.getString(
            R.string.mirror_sync_success,
            syncState.version,
            syncState.mirrorCount,
        )
        syncState is GitHubMirrorSyncState.Failed -> stringResource(R.string.mirror_sync_keep_last)
        syncState is GitHubMirrorSyncState.NoManifest -> stringResource(R.string.mirror_sync_no_manifest_summary)
        meta.lastRefreshAt > 0L -> context.getString(R.string.mirror_sync_last_updated, meta.version.orEmpty())
        else -> stringResource(R.string.mirror_sync_never)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.End),
        ) {
            TextButton(onClick = onRefresh, enabled = enabled && !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.mirror_sync_action))
            }
            TextButton(
                onClick = { if (probing) onCancelProbe() else onProbe() },
                enabled = enabled,
            ) {
                if (probing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    stringResource(
                        if (probing) R.string.mirror_probe_cancel else R.string.mirror_probe_short_action,
                    ),
                )
            }
        }
    }
}

@Composable
private fun WizardRepoFetchRow(status: WizardRepoFetchStatus) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (status.phase) {
            WizardRepoFetchPhase.PENDING -> Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            WizardRepoFetchPhase.RUNNING -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            WizardRepoFetchPhase.DONE -> Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            WizardRepoFetchPhase.FAILED -> Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = status.repo.name,
                style = MaterialTheme.typography.bodyMedium,
            )
            status.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ContentTypeChips(
    types: FilterProperty<ContentType>,
    enabled: Boolean,
    onTypeToggle: (ContentType, Boolean) -> Unit,
) {
    FilterChipGroup(
        items = types.availableItems,
        selectedItems = types.selectedItems,
        label = { stringResource(it.titleResId) },
        leadingIcon = { type ->
            when (type) {
                ContentType.NOVEL, ContentType.HENTAI_NOVEL -> R.drawable.ic_book_page
                ContentType.VIDEO, ContentType.HENTAI_VIDEO -> R.drawable.ic_play
                else -> R.drawable.ic_manga_source
            }
        },
        onToggle = onTypeToggle,
        enabled = enabled,
    )
}

@Composable
private fun <T> FilterChipGroup(
    items: List<T>,
    selectedItems: Set<T>,
    label: @Composable (T) -> String,
    onToggle: (T, Boolean) -> Unit,
    leadingIcon: ((T) -> Int)? = null,
    enabled: Boolean = true,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val selected = item in selectedItems
            WizardFilterChip(
                selected = selected,
                onClick = { onToggle(item, !selected) },
                enabled = enabled,
                label = { Text(label(item)) },
            )
        }
    }
}
