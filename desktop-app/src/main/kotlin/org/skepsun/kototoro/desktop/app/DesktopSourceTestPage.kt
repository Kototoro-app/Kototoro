package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader

/**
 * Android's "test all sources" dialog as a Windows page. The dialog exists on the phone because the list of sources is
 * the screen behind it; here the run needs room for six steps per source, so it gets its own page reached from
 * 扩展与仓库 (where 来源 and the installed packages already live).
 *
 * What arrives from Android unchanged: the six steps and their wording, the pass/fail/skip/challenge verdicts, the
 * "N / M checked" progress line, the cancelled note, the "available / unavailable / Cloudflare" summary, the exported
 * JSON, and "disable the failed sources" — which writes the same `disabled_sources` set the source switch dialog edits.
 *
 * What is deliberately different: Android's RANDOM step has no Windows operation (see [DesktopSourceTester]), and a
 * Cloudflare challenge is *reported* here instead of opening a browser window mid-run — the sources that need manual
 * verification are exactly the ones this page is supposed to name.
 */
@Composable
internal fun DesktopSourceTestPage(controller: DesktopController, state: DesktopAppState) {
    val progress by controller.sourceTester.progress.collectAsState()
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val active = !state.busy
    Column(
        modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.showExtensions() }, enabled = active,
            modifier = Modifier.testTag("source-test-back")) { Text(desktopText("common.back")) }
        SettingsGroup {
            SettingsInfoRow(desktopText("sourceTest.intro"),
                desktopText("sourceTest.steps", state.enabledSources.size))
            SettingsRowDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 1f else progress.done.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth().testTag("source-test-bar"),
                    )
                    Text(
                        AndroidStrings.format("source_test_progress", progress.done, progress.total),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("source-test-progress"),
                    )
                    Text(
                        if (!progress.finished && progress.running.isNotEmpty()) {
                            AndroidStrings.format("source_test_running", progress.running.joinToString())
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // A fixed two lines keeps the buttons below from jumping while sources report in.
                        minLines = 2,
                        maxLines = 2,
                    )
                    val passed = progress.results.count { it.overall == SourceTestStatus.PASS }
                    val unavailable = progress.results.count { it.overall == SourceTestStatus.FAIL }
                    val challenged = progress.results.count { it.overall == SourceTestStatus.CHALLENGE }
                    Text(
                        if (progress.finished) {
                            AndroidStrings.format("source_test_summary", passed, unavailable, challenged)
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("source-test-summary"),
                    )
                    if (progress.finished && progress.cancelled) {
                        Text(
                            AndroidStrings["source_test_cancelled_note"],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("source-test-cancelled"),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (progress.isRunning) {
                        TextButton({ controller.cancelSourceTest() }, enabled = active,
                            modifier = Modifier.testTag("source-test-cancel")) {
                            Text(AndroidStrings["source_test_cancel"])
                        }
                    } else {
                        TextButton({ controller.startSourceTest() },
                            enabled = active && state.enabledSources.isNotEmpty(),
                            modifier = Modifier.testTag("source-test-run")) {
                            Text(AndroidStrings["source_test_all"])
                        }
                    }
                    TextButton({ controller.copySourceTestResults() },
                        enabled = active && progress.results.isNotEmpty(),
                        modifier = Modifier.testTag("source-test-copy")) {
                        Text(AndroidStrings["source_test_export"])
                    }
                    if (progress.finished && progress.failedSources.isNotEmpty()) {
                        TextButton({ controller.disableFailedSources() }, enabled = active,
                            modifier = Modifier.testTag("source-test-disable-failed")) {
                            Text(AndroidStrings["source_test_disable_failed"])
                        }
                    }
                }
            }
        }
        SettingsSectionHeader(desktopText("sourceTest.results", progress.results.size))
        SettingsGroup(Modifier.fillMaxWidth().testTag("source-test-results")) {
            if (progress.results.isEmpty()) {
                SettingsInfoRow(desktopText("sourceTest.empty"), null)
            } else {
                progress.results.forEachIndexed { index, result ->
                    if (index > 0) SettingsRowDivider()
                    SourceTestResultRow(
                        result = result,
                        expanded = result.sourceName in expanded,
                        onToggle = {
                            expanded = if (result.sourceName in expanded) {
                                expanded - result.sourceName
                            } else {
                                expanded + result.sourceName
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * One source's row: the title, its verdict and the step count, expandable into the six step lines Android shows in the
 * dialog (label, verdict, duration, and the message under the failing ones).
 */
@Composable
private fun SourceTestResultRow(result: SourceTestResult, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .testTag("source-test-result:${result.sourceName}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(result.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                result.overall.label(),
                style = MaterialTheme.typography.labelLarge,
                color = result.overall.color(),
                modifier = Modifier.padding(horizontal = 8.dp).testTag(
                    "source-test-verdict:${result.sourceName}"),
            )
            Text(
                desktopText("sourceTest.stepCount", result.steps.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            SourceTestStep.entries.forEach { step ->
                val stepResult = result.steps[step] ?: return@forEach
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(step.label(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(
                        stepResult.status.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color = stepResult.status.color(),
                        modifier = Modifier.testTag("source-test-step:${result.sourceName}:${step.name}"),
                    )
                    Text(
                        "${stepResult.durationMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (stepResult.message.isNotBlank()) {
                    Text(
                        stepResult.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

/** Android's `statusLabelRes` for the four verdicts; the challenge label is Android's Cloudflare one, renamed. */
private fun SourceTestStatus.label(): String = when (this) {
    SourceTestStatus.PASS -> AndroidStrings["source_test_status_pass"]
    SourceTestStatus.FAIL -> AndroidStrings["source_test_status_fail"]
    SourceTestStatus.SKIP -> AndroidStrings["source_test_status_skip"]
    SourceTestStatus.CHALLENGE -> AndroidStrings["source_test_status_cloudflare"]
}

private fun SourceTestStatus.color(): Color = when (this) {
    SourceTestStatus.PASS -> Color(0xFF2E7D32)
    SourceTestStatus.FAIL -> Color(0xFFC62828)
    SourceTestStatus.SKIP -> Color(0xFF616161)
    SourceTestStatus.CHALLENGE -> Color(0xFFEF6C00)
}

/** Android's `stepLabelRes` for the steps this host actually runs. */
private fun SourceTestStep.label(): String = when (this) {
    SourceTestStep.LIST -> AndroidStrings["source_test_step_list"]
    SourceTestStep.COVERS -> AndroidStrings["source_test_step_covers"]
    SourceTestStep.DETAILS -> AndroidStrings["source_test_step_details"]
    SourceTestStep.CHAPTERS -> AndroidStrings["source_test_step_chapters"]
    SourceTestStep.PAGES -> AndroidStrings["source_test_step_pages"]
    SourceTestStep.SEARCH -> AndroidStrings["source_test_step_search"]
}
