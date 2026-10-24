package org.skepsun.kototoro.settings.sources.test

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog

/**
 * "Test all" dialog for the sources tab. Starts testing [sources] when shown and cancels
 * the run when dismissed.
 */
@Composable
internal fun SourceTestDialog(
	sources: List<ContentSource>,
	onDismiss: () -> Unit,
	viewModel: SourceTestViewModel = hiltViewModel(),
) {
	LaunchedEffect(Unit) { viewModel.testAll(sources) }
	val context = LocalContext.current
	val progress by viewModel.progress.collectAsStateWithLifecycle()
	var expandedKeys by remember { mutableStateOf(emptySet<String>()) }
	val failedCount = progress.results.count {
		it.overall == StepStatus.FAIL && it.sourceKey !in progress.disabledKeys
	}
	val closeButton: @Composable () -> Unit = {
		TvTextButton(
			onClick = onDismiss,
			enabled = progress.finished && failedCount > 0,
			modifier = Modifier.alpha(if (progress.finished && failedCount == 0) 0f else 1f),
		) {
			Text(stringResource(R.string.source_test_close))
		}
	}
	SettingsAlertDialog(
		title = stringResource(R.string.source_test_title),
		onDismissRequest = {
			viewModel.cancel()
			onDismiss()
		},
		confirmButton = {
			when {
				!progress.finished -> TvTextButton(onClick = viewModel::cancel) {
					Text(stringResource(R.string.source_test_cancel))
				}
				failedCount > 0 -> TvTextButton(onClick = viewModel::disableFailed) {
					Text(stringResource(R.string.source_test_disable_failed))
				}
				else -> TvTextButton(onClick = onDismiss) {
					Text(stringResource(R.string.source_test_close))
				}
			}
		},
		dismissButton = closeButton,
		text = {
			Column(modifier = Modifier.fillMaxWidth()) {
				val fraction = if (progress.total == 0) 1f else progress.done.toFloat() / progress.total
				LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
				Spacer(modifier = Modifier.height(8.dp))
				Text(
					text = stringResource(R.string.source_test_progress, progress.done, progress.total),
					style = MaterialTheme.typography.bodyMedium,
				)
				Text(
					text = if (!progress.finished && progress.running.isNotEmpty()) {
						stringResource(R.string.source_test_running, progress.running.joinToString())
					} else {
						""
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					minLines = 2,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				val passedCount = progress.results.count { it.overall == StepStatus.PASS }
				val unavailableCount = progress.results.count { it.overall == StepStatus.FAIL }
				val cloudflareCount = progress.results.count { it.overall == StepStatus.CLOUDFLARE }
				Text(
					text = if (progress.finished) {
						stringResource(R.string.source_test_summary, passedCount, unavailableCount, cloudflareCount)
					} else {
						""
					},
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
					minLines = 1,
				)
				if (progress.finished && progress.cancelled) {
					Text(
						text = stringResource(R.string.source_test_cancelled_note),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				Spacer(modifier = Modifier.height(8.dp))
				LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
					items(progress.results, key = { it.sourceKey }) { result ->
						SourceTestResultRow(
							result = result,
							disabled = result.sourceKey in progress.disabledKeys,
							expanded = result.sourceKey in expandedKeys,
							onToggle = {
								expandedKeys = if (result.sourceKey in expandedKeys) {
									expandedKeys - result.sourceKey
								} else {
									expandedKeys + result.sourceKey
								}
							},
						)
						HorizontalDivider()
					}
				}
				TvTextButton(
					onClick = {
						val intent = Intent(Intent.ACTION_SEND).apply {
							type = "text/plain"
							putExtra(Intent.EXTRA_TEXT, viewModel.exportJson())
						}
						val title = context.getString(R.string.source_test_export)
						context.startActivity(Intent.createChooser(intent, title))
					},
					enabled = progress.finished,
					modifier = Modifier.align(Alignment.End),
				) {
					Text(stringResource(R.string.source_test_export))
				}
			}
		},
	)
}

@Composable
private fun SourceTestResultRow(
	result: SourceTestResult,
	disabled: Boolean,
	expanded: Boolean,
	onToggle: () -> Unit,
) {
	val colors = MaterialTheme.colorScheme
	val verdictColor = if (disabled) colors.onSurfaceVariant else statusColor(result.overall)
	val verdictText = if (disabled) {
		stringResource(R.string.source_test_disabled)
	} else {
		stringResource(statusLabelRes(result.overall))
	}
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onToggle)
			.padding(vertical = 8.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = result.title,
				style = MaterialTheme.typography.bodyLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = verdictText,
				style = MaterialTheme.typography.labelLarge,
				color = verdictColor,
				modifier = Modifier.padding(horizontal = 8.dp),
			)
			Icon(
				imageVector = Icons.Filled.ExpandMore,
				contentDescription = null,
				modifier = Modifier.rotate(if (expanded) 180f else 0f),
			)
		}
		if (expanded) {
			SourceTestStep.entries.forEach { step ->
				val stepResult = result.steps[step] ?: return@forEach
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp),
				) {
					Text(
						text = stringResource(stepLabelRes(step)),
						style = MaterialTheme.typography.bodySmall,
						modifier = Modifier.weight(1f),
					)
					Text(
						text = stringResource(statusLabelRes(stepResult.status)),
						style = MaterialTheme.typography.labelMedium,
						color = statusColor(stepResult.status),
					)
					Text(
						text = "${stepResult.durationMs} ms",
						style = MaterialTheme.typography.labelSmall,
						color = colors.onSurfaceVariant,
						modifier = Modifier.padding(start = 8.dp),
					)
				}
				if (stepResult.message.isNotBlank()) {
					Text(
						text = stepResult.message,
						style = MaterialTheme.typography.bodySmall,
						color = colors.onSurfaceVariant,
						modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun statusColor(status: StepStatus): Color {
	val colors = MaterialTheme.colorScheme
	return when (status) {
		StepStatus.PASS -> colors.primary
		StepStatus.FAIL -> colors.error
		StepStatus.SKIP -> colors.onSurfaceVariant
		StepStatus.CLOUDFLARE -> colors.tertiary
	}
}

private fun statusLabelRes(status: StepStatus): Int = when (status) {
	StepStatus.PASS -> R.string.source_test_status_pass
	StepStatus.FAIL -> R.string.source_test_status_fail
	StepStatus.SKIP -> R.string.source_test_status_skip
	StepStatus.CLOUDFLARE -> R.string.source_test_status_cloudflare
}

private fun stepLabelRes(step: SourceTestStep): Int = when (step) {
	SourceTestStep.LIST -> R.string.source_test_step_list
	SourceTestStep.COVERS -> R.string.source_test_step_covers
	SourceTestStep.DETAILS -> R.string.source_test_step_details
	SourceTestStep.CHAPTERS -> R.string.source_test_step_chapters
	SourceTestStep.PAGES -> R.string.source_test_step_pages
	SourceTestStep.RANDOM -> R.string.source_test_step_random
	SourceTestStep.SEARCH -> R.string.source_test_step_search
}
