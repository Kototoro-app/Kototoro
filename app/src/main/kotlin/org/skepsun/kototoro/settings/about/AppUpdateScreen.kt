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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.TextViewCompat
import io.noties.markwon.Markwon
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.github.AppUpdateSource
import org.skepsun.kototoro.core.github.AppUpdateSourceProbe
import org.skepsun.kototoro.core.github.AppVersion
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.widgets.SelectableTextView
import org.skepsun.kototoro.core.util.FileSize

data class AppUpdateMirrorOption(
    val mirror: AppSettings.GitHubMirror,
    val label: String,
)

internal data class AppUpdateSourceOption(
    val source: AppUpdateSource,
    val probe: AppUpdateSourceProbe?,
)

internal fun buildAppUpdateSourceOptions(
    probes: Map<AppUpdateSource, AppUpdateSourceProbe>,
): List<AppUpdateSourceOption> = AppUpdateSource.entries.map { source ->
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
    selectedMirror: AppSettings.GitHubMirror,
    selectedSource: AppUpdateSource,
    sourceProbes: Map<AppUpdateSource, AppUpdateSourceProbe>,
    onSourceSelected: (AppUpdateSource) -> Unit,
    onMirrorSelected: (AppSettings.GitHubMirror) -> Unit,
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
                    UpdateStatusBanner(message = message, isError = true)
                }
                updateMessage?.let { message ->
                    UpdateStatusBanner(message = message, isError = false)
                }

                SourceSelector(
                    selectedSource = selectedSource,
                    probes = sourceProbes,
                    onSourceSelected = onSourceSelected,
                    modifier = Modifier.fillMaxWidth(),
                )

                AnimatedVisibility(visible = selectedSource == AppUpdateSource.GITHUB) {
                    MirrorSelector(
                        options = mirrorOptions,
                        selectedMirror = selectedMirror,
                        onMirrorSelected = onMirrorSelected,
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
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SourceSelector(
    selectedSource: AppUpdateSource,
    probes: Map<AppUpdateSource, AppUpdateSourceProbe>,
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
            buildAppUpdateSourceOptions(probes).forEach { option ->
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
    selectedMirror: AppSettings.GitHubMirror,
    onMirrorSelected: (AppSettings.GitHubMirror) -> Unit,
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
                    text = stringResource(R.string.pref_github_mirror),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option.mirror == selectedMirror,
                        onClick = { onMirrorSelected(option.mirror) },
                        label = { Text(option.label) },
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
            isLoading = true,
            downloadProgress = -1f,
            downloadState = DownloadManager.STATUS_PENDING,
            updateMessage = null,
            operationErrorMessage = null,
            mirrorOptions = emptyList(),
            selectedMirror = AppSettings.GitHubMirror.NATIVE,
            selectedSource = AppUpdateSource.GITCODE,
            sourceProbes = emptyMap(),
            onSourceSelected = {},
            onMirrorSelected = {},
            onCancel = {},
            onUpdate = {},
        )
    }
}
