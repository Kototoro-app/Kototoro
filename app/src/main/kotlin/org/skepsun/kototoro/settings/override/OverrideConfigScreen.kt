package org.skepsun.kototoro.settings.override

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.skepsun.kototoro.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverrideConfigScreen(
    mangaTitle: String,
    coverRequest: ImageRequest?,
    initialName: String,
    canResetCover: Boolean,
    isDataReady: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onSave: (String) -> Unit,
    onPickFile: () -> Unit,
    onPickPage: () -> Unit,
    onResetCover: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.change_cover)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = isDataReady && !isLoading,
                        onClick = { onSave(name.trim()) },
                    ) {
                        Text(text = stringResource(R.string.save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AsyncImage(
                        model = coverRequest,
                        contentDescription = stringResource(R.string.change_cover),
                        modifier = Modifier
                            .fillMaxWidth(0.3f)
                            .aspectRatio(13f / 18f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(R.drawable.ic_placeholder),
                        error = painterResource(R.drawable.ic_placeholder),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.change_cover),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        CoverAction(
                            icon = R.drawable.ic_folder_file,
                            label = stringResource(R.string.pick_custom_file),
                            enabled = isDataReady && !isLoading,
                            onClick = onPickFile,
                        )
                        CoverAction(
                            icon = R.drawable.ic_grid,
                            label = stringResource(R.string.pick_manga_page),
                            enabled = isDataReady && !isLoading,
                            onClick = onPickPage,
                        )
                        CoverAction(
                            icon = R.drawable.ic_revert,
                            label = stringResource(R.string.use_default_cover),
                            enabled = isDataReady && !isLoading && canResetCover,
                            onClick = onResetCover,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(R.string.name)) },
                    placeholder = { Text(text = mangaTitle) },
                    trailingIcon = {
                        IconButton(
                            enabled = isDataReady && !isLoading,
                            onClick = { name = "" },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.reset),
                            )
                        }
                    },
                    enabled = isDataReady && !isLoading,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.manga_override_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                errorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun CoverAction(
    icon: Int,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun OverrideConfigScreenPreview() {
    MaterialTheme {
        OverrideConfigScreen(
            mangaTitle = "Sample manga title",
            coverRequest = null,
            initialName = "",
            canResetCover = true,
            isDataReady = true,
            isLoading = false,
            errorMessage = null,
            onSave = {},
            onPickFile = {},
            onPickPage = {},
            onResetCover = {},
            onNavigateUp = {},
        )
    }
}
