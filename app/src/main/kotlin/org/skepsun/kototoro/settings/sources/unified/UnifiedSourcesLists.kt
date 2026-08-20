package org.skepsun.kototoro.settings.sources.unified


import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getSummary
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.VerticalScrollbar
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.settings.compose.SettingsContentHorizontalPadding

@Composable
internal fun UnifiedSourceList(
	modifier: Modifier = Modifier,
	listState: LazyListState,
	sources: List<UnifiedSourceItem>,
	onBrowseSource: (UnifiedSourceItem) -> Unit,
	onOpenSourceSettings: (UnifiedSourceItem) -> Unit,
	onSourceEnabledChange: (String, Boolean) -> Unit,
	onEnableAllSources: () -> Unit,
	onDisableAllSources: () -> Unit,
	selectedSourceIds: Set<String>,
	onSourceSelectionChange: (Set<String>) -> Unit,
	onSourcePinnedChange: (String, Boolean) -> Unit,
) {
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	val horizontalPadding = if (expressive) 8.dp else 0.dp
	Box(modifier = modifier) {
		LazyColumn(
			state = listState,
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = horizontalPadding,
				top = 4.dp,
				end = SettingsContentHorizontalPadding,
				bottom = 4.dp,
			),
		) {
			item(key = "source_actions") {
				LazyRow(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 4.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					item(key = "enable_all_sources") {
						CompactActionChip(
							onClick = onEnableAllSources,
							enabled = sources.isNotEmpty(),
							label = { Text(stringResource(R.string.unified_sources_enable_all)) },
						)
					}
					item(key = "disable_all_sources") {
						CompactActionChip(
							onClick = onDisableAllSources,
							enabled = sources.isNotEmpty(),
							label = { Text(stringResource(R.string.unified_sources_disable_all)) },
						)
					}
				}
			}
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
		VerticalScrollbar(
			state = listState,
			alwaysVisible = true,
			endInset = 4.dp,
		)
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
			}
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				CompactTag(text = item.kind.displayLabel())
				if (!item.isAvailable || item.isBroken) {
					CompactTag(text = stringResource(R.string.unavailable), tone = CompactTagTone.Warning)
				}
				when (item.testAvailability) {
					ContentSourceAvailability.AVAILABLE -> CompactTag(
						text = stringResource(R.string.source_test_available),
						tone = CompactTagTone.TestedAvailable,
					)
					ContentSourceAvailability.EMPTY -> CompactTag(
						text = stringResource(R.string.source_test_unavailable),
						tone = CompactTagTone.TestedUnavailable,
					)
					ContentSourceAvailability.UNKNOWN -> Unit
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
internal fun UnifiedRepositoryList(
	modifier: Modifier = Modifier,
	listState: LazyListState,
	repositories: List<UnifiedSourceRepositoryItem>,
	onAddRepository: (UnifiedSourceRepositoryItem?) -> Unit,
	onRefreshRepository: (UnifiedSourceRepositoryItem) -> Unit,
	onDeleteRepository: (UnifiedSourceRepositoryItem) -> Unit,
) {
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	val style = rememberUnifiedSourcesVisualStyle()
	Box(modifier = modifier) {
		LazyColumn(
			state = listState,
			modifier = Modifier.fillMaxSize(),
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
		VerticalScrollbar(
			state = listState,
			alwaysVisible = true,
			endInset = 4.dp,
		)
	}
}

@Composable
internal fun UnifiedPackageList(
	modifier: Modifier = Modifier,
	listState: LazyListState,
	packages: List<UnifiedSourcePackageItem>,
	updateAllInProgress: Boolean,
	onUpdateAllPackages: () -> Unit,
	onPackagePrimaryAction: (String) -> Unit,
	onPackageSystemInstall: (String) -> Unit,
	onPackageUninstall: (String) -> Unit,
	onPackageCancelInstall: (String) -> Unit,
	onImportLocalJar: () -> Unit,
) {
	Box(modifier = modifier) {
		LazyColumn(
			state = listState,
			modifier = Modifier.fillMaxSize(),
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
					item(key = "update_all_packages") {
						CompactActionChip(
							onClick = onUpdateAllPackages,
							label = {
								Text(
									stringResource(
										if (updateAllInProgress) {
											R.string.cancel_update_all_packages
										} else {
											R.string.update_all_packages
										},
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
		VerticalScrollbar(
			state = listState,
			alwaysVisible = true,
			endInset = 4.dp,
		)
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

