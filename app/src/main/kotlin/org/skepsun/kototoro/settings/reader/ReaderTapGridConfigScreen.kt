package org.skepsun.kototoro.settings.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.tapgrid.TapAction
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton

private const val ACTION_TINT_ALPHA = 40 / 255f

private val tapGridRows = TapGridArea.entries.chunked(3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTapGridConfigScreen(
	content: Map<TapGridArea, ReaderTapGridConfigViewModel.TapActions>,
	onNavigateUp: () -> Unit,
	onSetTapAction: (TapGridArea, Boolean, TapAction?) -> Unit,
	onReset: () -> Unit,
	onDisableAll: () -> Unit,
) {
	var menuExpanded by remember { mutableStateOf(false) }
	var showResetConfirmation by remember { mutableStateOf(false) }
	var actionSelector by remember { mutableStateOf<ActionSelector?>(null) }

	Scaffold(
		modifier = Modifier.fillMaxSize(),
		containerColor = MaterialTheme.colorScheme.background,
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		topBar = {
			TopAppBar(
				modifier = Modifier.windowInsetsPadding(
					WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
				),
				title = { Text(stringResource(R.string.reader_actions)) },
				navigationIcon = {
					IconButton(onClick = onNavigateUp) {
						Icon(
							imageVector = Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.back),
						)
					}
				},
				actions = {
					Box {
						IconButton(onClick = { menuExpanded = true }) {
							Icon(
								imageVector = Icons.Default.MoreVert,
								contentDescription = stringResource(R.string.more),
							)
						}
						DropdownMenu(
							expanded = menuExpanded,
							onDismissRequest = { menuExpanded = false },
						) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.reset)) },
								onClick = {
									menuExpanded = false
									showResetConfirmation = true
								},
							)
							DropdownMenuItem(
								text = { Text(stringResource(R.string.disable_all)) },
								onClick = {
									menuExpanded = false
									onDisableAll()
								},
							)
						}
					}
				},
				windowInsets = WindowInsets.statusBars,
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
				),
			)
		},
	) { contentPadding ->
			Box(
				modifier = Modifier
					.fillMaxSize()
					.padding(contentPadding)
					.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
					.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
			) {
				TapGrid(
					content = content,
					onTap = { area -> actionSelector = ActionSelector(area, isLongTap = false) },
					onLongTap = { area -> actionSelector = ActionSelector(area, isLongTap = true) },
				)
			}
		}
	if (showResetConfirmation) {
		SettingsAlertDialog(
			onDismissRequest = { showResetConfirmation = false },
			title = stringResource(R.string.reader_actions),
			text = { Text(stringResource(R.string.config_reset_confirm)) },
			confirmButton = {
				SettingsDialogActionButton(
					text = stringResource(R.string.reset),
					onClick = {
						showResetConfirmation = false
						onReset()
					},
				)
			},
			dismissButton = {
				SettingsDialogActionButton(
					text = stringResource(android.R.string.cancel),
					onClick = { showResetConfirmation = false },
				)
			},
		)
	}

	actionSelector?.let { selector ->
		ActionSelectorDialog(
			isLongTap = selector.isLongTap,
			selectedAction = content[selector.area]?.let {
				if (selector.isLongTap) it.longTapAction else it.tapAction
			},
			onActionSelected = { action ->
				onSetTapAction(selector.area, selector.isLongTap, action)
				actionSelector = null
			},
			onDismissRequest = { actionSelector = null },
		)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TapGrid(
	content: Map<TapGridArea, ReaderTapGridConfigViewModel.TapActions>,
	onTap: (TapGridArea) -> Unit,
	onLongTap: (TapGridArea) -> Unit,
) {
	val dividerColor = MaterialTheme.colorScheme.outline
	Box(
		modifier = Modifier
			.fillMaxSize()
			.drawWithContent {
				drawContent()
				drawTapGridDividers(dividerColor)
			},
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			tapGridRows.forEach { row ->
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.weight(1f),
					horizontalArrangement = Arrangement.Start,
				) {
					row.forEach { area ->
						TapGridCell(
							actions = content[area],
							onTap = { onTap(area) },
							onLongTap = { onLongTap(area) },
							modifier = Modifier
								.weight(1f)
								.fillMaxHeight(),
						)
					}
				}
			}
		}
	}
}

/** Maps the XML TextView's centered, partly bold text and action tint to one Compose cell. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TapGridCell(
	actions: ReaderTapGridConfigViewModel.TapActions?,
	onTap: () -> Unit,
	onLongTap: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val tapAction = actions?.tapAction
	val longTapAction = actions?.longTapAction
	val tapActionName = tapAction?.let { stringResource(it.nameStringResId) } ?: stringResource(R.string.none)
	val longTapActionName = longTapAction?.let { stringResource(it.nameStringResId) } ?: stringResource(R.string.none)
	val label = buildAnnotatedString {
		append(stringResource(R.string.tap_action))
		append('\n')
		withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
			append(tapActionName)
		}
		append('\n')
		append('\n')
		append(stringResource(R.string.long_tap_action))
		append('\n')
		withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
			append(longTapActionName)
		}
	}

	Box(
		modifier = modifier
			.background(tapAction?.let(::actionTint) ?: Color.Transparent)
			.combinedClickable(
				onClick = onTap,
				onLongClick = onLongTap,
			),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodyMedium,
			textAlign = TextAlign.Center,
		)
	}
}

@Composable
private fun ActionSelectorDialog(
	isLongTap: Boolean,
	selectedAction: TapAction?,
	onActionSelected: (TapAction?) -> Unit,
	onDismissRequest: () -> Unit,
) {
	val actions: List<TapAction?> = listOf(null) + TapAction.entries
	SettingsAlertDialog(
		onDismissRequest = onDismissRequest,
		title = stringResource(if (isLongTap) R.string.long_tap_action else R.string.tap_action),
		icon = {
			Icon(
				painter = painterResource(R.drawable.ic_tap),
				contentDescription = null,
			)
		},
		text = {
			Column {
				actions.forEach { action ->
					val label = stringResource(action?.nameStringResId ?: R.string.none)
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.selectable(
								selected = action == selectedAction,
								onClick = { onActionSelected(action) },
								role = Role.RadioButton,
							)
							.padding(vertical = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						RadioButton(
							selected = action == selectedAction,
							onClick = null,
						)
						Text(text = label)
					}
				}
			}
		},
		dismissButton = {
			SettingsDialogActionButton(
				text = stringResource(android.R.string.cancel),
				onClick = onDismissRequest,
			)
		},
		confirmButton = {},
	)
}

private fun actionTint(action: TapAction): Color {
	return Color(
		red = (action.color shr 16 and 0xFF) / 255f,
		green = (action.color shr 8 and 0xFF) / 255f,
		blue = (action.color and 0xFF) / 255f,
		alpha = ACTION_TINT_ALPHA,
	)
}

private fun DrawScope.drawTapGridDividers(color: Color) {
	val strokeWidth = 1.dp.toPx()
	drawLine(
		color = color,
		start = androidx.compose.ui.geometry.Offset(size.width / 3f, 0f),
		end = androidx.compose.ui.geometry.Offset(size.width / 3f, size.height),
		strokeWidth = strokeWidth,
	)
	drawLine(
		color = color,
		start = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, 0f),
		end = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, size.height),
		strokeWidth = strokeWidth,
	)
	drawLine(
		color = color,
		start = androidx.compose.ui.geometry.Offset(0f, size.height / 3f),
		end = androidx.compose.ui.geometry.Offset(size.width, size.height / 3f),
		strokeWidth = strokeWidth,
	)
	drawLine(
		color = color,
		start = androidx.compose.ui.geometry.Offset(0f, size.height * 2f / 3f),
		end = androidx.compose.ui.geometry.Offset(size.width, size.height * 2f / 3f),
		strokeWidth = strokeWidth,
	)
}

private data class ActionSelector(
	val area: TapGridArea,
	val isLongTap: Boolean,
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ReaderTapGridConfigScreenPreview() {
	KototoroTheme {
		ReaderTapGridConfigScreen(
			content = mapOf(
				TapGridArea.TOP_LEFT to ReaderTapGridConfigViewModel.TapActions(TapAction.PAGE_PREV, null),
				TapGridArea.TOP_RIGHT to ReaderTapGridConfigViewModel.TapActions(TapAction.PAGE_NEXT, null),
				TapGridArea.CENTER to ReaderTapGridConfigViewModel.TapActions(TapAction.TOGGLE_UI, TapAction.SHOW_MENU),
			),
			onNavigateUp = {},
			onSetTapAction = { _, _, _ -> },
			onReset = {},
			onDisableAll = {},
		)
	}
}
