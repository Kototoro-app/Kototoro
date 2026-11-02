package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.compose.compactPosterRailCardStyle
import org.skepsun.kototoro.list.ui.compose.ContentCardPresentation
import org.skepsun.kototoro.list.ui.compose.ContentPosterCard
import org.skepsun.kototoro.list.ui.compose.ContentRailSection
import org.skepsun.kototoro.list.ui.compose.rememberCoverRimBorderBrush

/**
 * Android's details related-works list (`details_related_works`): the works the source points at, side by side, each
 * opening its own details page. The rail only appears when the source answered with something or the answer is still
 * coming, so a work without related works keeps the page as short as before.
 *
 * The section and its cards are Android's own (`ContentRailSection` with the rail poster style); the desktop keeps the
 * page's card surface around it and a small spinner beside the title while the request runs.
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
    val style = compactPosterRailCardStyle(gridScale = 1f)
    val rimBorderBrush = rememberCoverRimBorderBrush(
        LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS, isDark = LocalDesktopDark.current,
    )
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        modifier = modifier.testTag("details-related")) {
        ContentRailSection(
            title = AndroidStrings["details_related_works"],
            count = works.size,
            key = { index -> "${works[index].source.name}:${works[index].id}" },
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalPadding = 16.dp,
            titleTrailing = if (state.relatedLoading) ({
                CircularProgressIndicator(Modifier.size(14.dp).testTag("details-related-loading"), strokeWidth = 2.dp)
            }) else null,
            railModifier = Modifier.testTag("details-related-rail"),
        ) { index ->
            val work = works[index]
            ContentPosterCard(ContentCardPresentation(work.id, work.title), style, rimBorderBrush,
                onClick = { controller.details(work) }, onLongClick = {}, enabled = enabled, compactOverlay = true,
                cellContentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(style.itemWidth + 8.dp).testTag("details-related:${work.id}"),
                cover = { DesktopCover(work, controller.session.covers, Modifier.matchParentSize()) })
        }
    }
}
