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
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.ListToDetailsTransition
import org.skepsun.kototoro.core.prefs.SpaceSwitcherPosition
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer
import org.skepsun.kototoro.core.ui.glass.GlassTuningController
import org.skepsun.kototoro.core.ui.glass.rememberGlassTuning
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.util.ext.getDisplayName
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
import kotlin.math.roundToInt

private const val REPO_KOTOTORO =
    "https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/index.min.json"
private const val REPO_REDO =
    "https://raw.githubusercontent.com/skepsun/k-parsers-r/repo/index.min.json"

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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    // The wizard must not be dismissed by a swipe-down gesture (easy to trigger
    // accidentally while panning the pager); only an explicit tap (scrim,
    // back button) can close it.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    val backupSelectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(onRestoreBackup)
    }
    val mirrorEntries = remember(context) {
        context.resources.getStringArray(R.array.pref_github_mirror_entries).toList()
    }

    // SYSTEM-mode APK installs must be launched by the host activity.
    LaunchedEffect(Unit) {
        viewModel.systemInstallRequests.collect { intent ->
            onStartSystemInstall(intent)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
    ) {
        KototoroTheme {
            WelcomeContent(
                viewModel = viewModel,
                mirrorEntries = mirrorEntries,
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
    mirrorEntries: List<String>,
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
    // Glass finish preset state, shared with Settings → Appearance → glass tuner.
    val appContext = LocalContext.current.applicationContext
    val glassSettings = remember(appContext) { AppSettings(appContext) }
    val glassTuning = rememberGlassTuning(glassSettings)
    val glassTuningController = remember { GlassTuningController(glassSettings) }
    val activeGlassPreset = GlassPreset.entries.firstOrNull { it.matches(glassTuning) }
    val pagerState = rememberPagerState(pageCount = { 6 })
    val scope = rememberCoroutineScope()
    val recommendedRepos = remember { UnifiedRecommendedRepositories.all }
    val defaultRepoUrls = remember { setOf(REPO_KOTOTORO, REPO_REDO) }
    val selectedRepos = remember {
        mutableStateListOf<UnifiedRecommendedRepository>().apply {
            addAll(recommendedRepos.filter { it.url in defaultRepoUrls })
        }
    }
    var expandedKinds by remember { mutableStateOf(setOf(UnifiedSourceKind.JAR)) }
    var selectedMirrorIndex by rememberSaveable { mutableIntStateOf(0) }
    var showDisclaimer by rememberSaveable { mutableStateOf(false) }
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    BackHandler(enabled = pagerState.currentPage > 0 && !isInitializing) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    // Recompute the install plan whenever the user is on the batch-install page,
    // so the counts reflect the latest configured repos and selected languages.
    LaunchedEffect(pagerState.currentPage, locales.selectedItems) {
        if (pagerState.currentPage == 2 && !isInstallingPackages && !isInitializing) {
            viewModel.refreshInstallPlan()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isInitializing,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when (page) {
                    0 -> {
                        WelcomeIntroStep(onContinue = {
                            scope.launch { pagerState.animateScrollToPage(1) }
                        })
                    }

                    1 -> {
                        WelcomeHero(expressive = expressive)
                        WelcomeSourcesStep(
                            recommendedRepos = recommendedRepos,
                            selectedRepos = selectedRepos,
                            expandedKinds = expandedKinds,
                            onKindToggled = { kind ->
                                expandedKinds = if (kind in expandedKinds) {
                                    expandedKinds - kind
                                } else {
                                    expandedKinds + kind
                                }
                            },
                            mirrorEntries = mirrorEntries,
                            selectedMirrorIndex = selectedMirrorIndex,
                            onMirrorSelected = { selectedMirrorIndex = it },
                            isInitializing = isInitializing,
                            onInitialize = { showDisclaimer = true },
                            onRestoreBackup = onRestoreBackup,
                        )
                    }

                    2 -> WelcomeBatchInstallStep(
                        configuredKinds = configuredInstallKinds,
                        selectedKinds = selectedInstallKinds,
                        onKindToggle = viewModel::toggleInstallKind,
                        locales = locales,
                        types = types,
                        onLocaleToggle = viewModel::setLocaleChecked,
                        onTypeToggle = viewModel::setTypeChecked,
                        includeNsfw = includeNsfw,
                        onIncludeNsfwChange = viewModel::setIncludeNsfw,
                        hasApkRepos = hasApkRepos,
                        systemInstallMode = systemInstallMode,
                        onSystemInstallModeChange = viewModel::setSystemInstallMode,
                        installPlan = installPlan,
                        installState = installState,
                        isInstallingPackages = isInstallingPackages,
                        onInstall = viewModel::installMatchingPackages,
                        onCancelInstall = viewModel::cancelInstall,
                        onSkip = {
                            viewModel.setInstallSkipped(true)
                            scope.launch { pagerState.animateScrollToPage(3) }
                        },
                    )

                    3 -> WelcomeDoneStep(
                        installState = installState,
                        skipped = installSkipped,
                        onOpenExtensionManagement = onOpenExtensionManagement,
                    )

                    4 -> WelcomePermissionsStep()

                    5 -> {
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
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    Button(
                        onClick = {
                            if (pagerState.currentPage == pagerState.pageCount - 1) {
                                onDone()
                            } else {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            }
                        },
                        enabled = !isInitializing,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (pagerState.currentPage == pagerState.pageCount - 1) R.string.done else R.string.next,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (pagerState.currentPage == pagerState.pageCount - 1) {
                                Icons.Default.Done
                            } else {
                                Icons.AutoMirrored.Filled.ArrowForward
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
                    viewModel.initializePlugins(selectedMirrorIndex, selectedRepos.toList())
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisclaimer = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    reposConfiguredEvent?.let { info ->
        val context = LocalContext.current
        val kindsLabel = info.kinds.joinToString(", ") { context.getString(it.labelResId()) }
        AlertDialog(
            onDismissRequest = { viewModel.consumeReposConfiguredEvent() },
            title = { Text(stringResource(R.string.welcome_repos_configured_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.welcome_repos_configured_message,
                        info.kinds.size,
                        kindsLabel,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.consumeReposConfiguredEvent()
                    scope.launch { pagerState.animateScrollToPage(2) }
                }) { Text(stringResource(R.string.welcome_repos_configured_next)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.consumeReposConfiguredEvent()
                    onOpenExtensionManagement()
                }) { Text(stringResource(R.string.welcome_open_extension_management)) }
            },
        )
    }

    if (installFinishedEvent == true) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeInstallFinishedEvent() },
            title = { Text(stringResource(R.string.welcome_install_done_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.welcome_install_done_message,
                        installState.completed,
                        installState.failed,
                        installState.cancelled,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.consumeInstallFinishedEvent()
                    scope.launch { pagerState.animateScrollToPage(3) }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.consumeInstallFinishedEvent()
                    onOpenExtensionManagement()
                }) { Text(stringResource(R.string.welcome_open_extension_management)) }
            },
        )
    }
}

@Composable
private fun WelcomeHero(expressive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(if (expressive) 22.dp else 14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                painter = rememberSafePainter(R.drawable.ic_welcome),
                contentDescription = null,
                modifier = Modifier.padding(12.dp).size(if (expressive) 30.dp else 26.dp),
            )
        }
        Text(
            text = stringResource(R.string.welcome_intro_app_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun WelcomeSourcesStep(
    recommendedRepos: List<UnifiedRecommendedRepository>,
    selectedRepos: MutableList<UnifiedRecommendedRepository>,
    expandedKinds: Set<UnifiedSourceKind>,
    onKindToggled: (UnifiedSourceKind) -> Unit,
    mirrorEntries: List<String>,
    selectedMirrorIndex: Int,
    onMirrorSelected: (Int) -> Unit,
    isInitializing: Boolean,
    onInitialize: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    SectionHeader(
        title = stringResource(R.string.welcome_plugins_title),
        summary = stringResource(R.string.welcome_plugins_summary),
    )
    Button(
        onClick = onInitialize,
        enabled = selectedRepos.isNotEmpty() && !isInitializing,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Icon(rememberSafePainter(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.welcome_plugins_start_btn))
    }
    if (isInitializing) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text(
        text = stringResource(R.string.welcome_sources_kinds_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        welcomeRepoKinds(recommendedRepos).forEach { kind ->
            val selectedCount = recommendedRepos.count { it.kind == kind && it in selectedRepos }
            val kindLabel = stringResource(kind.labelResId())
            FilterChip(
                selected = kind in expandedKinds,
                onClick = { onKindToggled(kind) },
                enabled = !isInitializing,
                label = {
                    Text(
                        text = if (selectedCount > 0) "$kindLabel · $selectedCount" else kindLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
    expandedKinds.forEach { kind ->
        val kindRepos = recommendedRepos.filter { it.kind == kind }
        if (kindRepos.isEmpty()) {
            return@forEach
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(kind.labelResId()),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { selectAllRepos(kindRepos, selectedRepos) }, enabled = !isInitializing) {
                Text(stringResource(R.string.select_all))
            }
            TextButton(onClick = { selectedRepos.removeAll { it.kind == kind } }, enabled = !isInitializing) {
                Text(stringResource(R.string.clear))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            kindRepos.forEach { repo ->
                RepoChip(
                    repo = repo,
                    selectedRepos = selectedRepos,
                    enabled = !isInitializing,
                )
            }
        }
    }
    MirrorDropdown(
        entries = mirrorEntries,
        selectedIndex = selectedMirrorIndex,
        onSelected = onMirrorSelected,
        enabled = !isInitializing,
    )
    TextButton(onClick = onRestoreBackup, enabled = !isInitializing) {
        Icon(rememberSafePainter(R.drawable.ic_backup_restore), contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.restore_backup))
    }
}

private fun selectAllRepos(
    kindRepos: List<UnifiedRecommendedRepository>,
    selectedRepos: MutableList<UnifiedRecommendedRepository>,
) {
    val selectedKeys = selectedRepos.mapTo(HashSet()) { repoKey(it) }
    kindRepos.filter { repoKey(it) !in selectedKeys }.forEach { selectedRepos.add(it) }
}

@Composable
private fun WelcomeBatchInstallStep(
    configuredKinds: List<UnifiedSourceKind>,
    selectedKinds: Set<UnifiedSourceKind>,
    onKindToggle: (UnifiedSourceKind, Boolean) -> Unit,
    locales: FilterProperty<Locale>,
    types: FilterProperty<ContentType>,
    onLocaleToggle: (Locale, Boolean) -> Unit,
    onTypeToggle: (ContentType, Boolean) -> Unit,
    includeNsfw: Boolean,
    onIncludeNsfwChange: (Boolean) -> Unit,
    hasApkRepos: Boolean,
    systemInstallMode: Boolean,
    onSystemInstallModeChange: (Boolean) -> Unit,
    installPlan: List<WizardInstallItem>,
    installState: WizardInstallState,
    isInstallingPackages: Boolean,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onSkip: () -> Unit,
) {
    SectionHeader(
        title = stringResource(R.string.welcome_install_step_title),
        summary = stringResource(R.string.welcome_install_step_summary),
    )

    SectionHeader(
        title = stringResource(R.string.welcome_install_kinds_title),
        summary = stringResource(R.string.welcome_install_kinds_summary),
    )
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
                FilterChip(
                    selected = kind in selectedKinds,
                    onClick = { onKindToggle(kind, kind !in selectedKinds) },
                    enabled = !isInstallingPackages,
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

    SectionHeader(
        title = stringResource(R.string.welcome_source_formats_title),
        summary = stringResource(R.string.welcome_source_formats_summary),
    )
    ContentTypeChips(types = types, onTypeToggle = onTypeToggle)

    SectionHeader(
        title = stringResource(R.string.languages),
        summary = stringResource(R.string.welcome_preferences_summary),
    )
    FilterChipGroup(
        items = locales.availableItems,
        selectedItems = locales.selectedItems,
        label = { it.getDisplayName(LocalContext.current) },
        onToggle = onLocaleToggle,
    )
    if (locales.isLoading || types.isLoading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = includeNsfw,
                role = Role.Switch,
                onValueChange = onIncludeNsfwChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.welcome_install_nsfw_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.welcome_install_nsfw_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = includeNsfw,
            onCheckedChange = null,
        )
    }

    if (hasApkRepos && selectedKinds.any { kind ->
            kind == UnifiedSourceKind.MIHON ||
                kind == UnifiedSourceKind.ANIYOMI ||
                kind == UnifiedSourceKind.IREADER ||
                kind == UnifiedSourceKind.TSUNDOKU
        }
    ) {
        SectionHeader(
            title = stringResource(R.string.welcome_install_mode_title),
            summary = stringResource(R.string.welcome_install_mode_summary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !systemInstallMode,
                onClick = { onSystemInstallModeChange(false) },
                enabled = !isInstallingPackages,
                label = { Text(stringResource(R.string.welcome_install_mode_sideload)) },
            )
            FilterChip(
                selected = systemInstallMode,
                onClick = { onSystemInstallModeChange(true) },
                enabled = !isInstallingPackages,
                label = { Text(stringResource(R.string.welcome_install_mode_system)) },
            )
        }
    }

    SectionHeader(
        title = stringResource(R.string.welcome_install_section_title),
        summary = stringResource(R.string.welcome_install_section_summary),
    )
    when {
        isInstallingPackages || installState.phase == WizardInstallPhase.FINISHED -> {
            WizardInstallProgressPanel(
                installState = installState,
                isInstalling = isInstallingPackages,
                onCancel = onCancelInstall,
            )
        }
        installPlan.isEmpty() -> {
            Text(
                text = stringResource(R.string.welcome_install_plan_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            val planSummary = installPlan
                .groupBy { it.kind }
                .map { (kind, items) -> "${stringResource(kind.labelResId())} ${items.size}" }
                .joinToString(" · ")
            Text(
                text = stringResource(R.string.welcome_install_plan_summary, installPlan.size, planSummary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onInstall,
                enabled = !isInstallingPackages,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = rememberSafePainter(R.drawable.ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.welcome_install_start))
            }
        }
    }
    if (installState.phase != WizardInstallPhase.FINISHED) {
        TextButton(
            onClick = onSkip,
            enabled = !isInstallingPackages,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.welcome_install_skip))
        }
    }
}

@Composable
private fun WelcomeIntroStep(onContinue: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.welcome_intro_app_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.welcome_intro_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Text(
            text = stringResource(R.string.welcome_intro_concepts_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IntroConceptCard(
            title = stringResource(R.string.welcome_intro_repo_label),
            summary = stringResource(R.string.welcome_intro_repo_body),
        )
        IntroConceptCard(
            title = stringResource(R.string.welcome_intro_extension_label),
            summary = stringResource(R.string.welcome_intro_extension_body),
        )
        IntroConceptCard(
            title = stringResource(R.string.welcome_intro_source_label),
            summary = stringResource(R.string.welcome_intro_source_body),
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
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

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.welcome_intro_continue))
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun IntroConceptCard(title: String, summary: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun WelcomeDoneStep(
    installState: WizardInstallState,
    skipped: Boolean,
    onOpenExtensionManagement: () -> Unit,
) {
    SectionHeader(
        title = stringResource(R.string.welcome_done_title),
        summary = stringResource(R.string.welcome_done_summary),
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (skipped) {
                Text(
                    text = stringResource(R.string.welcome_done_skipped),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.welcome_done_summary_text,
                        installState.completed,
                        installState.failed,
                        installState.cancelled,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.welcome_done_json_note),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    SectionHeader(
        title = stringResource(R.string.welcome_done_advice_title),
        summary = stringResource(R.string.welcome_done_advice_summary),
    )
    Text(
        text = stringResource(R.string.welcome_done_advice_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    TextButton(
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

@Composable
private fun WelcomePermissionsStep() {
    val context = LocalContext.current
    SectionHeader(
        title = stringResource(R.string.welcome_permissions_title),
        summary = stringResource(R.string.welcome_permissions_summary),
    )

    // Notifications
    val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
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

    // Query all packages (declared in the manifest as a normal permission; granted at install time)
    WelcomePermissionRow(
        icon = rememberSafePainter(R.drawable.ic_source_builtin),
        title = stringResource(R.string.welcome_permissions_packages_title),
        summary = stringResource(R.string.welcome_permissions_packages_summary),
        granted = true,
        actionLabel = null,
        onAction = null,
    )

    // Battery optimization / background survival
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
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
            if (isInstalling) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.welcome_install_cancel))
                }
            }
        }
        LinearProgressIndicator(
            progress = { installState.progressPercent / 100f },
            modifier = Modifier.fillMaxWidth(),
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.state == WizardPackageState.DOWNLOADING && item.progressPercent != null) {
                                LinearProgressIndicator(
                                    progress = { item.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Text(
                            text = stringResource(item.state.labelResId()),
                            style = MaterialTheme.typography.labelSmall,
                            color = item.state.color(),
                        )
                    }
                }
            }
        }
    }
}

private fun WizardPackageState.labelResId(): Int {
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
    SectionHeader(
        title = stringResource(R.string.welcome_spaces_title),
        summary = stringResource(R.string.welcome_spaces_summary),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = spacesEnabled,
                role = Role.Switch,
                onValueChange = onSpacesEnabledChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.spaces_enabled),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.spaces_enabled_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = spacesEnabled,
            onCheckedChange = null,
        )
    }
    if (spacesEnabled) {
        Text(
            text = stringResource(R.string.space_switcher_position),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpaceSwitcherPosition.entries.forEach { position ->
                FilterChip(
                    selected = position == spaceSwitcherPosition,
                    onClick = { onSpaceSwitcherPositionChange(position) },
                    label = { Text(stringResource(position.labelResId())) },
                )
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
    SectionHeader(
        title = stringResource(R.string.welcome_appearance_title),
        summary = stringResource(R.string.welcome_appearance_summary),
    )
    Text(
        text = stringResource(R.string.interface_style),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InterfaceStyle.selectableEntries.forEach { style ->
            FilterChip(
                selected = style == interfaceStyle,
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
                    FilterChip(
                        selected = preset == glassPreset,
                        onClick = { onGlassPresetChange(preset) },
                        label = { Text(stringResource(preset.titleRes)) },
                    )
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.pref_list_to_details_transition),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.pref_list_to_details_transition_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListToDetailsTransition.entries.forEach { option ->
                FilterChip(
                    selected = option == listToDetailsTransition,
                    onClick = { onListToDetailsTransitionChange(option) },
                    label = { Text(stringResource(option.labelRes())) },
                )
            }
        }
    }
    WelcomeSwitchRow(
        title = stringResource(R.string.pref_panorama_animation),
        summary = stringResource(R.string.pref_panorama_animation_summary),
        checked = panoramaAnimationEnabled,
        onCheckedChange = onPanoramaAnimationChange,
    )
}

@Composable
private fun WelcomeSliderRow(
    title: String,
    summary: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                text = "$value%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KototoroSlider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 100)) },
            modifier = Modifier.fillMaxWidth(),
            valueRange = 0f..100f,
            steps = 19,
        )
    }
}

@Composable
private fun WelcomeSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
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
private fun SectionHeader(title: String, summary: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RepoChip(
    repo: UnifiedRecommendedRepository,
    selectedRepos: MutableList<UnifiedRecommendedRepository>,
    enabled: Boolean,
) {
    val key = repoKey(repo)
    val selected = selectedRepos.any { repoKey(it) == key }
    FilterChip(
        selected = selected,
        onClick = {
            if (selected) {
                selectedRepos.removeAll { repoKey(it) == key }
            } else {
                selectedRepos.add(repo)
            }
        },
        enabled = enabled,
        label = { Text(repo.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
    )
}

@Composable
private fun MirrorDropdown(
    entries: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            enabled = enabled && entries.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${stringResource(R.string.pref_github_mirror)}: ${entries.getOrNull(selectedIndex).orEmpty()}",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ContentTypeChips(
    types: FilterProperty<ContentType>,
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
    )
}

@Composable
private fun <T> FilterChipGroup(
    items: List<T>,
    selectedItems: Set<T>,
    label: @Composable (T) -> String,
    onToggle: (T, Boolean) -> Unit,
    leadingIcon: ((T) -> Int)? = null,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val selected = item in selectedItems
            FilterChip(
                selected = selected,
                onClick = { onToggle(item, !selected) },
                label = { Text(label(item)) },
                leadingIcon = when {
                    leadingIcon != null -> {
                        { Icon(rememberSafePainter(leadingIcon(item)), contentDescription = null, modifier = Modifier.size(18.dp)) }
                    }
                    selected -> {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    }
                    else -> null
                },
            )
        }
    }
}
