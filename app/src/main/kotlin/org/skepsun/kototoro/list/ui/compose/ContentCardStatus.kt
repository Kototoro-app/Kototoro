package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.list.domain.ReadingProgress
import kotlin.math.roundToInt

/** What the reading-progress label of a list row says; see [contentCardProgressLabel]. */
@Immutable
sealed interface ContentCardProgressLabel {
    data object Completed : ContentCardProgressLabel
    data class PercentRead(val percent: Int) : ContentCardProgressLabel
    data class PercentLeft(val percent: Int) : ContentCardProgressLabel
    data class ChaptersRead(val chapters: Int) : ContentCardProgressLabel
    data class ChaptersLeft(val chapters: Int) : ContentCardProgressLabel
}

/**
 * Progress label in the user's progress indicator mode, the same mode the cover badge
 * follows. Nothing for unread works or when the mode cannot be honoured.
 */
fun contentCardProgressLabel(progress: ReadingProgress?): ContentCardProgressLabel? {
    if (progress == null || !progress.isValid() || progress.percent <= 0f) return null
    if (progress.isCompleted()) return ContentCardProgressLabel.Completed
    return when (progress.mode) {
        ProgressIndicatorMode.NONE -> null
        ProgressIndicatorMode.PERCENT_READ -> ContentCardProgressLabel.PercentRead((progress.percent * 100f).roundToInt())
        ProgressIndicatorMode.PERCENT_LEFT -> ContentCardProgressLabel.PercentLeft((progress.percentLeft * 100f).roundToInt())
        ProgressIndicatorMode.CHAPTERS_READ -> ContentCardProgressLabel.ChaptersRead(progress.chapters)
        ProgressIndicatorMode.CHAPTERS_LEFT -> ContentCardProgressLabel.ChaptersLeft(progress.chaptersLeft)
    }
}

/**
 * Status line of list / detailed rows: a tonal "N new chapters" pill and the reading
 * progress in words. The cover badges carry the same facts in shorthand; the row has the
 * room to spell them out. Draws nothing when there is neither.
 */
@Composable
fun ContentCardStatusRow(
    counter: Int,
    progress: ReadingProgress?,
    modifier: Modifier = Modifier,
) {
    val progressLabel = remember(progress) { contentCardProgressLabel(progress) }
    if (counter <= 0 && progressLabel == null) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (counter > 0) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    text = pluralStringResource(R.plurals.new_chapters, counter, counter),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (progressLabel != null) {
            Text(
                text = when (progressLabel) {
                    ContentCardProgressLabel.Completed -> stringResource(R.string.card_progress_completed)
                    is ContentCardProgressLabel.PercentRead ->
                        stringResource(R.string.card_progress_percent_read, progressLabel.percent)
                    is ContentCardProgressLabel.PercentLeft ->
                        stringResource(R.string.card_progress_percent_left, progressLabel.percent)
                    is ContentCardProgressLabel.ChaptersRead ->
                        stringResource(R.string.card_progress_chapters_read, progressLabel.chapters)
                    is ContentCardProgressLabel.ChaptersLeft ->
                        stringResource(R.string.card_progress_chapters_left, progressLabel.chapters)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
