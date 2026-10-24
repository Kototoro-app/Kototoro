package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale

/** How Android labels a content state; unknown or missing states get no row at all. */
internal fun desktopContentStatusLabel(state: String?): String? = when (state) {
    "ONGOING" -> desktopText("details.status.ongoing")
    "FINISHED" -> desktopText("details.status.finished")
    "ABANDONED" -> desktopText("details.status.abandoned")
    "PAUSED" -> desktopText("details.status.paused")
    "UPCOMING" -> desktopText("details.status.upcoming")
    "RESTRICTED" -> desktopText("details.status.restricted")
    else -> null
}

/** Android's `ContentState` order, so the library's publication-state filter matches the app's wording. */
internal val DesktopContentStates = listOf("ONGOING", "FINISHED", "ABANDONED", "PAUSED", "UPCOMING", "RESTRICTED")

/** A warned tag chip says so on its semantics, so the probe can read the tint decision instead of sampling pixels. */
internal val DesktopWarnedTagKey = SemanticsPropertyKey<Boolean>("DesktopWarnedTag")

/** The link Android's share sheet offers, minus the sheet: a desktop copies it. */
internal fun desktopContentLink(content: SourceContent): String =
    content.publicUrl.takeIf(String::isNotBlank) ?: content.url

/**
 * Android's details facts: state, rating, alternative titles, tags and a copy button. Rows the source did not
 * answer are left out instead of showing blanks. A tag the shared warn-list calls extreme keeps the chip but takes
 * the warning tint, the same signal Android's `ContentListMapper.tagTint` gives it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopDetailsInfo(
    content: SourceContent,
    enabled: Boolean,
    warnings: DesktopSourceSettings = DesktopSourceSettings(),
    modifier: Modifier = Modifier,
) {
    val link = remember(content) { desktopContentLink(content) }
    val status = remember(content.state) { desktopContentStatusLabel(content.state) }
    val rating = remember(content.rating) {
        content.rating.takeIf { it > 0f }?.let { String.format(Locale.getDefault(), "%.1f", it * 10f) }
    }
    val altTitles = remember(content.altTitles) { content.altTitles.filter(String::isNotBlank) }
    val tags = remember(content.tags) { content.tags.map { it.title }.filter(String::isNotBlank).distinct().sorted() }
    var copied by remember(link) { mutableStateOf(false) }
    Surface(
        modifier.fillMaxWidth().testTag("details-info"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(desktopText("details.info"), style = MaterialTheme.typography.titleMedium)
            status?.let { InfoRow(desktopText("details.statusLabel"), it, "details-status") }
            rating?.let { InfoRow(desktopText("details.ratingLabel"), it, "details-rating") }
            if (altTitles.isNotEmpty()) {
                InfoRow(desktopText("details.altTitles"), altTitles.joinToString(" · "), "details-alt-titles")
            }
            if (tags.isNotEmpty()) {
                Text(desktopText("details.tags"), color = Muted, fontSize = 12.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { tag ->
                        val warned = warnings.isTagWarning(tag)
                        val tone = if (warned) Warn else Accent
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = tone.copy(alpha = .12f),
                            modifier = Modifier
                                .testTag("details-tag:$tag")
                                .semantics { if (warned) this[DesktopWarnedTagKey] = true },
                        ) {
                            Text(
                                tag,
                                fontSize = 12.sp,
                                color = tone,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    // A clipboard the platform refuses is rare enough not to own a second label; the work is
                    // still what the button promised, so it confirms the attempt.
                    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null) }
                    copied = true
                },
                enabled = enabled && link.isNotBlank(),
                modifier = Modifier.heightIn(min = 40.dp).testTag("details-copy-link"),
            ) {
                Text(if (copied) desktopText("details.copied") else desktopText("details.copyLink"))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, tag: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(tag).semantics(mergeDescendants = true) {},
    ) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
