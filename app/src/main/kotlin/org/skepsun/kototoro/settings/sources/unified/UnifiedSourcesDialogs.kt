package org.skepsun.kototoro.settings.sources.unified


import android.util.Log
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepo
import org.skepsun.kototoro.settings.sources.extensions.formatExtensionFingerprint
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton

@Composable
internal fun <T> UnifiedSelectionDialog(
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
internal fun UnifiedInstallChoiceDialog(
	kind: UnifiedSourceKind,
	name: String,
	sourceCount: Int,
	onDismiss: () -> Unit,
	onChoice: (ExtensionInstallPolicy, Boolean) -> Unit,
) {
	var rememberChoice by rememberSaveable(kind) { mutableStateOf(false) }
	SettingsAlertDialog(
		title = stringResource(R.string.extension_install_choice_title, name),
		onDismissRequest = onDismiss,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(R.string.extension_install_and_enable),
				onClick = {
					onChoice(ExtensionInstallPolicy.INSTALL_AND_ENABLE, rememberChoice)
				},
			)
		},
		dismissButton = {
			Row(verticalAlignment = Alignment.CenterVertically) {
				SettingsDialogActionButton(
					text = stringResource(android.R.string.cancel),
					onClick = onDismiss,
				)
				SettingsDialogActionButton(
					text = stringResource(R.string.extension_install_only),
					onClick = {
						onChoice(ExtensionInstallPolicy.INSTALL_ONLY, rememberChoice)
					},
				)
			}
		},
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(
					text = if (sourceCount > 0) {
						stringResource(R.string.extension_install_choice_message, sourceCount)
					} else {
						stringResource(R.string.extension_install_choice_message_unknown)
					},
				)
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.combinedClickable(onClick = { rememberChoice = !rememberChoice }),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Checkbox(
						checked = rememberChoice,
						onCheckedChange = { rememberChoice = it },
					)
					Text(
						text = stringResource(
							R.string.extension_install_remember_choice,
							stringResource(kind.dialogLabelResId()),
						),
					)
				}
			}
		},
	)
}

@Composable
internal fun UnifiedRepositoryUrlDialog(
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
internal fun UnifiedInlineRepositoryDialog(
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
internal fun UnifiedDisclaimerDialog(
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
internal fun UnifiedDeleteRepositoryDialog(
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
internal fun UnifiedSetFilteredSourcesEnabledDialog(
	enabled: Boolean,
	sourceCount: Int,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	SettingsAlertDialog(
		title = stringResource(
			if (enabled) {
				R.string.unified_sources_enable_all
			} else {
				R.string.unified_sources_disable_all
			},
		),
		onDismissRequest = onDismiss,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(if (enabled) R.string.enable else R.string.disable),
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
				stringResource(
					if (enabled) {
						R.string.unified_sources_enable_all_confirmation
					} else {
						R.string.unified_sources_disable_all_confirmation
					},
					sourceCount,
				),
			)
		},
	)
}

@Composable
internal fun UnifiedDeleteSelectedSourcesDialog(
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
internal fun UnifiedTrustRepositoryDialog(
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
internal fun UnifiedPackageStateDetailsDialog(
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
internal fun UnifiedLanguageFilterDialog(
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

