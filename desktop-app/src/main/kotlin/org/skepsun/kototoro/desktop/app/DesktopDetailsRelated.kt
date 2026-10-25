package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.source.SourceContent

/**
 * Android's details related-works list (`details_related_works`): the works the source points at, side by side, each
 * opening its own details page. The rail only appears when the source answered with something or the answer is still
 * coming, so a work without related works keeps the page as short as before.
 *
 * The switch that governs it is `related_manga` on the services page; `relatedLoading` is the background request the
 * controller starts when details opens.
 */
@Composable
internal fun DesktopDetailsRelated(
    controller: DesktopController,
    state: DesktopAppState,
    modifier: Modifier = Modifier,
) {
    val works = state.relatedWorks
    if (works.isEmpty() && !state.relatedLoading) return
    val enabled = !state.busy
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth().testTag("details-related")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AndroidStrings["details_related_works"], style = MaterialTheme.typography.titleMedium)
                if (state.relatedLoading) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp).testTag("details-related-loading"),
                        strokeWidth = 2.dp,
                    )
                }
            }
            val railModifier = Modifier.testTag("details-related-rail")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = railModifier) {
                items(works, key = { work -> "${work.source.name}:${work.id}" }) { work ->
                    val sourceLabel = state.sources.firstOrNull { it.source.name == work.source.name }?.displayName
                        ?: work.source.name
                    Column(Modifier.width(96.dp).clickable(enabled) { controller.details(work) }
                        .testTag("details-related:${work.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DesktopCover(work, controller.session.covers,
                            Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
                                .background(Accent.copy(alpha = .06f)))
                        Text(work.title, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(sourceLabel, color = Accent, fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
