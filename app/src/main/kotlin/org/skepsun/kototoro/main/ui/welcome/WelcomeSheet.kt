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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.ListToDetailsTransition
import org.skepsun.kototoro.core.prefs.SpaceSwitcherPosition
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer
import org.skepsun.kototoro.core.ui.glass.GlassTuningController
import org.skepsun.kototoro.core.ui.glass.rememberGlassTuning
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.settings.compose.glass.GlassPreset

/**
 * The setup wizard.
 *
 * Since the wizard no longer imports repositories or batch-installs extensions, it is a short
 * four-page walkthrough: intro → permissions → appearance/spaces → done. Kototoro never ships or
 * curates third-party repositories (see [org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepositories]),
 * so users add their own sources explicitly afterwards in Settings › Content sources.
 */
private const val WIZARD_PAGE_INTRO = 0
private const val WIZARD_PAGE_PERMISSIONS = 1
private const val WIZARD_PAGE_APPEARANCE = 2
private const val WIZARD_PAGE_DONE = 3
private const val WIZARD_PAGE_COUNT = 4

/** Maximum content width so the wizard stays readable on tablets / landscape. */
private val WIZARD_CONTENT_MAX_WIDTH = 680.dp

@Composable
internal fun WelcomeRoute(
    onDismissRequest: () -> Unit,
    onRestoreBackup: (Uri) -> Unit,
    onOpenDocumentUnsupported: () -> Unit = {},
    onOpenExtensionManagement: () -> Unit = {},
    viewModel: WelcomeViewModel = hiltViewModel(),
) {
    val backupSelectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(onRestoreBackup)
    }
    // Custom Dialog + AnchoredDraggableState sheet (mihon pattern): only the
    // top drag handle can move the panel (pull down to dismiss); the content
    // area just scrolls and can never drag or dismiss the wizard.
    WelcomeBottomSheet(onDismissRequest = onDismissRequest) {
        KototoroTheme {
            WelcomeContent(
                viewModel = viewModel,
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
    onRestoreBackup: () -> Unit,
    onOpenExtensionManagement: () -> Unit = {},
    onDone: () -> Unit,
) {
    val interfaceStyle by viewModel.interfaceStyle.collectAsStateWithLifecycle()
    val listToDetailsTransition by viewModel.listToDetailsTransition.collectAsStateWithLifecycle()
    val panoramaAnimationEnabled by viewModel.panoramaAnimationEnabled.collectAsStateWithLifecycle()
    val spacesEnabled by viewModel.spacesEnabled.collectAsStateWithLifecycle()
    val spaceSwitcherPosition by viewModel.spaceSwitcherPosition.collectAsStateWithLifecycle()
    val savedCurrentPage by viewModel.currentPage.collectAsStateWithLifecycle()

    // Glass finish preset state, shared with Settings → Appearance → glass tuner.
    val appContext = LocalContext.current.applicationContext
    val glassSettings = remember(appContext) { AppSettings(appContext) }
    val glassTuning = rememberGlassTuning(glassSettings)
    val glassTuningController = remember { GlassTuningController(glassSettings) }
    val activeGlassPreset = GlassPreset.entries.firstOrNull { it.matches(glassTuning) }
    val pagerState = rememberPagerState(initialPage = savedCurrentPage, pageCount = { WIZARD_PAGE_COUNT })
    val scope = rememberCoroutineScope()

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    LaunchedEffect(pagerState.currentPage) {
        viewModel.setCurrentPage(pagerState.currentPage)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
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

                    WIZARD_PAGE_DONE -> WelcomeDoneStep(
                        onRestoreBackup = onRestoreBackup,
                        onOpenExtensionManagement = onOpenExtensionManagement,
                    )
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
                WizardActionBar(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    back = {
                        IconButton(
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            },
                            enabled = pagerState.currentPage > 0,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    progress = {
                        Row(horizontalArrangement = Arrangement.spacedBy(WizardPageDotsSlotGap)) {
                            repeat(pagerState.pageCount) { index ->
                                Box(
                                    modifier = Modifier.size(
                                        width = WizardPageDotsSlotWidth,
                                        height = WizardPageDotsHeight,
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (index == pagerState.currentPage) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        modifier = Modifier.size(
                                            width = if (index == pagerState.currentPage) {
                                                WizardPageDotsSlotWidth
                                            } else {
                                                WizardPageDotsHeight
                                            },
                                            height = WizardPageDotsHeight,
                                        ),
                                    ) {}
                                }
                            }
                        }
                    },
                    action = {
                        WizardPrimaryButton(
                            page = pagerState.currentPage,
                            onNext = {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            },
                            onDone = onDone,
                        )
                    },
                )
            }
        }
    }
}

/**
 * The setup-wizard action bar: `[back] [page dots] [primary action]`.
 *
 * Every child is measured *before* anything is allocated, so the primary action
 * always keeps its natural width. It used to be the last child of a plain `Row`
 * and therefore absorbed the entire width deficit: on narrow screens (or under a
 * large font scale) the label wrapped inside the fixed-height pill and read as
 * clipped text. When the leftover slot cannot hold the dot rail the rail steps
 * out — `WizardPageHeader` already carries "Step N of M" — and the pill hugs the
 * two items that remain instead of stretching around an empty hole.
 */
@Composable
private fun WizardActionBar(
    back: @Composable () -> Unit,
    progress: @Composable () -> Unit,
    action: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            back()
            progress()
            action()
        },
    ) { measurables, constraints ->
        val spacing = WizardActionBarItemSpacing.roundToPx()
        val availableWidth = constraints.maxWidth
        val freeHeight = constraints.copy(minHeight = 0)
        val backPlaceable = measurables[0].measure(freeHeight)
        // The action is the only child allowed to be constrained: an over-long
        // localized label ellipsizes (see WizardPrimaryButtonContent) rather than
        // push the back button out of the bar.
        val actionPlaceable = measurables[2].measure(
            freeHeight.copy(
                maxWidth = (availableWidth - backPlaceable.width - spacing).coerceAtLeast(0),
            ),
        )
        // Unbounded, so the rail reports the width the fit decision needs.
        val progressPlaceable = measurables[1].measure(Constraints())
        val spec = resolveWizardActionBar(
            availableWidth = availableWidth.toDp(),
            backButtonWidth = backPlaceable.width.toDp(),
            dotsWidth = progressPlaceable.width.toDp(),
            actionWidth = actionPlaceable.width.toDp(),
        )
        val width = spec.width.roundToPx()
        val showProgress = spec.progress == WizardProgressPresentation.Roomy
        val height = maxOf(
            backPlaceable.height,
            actionPlaceable.height,
            if (showProgress) progressPlaceable.height else 0,
        )
        layout(width, height) {
            if (showProgress) {
                val slotStart = backPlaceable.width + spacing
                val slotEnd = (width - actionPlaceable.width - spacing).coerceAtLeast(slotStart)
                progressPlaceable.placeRelative(
                    x = slotStart + (slotEnd - slotStart - progressPlaceable.width) / 2,
                    y = (height - progressPlaceable.height) / 2,
                )
            }
            backPlaceable.placeRelative(x = 0, y = (height - backPlaceable.height) / 2)
            actionPlaceable.placeRelative(
                x = width - actionPlaceable.width,
                y = (height - actionPlaceable.height) / 2,
            )
        }
    }
}

/**
 * The single primary action of the wizard. Its meaning follows the current
 * page so the content area never carries a competing main button.
 */
@Composable
private fun WizardPrimaryButton(
    page: Int,
    onNext: () -> Unit,
    onDone: () -> Unit,
) {
    val action: WizardPrimaryAction? = when (page) {
        WIZARD_PAGE_DONE -> WizardPrimaryAction(
            label = stringResource(R.string.done),
            iconVector = Icons.Default.Done,
            enabled = true,
            onClick = onDone,
        )
        else -> WizardPrimaryAction(
            label = stringResource(R.string.next),
            iconVector = Icons.AutoMirrored.Filled.ArrowForward,
            enabled = true,
            onClick = onNext,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
    // The pill's height is fixed, so a wrapping label would be cut off mid-line.
    // One line, ellipsized: same guard KototoroBottomNav uses for its labels
    // (see also `WizardActionBar`, which keeps this label from being squeezed).
    Text(
        text = label,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
    if (iconVector != null) {
        Spacer(Modifier.width(8.dp))
        Icon(iconVector, contentDescription = null, modifier = Modifier.size(18.dp))
    } else if (iconPainter != null) {
        Spacer(Modifier.width(8.dp))
        Icon(iconPainter, contentDescription = null, modifier = Modifier.size(18.dp))
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
    onRestoreBackup: () -> Unit,
    onOpenExtensionManagement: () -> Unit,
) {
    WizardPageHeader(
        step = WIZARD_PAGE_DONE + 1,
        totalSteps = WIZARD_PAGE_COUNT,
        title = stringResource(R.string.welcome_done_title_finished),
        summary = stringResource(R.string.welcome_done_summary_finished),
        icon = rememberSafePainter(R.drawable.ic_check),
        prominent = true,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    WizardSectionCard {
        Text(
            text = stringResource(R.string.welcome_done_body_finished),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
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
        title = stringResource(R.string.restore_backup),
        summary = stringResource(R.string.welcome_restore_summary),
    ) {
        FilledTonalButton(
            onClick = onRestoreBackup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = rememberSafePainter(R.drawable.ic_backup_restore),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.restore_backup))
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
