package org.skepsun.kototoro.suggestions.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.page.PageSummaryAction
import org.skepsun.kototoro.core.ui.page.PageSummaryHeader

@Composable
fun SuggestionsHeaderCard(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    PageSummaryHeader(
        icon = painterResource(R.drawable.ic_explore_normal),
        title = stringResource(R.string.suggestions_header_title),
        subtitle = stringResource(R.string.suggestions_header_subtitle),
        accent = accent,
        modifier = modifier,
    ) {
        PageSummaryAction(
            text = stringResource(R.string.suggestions_header_refresh),
            icon = painterResource(R.drawable.ic_sync),
            onClick = onRefresh,
            accent = accent,
        )
    }
}
