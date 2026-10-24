package org.skepsun.kototoro.local.ui.info.compose

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.compose.KototoroLinearProgressIndicator
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.local.ui.info.LocalInfoViewModel
import org.skepsun.kototoro.parsers.model.Content

@Composable
fun LocalInfoDialogRoute(
    manga: Content,
    onDismissRequest: () -> Unit,
    viewModel: LocalInfoViewModel = hiltViewModel(key = "local-info-${manga.id}"),
) {
    val context = LocalContext.current

    LaunchedEffect(manga) {
        viewModel.initialize(manga)
    }

    LaunchedEffect(viewModel.onCleanedUp) {
        viewModel.onCleanedUp.collect { event ->
            event?.consume { result ->
                val text = if (result.first == 0 && result.second == 0L) {
                    context.getString(R.string.no_chapters_deleted)
                } else {
                    context.getString(
                        R.string.chapters_deleted_pattern,
                        context.resources.getQuantityStringSafe(R.plurals.chapters, result.first, result.first),
                        FileSize.BYTES.format(context, result.second),
                    )
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val path by viewModel.path.collectAsStateWithLifecycle()
    val size by viewModel.size.collectAsStateWithLifecycle()
    val availableSize by viewModel.availableSize.collectAsStateWithLifecycle()
    val isCleaningUp by viewModel.isCleaningUp.collectAsStateWithLifecycle()

    LocalInfoDialog(
        path = path,
        size = size,
        availableSize = availableSize,
        isCleaningUp = isCleaningUp,
        onCleanupClick = viewModel::cleanup,
        onDismissRequest = onDismissRequest,
    )
}

@Composable
private fun LocalInfoDialog(
    path: String?,
    size: Long,
    availableSize: Long,
    isCleaningUp: Boolean,
    onCleanupClick: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!isCleaningUp) {
                onDismissRequest()
            }
        },
        title = {
            Text(text = stringResource(R.string.saved_manga))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.location),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = path.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                StorageUsageBar(size = size, availableSize = availableSize)
                StorageUsageLabel(
                    color = MaterialTheme.colorScheme.primary,
                    text = if (size >= 0L) {
                        stringResource(
                            R.string.memory_usage_pattern,
                            stringResource(R.string.this_manga),
                            FileSize.BYTES.format(LocalContext.current, size),
                        )
                    } else {
                        stringResource(R.string.this_manga)
                    },
                )
                StorageUsageLabel(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    text = if (availableSize >= 0L) {
                        stringResource(
                            R.string.memory_usage_pattern,
                            stringResource(R.string.available),
                            FileSize.BYTES.format(LocalContext.current, availableSize),
                        )
                    } else {
                        stringResource(R.string.available)
                    },
                )
                AssistChip(
                    enabled = !isCleaningUp,
                    onClick = onCleanupClick,
                    leadingIcon = {
                        if (isCleaningUp) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                            )
                        }
                    },
                    label = {
                        Text(text = stringResource(R.string.delete_read_chapters))
                    },
                )
            }
        },
        confirmButton = {
            TvTextButton(
                enabled = !isCleaningUp,
                onClick = onDismissRequest,
            ) {
                Text(text = stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun StorageUsageBar(
    size: Long,
    availableSize: Long,
    modifier: Modifier = Modifier,
) {
    if (size >= 0L && availableSize >= 0L && size + availableSize > 0L) {
        val percent = size.toFloat() / (size + availableSize).toFloat()
        KototoroLinearProgressIndicator(
            progress = { percent },
            modifier = modifier
                .fillMaxWidth()
                .height(18.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    } else {
        KototoroLinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .height(18.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun StorageUsageLabel(
    color: Color,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(12.dp)
                .fillMaxWidth(0.04f)
                .background(color, MaterialTheme.shapes.extraSmall),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
