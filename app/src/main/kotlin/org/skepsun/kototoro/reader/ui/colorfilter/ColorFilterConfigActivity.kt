package org.skepsun.kototoro.reader.ui.colorfilter

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.ui.compose.toComposeColorFilter

@AndroidEntryPoint
class ColorFilterConfigActivity : BaseComposeActivity() {

    private val viewModel: ColorFilterConfigViewModel by viewModels()
    private var saveDialogVisible by mutableStateOf(false)
    private var discardDialogVisible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KototoroTheme {
                val colorFilter by viewModel.colorFilter.collectAsStateWithLifecycle()
                val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
                BackHandler { handleNavigateBack() }
                LaunchedEffect(Unit) {
                    viewModel.onDismiss.collect { finishAfterTransition() }
                }
                ColorFilterScreen(
                    colorFilter = colorFilter,
                    isLoading = isLoading,
                    onClose = ::handleNavigateBack,
                    onDone = { saveDialogVisible = true },
                    onReset = viewModel::reset,
                )
                if (saveDialogVisible) SaveTargetDialog()
                if (discardDialogVisible) UnsavedChangesDialog()
            }
        }
    }

    private fun handleNavigateBack() {
        if (viewModel.isChanged) {
            discardDialogVisible = true
        } else {
            finishAfterTransition()
        }
    }

    @Composable
    private fun ColorFilterScreen(
        colorFilter: ReaderColorFilter?,
        isLoading: Boolean,
        onClose: () -> Unit,
        onDone: () -> Unit,
        onReset: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                Text(stringResource(R.string.color_correction), style = MaterialTheme.typography.titleLarge)
                Button(onClick = onDone, enabled = !isLoading) { Text(stringResource(R.string.done)) }
            }
            HorizontalDivider()
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                PreviewComparison(colorFilter = colorFilter, isLoading = isLoading)
                ToggleRow(
                    label = stringResource(R.string.invert_colors),
                    checked = colorFilter?.isInverted == true,
                    enabled = !isLoading,
                    onCheckedChange = viewModel::setInversion,
                )
                ToggleRow(
                    label = stringResource(R.string.grayscale),
                    checked = colorFilter?.isGrayscale == true,
                    enabled = !isLoading,
                    onCheckedChange = viewModel::setGrayscale,
                )
                FilterSlider(
                    label = stringResource(R.string.brightness),
                    value = colorFilter?.brightness ?: 0f,
                    enabled = !isLoading,
                    onValueChange = viewModel::setBrightness,
                )
                FilterSlider(
                    label = stringResource(R.string.contrast),
                    value = colorFilter?.contrast ?: 0f,
                    enabled = !isLoading,
                    onValueChange = viewModel::setContrast,
                )
                ToggleRow(
                    label = stringResource(R.string.book_effect),
                    checked = colorFilter?.isBookBackground == true,
                    enabled = !isLoading,
                    onCheckedChange = viewModel::setBookEffect,
                )
                OutlinedButton(onClick = onReset, enabled = !isLoading, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.reset))
                }
            }
        }
    }

    @Composable
    private fun PreviewComparison(colorFilter: ReaderColorFilter?, isLoading: Boolean) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PreviewImage(colorFilter = null, modifier = Modifier.weight(1f))
            PreviewImage(colorFilter = colorFilter, modifier = Modifier.weight(1f))
        }
        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    @Composable
    private fun PreviewImage(colorFilter: ReaderColorFilter?, modifier: Modifier = Modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = modifier.heightIn(min = 150.dp, max = 280.dp),
        ) {
            AsyncImage(
                model = viewModel.preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter.toComposeColorFilter(),
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
            )
        }
    }

    @Composable
    private fun ToggleRow(
        label: String,
        checked: Boolean,
        enabled: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }

    @Composable
    private fun FilterSlider(
        label: String,
        value: Float,
        enabled: Boolean,
        onValueChange: (Float) -> Unit,
    ) {
        Column {
            Text("$label: ${((value + 1f) * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = value.coerceIn(-1f, 1f),
                onValueChange = onValueChange,
                valueRange = -1f..1f,
                enabled = enabled,
            )
        }
    }

    @Composable
    private fun SaveTargetDialog() {
        AlertDialog(
            onDismissRequest = { saveDialogVisible = false },
            title = { Text(stringResource(R.string.apply)) },
            text = { Text(stringResource(R.string.color_correction_apply_text)) },
            confirmButton = {
                TextButton(onClick = {
                    saveDialogVisible = false
                    viewModel.save()
                }) { Text(stringResource(R.string.this_manga)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        saveDialogVisible = false
                        viewModel.saveGlobally()
                    }) { Text(stringResource(R.string.globally)) }
                    TextButton(onClick = { saveDialogVisible = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            },
        )
    }

    @Composable
    private fun UnsavedChangesDialog() {
        AlertDialog(
            onDismissRequest = { discardDialogVisible = false },
            title = { Text(stringResource(R.string.color_correction)) },
            text = { Text(stringResource(R.string.text_unsaved_changes_prompt)) },
            confirmButton = {
                TextButton(onClick = {
                    discardDialogVisible = false
                    saveDialogVisible = true
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        discardDialogVisible = false
                        finishAfterTransition()
                    }) { Text(stringResource(R.string.discard)) }
                    TextButton(onClick = { discardDialogVisible = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            },
        )
    }
}
