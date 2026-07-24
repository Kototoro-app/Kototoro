package org.skepsun.kototoro.settings.storage.directories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.FileSize
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentDirectoriesScreen(
	items: List<DirectoryConfigModel>,
	isLoading: Boolean,
	onBack: () -> Unit,
	onAddDirectory: () -> Unit,
	onRemoveDirectory: (File) -> Unit,
) {
	val listSpacing = dimensionResource(R.dimen.list_spacing_large)
	Scaffold(
		modifier = Modifier.fillMaxSize(),
		containerColor = MaterialTheme.colorScheme.background,
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		topBar = {
			Column {
				TopAppBar(
					modifier = Modifier.windowInsetsPadding(
						WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
					),
					title = { Text(stringResource(R.string.local_manga_directories)) },
					navigationIcon = {
						IconButton(onClick = onBack) {
							Icon(
								imageVector = Icons.AutoMirrored.Filled.ArrowBack,
								contentDescription = stringResource(R.string.back),
							)
						}
					},
					windowInsets = WindowInsets.statusBars,
					colors = TopAppBarDefaults.topAppBarColors(
						containerColor = MaterialTheme.colorScheme.background,
					),
				)
				if (isLoading) {
					LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
				}
			}
		},
		floatingActionButton = {
			ExtendedFloatingActionButton(
				text = { Text(stringResource(R.string.add)) },
				icon = {
					Icon(
						imageVector = Icons.Default.Add,
						contentDescription = null,
					)
				},
				onClick = onAddDirectory,
				modifier = Modifier
					.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
					.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
					.padding(16.dp),
			)
		},
	) { contentPadding ->
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
				.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
			contentPadding = PaddingValues(
				top = contentPadding.calculateTopPadding() + listSpacing,
				bottom = contentPadding.calculateBottomPadding() + listSpacing,
				start = listSpacing,
				end = listSpacing,
			),
			verticalArrangement = Arrangement.spacedBy(listSpacing),
		) {
			items(
				items = items,
				key = { it.path.absolutePath },
			) { item ->
				DirectoryConfigCard(item = item, onRemove = { onRemoveDirectory(item.path) })
			}
		}
	}
}

@Composable
private fun DirectoryConfigCard(
	item: DirectoryConfigModel,
	onRemove: () -> Unit,
) {
	val horizontalPadding = dimensionResource(R.dimen.screen_padding)
	val info = directoryInfo(item)
	Card(modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(bottom = dimensionResource(R.dimen.margin_small))) {
			Text(
				text = item.title,
				style = MaterialTheme.typography.titleMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(horizontal = horizontalPadding).padding(top = horizontalPadding),
			)
			Text(
				text = item.path.absolutePath,
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier.padding(horizontal = horizontalPadding).padding(top = dimensionResource(R.dimen.margin_small)),
			)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = horizontalPadding)
					.padding(top = horizontalPadding),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = stringResource(
						R.string.available_pattern,
						FileSize.BYTES.format(androidx.compose.ui.platform.LocalContext.current, item.available),
					),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f).padding(end = dimensionResource(R.dimen.margin_small)),
				)
				LinearProgressIndicator(
					progress = { directoryProgress(item) },
					modifier = Modifier.width(160.dp).height(10.dp),
				)
			}
			if (info.isNotEmpty()) {
				Text(
					text = info,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = horizontalPadding).padding(top = dimensionResource(R.dimen.margin_small)),
				)
			}
			if (item.isAppPrivate) {
				SpacerForPrivateDirectory()
			} else {
				TextButton(
					onClick = onRemove,
					enabled = !item.isDefault,
					modifier = Modifier.align(Alignment.End),
				) {
					Text(stringResource(R.string.remove))
				}
			}
		}
	}
}

@Composable
private fun SpacerForPrivateDirectory() {
	Box(modifier = Modifier.height(dimensionResource(R.dimen.margin_small)))
}

private fun directoryProgress(item: DirectoryConfigModel): Float {
	val availableKilobytes = FileSize.BYTES.convert(item.available, FileSize.KILOBYTES)
	if (availableKilobytes <= 0L) return 0f
	val usedKilobytes = FileSize.BYTES.convert(item.size, FileSize.KILOBYTES)
	return (usedKilobytes.toDouble() / availableKilobytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun directoryInfo(item: DirectoryConfigModel): AnnotatedString {
	val defaultDirectoryText = if (item.isDefault) {
		stringResource(R.string.download_default_directory)
	} else {
		null
	}
	val noWritePermissionText = if (!item.isAccessible) {
		stringResource(R.string.no_write_permission_to_file)
	} else {
		null
	}
	val privateDirectoryText = if (item.isAppPrivate) {
		stringResource(R.string.private_app_directory_warning)
	} else {
		null
	}
	return buildAnnotatedString {
	if (defaultDirectoryText != null) {
		withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
			append(defaultDirectoryText)
		}
	}
	if (noWritePermissionText != null) {
		if (length > 0) append('\n')
		withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
			append(noWritePermissionText)
		}
	}
	if (privateDirectoryText != null) {
		if (length > 0) append('\n')
		append(privateDirectoryText)
	}
	}
}

@Preview(showBackground = true)
@Composable
private fun ContentDirectoriesScreenPreview() {
	MaterialTheme {
		ContentDirectoriesScreen(
			items = listOf(
				DirectoryConfigModel(
					title = "App storage",
					path = File("/storage/emulated/0/Android/data/org.skepsun.kototoro/files"),
					isDefault = true,
					isAppPrivate = true,
					isAccessible = true,
					size = 512L * 1024L * 1024L,
					available = 8L * 1024L * 1024L * 1024L,
				),
			),
			isLoading = false,
			onBack = {},
			onAddDirectory = {},
			onRemoveDirectory = {},
		)
	}
}
