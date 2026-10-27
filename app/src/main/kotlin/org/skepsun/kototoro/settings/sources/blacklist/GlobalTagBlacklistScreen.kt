package org.skepsun.kototoro.settings.sources.blacklist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.displayName
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.settings.BlacklistTagUiModel
import org.skepsun.kototoro.core.ui.settings.BlacklistedTagsContent
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton

/**
 * Android's global tag blacklist. The grid, the chips and the "add this pattern" action are the shared
 * [BlacklistedTagsContent] the desktop page also draws; this screen keeps the phone's top bar (back, CLEAR) and the
 * long-press aliases dialog, which the desktop version extends with the key and the English label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalTagBlacklistScreen(
    state: GlobalTagBlacklistUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onAddQuery: () -> Unit,
    onToggleTag: (String) -> Unit,
    onClear: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    var aliasDetailsTag by remember { mutableStateOf<BlacklistTagUiModel?>(null) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.blacklisted_tags)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp, modifier = Modifier.tvIconButtonRing()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (state.selectedTags.isNotEmpty()) {
                        TvTextButton(onClick = onClear) {
                            Text(stringResource(R.string.clear))
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        ) {
            BlacklistedTagsContent(
                available = state.tags.map { it.toUiModel() },
                selected = state.selectedItems.map { it.toUiModel() },
                query = query,
                onQueryChange = onQueryChange,
                onToggle = onToggleTag,
                onShowDetails = { aliasDetailsTag = it },
                // Android offers the raw pattern as soon as the field says something.
                rawPatternLabel = query.trim()
                    .takeIf { query.isNotBlank() }
                    ?.let { stringResource(R.string.add_tag_pattern, it) },
                onAddRawPattern = onAddQuery,
                tagPrefix = "blacklist-tag",
            )
        }
    }

    aliasDetailsTag?.let { tag ->
        SettingsAlertDialog(
            title = stringResource(R.string.aliases_for_tag, tag.label),
            onDismissRequest = { aliasDetailsTag = null },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.close),
                    onClick = { aliasDetailsTag = null },
                )
            },
            text = {
                SelectionContainer {
                    Text(
                        text = tag.aliases.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n")
                            ?: stringResource(R.string.no_aliases),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
        )
    }
}

/** The view model's item as the shared grid takes it: the category's display name is the only thing it adds. */
private fun GlobalTagBlacklistItem.toUiModel() = BlacklistTagUiModel(
    key = key,
    label = label,
    aliases = aliases,
    categoryId = category.name,
    categoryName = category.displayName(),
)
