package org.skepsun.kototoro.main.ui.welcome

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer
import org.skepsun.kototoro.core.ui.glass.LocalHazeState
import org.skepsun.kototoro.core.ui.sheet.BaseAdaptiveSheet
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.util.ext.getDisplayName
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.databinding.SheetWelcomeBinding
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.parsers.model.ContentType
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.HazePositionStrategy
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.util.Locale

private const val REPO_KOTOTORO =
	"https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/index.min.json"
private const val REPO_REDO =
	"https://raw.githubusercontent.com/skepsun/k-parsers-r/repo/index.min.json"

@AndroidEntryPoint
class WelcomeSheet : BaseAdaptiveSheet<SheetWelcomeBinding>(), ActivityResultCallback<Uri?> {

	private val viewModel by viewModels<WelcomeViewModel>()

	private val backupSelectCall = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
		this,
	)

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetWelcomeBinding {
		return SheetWelcomeBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetWelcomeBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		disableFitToContents()
		binding.root.post { setExpanded(isExpanded = true, isLocked = true) }
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			KototoroTheme {
				WelcomeRoute(
					viewModel = viewModel,
					mirrorEntries = resources.getStringArray(R.array.pref_github_mirror_entries).toList(),
					onRestoreBackup = ::openBackupDocument,
					onDone = { dismiss() },
				)
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		return insets
	}

	override fun onActivityResult(result: Uri?) {
		if (result != null) {
			router.showBackupRestoreDialog(result)
		}
	}

	private fun openBackupDocument() {
		if (!backupSelectCall.tryLaunch(arrayOf("*/*"))) {
			view?.let { Snackbar.make(it, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show() }
		}
	}

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeRoute(
	viewModel: WelcomeViewModel,
	mirrorEntries: List<String>,
	onRestoreBackup: () -> Unit,
	onDone: () -> Unit,
) {
	val locales by viewModel.locales.collectAsStateWithLifecycle()
	val types by viewModel.types.collectAsStateWithLifecycle()
	val isInitializing by viewModel.isInitializingPlugins.collectAsStateWithLifecycle()
	val pagerState = rememberPagerState(pageCount = { 2 })
	val scope = rememberCoroutineScope()
	val selectedRepos = remember { mutableStateListOf(REPO_KOTOTORO, REPO_REDO) }
	var selectedMirrorIndex by rememberSaveable { mutableIntStateOf(0) }
	var showAdvanced by rememberSaveable { mutableStateOf(false) }
	var showDisclaimer by rememberSaveable { mutableStateOf(false) }
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	val hazeState = remember { HazeState().apply { positionStrategy = HazePositionStrategy.Screen } }

	BackHandler(enabled = pagerState.currentPage > 0 && !isInitializing) {
		scope.launch { pagerState.animateScrollToPage(0) }
	}

	CompositionLocalProvider(LocalHazeState provides hazeState) {
	Box(
		modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
	) {
		HorizontalPager(
			state = pagerState,
			userScrollEnabled = !isInitializing,
			modifier = Modifier.fillMaxSize().hazeSource(hazeState),
		) { page ->
			Column(
				modifier = Modifier
					.fillMaxSize()
					.verticalScroll(rememberScrollState())
					.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 112.dp),
				verticalArrangement = Arrangement.spacedBy(18.dp),
			) {
				if (page == 0) {
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
				} else {
					WelcomePreferencesStep(
						locales = locales,
						types = types,
						onLocaleToggle = viewModel::setLocaleChecked,
						onTypeToggle = viewModel::setTypeChecked,
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
						repeat(2) { index ->
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
							if (pagerState.currentPage == 1) {
								onDone()
							} else {
								scope.launch { pagerState.animateScrollToPage(1) }
							}
						},
						enabled = !isInitializing,
						modifier = Modifier.height(48.dp),
					) {
						Text(stringResource(if (pagerState.currentPage == 1) R.string.done else R.string.next))
						Spacer(Modifier.width(8.dp))
						Icon(
							if (pagerState.currentPage == 1) Icons.Default.Done else Icons.AutoMirrored.Filled.ArrowForward,
							contentDescription = null,
							modifier = Modifier.size(18.dp),
						)
					}
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
