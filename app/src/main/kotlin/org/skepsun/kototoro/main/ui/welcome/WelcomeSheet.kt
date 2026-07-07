package org.skepsun.kototoro.main.ui.welcome

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.skepsun.kototoro.core.ui.sheet.BaseAdaptiveSheet
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.util.ext.getDisplayName
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.databinding.SheetWelcomeBinding
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.parsers.model.ContentType
import java.util.Locale

private const val REPO_KOTOTORO =
	"https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/index.min.json"
private const val REPO_YAKATEAM =
	"https://raw.githubusercontent.com/skepsun/k-parsers-y/repo/index.min.json"
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
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			KototoroTheme {
				WelcomeRoute(
					viewModel = viewModel,
					mirrorEntries = resources.getStringArray(R.array.pref_github_mirror_entries).toList(),
					onRestoreBackup = ::openBackupDocument,
					onSync = { router.openSyncSettings() },
					onDirectories = { router.openDirectoriesSettings() },
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
	onSync: () -> Unit,
	onDirectories: () -> Unit,
	onDone: () -> Unit,
) {
	val locales by viewModel.locales.collectAsStateWithLifecycle()
	val types by viewModel.types.collectAsStateWithLifecycle()
	val isInitializing by viewModel.isInitializingPlugins.collectAsStateWithLifecycle()
	var step by rememberSaveable { mutableIntStateOf(0) }
	val selectedRepos = remember { mutableStateListOf(REPO_KOTOTORO) }
	var selectedMirrorIndex by rememberSaveable { mutableIntStateOf(0) }
	var disclaimerAccepted by rememberSaveable { mutableStateOf(false) }
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	val steps = listOf(
		stringResource(R.string.welcome_step_start),
		stringResource(R.string.welcome_step_sources),
		stringResource(R.string.welcome_step_preferences),
		stringResource(R.string.welcome_step_ready),
	)

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.surface)
			.windowInsetsPadding(WindowInsets.navigationBars)
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 20.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		WelcomeHero(expressive = expressive)
		StepIndicator(steps = steps, currentStep = step, expressive = expressive)

		when (step) {
			0 -> WelcomeStartStep(
				onRestoreBackup = onRestoreBackup,
				onSync = onSync,
				onDirectories = onDirectories,
				expressive = expressive,
			)
			1 -> WelcomeSourcesStep(
				mirrorEntries = mirrorEntries,
				selectedMirrorIndex = selectedMirrorIndex,
				onMirrorSelected = { selectedMirrorIndex = it },
				selectedRepos = selectedRepos,
				disclaimerAccepted = disclaimerAccepted,
				onDisclaimerAccepted = { disclaimerAccepted = it },
				isInitializing = isInitializing,
				onInitialize = {
					viewModel.initializePlugins(selectedMirrorIndex, selectedRepos.toList())
				},
				expressive = expressive,
			)
			2 -> WelcomePreferencesStep(
				locales = locales,
				types = types,
				onLocaleToggle = viewModel::setLocaleChecked,
				onTypeToggle = viewModel::setTypeChecked,
				expressive = expressive,
			)
			else -> WelcomeReadyStep(types = types, locales = locales, expressive = expressive)
		}

		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			OutlinedButton(
				onClick = { step = (step - 1).coerceAtLeast(0) },
				enabled = step > 0 && !isInitializing,
			) {
				Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
				Spacer(Modifier.width(8.dp))
				Text(stringResource(R.string.back))
			}
			Button(
				onClick = {
					if (step == steps.lastIndex) {
						onDone()
					} else {
						step = (step + 1).coerceAtMost(steps.lastIndex)
					}
				},
				enabled = !isInitializing,
			) {
				Text(stringResource(if (step == steps.lastIndex) R.string.done else R.string.next))
				Spacer(Modifier.width(8.dp))
				Icon(
					if (step == steps.lastIndex) Icons.Default.Done else Icons.Default.ArrowForward,
					contentDescription = null,
					modifier = Modifier.size(18.dp),
				)
			}
		}
	}
}

@Composable
private fun WelcomeHero(expressive: Boolean) {
	val shape = RoundedCornerShape(if (expressive) 28.dp else 18.dp)
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.primaryContainer,
		contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier.padding(20.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			Surface(
				shape = RoundedCornerShape(if (expressive) 22.dp else 14.dp),
				color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
			) {
				Icon(
					painter = rememberSafePainter(R.drawable.ic_welcome),
					contentDescription = null,
					modifier = Modifier
						.padding(14.dp)
						.size(if (expressive) 34.dp else 28.dp),
				)
			}
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(
					text = stringResource(R.string.welcome_intro_title),
					style = MaterialTheme.typography.headlineSmall,
					fontWeight = FontWeight.SemiBold,
				)
				Text(
					text = stringResource(R.string.welcome_intro_summary),
					style = MaterialTheme.typography.bodyMedium,
				)
			}
		}
	}
}

@Composable
private fun StepIndicator(steps: List<String>, currentStep: Int, expressive: Boolean) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		steps.forEachIndexed { index, label ->
			val selected = index == currentStep
			Surface(
				modifier = Modifier.weight(1f),
				shape = RoundedCornerShape(if (expressive) 18.dp else 12.dp),
				color = if (selected) {
					MaterialTheme.colorScheme.secondaryContainer
				} else {
					MaterialTheme.colorScheme.surfaceContainerLow
				},
				contentColor = if (selected) {
					MaterialTheme.colorScheme.onSecondaryContainer
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			) {
				Text(
					text = label,
					modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
					style = MaterialTheme.typography.labelMedium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun WelcomeStartStep(
	onRestoreBackup: () -> Unit,
	onSync: () -> Unit,
	onDirectories: () -> Unit,
	expressive: Boolean,
) {
	SectionHeader(
		title = stringResource(R.string.welcome),
		summary = stringResource(R.string.welcome_restore_summary),
	)
	FlowRow(
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		AssistActionChip(R.drawable.ic_backup_restore, R.string.restore_backup, onRestoreBackup)
		AssistActionChip(R.drawable.ic_sync, R.string.sync_auth, onSync)
		AssistActionChip(R.drawable.ic_storage, R.string.local_manga_directories, onDirectories)
	}
	Surface(
		shape = RoundedCornerShape(if (expressive) 24.dp else 16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
	) {
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			CapabilityRow(R.drawable.ic_manga_source, stringResource(R.string.welcome_ecosystem_manga))
			CapabilityRow(R.drawable.ic_book_page, stringResource(R.string.welcome_ecosystem_novel))
			CapabilityRow(R.drawable.ic_play, stringResource(R.string.welcome_ecosystem_video))
		}
	}
}

@Composable
private fun WelcomeSourcesStep(
	mirrorEntries: List<String>,
	selectedMirrorIndex: Int,
	onMirrorSelected: (Int) -> Unit,
	selectedRepos: MutableList<String>,
	disclaimerAccepted: Boolean,
	onDisclaimerAccepted: (Boolean) -> Unit,
	isInitializing: Boolean,
	onInitialize: () -> Unit,
	expressive: Boolean,
) {
	SectionHeader(
		title = stringResource(R.string.welcome_ecosystems_title),
		summary = stringResource(R.string.welcome_ecosystems_summary),
	)
	SourceFamilyGrid(expressive = expressive)
	SectionHeader(
		title = stringResource(R.string.welcome_plugin_repositories),
		summary = stringResource(R.string.welcome_plugins_summary),
	)
	FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		RepoChip(R.string.welcome_plugins_repo_kototoro, REPO_KOTOTORO, selectedRepos, enabled = !isInitializing)
		RepoChip(R.string.welcome_plugins_repo_yakateam, REPO_YAKATEAM, selectedRepos, enabled = !isInitializing)
		RepoChip(R.string.welcome_plugins_repo_redo, REPO_REDO, selectedRepos, enabled = !isInitializing)
	}
	MirrorDropdown(
		entries = mirrorEntries,
		selectedIndex = selectedMirrorIndex,
		onSelected = onMirrorSelected,
		enabled = !isInitializing,
	)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = !isInitializing) { onDisclaimerAccepted(!disclaimerAccepted) }
			.padding(vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Checkbox(
			checked = disclaimerAccepted,
			onCheckedChange = onDisclaimerAccepted,
			enabled = !isInitializing,
		)
		Text(
			text = stringResource(R.string.welcome_plugin_acknowledge),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
	Button(
		onClick = onInitialize,
		enabled = disclaimerAccepted && selectedRepos.isNotEmpty() && !isInitializing,
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
}

@Composable
private fun WelcomePreferencesStep(
	locales: FilterProperty<Locale>,
	types: FilterProperty<ContentType>,
	onLocaleToggle: (Locale, Boolean) -> Unit,
	onTypeToggle: (ContentType, Boolean) -> Unit,
	expressive: Boolean,
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
		label = { it.getDisplayName(androidx.compose.ui.platform.LocalContext.current) },
		onToggle = onLocaleToggle,
	)
	if (locales.isLoading || types.isLoading) {
		LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
	}
}

@Composable
private fun WelcomeReadyStep(
	types: FilterProperty<ContentType>,
	locales: FilterProperty<Locale>,
	expressive: Boolean,
) {
	Surface(
		shape = RoundedCornerShape(if (expressive) 28.dp else 18.dp),
		color = MaterialTheme.colorScheme.tertiaryContainer,
		contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier.padding(20.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(36.dp))
			Text(
				text = stringResource(R.string.welcome_step_ready),
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.SemiBold,
			)
			Text(text = stringResource(R.string.welcome_ready_summary), style = MaterialTheme.typography.bodyMedium)
		}
	}
	SelectionSummary(types = types, locales = locales)
}

@Composable
private fun SectionHeader(title: String, summary: String) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
		Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
private fun AssistActionChip(iconRes: Int, textRes: Int, onClick: () -> Unit) {
	AssistChip(
		onClick = onClick,
		label = { Text(stringResource(textRes)) },
		leadingIcon = {
			Icon(rememberSafePainter(iconRes), contentDescription = null, modifier = Modifier.size(18.dp))
		},
	)
}

@Composable
private fun CapabilityRow(iconRes: Int, text: String) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		Icon(
			painter = rememberSafePainter(iconRes),
			contentDescription = null,
			modifier = Modifier.size(22.dp),
			tint = MaterialTheme.colorScheme.primary,
		)
		Text(text = text, style = MaterialTheme.typography.bodyMedium)
	}
}

@Composable
private fun SourceFamilyGrid(expressive: Boolean) {
	val items = listOf(
		R.drawable.ic_extension to "JAR",
		R.drawable.ic_source_mihon to "Mihon",
		R.drawable.ic_source_aniyomi to "Aniyomi",
		R.drawable.ic_source_ireader to "IReader",
		R.drawable.ic_source_legado to "Legado",
		R.drawable.ic_source_tvbox to "TVBox",
		R.drawable.ic_source_lnreader to "LNReader",
		R.drawable.ic_source_cloudstream to "Cloudstream",
	)
	FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		items.forEach { (icon, label) ->
			Surface(
				shape = RoundedCornerShape(if (expressive) 18.dp else 12.dp),
				color = MaterialTheme.colorScheme.surfaceContainerLow,
				border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
			) {
				Row(
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Icon(rememberSafePainter(icon), contentDescription = null, modifier = Modifier.size(20.dp))
					Text(label, style = MaterialTheme.typography.labelLarge)
				}
			}
		}
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

@Composable
private fun SelectionSummary(types: FilterProperty<ContentType>, locales: FilterProperty<Locale>) {
	val context = LocalContext.current
	val typeNames = types.selectedItems.map { stringResource(it.titleResId) }
	val localeNames = locales.selectedItems.map { it.getDisplayName(context) }
	Surface(
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			SummaryLine(
				iconRes = R.drawable.ic_manga_source,
				title = stringResource(R.string.type),
				value = typeNames.joinToString(),
			)
			SummaryLine(
				iconRes = R.drawable.ic_language,
				title = stringResource(R.string.languages),
				value = localeNames.joinToString(),
			)
		}
	}
}

@Composable
private fun SummaryLine(iconRes: Int, title: String, value: String) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		Icon(rememberSafePainter(iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
		Column {
			Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			Text(value.ifBlank { "-" }, style = MaterialTheme.typography.bodyMedium)
		}
	}
}
