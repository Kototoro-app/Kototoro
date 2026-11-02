package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import org.skepsun.kototoro.core.ui.compose.DetailsTagChip
import org.skepsun.kototoro.core.ui.compose.DetailsTagChips
import org.skepsun.kototoro.core.ui.compose.DetailsMetadataEntry
import org.skepsun.kototoro.core.ui.compose.DetailsMetadataGrid
import androidx.compose.ui.res.painterResource
import org.skepsun.kototoro.desktop.runtime.DesktopReadingTime
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
 * Android's `HistoryInfo.estimatedTime` as text. Android computes the estimate but never puts it on screen; Windows
 * shows it in the details facts instead, because otherwise the 阅读时间估算 switch would gate nothing at all.
 */
internal fun desktopReadingTimeText(value: DesktopReadingTime): String = when {
    value.hours > 0 -> desktopText("details.readingTimeHoursMinutes", value.hours, value.minutes)
    else -> desktopText("details.readingTimeMinutes", value.minutes)
}

/**
 * Android's details facts: state, rating, alternative titles, tags and a copy button. Rows the source did not
 * answer are left out instead of showing blanks. A tag the shared warn-list calls extreme keeps the chip but takes
 * the warning tint, the same signal Android's `ContentListMapper.tagTint` gives it.
 */
@Composable
internal fun DesktopDetailsInfo(
    content: SourceContent,
    enabled: Boolean,
    warnings: DesktopSourceSettings = DesktopSourceSettings(),
    readingTime: DesktopReadingTime? = null,
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
            // Android's details fact grid: icon + value in two columns, the label read out by the icon.
            val facts = buildList {
                status?.let {
                    add(fact(desktopText("details.statusLabel"), it, desktopContentStateIcon(content.state),
                        "details-status"))
                }
                rating?.let { add(fact(desktopText("details.ratingLabel"), it, "icons/ic_star_small.svg", "details-rating")) }
                // Android's reading-time estimate, hidden while its switch is off or the work is too short to estimate.
                readingTime?.takeUnless { it.isEmpty }?.let {
                    add(fact(desktopText("details.readingTime"), desktopReadingTimeText(it), "icons/ic_timer.svg",
                        "details-reading-time"))
                }
                if (altTitles.isNotEmpty()) {
                    add(fact(desktopText("details.altTitles"), altTitles.joinToString(" · "),
                        "icons/ic_info_outline.svg", "details-alt-titles"))
                }
            }
            if (facts.isNotEmpty()) DetailsMetadataGrid(facts)
            if (tags.isNotEmpty()) {
                Text(desktopText("details.tags"), color = Muted, fontSize = 12.sp)
                // Android's details tag chips; the warning decision is the shared warn-list behind 高亮敏感类别.
                DetailsTagChips(
                    tags = tags.map { tag -> DetailsTagChip(tag, tag, highlighted = warnings.isTagWarning(tag)) },
                    chipModifier = { chip ->
                        Modifier.testTag("details-tag:${chip.title}")
                            .semantics { if (chip.highlighted) this[DesktopWarnedTagKey] = true }
                    },
                )
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
private fun fact(label: String, value: String, icon: String, tag: String) = DetailsMetadataEntry(
    label, value, painterResource(icon),
    modifier = Modifier.testTag(tag).semantics(mergeDescendants = true) {},
)

/** Android's `ContentState.iconResId`; UPCOMING's clock comes from the Material library, so the timer stands in. */
private fun desktopContentStateIcon(state: String?): String = when (state) {
    "ONGOING" -> "icons/ic_play.svg"
    "FINISHED" -> "icons/ic_state_finished.svg"
    "ABANDONED" -> "icons/ic_state_abandoned.svg"
    "PAUSED" -> "icons/ic_action_pause.svg"
    "UPCOMING" -> "icons/ic_timer.svg"
    "RESTRICTED" -> "icons/ic_disable.svg"
    else -> "icons/ic_info_outline.svg"
}
