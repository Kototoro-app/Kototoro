package org.skepsun.kototoro.main.ui.welcome

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import org.skepsun.kototoro.core.prefs.SpaceSwitcherPosition
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.util.ext.getDisplayName
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepositories
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private const val REPO_KOTOTORO =
	"https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/index.min.json"
private const val REPO_REDO =
	"https://raw.githubusercontent.com/skepsun/k-parsers-r/repo/index.min.json"

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeRoute(
	onDismissRequest: () -> Unit,
	onRestoreBackup: (Uri) -> Unit,
	onOpenDocumentUnsupported: () -> Unit = {},
	viewModel: WelcomeViewModel = hiltViewModel(),
) {
	val context = LocalContext.current
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val backupSelectLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		uri?.let(onRestoreBackup)
	}
	val mirrorEntries = remember(context) {
		context.resources.getStringArray(R.array.pref_github_mirror_entries).toList()
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
	onDone: () -> Unit,
) {
	val locales by viewModel.locales.collectAsStateWithLifecycle()
	val types by viewModel.types.collectAsStateWithLifecycle()
	val spacesEnabled by viewModel.spacesEnabled.collectAsStateWithLifecycle()
	val interfaceStyle by viewModel.interfaceStyle.collectAsStateWithLifecycle()
	val heroTransitionsEnabled by viewModel.heroTransitionsEnabled.collectAsStateWithLifecycle()
	val panoramaAnimationEnabled by viewModel.panoramaAnimationEnabled.collectAsStateWithLifecycle()
	val panoramaTransitionIntensity by viewModel.panoramaTransitionIntensity.collectAsStateWithLifecycle()
	val detailsPanoramaHalfScreenEnabled by viewModel.detailsPanoramaHalfScreenEnabled.collectAsStateWithLifecycle()
	val spaceSwitcherPosition by viewModel.spaceSwitcherPosition.collectAsStateWithLifecycle()
	val isInitializing by viewModel.isInitializingPlugins.collectAsStateWithLifecycle()
	val pagerState = rememberPagerState(pageCount = { 4 })
	val scope = rememberCoroutineScope()
	val selectedRepos = remember { mutableStateListOf(REPO_KOTOTORO, REPO_REDO) }
	var selectedMirrorIndex by rememberSaveable { mutableIntStateOf(0) }
	var showAdvanced by rememberSaveable { mutableStateOf(false) }
	var showDisclaimer by rememberSaveable { mutableStateOf(false) }
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	BackHandler(enabled = pagerState.currentPage > 0 && !isInitializing) {
		scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
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
						WelcomeHero(expressive = expressive)
						WelcomeSourcesStep(
							mirrorEntries = mirrorEntries,
							selectedMirrorIndex = selectedMirrorIndex,
							onMirrorSelected = { selectedMirrorIndex = it },
							selectedRepos = selectedRepos,
							showAdvanced = showAdvanced,
							onAdvancedToggle = { showAdvanced = !showAdvanced },
							isInitializing = isInitializing,
							onInitialize = { showDisclaimer = true },
							onRestoreBackup = onRestoreBackup,
						)
					}

					1 -> WelcomePreferencesStep(
						locales = locales,
						types = types,
						onLocaleToggle = viewModel::setLocaleChecked,
						onTypeToggle = viewModel::setTypeChecked,
					)

					2 -> WelcomeAppearanceStep(
						interfaceStyle = interfaceStyle,
						heroTransitionsEnabled = heroTransitionsEnabled,
						panoramaAnimationEnabled = panoramaAnimationEnabled,
						panoramaTransitionIntensity = panoramaTransitionIntensity,
						detailsPanoramaHalfScreenEnabled = detailsPanoramaHalfScreenEnabled,
						onInterfaceStyleChange = viewModel::setInterfaceStyle,
						onHeroTransitionsChange = viewModel::setHeroTransitionsEnabled,
						onPanoramaAnimationChange = viewModel::setPanoramaAnimationEnabled,
						onPanoramaTransitionIntensityChange = viewModel::setPanoramaTransitionIntensity,
						onDetailsPanoramaHalfScreenChange = viewModel::setDetailsPanoramaHalfScreenEnabled,
					)

					else -> WelcomeSpacesStep(
						spacesEnabled = spacesEnabled,
						onSpacesEnabledChange = viewModel::setSpacesEnabled,
						spaceSwitcherPosition = spaceSwitcherPosition,
						onSpaceSwitcherPositionChange = viewModel::setSpaceSwitcherPosition,
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
			text = stringResource(R.string.welcome_intro_title),
			style = MaterialTheme.typography.headlineSmall,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}

@Composable
private fun WelcomeSourcesStep(
	mirrorEntries: List<String>,
	selectedMirrorIndex: Int,
	onMirrorSelected: (Int) -> Unit,
	selectedRepos: MutableList<String>,
	showAdvanced: Boolean,
	onAdvancedToggle: () -> Unit,
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
	TextButton(
		onClick = onAdvancedToggle,
		enabled = !isInitializing,
		modifier = Modifier.fillMaxWidth(),
	) {
		Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
		Spacer(Modifier.width(8.dp))
		Text(stringResource(R.string.advanced))
		Spacer(Modifier.weight(1f))
		Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
	}
	if (showAdvanced) {
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			RepoChip(R.string.welcome_plugins_repo_kototoro, REPO_KOTOTORO, selectedRepos, enabled = !isInitializing)
			RepoChip(R.string.welcome_plugins_repo_redo, REPO_REDO, selectedRepos, enabled = !isInitializing)
			RepoChip(
				R.string.welcome_plugins_repo_uma,
				UnifiedRecommendedRepositories.UMA_REPOSITORY_URL,
				selectedRepos,
				enabled = !isInitializing,
			)
		}
		MirrorDropdown(
			entries = mirrorEntries,
			selectedIndex = selectedMirrorIndex,
			onSelected = onMirrorSelected,
			enabled = !isInitializing,
		)
	}
	TextButton(onClick = onRestoreBackup, enabled = !isInitializing) {
		Icon(rememberSafePainter(R.drawable.ic_backup_restore), contentDescription = null)
		Spacer(Modifier.width(8.dp))
		Text(stringResource(R.string.restore_backup))
	}
}

@Composable
private fun WelcomePreferencesStep(
	locales: FilterProperty<Locale>,
	types: FilterProperty<ContentType>,
	onLocaleToggle: (Locale, Boolean) -> Unit,
	onTypeToggle: (ContentType, Boolean) -> Unit,
) {
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
	heroTransitionsEnabled: Boolean,
	panoramaAnimationEnabled: Boolean,
	panoramaTransitionIntensity: Int,
	detailsPanoramaHalfScreenEnabled: Boolean,
	onInterfaceStyleChange: (InterfaceStyle) -> Unit,
	onHeroTransitionsChange: (Boolean) -> Unit,
	onPanoramaAnimationChange: (Boolean) -> Unit,
	onPanoramaTransitionIntensityChange: (Int) -> Unit,
	onDetailsPanoramaHalfScreenChange: (Boolean) -> Unit,
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
	WelcomeSwitchRow(
		title = stringResource(R.string.shared_element_transitions),
		summary = stringResource(R.string.shared_element_transitions_summary),
		checked = heroTransitionsEnabled,
		onCheckedChange = onHeroTransitionsChange,
	)
	WelcomeSwitchRow(
		title = stringResource(R.string.pref_panorama_animation),
		summary = stringResource(R.string.pref_panorama_animation_summary),
		checked = panoramaAnimationEnabled,
		onCheckedChange = onPanoramaAnimationChange,
	)
	WelcomeSliderRow(
		title = stringResource(R.string.pref_panorama_transition_intensity),
		summary = stringResource(R.string.pref_panorama_transition_intensity_summary),
		value = panoramaTransitionIntensity,
		onValueChange = onPanoramaTransitionIntensityChange,
	)
	WelcomeSwitchRow(
		title = stringResource(R.string.pref_details_panorama_limit_to_info_card_midpoint),
		summary = stringResource(R.string.pref_details_panorama_limit_to_info_card_midpoint_summary),
		checked = detailsPanoramaHalfScreenEnabled,
		onCheckedChange = onDetailsPanoramaHalfScreenChange,
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
	labelRes: Int,
	repoUrl: String,
	selectedRepos: MutableList<String>,
	enabled: Boolean,
) {
	val selected = repoUrl in selectedRepos
	FilterChip(
		selected = selected,
		onClick = {
			if (selected) {
				selectedRepos.remove(repoUrl)
			} else {
				selectedRepos.add(repoUrl)
			}
		},
		enabled = enabled,
		label = { Text(stringResource(labelRes)) },
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
