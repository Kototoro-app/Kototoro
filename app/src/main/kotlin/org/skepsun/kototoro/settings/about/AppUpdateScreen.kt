package org.skepsun.kototoro.settings.about

import android.app.DownloadManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.TextViewCompat
import io.noties.markwon.Markwon
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.github.AppUpdateSource
import org.skepsun.kototoro.core.github.AppUpdateSourceProbe
import org.skepsun.kototoro.core.github.AppVersion
import org.skepsun.kototoro.core.github.GitHubMirrorProbeResult
import org.skepsun.kototoro.core.github.GitHubMirrorProbeState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.widgets.SelectableTextView
import org.skepsun.kototoro.core.util.FileSize

data class AppUpdateMirrorOption(
    val id: String,
    val name: String,
    val probeResult: GitHubMirrorProbeResult? = null,
    val isFastest: Boolean = false,
) {
    val label: String
        get() = name
}

internal data class AppUpdateSourceOption(
    val source: AppUpdateSource,
    val probe: AppUpdateSourceProbe?,
)

internal fun buildAppUpdateSourceOptions(
    probes: Map<AppUpdateSource, AppUpdateSourceProbe>,
): List<AppUpdateSourceOption> = AppUpdateSource.entries
    .filter { it != AppUpdateSource.GITCODE }
    .map { source ->
        AppUpdateSourceOption(source = source, probe = probes[source])
    }

@Composable
fun AppUpdateScreen(
    version: AppVersion?,
    isLoading: Boolean,
    downloadProgress: Float,
    downloadState: Int,
    updateMessage: String?,
    operationErrorMessage: String?,
    mirrorOptions: List<AppUpdateMirrorOption>,
    selectedMirror: String,
    selectedSource: AppUpdateSource,
    sourceProbes: Map<AppUpdateSource, AppUpdateSourceProbe>,
    mirrorProbeState: GitHubMirrorProbeState = GitHubMirrorProbeState.Idle,
    onSourceSelected: (AppUpdateSource) -> Unit,
    onMirrorSelected: (String) -> Unit,
    onProbeMirrors: () -> Unit = {},
    onCancelMirrorProbes: () -> Unit = {},
    onSelectFastestMirror: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCancel: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenPadding = androidx.compose.ui.res.dimensionResource(R.dimen.screen_padding)
    val downloadError = when (downloadState) {
        DownloadManager.STATUS_FAILED -> stringResource(R.string.error_occurred)
        DownloadManager.STATUS_PAUSED -> stringResource(R.string.downloads_paused)
        else -> null
    }
    val criticalErrorMessage = operationErrorMessage ?: downloadError
    val scrollState = rememberScrollState()
    val sourceOptions = remember(sourceProbes) { buildAppUpdateSourceOptions(sourceProbes) }

    Surface(
        modifier = modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = screenPadding)
                    .padding(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UpdateHero(
                    version = version,
                    isLoading = isLoading,
                    downloadProgress = downloadProgress,
                )

                criticalErrorMessage?.let { message ->
                    UpdateStatusBanner(
                        message = message,
                        isError = true,
                        onRetry = onRetry,
                    )
                }
                updateMessage?.let { message ->
                    UpdateStatusBanner(
                        message = message,
                        isError = false,
                        onRetry = if (version == null) onRetry else null,
                    )
                }

                if (sourceOptions.size > 1) {
                    SourceSelector(
                        options = sourceOptions,
                        selectedSource = selectedSource,
                        onSourceSelected = onSourceSelected,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                AnimatedVisibility(visible = selectedSource == AppUpdateSource.GITHUB) {
                    MirrorSelector(
                        options = mirrorOptions,
                        selectedId = selectedMirror,
                        probeState = mirrorProbeState,
                        onMirrorSelected = onMirrorSelected,
                        onProbeMirrors = onProbeMirrors,
                        onCancelMirrorProbes = onCancelMirrorProbes,
                        onSelectFastestMirror = onSelectFastestMirror,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                UpdateDescriptionCard(
                    version = version,
                    isLoading = isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            UpdateActionBar(
                isUpdateEnabled = !isLoading && version != null,
                onCancel = onCancel,
                onUpdate = onUpdate,
                screenPadding = screenPadding,
            )
        }
    }
}

@Composable
private fun UpdateHero(
    version: AppVersion?,
    isLoading: Boolean,
    downloadProgress: Float,
) {
    ElevatedCard(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(R.drawable.ic_app_update),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (version == null) R.string.check_for_updates else R.string.app_update_available,
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            version != null -> stringResource(R.string.new_version_s, version.name)
                            isLoading -> stringResource(R.string.loading_)
                            else -> stringResource(R.string.no_update_available)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (isLoading) {
                if (downloadProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateStatusBanner(
    message: String,
    isError: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(
                        text = stringResource(R.string.retry),
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceSelector(
    options: List<AppUpdateSourceOption>,
    selectedSource: AppUpdateSource,
    onSourceSelected: (AppUpdateSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_web),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.app_update_source),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            options.forEach { option ->
                val isSelected = option.source == selectedSource
                Surface(
                    onClick = { onSourceSelected(option.source) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        ) {
                            Text(
                                text = sourceLabel(option.source),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 3.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(sourceProbeColor(option.probe)),
                                )
                                Text(
                                    text = sourceProbeLabel(option.probe),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 7.dp),
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = when (selectedSource) {
                    AppUpdateSource.GITHUB -> stringResource(R.string.app_update_source_github_summary)
                    AppUpdateSource.GITCODE -> stringResource(R.string.app_update_source_gitcode_summary)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.app_update_source_saved_summary),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun sourceLabel(source: AppUpdateSource): String = when (source) {
    AppUpdateSource.GITHUB -> stringResource(R.string.app_update_source_github)
    AppUpdateSource.GITCODE -> stringResource(R.string.app_update_source_gitcode)
}

@Composable
private fun sourceProbeLabel(probe: AppUpdateSourceProbe?): String = when {
    probe == null -> stringResource(R.string.app_update_source_checking)
    !probe.isAvailable -> stringResource(R.string.app_update_source_unavailable)
    else -> stringResource(R.string.app_update_source_latency, probe.latencyMillis ?: 0L)
}

@Composable
private fun sourceProbeColor(probe: AppUpdateSourceProbe?) = when {
    probe == null -> MaterialTheme.colorScheme.tertiary
    probe.isAvailable -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MirrorSelector(
    options: List<AppUpdateMirrorOption>,
    selectedId: String,
    probeState: GitHubMirrorProbeState,
    onMirrorSelected: (String) -> Unit,
    onProbeMirrors: () -> Unit,
    onCancelMirrorProbes: () -> Unit,
    onSelectFastestMirror: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_code),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.pref_github_mirror),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when (probeState) {
                                is GitHubMirrorProbeState.Running -> stringResource(
                                    R.string.mirror_probe_running,
                                    probeState.completed,
                                    probeState.total,
                                )
                                is GitHubMirrorProbeState.Finished -> when {
                                    probeState.total == 0 -> stringResource(R.string.mirror_probe_summary)
                                    probeState.available == 0 -> stringResource(R.string.mirror_probe_none_available)
                                    else -> stringResource(
                                        R.string.mirror_probe_finished,
                                        options.firstOrNull { it.id == probeState.fastestId }?.name
                                            ?: probeState.fastestId.orEmpty(),
                                        probeState.fastestMillis ?: 0L,
                                        probeState.available,
                                        probeState.total,
                                    )
                                }
                                GitHubMirrorProbeState.Idle -> stringResource(R.string.mirror_probe_summary)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                val isProbing = probeState is GitHubMirrorProbeState.Running
                FilledTonalButton(
                    onClick = if (isProbing) onCancelMirrorProbes else onProbeMirrors,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    if (isProbing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.mirror_probe_cancel),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_wifi),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.mirror_probe_short_action),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            val finishedState = probeState as? GitHubMirrorProbeState.Finished
            val fastestOption = finishedState?.fastestId?.let { id -> options.firstOrNull { it.id == id } }
            if (fastestOption != null && fastestOption.id != selectedId) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bolt),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${fastestOption.name} (${finishedState.fastestMillis ?: 0L} ms)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        TextButton(
                            onClick = onSelectFastestMirror,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.mirror_probe_select_fastest),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { option ->
                    val isSelected = option.id == selectedId
                    val probe = option.probeResult
                    FilterChip(
                        selected = isSelected,
                        onClick = { onMirrorSelected(option.id) },
                        shape = RoundedCornerShape(12.dp),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (option.isFastest && !isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                            borderWidth = if (isSelected) 1.5.dp else 1.dp,
                        ),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (option.isFastest) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_bolt),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                                Text(
                                    text = option.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                if (probe != null) {
                                    val isFast = (probe.latencyMillis ?: 0L) < 300L
                                    val isMedium = (probe.latencyMillis ?: 0L) < 800L
                                    val tagBg = when {
                                        !probe.isAvailable -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                                        isFast -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        isMedium -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                    val tagColor = when {
                                        !probe.isAvailable -> MaterialTheme.colorScheme.error
                                        isFast -> MaterialTheme.colorScheme.primary
                                        isMedium -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = tagBg,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(tagColor),
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = if (probe.isAvailable) {
                                                    "${probe.latencyMillis ?: "?"} ms"
                                                } else {
                                                    stringResource(R.string.mirror_probe_timeout)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = tagColor,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    }
                                } else if (probeState is GitHubMirrorProbeState.Running) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.5.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                    )
                }
            }

            Text(
                text = stringResource(R.string.pref_github_mirror_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateDescriptionCard(
    version: AppVersion?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.changelog),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            UpdateDescription(
                version = version,
                isLoading = isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun UpdateActionBar(
    isUpdateEnabled: Boolean,
    onCancel: () -> Unit,
    onUpdate: () -> Unit,
    screenPadding: androidx.compose.ui.unit.Dp,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
                .padding(horizontal = screenPadding)
                .height(72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(android.R.string.cancel))
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                enabled = isUpdateEnabled,
                onClick = onUpdate,
            ) {
                Text(stringResource(R.string.update))
            }
        }
    }
}

/** Keeps Markwon's existing Markdown, links, and span handling while the page itself is Compose. */
@Composable
private fun UpdateDescription(
    version: AppVersion?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val text = remember(version, context) {
        version?.let {
            buildString {
                append(context.getString(R.string.new_version_s, it.name))
                val downloadSize = it.patchSize ?: it.apkSize
                if (downloadSize > 0L) {
                    appendLine()
                    append(context.getString(R.string.size_s, FileSize.BYTES.format(context, downloadSize)))
                }
                appendLine()
                appendLine()
                append(it.description)
            }
        }
    }

    AndroidView(
        factory = { viewContext ->
            SelectableTextView(viewContext).apply {
                TextViewCompat.setTextAppearance(
                    this,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                )
                setTextIsSelectable(true)
            }
        },
        modifier = modifier,
        update = { textView ->
            when {
                text != null -> markwon.setMarkdown(textView, text)
                isLoading -> textView.setText(R.string.loading_)
                else -> textView.setText(R.string.no_update_available)
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun AppUpdateScreenPreview() {
    KototoroTheme {
        AppUpdateScreen(
            version = null,
            isLoading = false,
            downloadProgress = -1f,
            downloadState = DownloadManager.STATUS_PENDING,
            updateMessage = null,
            operationErrorMessage = null,
            mirrorOptions = listOf(
                AppUpdateMirrorOption("native", "Direct Native", GitHubMirrorProbeResult(120L, true), isFastest = false),
                AppUpdateMirrorOption("gh_proxy_com", "gh-proxy.com", GitHubMirrorProbeResult(45L, true), isFastest = true),
                AppUpdateMirrorOption("moeyy", "moeyy.xyz", GitHubMirrorProbeResult(null, false), isFastest = false),
            ),
            selectedMirror = "gh_proxy_com",
            selectedSource = AppUpdateSource.GITHUB,
            sourceProbes = emptyMap(),
            mirrorProbeState = GitHubMirrorProbeState.Finished(
                available = 2,
                total = 3,
                fastestId = "gh_proxy_com",
                fastestMillis = 45L,
            ),
            onSourceSelected = {},
            onMirrorSelected = {},
            onCancel = {},
            onUpdate = {},
        )
    }
}
