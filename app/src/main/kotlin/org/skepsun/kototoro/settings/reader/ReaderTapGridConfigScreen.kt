package org.skepsun.kototoro.settings.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.tapgrid.ReaderTapGridConfigGrid
import org.skepsun.kototoro.reader.ui.tapgrid.TapAction
import org.skepsun.kototoro.reader.ui.tapgrid.TapActions
import org.skepsun.kototoro.reader.ui.tapgrid.nameStringResId
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTapGridConfigScreen(
    content: Map<TapGridArea, TapActions>,
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
                ReaderTapGridConfigGrid(
                    content = content,
                    tapActionLabel = stringResource(R.string.tap_action),
                    longTapActionLabel = stringResource(R.string.long_tap_action),
                    actionName = { action -> stringResource(action?.nameStringResId ?: R.string.none) },
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
                TapGridArea.TOP_LEFT to TapActions(TapAction.PAGE_PREV, null),
                TapGridArea.TOP_RIGHT to TapActions(TapAction.PAGE_NEXT, null),
                TapGridArea.CENTER to TapActions(TapAction.TOGGLE_UI, TapAction.SHOW_MENU),
            ),
            onNavigateUp = {},
            onSetTapAction = { _, _, _ -> },
            onReset = {},
            onDisableAll = {},
        )
    }
}
