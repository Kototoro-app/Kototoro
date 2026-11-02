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
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestProgressHeader
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestResultLine
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestResultRow
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestStepLine
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestVerdict
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
        SettingsGroup {
            SettingsInfoRow(desktopText("sourceTest.intro"),
                desktopText("sourceTest.steps", state.enabledSources.size))
            SettingsRowDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (progress.total > 0) {
                    val passed = progress.results.count { it.overall == SourceTestStatus.PASS }
                    val unavailable = progress.results.count { it.overall == SourceTestStatus.FAIL }
                    val challenged = progress.results.count { it.overall == SourceTestStatus.CHALLENGE }
                    // Android's progress block, now the shared one: bar, "N / M checked", the source being asked, the
                    // pass/fail/challenge summary and the cancelled note, in Android's own wording.
                    SourceTestProgressHeader(
                        done = progress.done,
                        total = progress.total,
                        progressLabel = AndroidStrings.format("source_test_progress", progress.done, progress.total),
                        runningLabel = if (!progress.finished && progress.running.isNotEmpty()) {
                            AndroidStrings.format("source_test_running", progress.running.joinToString())
                        } else {
                            ""
                        },
                        summaryLabel = if (progress.finished) {
                            AndroidStrings.format("source_test_summary", passed, unavailable, challenged)
                        } else {
                            ""
                        },
                        cancelledNote = if (progress.finished && progress.cancelled) {
                            AndroidStrings["source_test_cancelled_note"]
                        } else {
                            null
                        },
                        barTag = "source-test-bar",
                        progressTag = "source-test-progress",
                        summaryTag = "source-test-summary",
                        cancelledTag = "source-test-cancelled",
                    )
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
                    SourceTestRowLine(
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
 * One source's row, projected into the shared [SourceTestResultRow]: the labels come from Android's catalog and the
 * step count is the one thing Windows adds to the shape, so a run covering six steps says so without expanding it.
 */
@Composable
private fun SourceTestRowLine(result: SourceTestResult, expanded: Boolean, onToggle: () -> Unit) {
    SourceTestResultRow(
        result = result.toLine(),
        expanded = expanded,
        onToggle = onToggle,
        rowTag = "source-test-result:${result.sourceName}",
        verdictTag = "source-test-verdict:${result.sourceName}",
        stepTag = { stepKey -> "source-test-step:${result.sourceName}:$stepKey" },
    )
}

private fun SourceTestResult.toLine(): SourceTestResultLine = SourceTestResultLine(
    key = sourceName,
    title = title,
    verdict = overall.toVerdict(),
    verdictLabel = overall.label(),
    trailingLabel = desktopText("sourceTest.stepCount", steps.size),
    steps = SourceTestStep.entries.mapNotNull { step ->
        val stepResult = steps[step] ?: return@mapNotNull null
        SourceTestStepLine(
            key = step.name,
            label = step.label(),
            verdict = stepResult.status.toVerdict(),
            verdictLabel = stepResult.status.label(),
            durationMs = stepResult.durationMs,
            message = stepResult.message,
        )
    },
)

/** Android's `StepStatus` and Windows' `SourceTestStatus` name the same four outcomes. */
private fun SourceTestStatus.toVerdict(): SourceTestVerdict = when (this) {
    SourceTestStatus.PASS -> SourceTestVerdict.PASS
    SourceTestStatus.FAIL -> SourceTestVerdict.FAIL
    SourceTestStatus.SKIP -> SourceTestVerdict.SKIP
    SourceTestStatus.CHALLENGE -> SourceTestVerdict.CHALLENGE
}

/** Android's `statusLabelRes` for the four verdicts; the challenge label is Android's Cloudflare one, renamed. */
private fun SourceTestStatus.label(): String = when (this) {
    SourceTestStatus.PASS -> AndroidStrings["source_test_status_pass"]
    SourceTestStatus.FAIL -> AndroidStrings["source_test_status_fail"]
    SourceTestStatus.SKIP -> AndroidStrings["source_test_status_skip"]
    SourceTestStatus.CHALLENGE -> AndroidStrings["source_test_status_cloudflare"]
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
