package org.skepsun.kototoro.settings.sources.unified

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getSummary
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.util.ext.getDisplayName
import org.skepsun.kototoro.core.util.ext.toLocaleOrNull
import org.skepsun.kototoro.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepo
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.settings.sources.extensions.formatExtensionFingerprint
import org.skepsun.kototoro.settings.sources.extensions.normalizeExtensionLanguageCode
import org.skepsun.kototoro.settings.sources.extensions.toInstalledIReaderPackageName
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton
import java.util.Locale
import kotlinx.coroutines.launch

enum class UnifiedToolbarFilterPanel {
	LANGUAGE,
	MORE,
}

private const val UNIFIED_SOURCES_TAB_SOURCES = 0
private const val UNIFIED_SOURCES_TAB_REPOSITORIES = 1
private const val UNIFIED_SOURCES_TAB_PACKAGES = 2
private const val UNIFIED_SOURCES_TAB_COUNT = 3
private val unifiedCardListPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
private val unifiedCardContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
private val unifiedCardSpacing = 8.dp

private data class UnifiedSourcesVisualStyle(
	val chipShape: RoundedCornerShape,
	val cardShape: RoundedCornerShape,
	val rowShape: RoundedCornerShape,
	val rowHorizontalPadding: androidx.compose.ui.unit.Dp,
	val rowVerticalPadding: androidx.compose.ui.unit.Dp,
	val iconShape: RoundedCornerShape,
	val cardElevation: androidx.compose.ui.unit.Dp,
)

@Composable
private fun rememberUnifiedSourcesVisualStyle(): UnifiedSourcesVisualStyle {
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
	data class PackageDetails(val item: UnifiedSourcePackageItem) : UnifiedSourcesDialogState
	data class ThirdPartyDisclaimer(val action: UnifiedThirdPartyAction) : UnifiedSourcesDialogState
}

private sealed interface UnifiedThirdPartyAction {
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

private data class UnifiedSelectedSourceDeletePlan(
	val deletablePackageIds: List<String>,
	val deletablePackageNames: List<String>,
	val skippedJarPackageNames: List<String>,
)

@Composable
fun UnifiedSourcesRoute(
	onBrowseSource: (UnifiedSourceItem) -> Unit,
	onOpenSourceSettings: (UnifiedSourceItem) -> Unit,
	onOpenRepositoryFile: (UnifiedSourceKind) -> Unit,
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

	fun proceedThirdPartyAction(action: UnifiedThirdPartyAction) {
		when (action) {
			is UnifiedThirdPartyAction.AddRepositoryUrl -> {
				viewModel.addRepositoryFromUrl(action.kind, action.url, action.title)
			}
			is UnifiedThirdPartyAction.AddInlineRepository -> {
				viewModel.addRepositoryFromInline(action.kind, action.content, action.title)
			}
			is UnifiedThirdPartyAction.OpenRepositoryFile -> {
				onOpenRepositoryFile(action.kind)
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
		onRefreshPackages = { viewModel.refreshPackages() },
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
				UNIFIED_SOURCES_TAB_REPOSITORIES,
				UNIFIED_SOURCES_TAB_PACKAGES -> viewModel.refreshPackages()
				else -> Unit
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
				proceedThirdPartyAction(dialog.action)
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

		is UnifiedSourcesDialogState.DeleteSelectedSources -> UnifiedDeleteSelectedSourcesDialog(
			plan = dialog.plan,
			onDismiss = { activeDialog = null },
			onConfirm = {
				viewModel.deletePackages(dialog.plan.deletablePackageIds.toSet())
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

		null -> Unit
	}
}

@Composable
private fun <T> UnifiedSelectionDialog(
	title: String,
	options: List<T>,
	optionLabel: (T) -> String,
	onDismiss: () -> Unit,
	onSelected: (T) -> Unit,
) {
	SettingsAlertDialog(
		title = title,
		onDismissRequest = onDismiss,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(android.R.string.cancel),
				onClick = onDismiss,
			)
		},
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 360.dp)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				options.forEach { option ->
					TextButton(
						onClick = { onSelected(option) },
						modifier = Modifier.fillMaxWidth(),
					) {
						Text(
							text = optionLabel(option),
							modifier = Modifier.fillMaxWidth(),
						)
					}
				}
			}
		},
	)
}

@Composable
private fun UnifiedRepositoryUrlDialog(
	kind: UnifiedSourceKind,
	initialUrl: String,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit,
) {
	var value by remember(kind, initialUrl) { mutableStateOf(initialUrl) }
	val hint = when (kind) {
		UnifiedSourceKind.LEGADO -> "https://example.com/legado.json"
		UnifiedSourceKind.TVBOX -> "http://z.qiqiv.cn/123.txt"
		else -> "https://example.com/index.min.json"
	}
	SettingsAlertDialog(
		title = stringResource(R.string.repository_url),
		onDismissRequest = onDismiss,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(android.R.string.ok),
				onClick = { onConfirm(value) },
			)
		},
		dismissButton = {
			SettingsDialogActionButton(
				text = stringResource(android.R.string.cancel),
				onClick = onDismiss,
			)
		},
		text = {
			OutlinedTextField(
				value = value,
				onValueChange = { value = it },
				modifier = Modifier.fillMaxWidth(),
				singleLine = true,
				label = { Text(hint) },
			)
		},
	)
}

@Composable
private fun UnifiedInlineRepositoryDialog(
	kind: UnifiedSourceKind,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit,
) {
	var value by remember(kind) { mutableStateOf("") }
	SettingsAlertDialog(
		title = stringResource(R.string.paste_repository_with_kind, stringResource(kind.dialogLabelResId())),
		onDismissRequest = onDismiss,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(android.R.string.ok),
				onClick = { onConfirm(value) },
			)
		},
		dismissButton = {
			SettingsDialogActionButton(
				text = stringResource(android.R.string.cancel),
				onClick = onDismiss,
			)
		},
		text = {
			OutlinedTextField(
				value = value,
				onValueChange = { value = it },
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 140.dp, max = 320.dp),
					label = { Text(stringResource(R.string.paste_content)) },
			)
		},
	)
}

@Composable
private fun UnifiedDisclaimerDialog(
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	SettingsAlertDialog(
		title = stringResource(R.string.add_repository),
		onDismissRequest = onDismiss,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(android.R.string.ok),
				onClick = onConfirm,
			)
		},
		dismissButton = {
			SettingsDialogActionButton(
				text = stringResource(android.R.string.cancel),
				onClick = onDismiss,
			)
		},
		text = { Text(stringResource(R.string.welcome_plugins_disclaimer)) },
	)
}

@Composable
private fun UnifiedDeleteRepositoryDialog(
	repository: UnifiedSourceRepositoryItem,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	SettingsAlertDialog(
		title = stringResource(R.string.delete_repository_title),
		onDismissRequest = onDismiss,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(R.string.delete),
				onClick = onConfirm,
			)
		},
		dismissButton = {
			SettingsDialogActionButton(
				text = stringResource(android.R.string.cancel),
				onClick = onDismiss,
			)
		},
		text = { Text(stringResource(R.string.delete_repository_message, repository.name)) },
	)
}

@Composable
private fun UnifiedDeleteSelectedSourcesDialog(
	plan: UnifiedSelectedSourceDeletePlan,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	val skippedJarText = plan.skippedJarPackageNames.joinToString(", ")
	val message = when {
		plan.deletablePackageIds.isNotEmpty() && plan.skippedJarPackageNames.isNotEmpty() -> {
			stringResource(
				R.string.unified_sources_delete_selected_with_skipped_jars,
				plan.deletablePackageIds.size,
				skippedJarText,
			)
		}
		plan.deletablePackageIds.isNotEmpty() -> {
			stringResource(
				R.string.unified_sources_delete_selected_message,
				plan.deletablePackageIds.size,
			)
		}
		plan.skippedJarPackageNames.isNotEmpty() -> {
			stringResource(
				R.string.unified_sources_delete_selected_only_skipped_jars,
				skippedJarText,
			)
		}
		else -> stringResource(R.string.unified_sources_delete_selected_no_packages)
	}
	SettingsAlertDialog(
		title = stringResource(R.string.delete),
		onDismissRequest = onDismiss,
		confirmButton = {
			if (plan.deletablePackageIds.isNotEmpty()) {
				SettingsDialogActionButton(
					text = stringResource(R.string.delete),
					onClick = onConfirm,
				)
			} else {
				SettingsDialogActionButton(
					text = stringResource(android.R.string.ok),
					onClick = onDismiss,
				)
			}
		},
		dismissButton = {
			if (plan.deletablePackageIds.isNotEmpty()) {
				SettingsDialogActionButton(
					text = stringResource(android.R.string.cancel),
					onClick = onDismiss,
				)
			}
		},
		text = { Text(message) },
	)
}

@Composable
private fun UnifiedTrustRepositoryDialog(
	repo: ExternalExtensionRepo,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	SettingsAlertDialog(
		title = stringResource(R.string.trust_extension_repository),
		onDismissRequest = onDismiss,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(R.string.trust_and_add),
				onClick = onConfirm,
			)
		},
		dismissButton = {
			SettingsDialogActionButton(
				text = stringResource(android.R.string.cancel),
				onClick = onDismiss,
			)
		},
		text = {
			Text(
				text = stringResource(
					R.string.trust_extension_repository_message,
					repo.displayName,
					repo.website,
					repo.signingKeyFingerprint.formatExtensionFingerprint(),
				),
			)
		},
	)
}

@Composable
private fun UnifiedPackageStateDetailsDialog(
	item: UnifiedSourcePackageItem,
	onDismiss: () -> Unit,
	onManageRepositories: () -> Unit,
	onUninstall: () -> Unit,
) {
	val title: String
	val message: String
	when (item.state) {
		UnifiedSourcePackageState.UNTRUSTED -> {
			title = stringResource(R.string.untrusted_extension)
			message = stringResource(
				R.string.untrusted_extension_message,
				item.name,
				item.packageName.orEmpty(),
				item.installPayload?.signatureHash.orEmpty().formatExtensionFingerprint(),
			)
		}
		UnifiedSourcePackageState.INCOMPATIBLE -> {
			title = stringResource(R.string.incompatible_extension)
			message = stringResource(
				R.string.incompatible_extension_message,
				item.name,
				item.versionName.orEmpty(),
				item.installPayload?.libVersion?.toString().orEmpty(),
			)
		}
		else -> return
	}
	SettingsAlertDialog(
		title = title,
		onDismissRequest = onDismiss,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(if (item.isInstalled) R.string.remove else android.R.string.ok),
				onClick = if (item.isInstalled) onUninstall else onDismiss,
			)
		},
		dismissButton = {
			Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				SettingsDialogActionButton(
					text = stringResource(R.string.manage_extension_repositories),
					onClick = onManageRepositories,
				)
				if (item.isInstalled) {
					SettingsDialogActionButton(
						text = stringResource(android.R.string.cancel),
						onClick = onDismiss,
					)
				}
			}
		},
		text = { Text(message) },
	)
}

@Composable
private fun UnifiedLanguageFilterDialog(
	languages: List<String>,
	selectedLanguages: Set<String>,
	onDismiss: () -> Unit,
	onLanguageClick: (String) -> Unit,
	onApplyPreferredLanguages: () -> Unit,
	onClear: () -> Unit,
) {
	LaunchedEffect(languages, selectedLanguages) {
		Log.d(
			"UnifiedLanguageFilter",
			"open language filter languages=$languages selectedLanguages=$selectedLanguages",
		)
	}

	SettingsAlertDialog(
		title = stringResource(R.string.filter_extensions_by_language),
		onDismissRequest = onDismiss,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(R.string.done),
				onClick = onDismiss,
			)
		},
		dismissButton = {
			Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				SettingsDialogActionButton(
					text = stringResource(R.string.clear),
					onClick = onClear,
				)
				SettingsDialogActionButton(
					text = stringResource(R.string.use_setup_wizard_languages),
					onClick = onApplyPreferredLanguages,
				)
			}
		},
		text = {
			LazyVerticalGrid(
				columns = GridCells.Adaptive(minSize = 120.dp),
				modifier = Modifier.heightIn(max = 360.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				gridItems(languages, key = { it }) { language ->
					CompactFilterChip(
						selected = language in selectedLanguages,
						onClick = { onLanguageClick(language) },
						text = language.displayLanguageLabel(),
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}
		},
	)
}

@Composable
private fun ToolbarSearchField(
	query: String,
	onQueryChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	autofocus: Boolean = false,
) {
	val focusRequester = remember { FocusRequester() }
	if (autofocus) {
		LaunchedEffect(Unit) {
			focusRequester.requestFocus()
		}
	}
	Surface(
		modifier = modifier.height(40.dp),
		shape = RoundedCornerShape(20.dp),
		color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
		contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	) {
		BasicTextField(
			value = query,
			onValueChange = onQueryChange,
			modifier = Modifier
				.fillMaxSize()
				.focusRequester(focusRequester),
			singleLine = true,
			textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
			decorationBox = { innerTextField ->
				Row(
					modifier = Modifier
						.fillMaxSize()
						.padding(horizontal = 12.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Icon(
						Icons.Filled.Search,
						contentDescription = null,
						modifier = Modifier.size(18.dp),
					)
					Spacer(modifier = Modifier.width(8.dp))
					Box(modifier = Modifier.weight(1f)) {
						if (query.isEmpty()) {
								Text(
									text = stringResource(R.string.search),
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
							)
						}
						innerTextField()
					}
				}
			},
		)
	}
}

@Composable
private fun ToolbarSearchIconButton(
	active: Boolean,
	onClick: () -> Unit,
) {
	Box(contentAlignment = Alignment.TopEnd) {
		IconButton(
			onClick = onClick,
			modifier = Modifier.size(40.dp),
		) {
			Icon(
				Icons.Filled.Search,
				contentDescription = stringResource(R.string.search),
				modifier = Modifier.size(20.dp),
				tint = if (active) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}
		if (active) {
			Surface(
				modifier = Modifier.size(8.dp),
				shape = RoundedCornerShape(4.dp),
				color = MaterialTheme.colorScheme.primary,
				contentColor = MaterialTheme.colorScheme.onPrimary,
			) {}
		}
	}
}

@Composable
private fun ToolbarFilterIconButton(
	iconRes: Int,
	activeCount: Int,
	contentDescription: String,
	onClick: () -> Unit,
) {
	Box(contentAlignment = Alignment.TopEnd) {
		IconButton(
			onClick = onClick,
			modifier = Modifier.size(40.dp),
		) {
			Icon(
				painter = painterResource(iconRes),
				contentDescription = contentDescription,
				modifier = Modifier.size(20.dp),
				tint = if (activeCount > 0) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}
		if (activeCount > 0) {
			Surface(
				shape = RoundedCornerShape(8.dp),
				color = MaterialTheme.colorScheme.primary,
				contentColor = MaterialTheme.colorScheme.onPrimary,
			) {
				Text(
					text = activeCount.coerceAtMost(9).toString(),
					modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
					style = MaterialTheme.typography.labelSmall,
				)
			}
		}
	}
}

@Composable
fun UnifiedSourcesToolbarActions(
	readyState: UnifiedSourcesUiState.Ready?,
	searchActive: Boolean,
	onSearchClick: () -> Unit,
	onSearchClose: () -> Unit,
	onSearchQueryChange: (String) -> Unit,
	onLanguageFilterClick: () -> Unit,
	onMoreFiltersClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	if (searchActive && readyState != null) {
		Row(
			modifier = modifier,
			horizontalArrangement = Arrangement.End,
			verticalAlignment = Alignment.CenterVertically,
		) {
			ToolbarSearchField(
				query = readyState.filters.query,
				onQueryChange = onSearchQueryChange,
				modifier = Modifier.weight(1f),
				autofocus = true,
			)
			IconButton(onClick = onSearchClose) {
				Icon(
					imageVector = Icons.Filled.Close,
					contentDescription = stringResource(android.R.string.cancel),
					tint = MaterialTheme.colorScheme.onSurface,
				)
			}
			ToolbarFilterIconButton(
				iconRes = R.drawable.ic_language,
				activeCount = readyState.filters.languages.size,
				contentDescription = stringResource(R.string.filter_extensions_by_language),
				onClick = onLanguageFilterClick,
			)
			ToolbarFilterIconButton(
				iconRes = R.drawable.ic_filter_menu,
				activeCount = readyState.filters.otherFilterCount(),
				contentDescription = stringResource(R.string.more_filters),
				onClick = onMoreFiltersClick,
			)
		}
		return
	}
	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.End,
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (readyState != null) {
			ToolbarSearchIconButton(
				active = readyState.filters.query.isNotBlank(),
				onClick = onSearchClick,
			)
			ToolbarFilterIconButton(
				iconRes = R.drawable.ic_language,
				activeCount = readyState.filters.languages.size,
				contentDescription = stringResource(R.string.filter_extensions_by_language),
				onClick = onLanguageFilterClick,
			)
			ToolbarFilterIconButton(
				iconRes = R.drawable.ic_filter_menu,
				activeCount = readyState.filters.otherFilterCount(),
				contentDescription = stringResource(R.string.more_filters),
				onClick = onMoreFiltersClick,
			)
		}
	}
}

@Composable
private fun UnifiedFilterGroupDialog(
	title: String,
	onDismiss: () -> Unit,
	onClear: () -> Unit,
	content: @Composable () -> Unit,
) {
	SettingsAlertDialog(
		title = title,
		onDismissRequest = onDismiss,
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				content()
			}
		},
		confirmButton = {
				SettingsDialogActionButton(
					text = stringResource(R.string.done),
					onClick = onDismiss,
				)
			},
			dismissButton = {
				SettingsDialogActionButton(
					text = stringResource(R.string.clear),
					onClick = onClear,
				)
			},
	)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedSourcesScreen(
	state: UnifiedSourcesUiState,
	isLoading: Boolean,
	updateAllInProgress: Boolean,
	searchActive: Boolean,
	onLanguageFilterClick: () -> Unit,
	onMoreFiltersClick: () -> Unit,
	onSearchClick: () -> Unit,
	onSearchClose: () -> Unit,
	onSearchQueryChange: (String) -> Unit,
	onKindClick: (UnifiedSourceKind?) -> Unit,
	onContentTypeClick: (ContentType?) -> Unit,
	onSourceEnabledChange: (String, Boolean) -> Unit,
	selectedSourceIds: Set<String>,
	onSourceSelectionChange: (Set<String>) -> Unit,
	onSelectAllVisibleSources: () -> Unit,
	onClearSourceSelection: () -> Unit,
	onEnableSelectedSources: () -> Unit,
	onDisableSelectedSources: () -> Unit,
	onDeleteSelectedSources: () -> Unit,
	onSourcePinnedChange: (String, Boolean) -> Unit,
	onBrowseSource: (UnifiedSourceItem) -> Unit,
	onOpenSourceSettings: (UnifiedSourceItem) -> Unit,
	onAddRepository: (UnifiedSourceRepositoryItem?) -> Unit,
	onRefreshRepository: (UnifiedSourceRepositoryItem) -> Unit,
	onDeleteRepository: (UnifiedSourceRepositoryItem) -> Unit,
	onRefreshPackages: () -> Unit,
	onUpdateAllPackages: () -> Unit,
	onPackagePrimaryAction: (String) -> Unit,
	onPackageSystemInstall: (String) -> Unit,
	onPackageUninstall: (String) -> Unit,
	onPackageCancelInstall: (String) -> Unit,
	onImportLocalJar: () -> Unit,
	onPullRefresh: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	val readyState = state as? UnifiedSourcesUiState.Ready
	val pagerState = rememberPagerState(pageCount = { UNIFIED_SOURCES_TAB_COUNT })
	val coroutineScope = rememberCoroutineScope()
	val selectedTab = pagerState.currentPage.coerceIn(0, UNIFIED_SOURCES_TAB_COUNT - 1)
	val activeSelectedSourceIds = remember(readyState?.sources, selectedSourceIds) {
		val visibleSourceIds = readyState?.sources.orEmpty().mapTo(LinkedHashSet()) { it.id }
		selectedSourceIds intersect visibleSourceIds
	}
	LaunchedEffect(activeSelectedSourceIds) {
		if (selectedSourceIds != activeSelectedSourceIds) {
			onSourceSelectionChange(activeSelectedSourceIds)
		}
	}
	LaunchedEffect(selectedTab) {
		if (selectedTab != UNIFIED_SOURCES_TAB_SOURCES && selectedSourceIds.isNotEmpty()) {
			onClearSourceSelection()
		}
	}
	BackHandler(
		enabled = selectedTab == UNIFIED_SOURCES_TAB_SOURCES && selectedSourceIds.isNotEmpty(),
	) {
		onClearSourceSelection()
	}
	Scaffold(
		modifier = modifier,
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		topBar = {
			Column {
				if (isLoading || state == UnifiedSourcesUiState.Loading) {
					LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
				}
				if (readyState != null) {
					UnifiedSourcesFilterTabs(
						state = readyState,
						onContentTypeClick = onContentTypeClick,
						onKindClick = onKindClick,
					)
				}
			}
		},
	) { innerPadding ->
		when (state) {
			UnifiedSourcesUiState.Loading -> {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.padding(innerPadding),
				)
			}
			is UnifiedSourcesUiState.Ready -> {
				Column(
					modifier = Modifier
						.fillMaxSize()
						.padding(innerPadding),
				) {
					SecondaryTabRow(
						selectedTabIndex = selectedTab,
						containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
					) {
						Tab(
							selected = selectedTab == UNIFIED_SOURCES_TAB_SOURCES,
							onClick = {
								coroutineScope.launch {
									pagerState.animateScrollToPage(UNIFIED_SOURCES_TAB_SOURCES)
								}
							},
							text = { Text(stringResource(R.string.sources_tab_title, state.sources.size)) },
						)
						Tab(
							selected = selectedTab == UNIFIED_SOURCES_TAB_REPOSITORIES,
							onClick = {
								onClearSourceSelection()
								coroutineScope.launch {
									pagerState.animateScrollToPage(UNIFIED_SOURCES_TAB_REPOSITORIES)
								}
							},
							text = { Text(stringResource(R.string.repositories_tab_title, state.repositories.size)) },
						)
						Tab(
							selected = selectedTab == UNIFIED_SOURCES_TAB_PACKAGES,
							onClick = {
								onClearSourceSelection()
								coroutineScope.launch {
									pagerState.animateScrollToPage(UNIFIED_SOURCES_TAB_PACKAGES)
								}
							},
							text = { Text(stringResource(R.string.packages_tab_title, state.packages.size)) },
						)
					}
					if (selectedTab == UNIFIED_SOURCES_TAB_SOURCES && activeSelectedSourceIds.isNotEmpty()) {
						UnifiedSourceSelectionBar(
							selectedCount = activeSelectedSourceIds.size,
							allVisibleSelected = activeSelectedSourceIds.size == state.sources.size,
							onSelectAllVisibleSources = onSelectAllVisibleSources,
							onClearSelection = onClearSourceSelection,
							onEnableSelectedSources = onEnableSelectedSources,
							onDisableSelectedSources = onDisableSelectedSources,
							onDeleteSelectedSources = onDeleteSelectedSources,
						)
					}
					KototoroPullToRefreshBox(
						isRefreshing = isLoading,
						onRefresh = { onPullRefresh(selectedTab) },
						modifier = Modifier
							.fillMaxWidth()
							.weight(1f),
					) {
						HorizontalPager(
							state = pagerState,
							modifier = Modifier.fillMaxSize(),
						) { page ->
							when (page) {
								UNIFIED_SOURCES_TAB_SOURCES -> UnifiedSourceList(
									modifier = Modifier.fillMaxSize(),
									sources = state.sources,
									onBrowseSource = onBrowseSource,
									onOpenSourceSettings = onOpenSourceSettings,
									onSourceEnabledChange = onSourceEnabledChange,
									selectedSourceIds = activeSelectedSourceIds,
									onSourceSelectionChange = onSourceSelectionChange,
									onSourcePinnedChange = onSourcePinnedChange,
								)
								UNIFIED_SOURCES_TAB_REPOSITORIES -> UnifiedRepositoryList(
									modifier = Modifier.fillMaxSize(),
									repositories = state.repositories,
									onAddRepository = onAddRepository,
									onRefreshRepository = onRefreshRepository,
									onDeleteRepository = onDeleteRepository,
								)
								UNIFIED_SOURCES_TAB_PACKAGES -> UnifiedPackageList(
									modifier = Modifier.fillMaxSize(),
									packages = state.packages,
									updateAllInProgress = updateAllInProgress,
									onRefreshPackages = onRefreshPackages,
									onUpdateAllPackages = onUpdateAllPackages,
									onPackagePrimaryAction = onPackagePrimaryAction,
									onPackageSystemInstall = onPackageSystemInstall,
									onPackageUninstall = onPackageUninstall,
									onPackageCancelInstall = onPackageCancelInstall,
									onImportLocalJar = onImportLocalJar,
								)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun UnifiedSourcesFilterTabs(
	state: UnifiedSourcesUiState.Ready,
	onContentTypeClick: (ContentType?) -> Unit,
	onKindClick: (UnifiedSourceKind?) -> Unit,
) {
	Column(
		modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 4.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			contentPadding = PaddingValues(horizontal = 1.dp),
		) {
			item(key = "content_all") {
					CompactFilterChip(
						selected = state.filters.contentTypes.isEmpty(),
						onClick = { onContentTypeClick(null) },
						text = stringResource(R.string.all_content),
				)
			}
			items(state.availableContentTypes, key = { it.name }) { type ->
				CompactFilterChip(
					selected = type in state.filters.contentTypes,
					onClick = { onContentTypeClick(type) },
					text = stringResource(type.titleResId),
				)
			}
		}
		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			contentPadding = PaddingValues(horizontal = 1.dp),
		) {
			item(key = "kind_all") {
					CompactFilterChip(
						selected = state.filters.kinds.isEmpty(),
						onClick = { onKindClick(null) },
						text = stringResource(R.string.all_sources),
				)
			}
			items(state.availableKinds, key = { it.name }) { kind ->
				CompactFilterChip(
					selected = kind in state.filters.kinds,
					onClick = { onKindClick(kind) },
					text = kind.displayLabel(),
				)
			}
		}
	}
}

@Composable
private fun FilterSection(
	title: String,
	content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(
			text = title,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			contentPadding = PaddingValues(horizontal = 1.dp),
			content = content,
		)
	}
}

@Composable
private fun CompactFilterChip(
	selected: Boolean,
	onClick: () -> Unit,
	text: String,
	modifier: Modifier = Modifier,
) {
	val style = rememberUnifiedSourcesVisualStyle()
	FilterChip(
		selected = selected,
		onClick = onClick,
		modifier = modifier.defaultMinSize(minHeight = 30.dp),
		shape = style.chipShape,
		label = {
			Text(
				text = text,
				style = MaterialTheme.typography.labelSmall,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		},
	)
}

@Composable
private fun UnifiedSourceSelectionBar(
	selectedCount: Int,
	allVisibleSelected: Boolean,
	onSelectAllVisibleSources: () -> Unit,
	onClearSelection: () -> Unit,
	onEnableSelectedSources: () -> Unit,
	onDisableSelectedSources: () -> Unit,
	onDeleteSelectedSources: () -> Unit,
) {
	LazyRow(
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.surface)
			.padding(horizontal = 12.dp, vertical = 6.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		item(key = "selected_count") {
			Text(
				text = stringResource(R.string.selected_count, selectedCount),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		item(key = "select_all") {
			CompactActionChip(
				onClick = if (allVisibleSelected) onClearSelection else onSelectAllVisibleSources,
				label = {
					Text(stringResource(if (allVisibleSelected) R.string.deselect_all else R.string.select_all))
				},
			)
		}
		item(key = "enable_selected") {
			CompactActionChip(
				onClick = onEnableSelectedSources,
				label = { Text(stringResource(R.string.enable)) },
			)
		}
		item(key = "disable_selected") {
			CompactActionChip(
				onClick = onDisableSelectedSources,
				label = { Text(stringResource(R.string.disable)) },
			)
		}
		item(key = "delete_selected") {
			CompactActionChip(
				onClick = onDeleteSelectedSources,
				label = { Text(stringResource(R.string.delete)) },
			)
		}
		item(key = "clear_selection") {
			IconButton(
				onClick = onClearSelection,
				modifier = Modifier.size(36.dp),
			) {
				Icon(
					imageVector = Icons.Filled.Close,
					contentDescription = stringResource(android.R.string.cancel),
					modifier = Modifier.size(18.dp),
				)
			}
		}
	}
}

@Composable
private fun UnifiedSourceList(
	modifier: Modifier = Modifier,
	sources: List<UnifiedSourceItem>,
	onBrowseSource: (UnifiedSourceItem) -> Unit,
	onOpenSourceSettings: (UnifiedSourceItem) -> Unit,
	onSourceEnabledChange: (String, Boolean) -> Unit,
	selectedSourceIds: Set<String>,
	onSourceSelectionChange: (Set<String>) -> Unit,
	onSourcePinnedChange: (String, Boolean) -> Unit,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	LazyColumn(state = listState,
		modifier = modifier,
		contentPadding = PaddingValues(
			horizontal = if (expressive) 8.dp else 0.dp,
			vertical = 4.dp,
		),
	) {
		items(sources, key = { it.id }) { item ->
			val isSelected = item.id in selectedSourceIds
			UnifiedSourceRow(
				item = item,
				isSelectionMode = selectedSourceIds.isNotEmpty(),
				isSelected = isSelected,
				onSelectionToggle = {
					onSourceSelectionChange(selectedSourceIds.toggle(item.id))
				},
				onBrowseSource = onBrowseSource,
				onOpenSourceSettings = onOpenSourceSettings,
				onSourceEnabledChange = onSourceEnabledChange,
				onSourcePinnedChange = onSourcePinnedChange,
			)
			if (expressive) {
				Spacer(modifier = Modifier.height(3.dp))
			} else {
				HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
			}
		}
	}
}

@Composable
private fun UnifiedSourceIcon(
	item: UnifiedSourceItem,
	modifier: Modifier = Modifier,
) {
	ContentSourceIcon(
		source = item.source,
		modifier = modifier,
		contentDescription = item.title,
	)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UnifiedSourceRow(
	item: UnifiedSourceItem,
	isSelectionMode: Boolean,
	isSelected: Boolean,
	onSelectionToggle: () -> Unit,
	onBrowseSource: (UnifiedSourceItem) -> Unit,
	onOpenSourceSettings: (UnifiedSourceItem) -> Unit,
	onSourceEnabledChange: (String, Boolean) -> Unit,
	onSourcePinnedChange: (String, Boolean) -> Unit,
) {
	val context = LocalContext.current
	var menuExpanded by rememberSaveable(item.id) { mutableStateOf(false) }
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	val style = rememberUnifiedSourcesVisualStyle()
	val rowContainerColor = when {
		isSelected -> MaterialTheme.colorScheme.secondaryContainer
		expressive -> MaterialTheme.colorScheme.surfaceContainerLow
		else -> MaterialTheme.colorScheme.background
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = style.rowHorizontalPadding, vertical = style.rowVerticalPadding)
			.background(rowContainerColor, style.rowShape)
			.combinedClickable(
				onClick = {
					if (isSelectionMode) {
						onSelectionToggle()
					} else {
						onBrowseSource(item)
					}
				},
				onLongClick = onSelectionToggle,
			)
			.padding(start = if (expressive) 12.dp else 16.dp, top = 7.dp, end = 4.dp, bottom = 7.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (isSelectionMode) {
			Checkbox(
				checked = isSelected,
				onCheckedChange = { onSelectionToggle() },
				modifier = Modifier.size(32.dp),
			)
		} else {
			Box(
				modifier = Modifier
					.size(36.dp)
					.background(MaterialTheme.colorScheme.surfaceContainerHigh, style.iconShape),
				contentAlignment = Alignment.Center,
			) {
				UnifiedSourceIcon(
					item = item,
					modifier = Modifier.size(24.dp),
				)
			}
		}
		Spacer(modifier = Modifier.width(12.dp))
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					text = item.title,
					modifier = Modifier.weight(1f, fill = false),
					style = MaterialTheme.typography.titleSmall,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (item.isPinned) {
					Icon(
						painter = painterResource(R.drawable.ic_pin_small),
						contentDescription = null,
						modifier = Modifier.size(14.dp),
						tint = MaterialTheme.colorScheme.primary,
					)
				}
				CompactTag(text = item.kind.displayLabel())
				if (!item.isAvailable || item.isBroken) {
					CompactTag(text = stringResource(R.string.unavailable), isWarning = true)
				}
			}
			Text(
				text = item.source.getSummary(context, item.contentType) ?: buildSourceSubtitle(item),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		Box {
			IconButton(
				onClick = { menuExpanded = true },
				modifier = Modifier.size(40.dp),
			) {
					Icon(
						painter = painterResource(R.drawable.ic_more_vert),
						contentDescription = stringResource(R.string.more_filters),
						modifier = Modifier.size(18.dp),
					)
				}
			DropdownMenu(
				expanded = menuExpanded,
				onDismissRequest = { menuExpanded = false },
			) {
					DropdownMenuItem(
						text = { Text(stringResource(R.string.browse_available_extensions)) },
					onClick = {
						menuExpanded = false
						onBrowseSource(item)
					},
				)
					DropdownMenuItem(
						text = { Text(stringResource(if (item.isPinned) R.string.unpin else R.string.pin)) },
					onClick = {
						menuExpanded = false
						onSourcePinnedChange(item.id, !item.isPinned)
					},
				)
					DropdownMenuItem(
						text = { Text(stringResource(R.string.settings)) },
					onClick = {
						menuExpanded = false
						onOpenSourceSettings(item)
					},
				)
			}
		}
		Switch(
			checked = item.isEnabled,
			onCheckedChange = { onSourceEnabledChange(item.id, it) },
		)
	}
}

@Composable
private fun UnifiedRepositoryList(
	modifier: Modifier = Modifier,
	repositories: List<UnifiedSourceRepositoryItem>,
	onAddRepository: (UnifiedSourceRepositoryItem?) -> Unit,
	onRefreshRepository: (UnifiedSourceRepositoryItem) -> Unit,
	onDeleteRepository: (UnifiedSourceRepositoryItem) -> Unit,
) {
	val listState2 = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	val style = rememberUnifiedSourcesVisualStyle()
	LazyColumn(state = listState2,
		modifier = modifier,
		contentPadding = unifiedCardListPadding,
		verticalArrangement = Arrangement.spacedBy(unifiedCardSpacing),
	) {
		item(key = "add_repository") {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 4.dp),
			) {
					AssistChip(
						onClick = { onAddRepository(null) },
						label = { Text(stringResource(R.string.add_repository_prompt)) },
				)
			}
		}
		items(repositories, key = { it.id }) { item ->
			ElevatedCard(
				modifier = Modifier.fillMaxWidth(),
				shape = style.cardShape,
				colors = CardDefaults.elevatedCardColors(
					containerColor = if (expressive) {
						MaterialTheme.colorScheme.surfaceContainerLow
					} else {
						MaterialTheme.colorScheme.surface
					},
				),
				elevation = CardDefaults.elevatedCardElevation(
					defaultElevation = style.cardElevation,
				),
			) {
				Column(
					modifier = Modifier.padding(unifiedCardContentPadding),
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Text(
							text = item.name,
							modifier = Modifier.weight(1f),
							style = MaterialTheme.typography.titleSmall,
							fontWeight = FontWeight.SemiBold,
						)
						if (item.isConfigured) {
								AssistChip(
									onClick = { onRefreshRepository(item) },
									label = { Text(stringResource(R.string.refresh_action)) },
								)
								AssistChip(
									onClick = { onDeleteRepository(item) },
									label = { Text(stringResource(R.string.delete)) },
								)
							} else if (item.isPreset) {
								AssistChip(
									onClick = { onAddRepository(item) },
									label = { Text(stringResource(R.string.add)) },
								)
							}
					}
					Text(
						text = "${item.kind.displayLabel()} · ${item.locationType.displayLabel()}",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = item.url,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					item.lastError?.takeIf { it.isNotBlank() }?.let { error ->
						Text(
							text = stringResource(R.string.unified_sources_repository_last_refresh_failed, error),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.error,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun UnifiedPackageList(
	modifier: Modifier = Modifier,
	packages: List<UnifiedSourcePackageItem>,
	updateAllInProgress: Boolean,
	onRefreshPackages: () -> Unit,
	onUpdateAllPackages: () -> Unit,
	onPackagePrimaryAction: (String) -> Unit,
	onPackageSystemInstall: (String) -> Unit,
	onPackageUninstall: (String) -> Unit,
	onPackageCancelInstall: (String) -> Unit,
	onImportLocalJar: () -> Unit,
) {
	val listState3 = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
	LazyColumn(state = listState3,
		modifier = modifier,
		contentPadding = unifiedCardListPadding,
		verticalArrangement = Arrangement.spacedBy(unifiedCardSpacing),
	) {
		item(key = "package_actions") {
			LazyRow(
				modifier = Modifier
					.fillMaxWidth()
					.padding(bottom = 4.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				item(key = "refresh_packages") {
						CompactActionChip(
						onClick = onRefreshPackages,
						label = { Text(stringResource(R.string.refresh_packages)) },
						)
				}
				item(key = "update_all_packages") {
						CompactActionChip(
						onClick = onUpdateAllPackages,
						label = {
							Text(
								stringResource(
									if (updateAllInProgress) R.string.cancel_update_all_packages else R.string.update_all_packages,
								),
							)
						},
						)
				}
				item(key = "import_local_jar") {
						CompactActionChip(
						onClick = onImportLocalJar,
						label = { Text(stringResource(R.string.import_local_jar)) },
						)
				}
			}
		}
		items(packages, key = { it.id }) { item ->
			UnifiedPackageRow(
				item = item,
				onPrimaryAction = { onPackagePrimaryAction(item.id) },
				onSystemInstall = { onPackageSystemInstall(item.id) },
				onUninstall = { onPackageUninstall(item.id) },
				onCancelInstall = { onPackageCancelInstall(item.id) },
			)
		}
	}
}

@Composable
private fun UnifiedPackageRow(
	item: UnifiedSourcePackageItem,
	onPrimaryAction: () -> Unit,
	onSystemInstall: () -> Unit,
	onUninstall: () -> Unit,
	onCancelInstall: () -> Unit,
) {
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	val style = rememberUnifiedSourcesVisualStyle()
	ElevatedCard(
		modifier = Modifier.fillMaxWidth(),
		shape = style.cardShape,
		colors = CardDefaults.elevatedCardColors(
			containerColor = if (expressive) {
				MaterialTheme.colorScheme.surfaceContainerLow
			} else {
				MaterialTheme.colorScheme.surface
			},
		),
		elevation = CardDefaults.elevatedCardElevation(
			defaultElevation = style.cardElevation,
		),
	) {
		Column(
			modifier = Modifier.padding(unifiedCardContentPadding),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				UnifiedPackageIcon(item = item)
				Column(modifier = Modifier.weight(1f)) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						Text(
							text = item.name,
							modifier = Modifier.weight(1f, fill = false),
							style = MaterialTheme.typography.titleSmall,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						CompactTag(item.kind.displayLabel())
						CompactTag(item.state.displayLabel(), isWarning = item.state.isWarning)
					}
					Text(
						text = buildPackageSubtitle(item),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
				if (item.installProgressPercent != null) {
					Text(
						text = stringResource(R.string.package_download_progress, item.installProgressPercent),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				LinearProgressIndicator(
					progress = { item.installProgressPercent / 100f },
					modifier = Modifier.fillMaxWidth(),
				)
			}
			Row(
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				item.language.normalizedLanguageTag()?.let { CompactTag(it) }
				if (item.installedVersionName != null && item.state == UnifiedSourcePackageState.UPDATE_AVAILABLE) {
					CompactTag(stringResource(R.string.installed_version_pattern, item.installedVersionName))
				}
				if (item.isNsfw) {
					CompactTag(stringResource(R.string.nsfw), isWarning = true)
				}
				if (item.shadowedSourceCount > 0) {
					CompactTag(
						text = stringResource(R.string.unified_sources_shadowed_count, item.shadowedSourceCount),
						isWarning = true,
					)
				}
			}
			if (item.sourceNames.isNotEmpty()) {
				Text(
					text = item.sourceNames.take(8).joinToString(", "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Row(
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				when (item.state) {
					UnifiedSourcePackageState.AVAILABLE,
					UnifiedSourcePackageState.UPDATE_AVAILABLE -> {
						if (
							item.kind.isSideloadKind() &&
							item.installLocation != UnifiedSourcePackageInstallLocation.LOCAL_APK
						) {
							CompactActionChip(
								onClick = onSystemInstall,
								label = { Text(stringResource(R.string.install_extension)) },
							)
						}
						CompactActionChip(
							onClick = onPrimaryAction,
							label = { Text(item.primaryActionLabel()) },
						)
					}
					UnifiedSourcePackageState.UNTRUSTED,
					UnifiedSourcePackageState.INCOMPATIBLE -> {
						CompactActionChip(
							onClick = onPrimaryAction,
							label = { Text(item.primaryActionLabel()) },
						)
					}
						UnifiedSourcePackageState.INSTALLING -> {
							CompactActionChip(
								onClick = onCancelInstall,
								label = { Text(stringResource(android.R.string.cancel)) },
							)
						}
					UnifiedSourcePackageState.INSTALLED -> Unit
				}
					if (item.isInstalled) {
						CompactActionChip(
							onClick = onUninstall,
							label = { Text(stringResource(R.string.remove)) },
						)
					}
			}
		}
	}
}

@Composable
private fun UnifiedPackageIcon(
	item: UnifiedSourcePackageItem,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val fallbackPainter = rememberSafePainter(item.kind.packageIconRes())
	val installedIcon = remember(item.kind, item.packageName, item.isInstalled, context) {
		val installedPackageName = item.installedIconPackageName() ?: return@remember null
		runCatching { context.packageManager.getApplicationIcon(installedPackageName) }.getOrNull()
	}
	val iconModel = installedIcon ?: item.iconUrl
	val style = rememberUnifiedSourcesVisualStyle()

	Box(
		modifier = modifier
			.size(32.dp)
			.background(MaterialTheme.colorScheme.secondaryContainer, style.iconShape),
		contentAlignment = Alignment.Center,
	) {
		if (iconModel != null) {
			AsyncImage(
				model = iconModel,
				contentDescription = null,
				modifier = Modifier.size(20.dp),
				placeholder = fallbackPainter,
				error = fallbackPainter,
				fallback = fallbackPainter,
			)
		} else {
			Icon(
				painter = fallbackPainter,
				contentDescription = null,
				modifier = Modifier.size(18.dp),
				tint = MaterialTheme.colorScheme.onSecondaryContainer,
			)
		}
	}
}

@Composable
private fun buildSourceSubtitle(item: UnifiedSourceItem): String {
	return listOfNotNull(
		item.kind.displayLabel(),
		stringResource(item.contentType.titleResId),
		item.language,
		item.repositoryName,
		item.packageName,
	).joinToString(" · ")
}

@Composable
private fun buildPackageSubtitle(item: UnifiedSourcePackageItem): String {
	return listOfNotNull(
		item.versionName?.let { "v$it" },
		item.repositoryName,
		stringResource(
			R.string.unified_sources_package_effective_count,
			item.activeSourceCount.coerceAtLeast(0),
			item.sourceCount,
		),
	).joinToString(" · ")
}

private fun String?.normalizedLanguageTag(): String? {
	return this
		?.normalizeExtensionLanguageCode()
		?.takeIf { it.isNotBlank() }
		?.uppercase(Locale.ROOT)
}

private fun UnifiedSourcesUiState.Ready.buildDeletePlan(
	selectedSourceIds: Set<String>,
): UnifiedSelectedSourceDeletePlan {
	val packagesById = allPackages.associateBy { it.id }
	val deletablePackageIds = LinkedHashSet<String>()
	val deletablePackageNames = LinkedHashSet<String>()
	val skippedJarPackageNames = LinkedHashSet<String>()

	allSources
		.asSequence()
		.filter { it.id in selectedSourceIds }
		.forEach { item ->
			when {
				item.kind == UnifiedSourceKind.JAR -> {
					skippedJarPackageNames += packagesById[item.packageId]?.name ?: item.packageName ?: item.title
				}
				item.packageId != null -> {
					if (deletablePackageIds.add(item.packageId)) {
						deletablePackageNames += packagesById[item.packageId]?.name ?: item.packageName ?: item.title
					}
				}
			}
		}

	return UnifiedSelectedSourceDeletePlan(
		deletablePackageIds = deletablePackageIds.toList(),
		deletablePackageNames = deletablePackageNames.toList(),
		skippedJarPackageNames = skippedJarPackageNames.toList(),
	)
}

private fun UnifiedSourcePackageItem.installedIconPackageName(): String? {
	if (!isInstalled) {
		return null
	}
		return when (kind) {
			UnifiedSourceKind.CLOUDSTREAM -> packageName
			UnifiedSourceKind.MIHON,
			UnifiedSourceKind.ANIYOMI -> packageName
			UnifiedSourceKind.IREADER -> packageName?.toInstalledIReaderPackageName()
			else -> null
	}
}

private fun UnifiedSourceKind.packageIconRes(): Int {
		return when (this) {
			UnifiedSourceKind.JAR -> R.drawable.ic_file_zip
			UnifiedSourceKind.CLOUDSTREAM -> R.drawable.ic_source_cloudstream
			UnifiedSourceKind.MIHON -> R.drawable.ic_source_mihon
			UnifiedSourceKind.ANIYOMI -> R.drawable.ic_source_aniyomi
			UnifiedSourceKind.IREADER -> R.drawable.ic_source_ireader
		UnifiedSourceKind.LEGADO -> R.drawable.ic_source_legado
		UnifiedSourceKind.TVBOX -> R.drawable.ic_source_tvbox
		UnifiedSourceKind.JS -> R.drawable.ic_source_js
		UnifiedSourceKind.LNREADER -> R.drawable.ic_source_lnreader
		UnifiedSourceKind.NATIVE -> R.drawable.ic_source_builtin
	}
}

@Composable
private fun UnifiedSourcePackageState.displayLabel(): String {
	return when (this) {
		UnifiedSourcePackageState.AVAILABLE -> stringResource(R.string.available)
		UnifiedSourcePackageState.UPDATE_AVAILABLE -> stringResource(R.string.update)
		UnifiedSourcePackageState.INSTALLED -> stringResource(R.string.installed)
		UnifiedSourcePackageState.INSTALLING -> stringResource(R.string.installing_extension)
		UnifiedSourcePackageState.UNTRUSTED -> stringResource(R.string.untrusted_extension)
		UnifiedSourcePackageState.INCOMPATIBLE -> stringResource(R.string.incompatible_extension)
	}
}

private val UnifiedSourcePackageState.isWarning: Boolean
	get() = this == UnifiedSourcePackageState.UNTRUSTED || this == UnifiedSourcePackageState.INCOMPATIBLE

@Composable
private fun UnifiedSourcePackageItem.primaryActionLabel(): String {
	val isLocalApkAction = kind.isSideloadKind()
	return when (state) {
		UnifiedSourcePackageState.AVAILABLE -> stringResource(
			if (isLocalApkAction) R.string.sideload_extension else R.string.install_extension,
		)
		UnifiedSourcePackageState.UPDATE_AVAILABLE -> stringResource(
			if (isLocalApkAction) R.string.update_sideload_extension else R.string.update_extension,
		)
		UnifiedSourcePackageState.UNTRUSTED,
		UnifiedSourcePackageState.INCOMPATIBLE -> stringResource(R.string.details)
		UnifiedSourcePackageState.INSTALLING,
		UnifiedSourcePackageState.INSTALLED -> ""
	}
}

@Composable
private fun CompactActionChip(
	onClick: () -> Unit,
	label: @Composable () -> Unit,
	modifier: Modifier = Modifier,
) {
	AssistChip(
		onClick = onClick,
		modifier = modifier.defaultMinSize(minHeight = 30.dp),
		label = {
			Box(modifier = Modifier.padding(horizontal = 2.dp)) {
				label()
			}
		},
	)
}

@Composable
private fun CompactTag(
	text: String,
	isWarning: Boolean = false,
) {
	Surface(
		shape = RoundedCornerShape(4.dp),
		color = if (isWarning) {
			MaterialTheme.colorScheme.errorContainer
		} else {
			MaterialTheme.colorScheme.secondaryContainer
		},
		contentColor = if (isWarning) {
			MaterialTheme.colorScheme.onErrorContainer
		} else {
			MaterialTheme.colorScheme.onSecondaryContainer
		},
	) {
		Text(
			text = text,
			modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
			style = MaterialTheme.typography.labelSmall,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun UnifiedSourceKind.displayLabel(): String {
	return stringResource(labelResId())
}

private fun UnifiedSourceKind.labelResId(): Int {
		return when (this) {
			UnifiedSourceKind.NATIVE -> R.string.source_type_native
			UnifiedSourceKind.JAR -> R.string.source_type_jar
			UnifiedSourceKind.CLOUDSTREAM -> R.string.source_type_cloudstream
			UnifiedSourceKind.MIHON -> R.string.source_type_mihon
			UnifiedSourceKind.ANIYOMI -> R.string.source_type_aniyomi
			UnifiedSourceKind.IREADER -> R.string.source_type_ireader
		UnifiedSourceKind.LEGADO -> R.string.source_type_legado
		UnifiedSourceKind.TVBOX -> R.string.source_type_tvbox
		UnifiedSourceKind.JS -> R.string.source_type_js
		UnifiedSourceKind.LNREADER -> R.string.source_type_lnreader
	}
}

private fun UnifiedSourceKind.dialogLabelResId(): Int {
		return when (this) {
			UnifiedSourceKind.NATIVE -> R.string.source_type_builtin_short
			UnifiedSourceKind.JAR -> R.string.source_type_jar
			UnifiedSourceKind.CLOUDSTREAM -> R.string.source_type_cloudstream
			UnifiedSourceKind.MIHON -> R.string.source_type_mihon_apk
			UnifiedSourceKind.ANIYOMI -> R.string.source_type_aniyomi_apk
			UnifiedSourceKind.IREADER -> R.string.source_type_ireader_apk
		UnifiedSourceKind.LEGADO -> R.string.source_type_legado_json
		UnifiedSourceKind.TVBOX -> R.string.source_type_tvbox_json
		UnifiedSourceKind.JS -> R.string.source_type_js_source
		UnifiedSourceKind.LNREADER -> R.string.source_type_lnreader
	}
}

private fun UnifiedSourceKind.supportsJsonImport(): Boolean {
	return when (this) {
		UnifiedSourceKind.LEGADO,
		UnifiedSourceKind.TVBOX,
		UnifiedSourceKind.JS,
		UnifiedSourceKind.LNREADER -> true
		else -> false
	}
}

private fun UnifiedSourceKind.isSideloadKind(): Boolean {
		return when (this) {
			UnifiedSourceKind.MIHON,
			UnifiedSourceKind.ANIYOMI,
			UnifiedSourceKind.IREADER -> true
			else -> false
	}
}

@Composable
private fun UnifiedRepositoryLocationType.displayLabel(): String {
	return when (this) {
		UnifiedRepositoryLocationType.REMOTE_URL -> stringResource(R.string.remote_url)
		UnifiedRepositoryLocationType.LOCAL_FILE -> stringResource(R.string.local_file)
		UnifiedRepositoryLocationType.INLINE_IMPORT -> stringResource(R.string.repository_location_inline)
		UnifiedRepositoryLocationType.PRESET_ONLY -> stringResource(R.string.repository_location_preset)
	}
}

@Composable
private fun String.displayLanguageLabel(): String {
	if (isBlank()) return stringResource(R.string.multi_language_short)
	val extensionLabel = getExternalExtensionLanguageDisplayName(this)
	if (extensionLabel != uppercase(Locale.ROOT)) {
		return extensionLabel
	}
	val locale = toLocaleOrNull() ?: Locale.forLanguageTag(this)
	return locale.getDisplayName(LocalContext.current).ifBlank { uppercase() }
}

@Composable
private fun UnifiedEnabledFilter.displayLabel(): String {
	return when (this) {
		UnifiedEnabledFilter.ALL -> stringResource(R.string.all)
		UnifiedEnabledFilter.ENABLED -> stringResource(R.string.enabled)
		UnifiedEnabledFilter.DISABLED -> stringResource(R.string.disabled)
	}
}

@Composable
private fun UnifiedAvailabilityFilter.displayLabel(): String {
	return when (this) {
		UnifiedAvailabilityFilter.ALL -> stringResource(R.string.all)
		UnifiedAvailabilityFilter.AVAILABLE -> stringResource(R.string.available)
		UnifiedAvailabilityFilter.UNAVAILABLE -> stringResource(R.string.unavailable)
	}
}

@Composable
private fun UnifiedNsfwFilter.displayLabel(): String {
	return when (this) {
		UnifiedNsfwFilter.ALL -> stringResource(R.string.all)
		UnifiedNsfwFilter.SFW -> stringResource(R.string.sfw)
		UnifiedNsfwFilter.NSFW -> stringResource(R.string.nsfw)
	}
}

private fun UnifiedSourcesFilterState.otherFilterCount(): Int {
	return locationTypes.size +
		(if (enabledFilter == UnifiedEnabledFilter.ALL) 0 else 1) +
		(if (availabilityFilter == UnifiedAvailabilityFilter.ALL) 0 else 1) +
		(if (nsfwFilter == UnifiedNsfwFilter.ALL) 0 else 1)
}

private val topBarContentTypes = linkedSetOf(
	ContentType.MANGA,
	ContentType.NOVEL,
	ContentType.VIDEO,
)

private fun Set<ContentType>.primaryContentType(): ContentType? {
	return singleOrNull { it in topBarContentTypes }
}
