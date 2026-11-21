package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.skepsun.kototoro.community.ui.CommunityChartSection
import org.skepsun.kototoro.community.ui.CommunityCommentsContent
import org.skepsun.kototoro.community.ui.CommunityRatingCard
import org.skepsun.kototoro.core.source.SourceContent
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Android's community card on the details page: the shared [CommunityRatingCard] in the page's own 20 dp panel, and
 * the comment list in a dialog where Android opens a bottom sheet. Draws nothing while the community is off.
 */
@Composable
internal fun DesktopCommunityRating(controller: DesktopController, content: SourceContent, modifier: Modifier = Modifier) {
    val community = controller.community
    val enabled = controller.state.collectAsState().value.services.communityEnabled
    LaunchedEffect(content.id, content.source.name, enabled) { community.bindDetails(content) }
    DisposableEffect(content.id) { onDispose { community.bindDetails(null) } }
    val state = community.rating.collectAsState().value ?: return
    CommunityRatingCard(
        state = state,
        onRate = community::rate,
        onWithdraw = community::withdraw,
        onOpenComments = community::openComments,
        onSearchTitle = controller::searchTitle,
        container = { card ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                card()
            }
        },
        modifier = modifier,
    )
    val comments = community.comments.collectAsState().value
    if (comments != null) {
        Dialog(onDismissRequest = community::closeComments, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                Modifier.widthIn(max = 640.dp).fillMaxWidth(0.9f).height(640.dp).testTag("community-comments-dialog"),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                CommunityCommentsContent(
                    state = comments,
                    formatTime = ::desktopRelativeTime,
                    onRefresh = community::reloadComments,
                    onReplyTo = community::replyTo,
                    onPost = community::post,
                    onDelete = community::delete,
                    onReport = community::report,
                    onBlock = community::block,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}

/** The suggestions page's community chart; loads when shown, empty while the community is off. */
@Composable
internal fun DesktopCommunityChart(controller: DesktopController, modifier: Modifier = Modifier) {
    val community = controller.community
    val enabled = controller.state.collectAsState().value.services.communityEnabled
    LaunchedEffect(enabled) { community.loadChart() }
    CommunityChartSection(
        state = community.chart.collectAsState().value,
        onSelectWindow = community::loadChart,
        onSearchTitle = controller::searchTitle,
        modifier = modifier,
    )
}

/** Android's `DateUtils.getRelativeTimeSpanString` for a comment's time: minutes, hours and days ago, then the date. */
internal fun desktopRelativeTime(seconds: Long, now: Instant = Instant.now()): String {
    val then = Instant.ofEpochSecond(seconds)
    val elapsed = Duration.between(then, now)
    return when {
        elapsed.isNegative || elapsed.toMinutes() < 1 -> desktopText("feed.timeNow")
        elapsed.toHours() < 1 -> desktopText("feed.timeMinutesAgo", elapsed.toMinutes())
        elapsed.toDays() < 1 -> desktopText("feed.timeHoursAgo", elapsed.toHours())
        elapsed.toDays() < 7 -> desktopText("feed.timeDaysAgo", elapsed.toDays())
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(DesktopStrings.locale)
            .format(then.atZone(ZoneId.systemDefault()))
    }
}
