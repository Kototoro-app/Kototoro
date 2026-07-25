package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.reader.ui.config.ImageServerOptions
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelSection

@Immutable
internal data class ComposeReaderOptionsState(
	val visible: Boolean = false,
	val mode: ReaderMode = ReaderMode.STANDARD,
	val doublePage: Boolean = false,
	val doublePageFoldable: Boolean = false,
	val doublePageCover: Boolean = false,
	val splitPages: Boolean = false,
	val doublePageSensitivity: Float = 0.5f,
	val superResolution: Boolean = false,
	val background: ReaderBackground = ReaderBackground.DEFAULT,
	val imageServer: ImageServerOptions? = null,
)

internal data class ComposeReaderOptionsCallbacks(
	val onDismiss: () -> Unit = {},
	val onModeChanged: (ReaderMode) -> Unit = {},
	val onDoublePageChanged: (Boolean) -> Unit = {},
	val onDoublePageFoldableChanged: (Boolean) -> Unit = {},
	val onDoublePageCoverChanged: (Boolean) -> Unit = {},
	val onSplitPagesChanged: (Boolean) -> Unit = {},
	val onDoublePageSensitivityChanged: (Float) -> Unit = {},
	val onSuperResolutionChanged: (Boolean) -> Unit = {},
	val onBackgroundChanged: (ReaderBackground) -> Unit = {},
	val onImageServerChanged: (String?) -> Unit = {},
	val onSavePage: () -> Unit = {},
	val onBookmark: () -> Unit = {},
	val onRotate: () -> Unit = {},
	val onAutoScroll: () -> Unit = {},
	val onTranslation: () -> Unit = {},
	val onOpenSettings: () -> Unit = {},
	val onColorFilter: () -> Unit = {},
	val onOpenBrowser: () -> Unit = {},
	val onTranslationSettings: () -> Unit = {},
	val onRetranslatePage: () -> Unit = {},
	val onRetryFailedTranslations: () -> Unit = {},
	val onRetranslateChapter: () -> Unit = {},
	val onTranslationLog: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposeReaderOptionsSheet(
	state: ComposeReaderOptionsState,
	callbacks: ComposeReaderOptionsCallbacks,
	embedded: Boolean = false,
	modifier: Modifier = Modifier,
) {
	if (!state.visible) return
	val backgroundLabels = stringArrayResource(R.array.reader_backgrounds)
	Surface(
		shape = if (embedded) androidx.compose.foundation.shape.RoundedCornerShape(0.dp) else MaterialTheme.shapes.large,
		color = if (embedded) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 420.dp),
	) {
		ReaderPanelDragHandle(onDismiss = callbacks.onDismiss)
		BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
			val contentWidth = minOf(maxWidth, 760.dp)
			LazyColumn(
				verticalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier
					.widthIn(max = contentWidth)
					.align(Alignment.TopCenter)
					.padding(horizontal = 16.dp),
			) {
			item {
				ReaderPanelSection(embedded = embedded) {
					Column {
						SectionTitle(stringResource(R.string.reader_page_turning_mode))
					FlowRow(
						maxItemsInEachRow = 2,
						horizontalArrangement = Arrangement.spacedBy(12.dp),
						modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
					) {
						SelectRow(
							title = stringResource(R.string.reader_page_turning_mode),
							selected = state.mode.label(),
							options = ReaderMode.entries.map { it.label() },
							onSelected = { callbacks.onModeChanged(ReaderMode.entries[it]) },
							modifier = Modifier.weight(1f),
						)
						SelectRow(
							title = stringResource(R.string.background),
							selected = backgroundLabels.getOrElse(state.background.ordinal) { state.background.name },
							options = backgroundLabels.toList(),
							onSelected = { callbacks.onBackgroundChanged(ReaderBackground.entries[it]) },
							modifier = Modifier.weight(1f),
						)
						}
					}
				}
			}
			item {
				ReaderPanelSection(embedded = embedded) {
					Column {
						SectionTitle(stringResource(R.string.double_page_mode))
				FlowRow(
					maxItemsInEachRow = 2,
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					verticalArrangement = Arrangement.spacedBy(2.dp),
					modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
				) {
					OptionSwitch(
						text = stringResource(R.string.double_page_landscape),
						checked = state.doublePage,
						enabled = state.mode == ReaderMode.STANDARD || state.mode == ReaderMode.REVERSED,
						onCheckedChange = callbacks.onDoublePageChanged,
						modifier = Modifier.weight(1f),
					)
					OptionSwitch(
						text = stringResource(R.string.double_page_foldable),
						checked = state.doublePageFoldable,
						enabled = state.doublePage && (state.mode == ReaderMode.STANDARD || state.mode == ReaderMode.REVERSED),
						onCheckedChange = callbacks.onDoublePageFoldableChanged,
						modifier = Modifier.weight(1f),
					)
					OptionSwitch(
						text = stringResource(R.string.double_page_cover_page),
						checked = state.doublePageCover,
						enabled = state.doublePage && (state.mode == ReaderMode.STANDARD || state.mode == ReaderMode.REVERSED),
						onCheckedChange = callbacks.onDoublePageCoverChanged,
						modifier = Modifier.weight(1f),
					)
					if (state.doublePage) {
						Column(modifier = Modifier.fillMaxWidth()) {
							Row(verticalAlignment = Alignment.CenterVertically) {
								Text(
									text = stringResource(R.string.two_page_scroll_sensitivity),
									style = MaterialTheme.typography.bodyMedium,
									modifier = Modifier.weight(1f),
								)
								Text(
									text = "${(state.doublePageSensitivity * 100).toInt()}%",
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.primary,
								)
							}
							Slider(
								value = state.doublePageSensitivity,
								onValueChange = callbacks.onDoublePageSensitivityChanged,
								valueRange = 0f..1f,
							)
						}
					}
				}
				}
				}
			}
			item {
				ReaderPanelSection(embedded = embedded) {
					Column {
						SectionTitle(stringResource(R.string.miscellaneous))
					FlowRow(
						maxItemsInEachRow = 2,
						horizontalArrangement = Arrangement.spacedBy(12.dp),
						verticalArrangement = Arrangement.spacedBy(2.dp),
						modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
					) {
						OptionSwitch(
							text = stringResource(R.string.reader_super_resolution),
							checked = state.superResolution,
							onCheckedChange = callbacks.onSuperResolutionChanged,
							modifier = Modifier.weight(1f),
						)
						OptionSwitch(
							text = stringResource(R.string.split_double_pages),
							checked = state.splitPages,
							onCheckedChange = callbacks.onSplitPagesChanged,
							modifier = Modifier.weight(1f),
						)
						OptionAction(R.drawable.ic_save, R.string.save_page, callbacks.onSavePage)
						OptionAction(R.drawable.ic_appearance, R.string.color_correction, callbacks.onColorFilter)
						OptionAction(R.drawable.ic_web, R.string.open_in_browser, callbacks.onOpenBrowser)
						}
					}
				}
			}
			state.imageServer?.let { imageServer ->
				item {
					ReaderPanelSection(embedded = embedded) {
						val automatic = stringResource(R.string.automatic)
						val labels = imageServer.entries.map { it.label ?: automatic }
						val selected = imageServer.entries.indexOfFirst { it.value == imageServer.selectedValue }.coerceAtLeast(0)
						SelectRow(
							title = stringResource(R.string.image_server),
							selected = labels.getOrElse(selected) { automatic },
							options = labels,
							onSelected = { callbacks.onImageServerChanged(imageServer.entries[it].value) },
						)
					}
				}
			}
		}
		}
	}
}

@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.OptionAction(
	icon: Int,
	label: Int,
	onClick: () -> Unit,
) {
	TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
		Icon(painterResource(icon), contentDescription = null)
		Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
	}
}

@Composable
private fun OptionSwitch(
	text: String,
	checked: Boolean,
	enabled: Boolean = true,
	onCheckedChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
	) {
		Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
		Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
	}
}

@Composable
private fun SectionTitle(text: String) {
	Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun SelectRow(
	title: String,
	selected: String,
	options: List<String>,
	onSelected: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	var expanded by remember { mutableStateOf(false) }
	Box(modifier = modifier) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.fillMaxWidth()
				.clickable { expanded = true }
				.padding(horizontal = 12.dp, vertical = 12.dp),
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(selected, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
			Text(">", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			options.forEachIndexed { index, option ->
				DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelected(index) })
			}
		}
	}
}

@Composable
private fun ReaderMode.label(): String = stringResource(
	when (this) {
		ReaderMode.STANDARD -> R.string.standard
		ReaderMode.REVERSED -> R.string.right_to_left
		ReaderMode.VERTICAL -> R.string.vertical
		ReaderMode.WEBTOON -> R.string.webtoon
	},
)
