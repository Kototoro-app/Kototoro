package org.skepsun.kototoro.core.ui.sourcetest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Android's `StepStatus`, under a name that does not promise a Cloudflare-specific mechanism: a host reports CHALLENGE
 * whenever a source stopped at a challenge it cannot solve on its own.
 */
enum class SourceTestVerdict { PASS, FAIL, SKIP, CHALLENGE }

/** One step of a source's test: the host's own step name and wording, its verdict, how long it took and what it said. */
data class SourceTestStepLine(
    val key: String,
    val label: String,
    val verdict: SourceTestVerdict,
    val verdictLabel: String,
    val durationMs: Long,
    val message: String = "",
)

/**
 * One source's test result as both hosts show it: the work list / source title, the overall verdict as a coloured
 * label, and the steps below it when expanded. Labels arrive pre-formatted, so each host keeps reading its own catalog.
 */
data class SourceTestResultLine(
    val key: String,
    val title: String,
    val verdict: SourceTestVerdict,
    val steps: List<SourceTestStepLine>,
    val verdictLabel: String,
    /** Android greys the verdict of a source the run just switched off; Windows never switches one off mid-run. */
    val disabled: Boolean = false,
    /** Android shows only the verdict; Windows also writes how many steps the run covered. */
    val trailingLabel: String? = null,
)

/** Android's verdict colours, which now also decide Windows': pass on primary, fail on error, skip muted, challenge tertiary. */
@Composable
fun sourceTestVerdictColor(verdict: SourceTestVerdict): Color = when (verdict) {
    SourceTestVerdict.PASS -> MaterialTheme.colorScheme.primary
    SourceTestVerdict.FAIL -> MaterialTheme.colorScheme.error
    SourceTestVerdict.SKIP -> MaterialTheme.colorScheme.onSurfaceVariant
    SourceTestVerdict.CHALLENGE -> MaterialTheme.colorScheme.tertiary
}

/**
 * The "N / M checked" block above the results: the progress bar, the progress line, the source currently being asked,
 * the pass/fail/challenge summary and the note that the run was cancelled. Both hosts parse these strings themselves
 * (`source_test_progress`, `source_test_running`, `source_test_summary`, `source_test_cancelled_note`).
 */
@Composable
fun SourceTestProgressHeader(
    done: Int,
    total: Int,
    progressLabel: String,
    runningLabel: String,
    summaryLabel: String,
    cancelledNote: String?,
    modifier: Modifier = Modifier,
    fraction: Float = if (total == 0) 1f else done.toFloat() / total,
    barTag: String? = null,
    progressTag: String? = null,
    summaryTag: String? = null,
    cancelledTag: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().then(barTag?.let { Modifier.testTag(it) } ?: Modifier),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = progressLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.then(progressTag?.let { Modifier.testTag(it) } ?: Modifier),
        )
        Text(
            text = runningLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Two fixed lines keep the buttons below from jumping while sources report in.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = summaryLabel,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            minLines = 1,
            modifier = Modifier.then(summaryTag?.let { Modifier.testTag(it) } ?: Modifier),
        )
        if (cancelledNote != null) {
            Text(
                text = cancelledNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.then(cancelledTag?.let { Modifier.testTag(it) } ?: Modifier),
            )
        }
    }
}

/**
 * One source's row, expandable into its steps: the title, the verdict label, the optional trailing count, and per step
 * the label, verdict, duration and the message a failing step left behind. Android's dialog and the Windows page drew
 * the same shape twice (with different colours); this is the shape they share now.
 */
@Composable
fun SourceTestResultRow(
    result: SourceTestResultLine,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    rowTag: String? = null,
    verdictTag: String? = null,
    stepTag: ((key: String) -> String)? = null,
    expandIcon: (@Composable (expanded: Boolean) -> Unit)? = null,
    durationLabel: (Long) -> String = { "$it ms" },
) {
    val verdictColor = if (result.disabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        sourceTestVerdictColor(result.verdict)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp)
            .then(rowTag?.let { Modifier.testTag(it) } ?: Modifier),
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
                text = result.verdictLabel,
                style = MaterialTheme.typography.labelLarge,
                color = verdictColor,
                modifier = Modifier.padding(horizontal = 8.dp)
                    .then(verdictTag?.let { Modifier.testTag(it) } ?: Modifier),
            )
            if (result.trailingLabel != null) {
                Text(
                    text = result.trailingLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (expandIcon != null) {
                expandIcon(expanded)
            }
        }
        if (expanded) {
            result.steps.forEach { step ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = step.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = step.verdictLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = sourceTestVerdictColor(step.verdict),
                        modifier = Modifier.then(stepTag?.invoke(step.key)?.let { Modifier.testTag(it) } ?: Modifier),
                    )
                    Text(
                        text = durationLabel(step.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (step.message.isNotBlank()) {
                    Text(
                        text = step.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                    )
                }
            }
        }
    }
}
