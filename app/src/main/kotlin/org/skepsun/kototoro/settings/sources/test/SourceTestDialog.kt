package org.skepsun.kototoro.settings.sources.test

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestProgressHeader
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestResultLine
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestResultRow
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestStepLine
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestVerdict
import org.skepsun.kototoro.core.ui.text.LocalUiStrings
import org.skepsun.kototoro.core.ui.text.UiStrings
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import java.io.File
import java.time.Instant

/**
 * "Test all" dialog for the sources tab. Starts testing [sources] when shown and cancels
 * the run when dismissed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceTestDialog(
	sources: List<ContentSource>,
	onDismiss: () -> Unit,
	viewModel: SourceTestViewModel = hiltViewModel(),
) {
	LaunchedEffect(Unit) { viewModel.testAll(sources) }
	val context = LocalContext.current
	val clipboard = LocalClipboardManager.current
	val scope = rememberCoroutineScope()
	val progress by viewModel.progress.collectAsStateWithLifecycle()
	var expandedKeys by remember { mutableStateOf(emptySet<String>()) }
	val failedCount = progress.results.count {
		it.overall == StepStatus.FAIL && it.sourceKey !in progress.disabledKeys
	}
	val stopped = progress.finished && progress.cancelled && progress.done < progress.total
	SettingsAlertDialog(
		title = stringResource(R.string.source_test_title),
		onDismissRequest = {
			viewModel.cancel()
			onDismiss()
		},
		// The two dialog buttons are flow control; result actions live in the content.
		confirmButton = {
			when {
				!progress.finished -> TvTextButton(onClick = viewModel::cancel) {
					Text(stringResource(R.string.source_test_stop))
				}
				stopped -> TvTextButton(onClick = viewModel::resume) {
					Text(stringResource(R.string.source_test_resume))
				}
				else -> TvTextButton(onClick = onDismiss) {
					Text(stringResource(R.string.source_test_close))
				}
			}
		},
		dismissButton = if (stopped) {
			{
				TvTextButton(onClick = onDismiss) {
					Text(stringResource(R.string.source_test_close))
				}
			}
		} else {
			null
		},
		text = {
			Column(modifier = Modifier.fillMaxWidth()) {
				val passedCount = progress.results.count { it.overall == StepStatus.PASS }
				val unavailableCount = progress.results.count { it.overall == StepStatus.FAIL }
				val cloudflareCount = progress.results.count { it.overall == StepStatus.CLOUDFLARE }
				// Android's progress block, now shared with the Windows source test page.
				SourceTestProgressHeader(
					done = progress.done,
					total = progress.total,
					progressLabel = stringResource(R.string.source_test_progress, progress.done, progress.total),
					runningLabel = if (!progress.finished && progress.running.isNotEmpty()) {
						stringResource(R.string.source_test_running, progress.running.joinToString())
					} else {
						""
					},
					summaryLabel = if (progress.finished) {
						stringResource(R.string.source_test_summary, passedCount, unavailableCount, cloudflareCount)
					} else {
						""
					},
					cancelledNote = if (progress.finished && progress.cancelled) {
						stringResource(R.string.source_test_cancelled_note)
					} else {
						null
					},
				)
				Spacer(modifier = Modifier.height(8.dp))
				val strings = LocalUiStrings.current
				LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
					items(progress.results, key = { it.sourceKey }) { result ->
						SourceTestResultRow(
							result = result.toLine(
								strings = strings,
								disabled = result.sourceKey in progress.disabledKeys,
							),
							expanded = result.sourceKey in expandedKeys,
							onToggle = {
								expandedKeys = if (result.sourceKey in expandedKeys) {
									expandedKeys - result.sourceKey
								} else {
									expandedKeys + result.sourceKey
								}
							},
							// The chevron is Android's alone: a dialog row has no step count to trade it for.
							expandIcon = { expanded ->
								Icon(
									imageVector = Icons.Filled.ExpandMore,
									contentDescription = null,
									modifier = Modifier.rotate(if (expanded) 180f else 0f),
								)
							},
						)
						HorizontalDivider()
					}
				}
				FlowRow(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
					verticalArrangement = Arrangement.Center,
				) {
					if (progress.finished && failedCount > 0) {
						TvTextButton(onClick = viewModel::disableFailed) {
							Text(stringResource(R.string.source_test_disable_failed))
						}
					}
					TvTextButton(
						onClick = { clipboard.setText(AnnotatedString(viewModel.exportJson())) },
						enabled = progress.finished,
					) {
						Text(stringResource(R.string.source_test_copy_json))
					}
					TvTextButton(
						onClick = {
							val json = viewModel.exportJson()
							scope.launch(Dispatchers.IO) {
								val file = File(context.cacheDir, "kototoro-source-test-${Instant.now().epochSecond}.json")
								file.writeText(json)
								val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
								ShareCompat.IntentBuilder(context)
									.setType("application/json")
									.setStream(uri)
									.setChooserTitle(context.getString(R.string.source_test_export))
									.startChooser()
							}
						},
						enabled = progress.finished,
					) {
						Text(stringResource(R.string.source_test_export))
					}
				}
			}
		},
	)
}

/**
 * One result projected into the shared row: the verdict labels come from Android's catalog, and Android marks a source
 * the run just switched off instead of leaving a verdict that no longer holds.
 */
private fun SourceTestResult.toLine(strings: UiStrings, disabled: Boolean): SourceTestResultLine = SourceTestResultLine(
	key = sourceKey,
	title = title,
	verdict = overall.toVerdict(),
	verdictLabel = if (disabled) {
		strings.string("source_test_disabled")
	} else {
		strings.string(statusLabelKey(overall))
	},
	disabled = disabled,
	steps = SourceTestStep.entries.mapNotNull { step ->
		val stepResult = steps[step] ?: return@mapNotNull null
		SourceTestStepLine(
			key = step.name,
			label = strings.string(stepLabelKey(step)),
			verdict = stepResult.status.toVerdict(),
			verdictLabel = strings.string(statusLabelKey(stepResult.status)),
			durationMs = stepResult.durationMs,
			message = stepResult.message,
		)
	},
)

private fun StepStatus.toVerdict(): SourceTestVerdict = when (this) {
	StepStatus.PASS -> SourceTestVerdict.PASS
	StepStatus.FAIL -> SourceTestVerdict.FAIL
	StepStatus.SKIP -> SourceTestVerdict.SKIP
	StepStatus.CLOUDFLARE -> SourceTestVerdict.CHALLENGE
}

private fun statusLabelKey(status: StepStatus): String = when (status) {
	StepStatus.PASS -> "source_test_status_pass"
	StepStatus.FAIL -> "source_test_status_fail"
	StepStatus.SKIP -> "source_test_status_skip"
	StepStatus.CLOUDFLARE -> "source_test_status_cloudflare"
}

private fun stepLabelKey(step: SourceTestStep): String = when (step) {
	SourceTestStep.LIST -> "source_test_step_list"
	SourceTestStep.COVERS -> "source_test_step_covers"
	SourceTestStep.DETAILS -> "source_test_step_details"
	SourceTestStep.CHAPTERS -> "source_test_step_chapters"
	SourceTestStep.PAGES -> "source_test_step_pages"
	SourceTestStep.RANDOM -> "source_test_step_random"
	SourceTestStep.SEARCH -> "source_test_step_search"
}
