package org.skepsun.kototoro.favourites.ui.migration.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.R
import org.skepsun.kototoro.favourites.ui.migration.SourceMigrationViewModel
import org.skepsun.kototoro.favourites.ui.migration.buildEntityOrganizeCloseResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityOrganizeScreen(
    initialSelectedContentIds: Set<Long>,
    onBack: (shouldRefreshFavorites: Boolean, message: String?) -> Unit,
    viewModel: SourceMigrationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.entity_organize_title))
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            val result = buildEntityOrganizeCloseResult(uiState, context)
                            onBack(result.shouldRefreshFavorites, result.message)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        SourceMigrationPanel(
            initialSelectedContentIds = initialSelectedContentIds,
            onDismiss = {
                val result = buildEntityOrganizeCloseResult(uiState, context)
                onBack(result.shouldRefreshFavorites, result.message)
            },
            contentPadding = innerPadding,
            showHeader = false,
            viewModel = viewModel,
        )
    }
}
