package org.skepsun.kototoro.download.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.getSaveTitleResId
import org.skepsun.kototoro.core.model.getWholeWorkOptionResId
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.core.util.ext.findActivity
import org.skepsun.kototoro.core.util.ext.joinToStringWithLimit
import org.skepsun.kototoro.download.ui.dialog.ChapterSelectOptions
import org.skepsun.kototoro.download.ui.dialog.DownloadDialogViewModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.format
import org.skepsun.kototoro.settings.storage.DirectoryModel
import kotlin.math.roundToInt

private enum class SelectedMode {
    WHOLE_MANGA, WHOLE_BRANCH, FIRST_CHAPTERS, UNREAD_CHAPTERS
}

@Composable
fun DownloadDialog(
    mangaList: List<Content>,
    snackbarHostState: SnackbarHostState? = null,
    snackbarHostView: android.view.View? = null,
    onOpenDownloads: (() -> Unit)? = null,
    viewModel: DownloadDialogViewModel = hiltViewModel(key = "download-dialog-${mangaList.hashCode()}"),
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(mangaList) {
        viewModel.initialize(mangaList)
    }
    
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        viewModel.onScheduled.collect { event ->
            event?.consume { isStarted ->
                if (snackbarHostState != null) {
                    scope.launch {
                        val msg = if (isStarted) R.string.download_started else R.string.download_added
                        val result = snackbarHostState.showSnackbar(
                            message = context.getString(msg),
                            actionLabel = context.getString(R.string.downloads),
                            withDismissAction = true,
                        )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            onOpenDownloads?.invoke()
                        }
                    }
                } else if (snackbarHostView != null) {
                    showDownloadResultSnackbar(
                        snackbarHostView = snackbarHostView,
                        isStarted = isStarted,
                        onOpenDownloads = onOpenDownloads,
                    )
                }
                onDismiss()
            }
        }
    }

    val isOptionsLoading by viewModel.isOptionsLoading.collectAsState()
    val chaptersSelectOptions by viewModel.chaptersSelectOptions.collectAsState()
    val isVideoContent by viewModel.isVideoContent.collectAsState()
    val isVideoQualitiesLoading by viewModel.isVideoQualitiesLoading.collectAsState()
    val videoQualities by viewModel.videoQualities.collectAsState()
    val defaultFormat by viewModel.defaultFormat.collectAsState()
    val availableDestinations by viewModel.availableDestinations.collectAsState()

    var showMoreOptions by remember { mutableStateOf(false) }
    var startNow by remember { mutableStateOf(true) }
    var selectedMode by remember { mutableStateOf(SelectedMode.WHOLE_MANGA) }
    var isAlignReader by remember { mutableStateOf(viewModel.isDownloadAlignedWithReader()) }
    var isAutoRetry by remember { mutableStateOf(viewModel.isDownloadAutoRetryEnabled()) }
    var chapterDelaySeconds by remember { mutableStateOf(viewModel.getChapterDownloadDelay()) }
    var threads by remember { mutableStateOf(viewModel.getDownloadThreads()) }
    var requestDelayMs by remember { mutableStateOf(viewModel.getDownloadRequestDelayMs()) }
    var retryCount by remember { mutableStateOf(viewModel.getDownloadRetryCount()) }
    var retryDelayMs by remember { mutableStateOf(viewModel.getDownloadRetryDelayMs()) }
    var selectedFormat by remember { mutableStateOf<DownloadFormat?>(null) }
    var selectedDestination by remember { mutableStateOf<DirectoryModel?>(null) }
    var selectedVideoQuality by remember { mutableStateOf<String?>(null) }
    var showDestinationMenu by remember { mutableStateOf(false) }
    var showFormatMenu by remember { mutableStateOf(false) }
    var showVideoQualityMenu by remember { mutableStateOf(false) }
    var showWholeBranchMenu by remember { mutableStateOf(false) }
    var showFirstChaptersMenu by remember { mutableStateOf(false) }
    var showUnreadChaptersMenu by remember { mutableStateOf(false) }

    LaunchedEffect(defaultFormat) {
        selectedFormat = defaultFormat
    }
    LaunchedEffect(availableDestinations) {
        selectedDestination = availableDestinations.firstOrNull { it.isChecked }
            ?: availableDestinations.firstOrNull()
    }
    LaunchedEffect(videoQualities, isVideoContent) {
        if (!isVideoContent || selectedVideoQuality !in videoQualities.orEmpty()) {
            selectedVideoQuality = null
        }
    }

    val firstManga = mangaList.firstOrNull()
    val contentType = firstManga?.source?.getContentType() ?: ContentType.MANGA

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(id = contentType.getSaveTitleResId()))
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                Text(
                    text = mangaList.joinToStringWithLimit(context, 120) { it.title },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Whole Manga
                SelectableListItem(
                    title = stringResource(id = contentType.getWholeWorkOptionResId()),
                    subtitle = if (chaptersSelectOptions.wholeContent.chaptersCount > 0) {
                        pluralStringResource(id = R.plurals.chapters, count = chaptersSelectOptions.wholeContent.chaptersCount, chaptersSelectOptions.wholeContent.chaptersCount)
                    } else null,
                    selected = selectedMode == SelectedMode.WHOLE_MANGA,
                    onClick = { selectedMode = SelectedMode.WHOLE_MANGA }
                )

                // Whole Branch
                if (chaptersSelectOptions.wholeBranch != null) {
                    val branchOptions = chaptersSelectOptions.wholeBranch!!
                    SelectableDropdownItem(
                        title = stringResource(
                            id = R.string.download_option_all_chapters,
                            branchOptions.selectedBranch ?: stringResource(id = R.string.system_default),
                        ),
                        subtitle = if (branchOptions.chaptersCount > 0) {
                            pluralStringResource(id = R.plurals.chapters, count = branchOptions.chaptersCount, branchOptions.chaptersCount)
                        } else null,
                        selected = selectedMode == SelectedMode.WHOLE_BRANCH,
                        onClick = { selectedMode = SelectedMode.WHOLE_BRANCH },
                        isDropdownExpanded = showWholeBranchMenu,
                        onDismissDropdown = { showWholeBranchMenu = false },
                        onDropdownClick = {
                            val branches = branchOptions.branches.keys.toList()
                            if (branches.size > 1) {
                                selectedMode = SelectedMode.WHOLE_BRANCH
                                showWholeBranchMenu = true
                            }
                        },
                        dropdownContent = {
                            branchOptions.branches.forEach { (branch, count) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = buildString {
                                                append(branch ?: context.getString(R.string.system_default))
                                                append(" (")
                                                append(count)
                                                append(")")
                                            },
                                        )
                                    },
                                    onClick = {
                                        viewModel.setSelectedBranch(branch)
                                        selectedMode = SelectedMode.WHOLE_BRANCH
                                        showWholeBranchMenu = false
                                    },
                                )
                            }
                        },
                    )
                }

                // First N Chapters
                if (chaptersSelectOptions.firstChapters != null) {
                    val firstOptions = chaptersSelectOptions.firstChapters!!
                    SelectableDropdownItem(
                        title = stringResource(id = R.string.download_option_first_n_chapters, pluralStringResource(id = R.plurals.chapters, count = firstOptions.chaptersCount, firstOptions.chaptersCount)),
                        subtitle = firstOptions.branch ?: stringResource(id = R.string.system_default),
                        selected = selectedMode == SelectedMode.FIRST_CHAPTERS,
                        onClick = { selectedMode = SelectedMode.FIRST_CHAPTERS },
                        isDropdownExpanded = showFirstChaptersMenu,
                        onDismissDropdown = { showFirstChaptersMenu = false },
                        onDropdownClick = {
                            selectedMode = SelectedMode.FIRST_CHAPTERS
                            showFirstChaptersMenu = true
                        },
                        dropdownContent = {
                            rememberDownloadCountOptions(firstOptions.maxAvailableCount).forEach { count ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = pluralStringResource(
                                                id = R.plurals.chapters,
                                                count = count,
                                                count,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        viewModel.setFirstChaptersCount(count)
                                        selectedMode = SelectedMode.FIRST_CHAPTERS
                                        showFirstChaptersMenu = false
                                    },
                                )
                            }
                        },
                    )
                }

                // Unread Chapters
                if (chaptersSelectOptions.unreadChapters != null) {
                    val unreadOptions = chaptersSelectOptions.unreadChapters!!
                    val title = if (unreadOptions.chaptersCount == Int.MAX_VALUE) {
                        stringResource(id = R.string.download_option_all_unread)
                    } else {
                        stringResource(id = R.string.download_option_next_unread_n_chapters, pluralStringResource(id = R.plurals.chapters, count = unreadOptions.chaptersCount, unreadOptions.chaptersCount))
                    }
                    SelectableDropdownItem(
                        title = title,
                        subtitle = null,
                        selected = selectedMode == SelectedMode.UNREAD_CHAPTERS,
                        onClick = { selectedMode = SelectedMode.UNREAD_CHAPTERS },
                        isDropdownExpanded = showUnreadChaptersMenu,
                        onDismissDropdown = { showUnreadChaptersMenu = false },
                        onDropdownClick = {
                            selectedMode = SelectedMode.UNREAD_CHAPTERS
                            showUnreadChaptersMenu = true
                        },
                        dropdownContent = {
                            rememberDownloadCountOptions(unreadOptions.maxAvailableCount).forEach { count ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = pluralStringResource(
                                                id = R.plurals.chapters,
                                                count = count,
                                                count,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        viewModel.setUnreadChaptersCount(count)
                                        selectedMode = SelectedMode.UNREAD_CHAPTERS
                                        showUnreadChaptersMenu = false
                                    },
                                )
                            }
                            if (unreadOptions.maxAvailableCount > 0) {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(id = R.string.download_option_all_unread)) },
                                    onClick = {
                                        viewModel.setUnreadChaptersCount(Int.MAX_VALUE)
                                        selectedMode = SelectedMode.UNREAD_CHAPTERS
                                        showUnreadChaptersMenu = false
                                    },
                                )
                            }
                        },
                    )
                }

                if (isOptionsLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }

                if (mangaList.size == 1) {
                    Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(id = R.drawable.ic_tap), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(text = stringResource(id = R.string.chapter_selection_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Start Switch
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(id = R.string.start_download), modifier = Modifier.weight(1f))
                    Switch(checked = startNow, onCheckedChange = { startNow = it })
                }

                // Video Quality
                if (isVideoContent) {
                    Text(text = stringResource(id = R.string.video_quality), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    SelectableValueCard(
                        value = if (isVideoQualitiesLoading) {
                            stringResource(id = R.string.fetching_video_quality)
                        } else {
                            selectedVideoQuality ?: stringResource(id = R.string.system_default)
                        },
                        expanded = showVideoQualityMenu,
                        enabled = !isVideoQualitiesLoading,
                        onClick = { showVideoQualityMenu = true },
                        onDismissRequest = { showVideoQualityMenu = false },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.system_default)) },
                            onClick = {
                                selectedVideoQuality = null
                                showVideoQualityMenu = false
                            },
                        )
                        videoQualities.orEmpty().forEach { quality ->
                            DropdownMenuItem(
                                text = { Text(text = quality) },
                                onClick = {
                                    selectedVideoQuality = quality
                                    showVideoQualityMenu = false
                                },
                            )
                        }
                    }
                }

                // More Options Toggle
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showMoreOptions = !showMoreOptions }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(id = R.string.more_options), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(
                        imageVector = if (showMoreOptions) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, 
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = showMoreOptions) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Destinations
                        Text(text = stringResource(id = R.string.destination_directory), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        SelectableValueCard(
                            value = selectedDestination?.displayTitle() ?: stringResource(id = R.string.system_default),
                            expanded = showDestinationMenu,
                            enabled = availableDestinations.isNotEmpty(),
                            onClick = { showDestinationMenu = true },
                            onDismissRequest = { showDestinationMenu = false },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            availableDestinations.forEach { destination ->
                                DropdownMenuItem(
                                    text = {
                                        DestinationMenuItem(destination = destination)
                                    },
                                    onClick = {
                                        selectedDestination = destination
                                        showDestinationMenu = false
                                    },
                                )
                            }
                        }

                        // Format
                        Text(text = stringResource(id = R.string.preferred_download_format), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                        val formatLabels = stringArrayResource(id = R.array.download_formats)
                        SelectableValueCard(
                            value = selectedFormat.formatTitle(formatLabels),
                            expanded = showFormatMenu,
                            onClick = { showFormatMenu = true },
                            onDismissRequest = { showFormatMenu = false },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            DownloadFormat.entries.forEach { format ->
                                DropdownMenuItem(
                                    text = { Text(text = format.formatTitle(formatLabels)) },
                                    onClick = {
                                        selectedFormat = format
                                        showFormatMenu = false
                                    },
                                )
                            }
                        }

                        // Align reader
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = stringResource(id = R.string.download_align_reader), style = MaterialTheme.typography.titleSmall)
                                Text(text = stringResource(id = R.string.download_align_reader_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = isAlignReader,
                                onCheckedChange = { 
                                    isAlignReader = it 
                                    viewModel.setDownloadAlignedWithReader(it)
                                }
                            )
                        }

                        // Auto retry
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(id = R.string.download_auto_retry_summary), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = isAutoRetry,
                                onCheckedChange = { 
                                    isAutoRetry = it
                                    viewModel.setDownloadAutoRetryEnabled(it)
                                }
                            )
                        }

                        // Threads
                        DownloadIntSlider(
                            title = stringResource(id = R.string.download_threads),
                            value = threads,
                            valueText = threads.toString(),
                            valueRange = 1..10,
                            steps = 8,
                            enabled = !isAlignReader,
                            onValueChange = {
                                threads = it
                                viewModel.setDownloadThreads(it)
                            },
                        )

                        DownloadIntSlider(
                            title = stringResource(id = R.string.download_request_delay),
                            value = requestDelayMs,
                            valueText = "${requestDelayMs}ms",
                            valueRange = 0..5000,
                            steps = 49,
                            stepSize = 100,
                            enabled = !isAlignReader,
                            onValueChange = {
                                requestDelayMs = it
                                viewModel.setDownloadRequestDelayMs(it)
                            },
                        )

                        DownloadIntSlider(
                            title = stringResource(id = R.string.download_retry_count),
                            value = retryCount,
                            valueText = retryCount.toString(),
                            valueRange = 1..10,
                            steps = 8,
                            onValueChange = {
                                retryCount = it
                                viewModel.setDownloadRetryCount(it)
                            },
                        )

                        DownloadIntSlider(
                            title = stringResource(id = R.string.download_retry_delay),
                            value = retryDelayMs,
                            valueText = "${retryDelayMs}ms",
                            valueRange = 500..10000,
                            steps = 18,
                            stepSize = 500,
                            onValueChange = {
                                retryDelayMs = it
                                viewModel.setDownloadRetryDelayMs(it)
                            },
                        )

                        DownloadIntSlider(
                            title = stringResource(id = R.string.chapter_download_delay),
                            value = chapterDelaySeconds,
                            valueText = "${chapterDelaySeconds}s",
                            valueRange = 0..10,
                            steps = 9,
                            onValueChange = {
                                chapterDelaySeconds = it
                                viewModel.setChapterDownloadDelay(it)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isOptionsLoading,
                onClick = {
                    val chaptersMacro = when (selectedMode) {
                        SelectedMode.WHOLE_MANGA -> chaptersSelectOptions.wholeContent
                        SelectedMode.WHOLE_BRANCH -> chaptersSelectOptions.wholeBranch ?: return@Button
                        SelectedMode.FIRST_CHAPTERS -> chaptersSelectOptions.firstChapters ?: return@Button
                        SelectedMode.UNREAD_CHAPTERS -> chaptersSelectOptions.unreadChapters ?: return@Button
                    }
                    val schedule: (Boolean) -> Unit = { allowMetered ->
                        viewModel.confirm(
                            startNow = startNow,
                            chaptersMacro = chaptersMacro,
                            format = selectedFormat,
                            destination = selectedDestination,
                            allowMetered = allowMetered,
                            preferredQuality = selectedVideoQuality,
                        )
                    }
                    val activity = context.findActivity() as? FragmentActivity
                    if (activity != null) {
                        activity.router.askForDownloadOverMeteredNetwork(schedule)
                    } else {
                        schedule(true)
                    }
                }
            ) {
                Text(text = stringResource(id = R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun SelectableValueCard(
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dropdownContent: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = value,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            content = dropdownContent,
        )
    }
}

@Composable
private fun DownloadIntSlider(
    title: String,
    value: Int,
    valueText: String,
    valueRange: IntRange,
    steps: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    stepSize: Int = 1,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            KototoroSlider(
                value = value.toFloat(),
                onValueChange = { rawValue ->
                    val snappedValue = (rawValue / stepSize).roundToInt() * stepSize
                    onValueChange(snappedValue.coerceIn(valueRange.first, valueRange.last))
                },
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                steps = steps,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                modifier = Modifier.padding(start = 16.dp).width(64.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}

@Composable
private fun SelectableListItem(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SelectableDropdownItem(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onDropdownClick: () -> Unit,
    isDropdownExpanded: Boolean = false,
    onDismissDropdown: () -> Unit = {},
    dropdownContent: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = onDropdownClick) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_expand_more),
                    contentDescription = null,
                )
            }
            DropdownMenu(
                expanded = isDropdownExpanded,
                onDismissRequest = onDismissDropdown,
                content = dropdownContent,
            )
        }
    }
}

private fun showDownloadResultSnackbar(
    snackbarHostView: android.view.View,
    isStarted: Boolean,
    onOpenDownloads: (() -> Unit)?,
) {
    Snackbar.make(
        snackbarHostView,
        if (isStarted) R.string.download_started else R.string.download_added,
        Snackbar.LENGTH_LONG,
    ).apply {
        if (onOpenDownloads != null) {
            setAction(R.string.downloads) { onOpenDownloads() }
        }
    }.show()
}

@Composable
private fun DirectoryModel.displayTitle(): String {
    return title ?: stringResource(id = titleRes.takeIf { it != 0 } ?: R.string.system_default)
}

@Composable
private fun DestinationMenuItem(destination: DirectoryModel) {
    Column {
        Text(
            text = destination.displayTitle(),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        destination.file?.path?.let { path ->
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DownloadFormat?.formatTitle(formatLabels: Array<String>): String {
    return formatLabels.getOrNull(this?.ordinal ?: 0) ?: stringResource(id = R.string.system_default)
}

@Composable
private fun rememberDownloadCountOptions(maxAvailableCount: Int): List<Int> {
    return remember(maxAvailableCount) {
        buildList {
            listOf(1, 3, 5, 10, 20, 50).forEach { preset ->
                if (preset in 1 until maxAvailableCount) {
                    add(preset)
                }
            }
            if (maxAvailableCount > 0) {
                add(maxAvailableCount)
            }
        }.distinct()
    }
}
