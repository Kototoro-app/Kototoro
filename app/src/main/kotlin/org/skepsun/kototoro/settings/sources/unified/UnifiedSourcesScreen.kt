package org.skepsun.kototoro.settings.sources.unified


import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.settings.compose.SettingsContentHorizontalPadding
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepo
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton

enum class UnifiedToolbarFilterPanel {
    LANGUAGE,
    MORE,
}

internal const val UNIFIED_SOURCES_TAB_SOURCES = 0
internal const val UNIFIED_SOURCES_TAB_REPOSITORIES = 1
internal const val UNIFIED_SOURCES_TAB_PACKAGES = 2
internal const val UNIFIED_SOURCES_TAB_COUNT = 3
internal val unifiedCardListPadding = PaddingValues(start = SettingsContentHorizontalPadding, top = 8.dp, end = SettingsContentHorizontalPadding, bottom = 8.dp)
internal val unifiedCardContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
internal val unifiedCardSpacing = 8.dp

internal data class UnifiedSourcesVisualStyle(
    val chipShape: RoundedCornerShape,
    val cardShape: RoundedCornerShape,
    val rowShape: RoundedCornerShape,
    val rowHorizontalPadding: androidx.compose.ui.unit.Dp,
    val rowVerticalPadding: androidx.compose.ui.unit.Dp,
    val iconShape: RoundedCornerShape,
    val cardElevation: androidx.compose.ui.unit.Dp,
)

@Composable
internal fun rememberUnifiedSourcesVisualStyle(): UnifiedSourcesVisualStyle {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    return UnifiedSourcesVisualStyle(
        chipShape = RoundedCornerShape(if (expressive) 16.dp else 8.dp),
        cardShape = RoundedCornerShape(if (expressive) 22.dp else 12.dp),
        rowShape = RoundedCornerShape(if (expressive) 20.dp else 0.dp),
        rowHorizontalPadding = if (expressive) 8.dp else 0.dp,
        rowVerticalPadding = if (expressive) 3.dp else 0.dp,
        iconShape = RoundedCornerShape(if (expressive) 12.dp else 8.dp),
        cardElevation = if (expressive || isIosStyle) 0.dp else 1.dp,
    )
}

private sealed interface UnifiedSourcesDialogState {
    data object AddRepositoryKind : UnifiedSourcesDialogState
    data class AddRepositoryMode(
        val kind: UnifiedSourceKind,
        val prefillUrl: String? = null,
        val prefillTitle: String? = null,
    ) : UnifiedSourcesDialogState

    data class UrlInput(
        val kind: UnifiedSourceKind,
        val prefillUrl: String? = null,
        val prefillTitle: String? = null,
    ) : UnifiedSourcesDialogState

    data class InlineInput(
        val kind: UnifiedSourceKind,
        val prefillTitle: String? = null,
    ) : UnifiedSourcesDialogState

    data class TrustRepository(val repo: ExternalExtensionRepo) : UnifiedSourcesDialogState
    data class DeleteRepository(val repository: UnifiedSourceRepositoryItem) : UnifiedSourcesDialogState
    data class DeleteSelectedSources(val plan: UnifiedSelectedSourceDeletePlan) : UnifiedSourcesDialogState
    data class SetSelectedSourcesNsfw(
        val sourceIds: Set<String>,
    ) : UnifiedSourcesDialogState
    data class SetFilteredSourcesEnabled(
        val sourceIds: Set<String>,
        val enabled: Boolean,
    ) : UnifiedSourcesDialogState
    data class PackageDetails(val item: UnifiedSourcePackageItem) : UnifiedSourcesDialogState
    data class InstallChoice(
        val kind: UnifiedSourceKind,
        val name: String,
        val sourceCount: Int,
        val packageRequest: UnifiedSourcesEvent.ConfirmPackageInstall? = null,
        val importAction: UnifiedThirdPartyAction? = null,
    ) : UnifiedSourcesDialogState
    data class InstallError(val message: String) : UnifiedSourcesDialogState
    data class ThirdPartyDisclaimer(val action: UnifiedThirdPartyAction) : UnifiedSourcesDialogState
}

internal sealed interface UnifiedThirdPartyAction {
    data class AddRepositoryUrl(
        val kind: UnifiedSourceKind,
        val url: String,
        val title: String? = null,
    ) : UnifiedThirdPartyAction

    data class AddInlineRepository(
        val kind: UnifiedSourceKind,
        val content: String,
        val title: String? = null,
    ) : UnifiedThirdPartyAction

    data class OpenRepositoryFile(val kind: UnifiedSourceKind) : UnifiedThirdPartyAction
    data object OpenLocalJar : UnifiedThirdPartyAction
}

internal data class UnifiedSelectedSourceDeletePlan(
    val deletablePackageIds: List<String>,
    val deletablePackageNames: List<String>,
    val skippedJarPackageNames: List<String>,
)

internal enum class SelectedSourcesNsfwAction {
    NONE,
    SET_NSFW,
    SET_SFW,
    CHOOSE,
}

/**
 * Decides what the NSFW toggle chip should do for the current selection:
 * set NSFW when everything is non-NSFW, set SFW when everything is NSFW,
 * and ask the user when the selection mixes both.
 */
internal fun resolveSelectedSourcesNsfwAction(
    nsfwCount: Int,
    selectedCount: Int,
): SelectedSourcesNsfwAction = when {
    selectedCount <= 0 -> SelectedSourcesNsfwAction.NONE
    nsfwCount <= 0 -> SelectedSourcesNsfwAction.SET_NSFW
    nsfwCount >= selectedCount -> SelectedSourcesNsfwAction.SET_SFW
    else -> SelectedSourcesNsfwAction.CHOOSE
}

@Composable
fun UnifiedSourcesRoute(
    onBrowseSource: (UnifiedSourceItem) -> Unit,
    onOpenSourceSettings: (UnifiedSourceItem) -> Unit,
    onOpenRepositoryFile: (UnifiedSourceKind, Boolean) -> Unit,
    onOpenLocalJarPicker: () -> Unit,
    onStartInstall: (Intent) -> Unit,
    onStartUninstall: (Intent) -> Unit,
    searchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    activePanel: UnifiedToolbarFilterPanel?,
    onActivePanelChange: (UnifiedToolbarFilterPanel?) -> Unit,
    initialAddRepositoryKind: UnifiedSourceKind? = null,
    initialAddRepositoryUrl: String? = null,
    modifier: Modifier = Modifier,
    viewModel: UnifiedSourcesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val updateAllInProgress by viewModel.updateAllInProgress.collectAsStateWithLifecycle()
    var activeDialog by remember { mutableStateOf<UnifiedSourcesDialogState?>(null) }
    var initialRepositoryHandled by rememberSaveable { mutableStateOf(false) }
    var selectedSourceIdList by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selectedSourceIds = remember(selectedSourceIdList) { selectedSourceIdList.toSet() }

    fun proceedThirdPartyAction(action: UnifiedThirdPartyAction, enableImportedSources: Boolean = true) {
        when (action) {
            is UnifiedThirdPartyAction.AddRepositoryUrl -> {
                viewModel.addRepositoryFromUrl(action.kind, action.url, action.title, enableImportedSources)
            }
            is UnifiedThirdPartyAction.AddInlineRepository -> {
                viewModel.addRepositoryFromInline(action.kind, action.content, action.title, enableImportedSources)
            }
            is UnifiedThirdPartyAction.OpenRepositoryFile -> {
                onOpenRepositoryFile(action.kind, enableImportedSources)
            }
            UnifiedThirdPartyAction.OpenLocalJar -> {
                onOpenLocalJarPicker()
            }
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.events.collect { event ->
            when (event) {
                is UnifiedSourcesEvent.Message -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is UnifiedSourcesEvent.InstallFailed -> {
                    activeDialog = UnifiedSourcesDialogState.InstallError(event.message)
                }
                is UnifiedSourcesEvent.ConfirmPackageInstall -> {
                    activeDialog = UnifiedSourcesDialogState.InstallChoice(
                        kind = event.kind,
                        name = event.name,
                        sourceCount = event.sourceCount,
                        packageRequest = event,
                    )
                }
                is UnifiedSourcesEvent.TrustExternalRepository -> {
                    activeDialog = UnifiedSourcesDialogState.TrustRepository(event.repo)
                }
                is UnifiedSourcesEvent.StartInstall -> onStartInstall(event.intent)
                is UnifiedSourcesEvent.StartUninstall -> onStartUninstall(event.intent)
                is UnifiedSourcesEvent.PackageStateDetails -> {
                    activeDialog = UnifiedSourcesDialogState.PackageDetails(event.item)
                }
            }
        }
    }

    LaunchedEffect(initialAddRepositoryKind, initialAddRepositoryUrl, initialRepositoryHandled) {
        if (initialRepositoryHandled || initialAddRepositoryKind == null) {
            return@LaunchedEffect
        }
        activeDialog = UnifiedSourcesDialogState.UrlInput(
            kind = initialAddRepositoryKind,
            prefillUrl = initialAddRepositoryUrl,
        )
        initialRepositoryHandled = true
    }

    fun confirmSetFilteredSourcesEnabled(enabled: Boolean) {
        val sourceIds = (state as? UnifiedSourcesUiState.Ready)
            ?.sources
            .orEmpty()
            .mapTo(LinkedHashSet()) { it.id }
        if (sourceIds.isNotEmpty()) {
            activeDialog = UnifiedSourcesDialogState.SetFilteredSourcesEnabled(sourceIds, enabled)
        }
    }

    UnifiedSourcesScreen(
        state = state,
        isLoading = isLoading,
        updateAllInProgress = updateAllInProgress,
        searchActive = searchActive,
        onSearchClick = { onSearchActiveChange(true) },
        onSearchClose = {
            onSearchActiveChange(false)
            viewModel.setSearchQuery("")
        },
        onSearchQueryChange = viewModel::setSearchQuery,
        onKindClick = viewModel::setKindFilter,
        onContentTypeClick = viewModel::setContentTypeFilter,
        onLanguageFilterClick = { onActivePanelChange(UnifiedToolbarFilterPanel.LANGUAGE) },
        onMoreFiltersClick = { onActivePanelChange(UnifiedToolbarFilterPanel.MORE) },
        onSourceEnabledChange = viewModel::setSourceEnabled,
        onEnableAllSources = { confirmSetFilteredSourcesEnabled(true) },
        onDisableAllSources = { confirmSetFilteredSourcesEnabled(false) },
        selectedSourceIds = selectedSourceIds,
        onSourceSelectionChange = { selectedSourceIdList = it.toList() },
        onSelectAllVisibleSources = {
            selectedSourceIdList = (state as? UnifiedSourcesUiState.Ready)
                ?.sources
                .orEmpty()
                .map { it.id }
        },
        onClearSourceSelection = { selectedSourceIdList = emptyList() },
        onEnableSelectedSources = {
            viewModel.setSourcesEnabled(selectedSourceIds, true)
            selectedSourceIdList = emptyList()
        },
        onDisableSelectedSources = {
            viewModel.setSourcesEnabled(selectedSourceIds, false)
            selectedSourceIdList = emptyList()
        },
        onTestSelectedSources = {
            viewModel.testSources(selectedSourceIds)
            selectedSourceIdList = emptyList()
        },
        onToggleSelectedSourcesNsfw = {
            val readyStateForNsfw = state as? UnifiedSourcesUiState.Ready ?: return@UnifiedSourcesScreen
            val selected = readyStateForNsfw.sources.filter { it.id in selectedSourceIds }
            when (resolveSelectedSourcesNsfwAction(selected.count { it.isNsfw }, selected.size)) {
                SelectedSourcesNsfwAction.NONE -> Unit
                SelectedSourcesNsfwAction.SET_NSFW -> {
                    viewModel.setSourcesNsfw(selectedSourceIds, true)
                    selectedSourceIdList = emptyList()
                }

                SelectedSourcesNsfwAction.SET_SFW -> {
                    viewModel.setSourcesNsfw(selectedSourceIds, false)
                    selectedSourceIdList = emptyList()
                }

                SelectedSourcesNsfwAction.CHOOSE -> {
                    activeDialog = UnifiedSourcesDialogState.SetSelectedSourcesNsfw(selectedSourceIds)
                }
            }
        },
        onDeleteSelectedSources = {
            val readyStateForDelete = state as? UnifiedSourcesUiState.Ready ?: return@UnifiedSourcesScreen
            activeDialog = UnifiedSourcesDialogState.DeleteSelectedSources(
                readyStateForDelete.buildDeletePlan(selectedSourceIds),
            )
        },
        onSourcePinnedChange = viewModel::setSourcePinned,
        onBrowseSource = onBrowseSource,
        onOpenSourceSettings = onOpenSourceSettings,
        onAddRepository = { preset ->
            activeDialog = if (preset != null) {
                UnifiedSourcesDialogState.UrlInput(
                    kind = preset.kind,
                    prefillUrl = preset.url,
                    prefillTitle = preset.name,
                )
            } else {
                UnifiedSourcesDialogState.AddRepositoryKind
            }
        },
        onRefreshRepository = { item -> viewModel.refreshRepository(item.id) },
        onDeleteRepository = { item -> activeDialog = UnifiedSourcesDialogState.DeleteRepository(item) },
        onUpdateAllPackages = viewModel::onUpdateAllPackagesAction,
        onPackagePrimaryAction = viewModel::onPackagePrimaryAction,
        onPackageSystemInstall = viewModel::installPackageWithSystemInstaller,
        onPackageUninstall = viewModel::uninstallPackage,
        onPackageCancelInstall = viewModel::cancelPackageInstall,
        onImportLocalJar = {
            activeDialog = UnifiedSourcesDialogState.ThirdPartyDisclaimer(UnifiedThirdPartyAction.OpenLocalJar)
        },
        onPullRefresh = { tab ->
            when (tab) {
                UNIFIED_SOURCES_TAB_SOURCES -> viewModel.refreshInstalledSources()
                UNIFIED_SOURCES_TAB_REPOSITORIES -> viewModel.refreshRepositories()
                UNIFIED_SOURCES_TAB_PACKAGES -> viewModel.refreshPackages()
            }
        },
        modifier = modifier,
    )

    val readyState = state as? UnifiedSourcesUiState.Ready
    when (activePanel) {
        UnifiedToolbarFilterPanel.LANGUAGE -> if (readyState != null) {
            UnifiedLanguageFilterDialog(
                languages = readyState.availableLanguages,
                selectedLanguages = readyState.filters.languages,
                onDismiss = { onActivePanelChange(null) },
                onLanguageClick = viewModel::toggleLanguage,
                onApplyPreferredLanguages = viewModel::applyPreferredLanguages,
                onClear = viewModel::clearLanguages,
            )
        }
        UnifiedToolbarFilterPanel.MORE -> if (readyState != null) {
            UnifiedFilterGroupDialog(
                title = stringResource(R.string.more_filters),
                onDismiss = { onActivePanelChange(null) },
                onClear = viewModel::clearFilters,
            ) {
                FilterSection(title = stringResource(R.string.status)) {
                    items(UnifiedEnabledFilter.entries) { filter ->
                        CompactFilterChip(
                            selected = readyState.filters.enabledFilter == filter,
                            onClick = { viewModel.setEnabledFilter(filter) },
                            text = filter.displayLabel(),
                        )
                    }
                }
                FilterSection(title = stringResource(R.string.availability_filter_title)) {
                    items(UnifiedAvailabilityFilter.entries) { filter ->
                        CompactFilterChip(
                            selected = readyState.filters.availabilityFilter == filter,
                            onClick = { viewModel.setAvailabilityFilter(filter) },
                            text = filter.displayLabel(),
                        )
                    }
                }
                FilterSection(title = stringResource(R.string.source_test_availability_filter_title)) {
                    items(UnifiedTestAvailabilityFilter.entries) { filter ->
                        CompactFilterChip(
                            selected = readyState.filters.testAvailabilityFilter == filter,
                            onClick = { viewModel.setTestAvailabilityFilter(filter) },
                            text = filter.displayLabel(),
                        )
                    }
                }
                FilterSection(title = stringResource(R.string.nsfw_filter_title)) {
                    items(UnifiedNsfwFilter.entries) { filter ->
                        CompactFilterChip(
                            selected = readyState.filters.nsfwFilter == filter,
                            onClick = { viewModel.setNsfwFilter(filter) },
                            text = filter.displayLabel(),
                        )
                    }
                }
                FilterSection(title = stringResource(R.string.repository_source)) {
                    items(readyState.availableLocationTypes) { type ->
                        CompactFilterChip(
                            selected = type in readyState.filters.locationTypes,
                            onClick = { viewModel.toggleLocationType(type) },
                            text = type.displayLabel(),
                        )
                    }
                }
            }
        }
        null -> Unit
    }

    when (val dialog = activeDialog) {
            UnifiedSourcesDialogState.AddRepositoryKind -> UnifiedSelectionDialog(
                title = stringResource(R.string.add_repository),
            options = listOf(
                UnifiedSourceKind.CLOUDSTREAM,
                UnifiedSourceKind.LEGADO,
                UnifiedSourceKind.TVBOX,
                UnifiedSourceKind.LNREADER,
                UnifiedSourceKind.JAR,
                UnifiedSourceKind.MIHON,
                UnifiedSourceKind.ANIYOMI,
                UnifiedSourceKind.IREADER,
                UnifiedSourceKind.JS,
            ),
                optionLabel = { kind -> context.getString(kind.dialogLabelResId()) },
            onDismiss = { activeDialog = null },
            onSelected = { kind ->
                activeDialog = if (kind.supportsJsonImport()) {
                    UnifiedSourcesDialogState.AddRepositoryMode(kind)
                } else {
                    UnifiedSourcesDialogState.UrlInput(kind = kind)
                }
            },
        )

            is UnifiedSourcesDialogState.AddRepositoryMode -> UnifiedSelectionDialog(
                title = stringResource(
                    R.string.add_repository_with_kind,
                    stringResource(dialog.kind.dialogLabelResId()),
                ),
                options = listOf(R.string.remote_url, R.string.local_file, R.string.paste_content),
                optionLabel = { context.getString(it) },
                onDismiss = { activeDialog = null },
                onSelected = { mode ->
                    activeDialog = when (mode) {
                        R.string.local_file -> UnifiedSourcesDialogState.ThirdPartyDisclaimer(
                            UnifiedThirdPartyAction.OpenRepositoryFile(dialog.kind),
                        )
                        R.string.paste_content -> UnifiedSourcesDialogState.InlineInput(
                            kind = dialog.kind,
                            prefillTitle = dialog.prefillTitle,
                        )
                    else -> UnifiedSourcesDialogState.UrlInput(
                        kind = dialog.kind,
                        prefillUrl = dialog.prefillUrl,
                        prefillTitle = dialog.prefillTitle,
                    )
                }
            },
        )

        is UnifiedSourcesDialogState.UrlInput -> UnifiedRepositoryUrlDialog(
            kind = dialog.kind,
            initialUrl = dialog.prefillUrl.orEmpty(),
            onDismiss = { activeDialog = null },
            onConfirm = { url ->
                activeDialog = UnifiedSourcesDialogState.ThirdPartyDisclaimer(
                    UnifiedThirdPartyAction.AddRepositoryUrl(dialog.kind, url, dialog.prefillTitle),
                )
            },
        )

        is UnifiedSourcesDialogState.InlineInput -> UnifiedInlineRepositoryDialog(
            kind = dialog.kind,
            onDismiss = { activeDialog = null },
            onConfirm = { content ->
                activeDialog = UnifiedSourcesDialogState.ThirdPartyDisclaimer(
                    UnifiedThirdPartyAction.AddInlineRepository(dialog.kind, content, dialog.prefillTitle),
                )
            },
        )

        is UnifiedSourcesDialogState.ThirdPartyDisclaimer -> UnifiedDisclaimerDialog(
            onDismiss = { activeDialog = null },
            onConfirm = {
                val kind = dialog.action.installKindOrNull()
                if (kind == null) {
                    proceedThirdPartyAction(dialog.action)
                    activeDialog = null
                } else {
                    when (viewModel.getInstallPolicy(kind)) {
                        ExtensionInstallPolicy.ASK_EVERY_TIME -> {
                            activeDialog = UnifiedSourcesDialogState.InstallChoice(
                                kind = kind,
                                name = context.getString(kind.dialogLabelResId()),
                                sourceCount = 0,
                                importAction = dialog.action,
                            )
                        }
                        ExtensionInstallPolicy.INSTALL_ONLY -> {
                            proceedThirdPartyAction(dialog.action, enableImportedSources = false)
                            activeDialog = null
                        }
                        ExtensionInstallPolicy.INSTALL_AND_ENABLE -> {
                            proceedThirdPartyAction(dialog.action, enableImportedSources = true)
                            activeDialog = null
                        }
                    }
                }
            },
        )

        is UnifiedSourcesDialogState.InstallChoice -> UnifiedInstallChoiceDialog(
            kind = dialog.kind,
            name = dialog.name,
            sourceCount = dialog.sourceCount,
            onDismiss = { activeDialog = null },
            onChoice = { policy, remember ->
                dialog.packageRequest?.let { request ->
                    viewModel.confirmPackageInstall(
                        packageId = request.packageId,
                        mode = request.mode,
                        policy = policy,
                        remember = remember,
                    )
                }
                dialog.importAction?.let { action ->
                    if (remember) {
                        viewModel.setInstallPolicy(dialog.kind, policy)
                    }
                    proceedThirdPartyAction(
                        action,
                        enableImportedSources = policy == ExtensionInstallPolicy.INSTALL_AND_ENABLE,
                    )
                }
                activeDialog = null
            },
        )

        is UnifiedSourcesDialogState.DeleteRepository -> UnifiedDeleteRepositoryDialog(
            repository = dialog.repository,
            onDismiss = { activeDialog = null },
            onConfirm = {
                viewModel.deleteRepository(dialog.repository.id)
                activeDialog = null
            },
        )

        is UnifiedSourcesDialogState.SetFilteredSourcesEnabled -> UnifiedSetFilteredSourcesEnabledDialog(
            enabled = dialog.enabled,
            sourceCount = dialog.sourceIds.size,
            onDismiss = { activeDialog = null },
            onConfirm = {
                viewModel.setSourcesEnabled(dialog.sourceIds, dialog.enabled)
                activeDialog = null
            },
        )

        is UnifiedSourcesDialogState.DeleteSelectedSources -> UnifiedDeleteSelectedSourcesDialog(
            plan = dialog.plan,
            onDismiss = { activeDialog = null },
            onConfirm = {
                viewModel.deletePackages(dialog.plan.deletablePackageIds.toSet())
                selectedSourceIdList = emptyList()
                activeDialog = null
            },
        )

        is UnifiedSourcesDialogState.SetSelectedSourcesNsfw -> UnifiedSelectionDialog(
            title = stringResource(R.string.unified_sources_set_nsfw_title),
            options = listOf(true, false),
            optionLabel = { isNsfw ->
                context.getString(
                    if (isNsfw) {
                        R.string.unified_sources_mark_as_nsfw
                    } else {
                        R.string.unified_sources_mark_as_sfw
                    },
                )
            },
            onDismiss = { activeDialog = null },
            onSelected = { isNsfw ->
                viewModel.setSourcesNsfw(dialog.sourceIds, isNsfw)
                selectedSourceIdList = emptyList()
                activeDialog = null
            },
        )

        is UnifiedSourcesDialogState.TrustRepository -> UnifiedTrustRepositoryDialog(
            repo = dialog.repo,
            onDismiss = { activeDialog = null },
            onConfirm = {
                viewModel.confirmExternalRepository(dialog.repo)
                activeDialog = null
            },
        )

        is UnifiedSourcesDialogState.PackageDetails -> UnifiedPackageStateDetailsDialog(
            item = dialog.item,
            onDismiss = { activeDialog = null },
            onManageRepositories = {
                activeDialog = UnifiedSourcesDialogState.AddRepositoryKind
            },
            onUninstall = {
                viewModel.uninstallPackage(dialog.item.id)
                activeDialog = null
            },
        )

        is UnifiedSourcesDialogState.InstallError -> SettingsAlertDialog(
            title = stringResource(R.string.error),
            onDismissRequest = { activeDialog = null },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.close),
                    onClick = { activeDialog = null },
                )
            },
            text = {
                Text(
                    text = dialog.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
        )

        null -> Unit
    }
}

