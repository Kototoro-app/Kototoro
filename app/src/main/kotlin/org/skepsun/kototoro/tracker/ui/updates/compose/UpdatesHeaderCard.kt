package org.skepsun.kototoro.tracker.ui.updates.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.page.PageSummaryAction
import org.skepsun.kototoro.core.ui.page.PageSummaryHeader

@Composable
fun UpdatesHeaderCard(
    totalWorks: Int,
    totalNewChapters: Int,
    onMarkAllRead: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasUpdates = totalWorks > 0
    PageSummaryHeader(
        icon = painterResource(R.drawable.ic_updated),
        title = if (hasUpdates) {
            stringResource(R.string.updated_header_title)
        } else {
            stringResource(R.string.updated_header_up_to_date)
        },
        subtitle = if (hasUpdates) {
            stringResource(R.string.updated_header_summary, totalWorks, totalNewChapters)
        } else {
            stringResource(R.string.updated_header_up_to_date_subtitle)
        },
        modifier = modifier,
    ) {
        if (hasUpdates) {
            PageSummaryAction(
                text = null,
                icon = painterResource(R.drawable.ic_sync),
                contentDescription = stringResource(R.string.check_for_updates),
                onClick = onRefresh,
            )
            PageSummaryAction(
                text = stringResource(R.string.updated_header_all_read),
                onClick = onMarkAllRead,
                emphasized = true,
            )
        } else {
            PageSummaryAction(
                text = stringResource(R.string.check_for_updates),
                icon = painterResource(R.drawable.ic_sync),
                onClick = onRefresh,
            )
        }
    }
}
