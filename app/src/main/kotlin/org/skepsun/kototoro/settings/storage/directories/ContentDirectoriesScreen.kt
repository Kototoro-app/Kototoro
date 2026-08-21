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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.local.data.LocalStorageRoot
import org.skepsun.kototoro.local.data.StorageContentKind
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentDirectoriesScreen(
    mangaItems: List<DirectoryConfigModel>,
    novelItems: List<DirectoryConfigModel>,
    videoItems: List<DirectoryConfigModel>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onAddDirectory: (StorageContentKind) -> Unit,
    onRemoveDirectory: (LocalStorageRoot, StorageContentKind) -> Unit,
    onDefaultDirectory: (LocalStorageRoot, StorageContentKind) -> Unit,
) {
    val listSpacing = dimensionResource(R.dimen.list_spacing_large)
    val kinds = StorageContentKind.entries
    val pagerState = rememberPagerState(pageCount = kinds::size)
    val coroutineScope = rememberCoroutineScope()
    val selectedKind = kinds[pagerState.currentPage]
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
                    title = { Text(stringResource(R.string.local_content_directories)) },
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
                SecondaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    kinds.forEachIndexed { index, kind ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    stringResource(
                                        when (kind) {
                                            StorageContentKind.MANGA -> R.string.local_manga_storage
                                            StorageContentKind.NOVEL -> R.string.local_novel_storage
                                            StorageContentKind.VIDEO -> R.string.local_video_storage
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
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
                onClick = { onAddDirectory(selectedKind) },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(16.dp),
            )
        },
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val kind = kinds[page]
            val pageItems = when (kind) {
                StorageContentKind.MANGA -> mangaItems
                StorageContentKind.NOVEL -> novelItems
                StorageContentKind.VIDEO -> videoItems
            }
            DirectoryList(
                items = pageItems,
                contentPadding = contentPadding,
                listSpacing = listSpacing,
                onRemoveDirectory = { onRemoveDirectory(it, kind) },
                onDefaultDirectory = { onDefaultDirectory(it, kind) },
            )
        }
    }
}

@Composable
private fun DirectoryList(
    items: List<DirectoryConfigModel>,
    contentPadding: PaddingValues,
    listSpacing: androidx.compose.ui.unit.Dp,
    onRemoveDirectory: (LocalStorageRoot) -> Unit,
    onDefaultDirectory: (LocalStorageRoot) -> Unit,
) {
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
        item(key = "overview") {
            DirectoryOverview(items)
        }
        items(
            items = items,
            key = { it.root.key },
        ) { item ->
            DirectoryConfigCard(
                item = item,
                onRemove = { onRemoveDirectory(item.root) },
                onSetDefault = { onDefaultDirectory(item.root) },
            )
        }
    }
}

@Composable
private fun DirectoryOverview(items: List<DirectoryConfigModel>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val totalSize = items.sumOf(DirectoryConfigModel::size)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = pluralStringResource(R.plurals.items, items.size, items.size),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.directory_used_pattern,
                    FileSize.BYTES.format(context, totalSize),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DirectoryConfigCard(
    item: DirectoryConfigModel,
    onRemove: () -> Unit,
    onSetDefault: () -> Unit,
) {
    val horizontalPadding = dimensionResource(R.dimen.screen_padding)
    val info = directoryInfo(item)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isDefault) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (item.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = item.isDefault,
                        onClick = onSetDefault,
                        enabled = item.isAccessible,
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = if (item.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RadioButton(
                    selected = item.isDefault,
                    onClick = null,
                    enabled = item.isAccessible,
                )
            }
            Text(
                text = item.root.displayPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = directoryUsageText(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp),
            )
            if (item.available != null) {
                LinearProgressIndicator(
                    progress = { directoryProgress(item) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(6.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                )
            }
            if (info.isNotEmpty() || !item.isAppPrivate) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (info.isNotEmpty()) {
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f))
                    }
                    if (!item.isAppPrivate) {
                        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.remove),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun directoryUsageText(item: DirectoryConfigModel): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val used = FileSize.BYTES.format(context, item.size)
    return item.available?.let { available ->
        stringResource(
            R.string.directory_usage_pattern,
            used,
            FileSize.BYTES.format(context, available),
        )
    } ?: stringResource(R.string.directory_used_pattern, used)
}

private fun directoryProgress(item: DirectoryConfigModel): Float {
    val availableKilobytes = FileSize.BYTES.convert(item.available ?: return 0f, FileSize.KILOBYTES)
    val usedKilobytes = FileSize.BYTES.convert(item.size, FileSize.KILOBYTES)
    val totalKilobytes = usedKilobytes + availableKilobytes
    if (totalKilobytes <= 0L) return 0f
    return (usedKilobytes.toDouble() / totalKilobytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun directoryInfo(item: DirectoryConfigModel): AnnotatedString {
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
    if (noWritePermissionText != null) {
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
            mangaItems = listOf(
                DirectoryConfigModel(
                    title = "App storage",
                    root = LocalStorageRoot.fromFile(File("/storage/emulated/0/Android/data/org.skepsun.kototoro/files")),
                    isDefault = true,
                    isAppPrivate = true,
                    isAccessible = true,
                    size = 512L * 1024L * 1024L,
                    available = 8L * 1024L * 1024L * 1024L,
                ),
            ),
            novelItems = emptyList(),
            videoItems = emptyList(),
            isLoading = false,
            onBack = {},
            onAddDirectory = {},
            onRemoveDirectory = { _, _ -> },
            onDefaultDirectory = { _, _ -> },
        )
    }
}
