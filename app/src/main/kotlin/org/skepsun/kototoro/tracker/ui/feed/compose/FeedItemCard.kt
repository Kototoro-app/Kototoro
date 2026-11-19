package org.skepsun.kototoro.tracker.ui.feed.compose

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.rememberDeferredContentCoverBounds
import org.skepsun.kototoro.core.ui.feed.FeedContinueAction
import org.skepsun.kototoro.core.ui.feed.FeedCoverWidth
import org.skepsun.kototoro.core.ui.feed.FeedTimelineCard
import org.skepsun.kototoro.core.ui.feed.FeedChapterList
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgePill
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgeText
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgeTone
import org.skepsun.kototoro.core.ui.feed.FeedChapterToggle
import org.skepsun.kototoro.core.ui.feed.FeedChapterLine
import org.skepsun.kototoro.tracker.domain.UpdateChapter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.list.ui.compose.ContentCardNsfwBadge
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem

/** Android's feed card: the shared timeline card ([FeedTimelineCard]) with Coil covers and shared transitions. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FeedItemCard(
    item: FeedItem,
    onClick: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    timelineLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onContinueReading: (() -> Unit)? = null,
    /** Opens the reader at a chapter picked from the unfolded list; null in selection mode. */
    onReadChapter: ((chapterId: Long) -> Unit)? = null,
    onDetailsViewed: () -> Unit = {},
) {
    val coverBounds = rememberDeferredContentCoverBounds()
    val badgeMetrics = remember { contentCardBadgeMetricsFor(FeedCoverWidth) }
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val sharedElementKey = remember(item.id, item.imageUrl, item.manga.source.name) {
        contentCoverSharedKey(item.manga.source.name, item.imageUrl.orEmpty(), instanceKey = "feed_${item.id}")
    }
    val context = LocalContext.current
    val allowCrossfade = sharedTransitionScope == null || animatedVisibilityScope == null
    val imageRequest = remember(context, item.manga.source.name, item.manga.url, item.manga.publicUrl, item.imageUrl, allowCrossfade) {
        val cacheKey = contentCoverCacheKey(item.manga, item.imageUrl)
        ImageRequest.Builder(context)
            .data(item.imageUrl)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .mangaExtra(item.manga)
            .crossfade(allowCrossfade)
            .build()
    }
    val onImageSuccess = remember(sharedElementKey) {
        { state: coil3.compose.AsyncImagePainter.State.Success ->
            HeroCoverSnapshotStore.put(sharedElementKey, state.result.image)
        }
    }
    // Chapters in reading order: the entry shows the first unread one, i.e. the chapter right after what was read.
    val ordered = remember(item.updateChapters) {
        item.updateChapters.sortedBy { it.publishedAt.takeIf { time -> time > 0 } ?: Long.MAX_VALUE }
    }
    val pending = remember(ordered) { ordered.filter { it.readAt == 0L } }
    val primary = if (item.pendingCount < 0) null else pending.firstOrNull() ?: ordered.lastOrNull()
    val legacyText = run {
        val batch = if (item.count < 0) {
            stringResource(R.string.update_chapters_available)
        } else if (item.count > 0) {
            pluralStringResource(id = R.plurals.new_chapters, count = item.count, item.count)
        } else {
            pluralStringResource(id = R.plurals.old_chapters_in_total, count = item.totalChapters, item.totalChapters)
        }
        batch + when {
            item.pendingCount < 0 -> ""
            item.pendingCount == 0 -> " · " + stringResource(R.string.update_read_completed)
            else -> " · " + stringResource(R.string.update_pending_chapters, item.pendingCount)
        }
    }
    // With known chapters the chapter range sits on the summary line; only legacy entries keep a count line.
    val chapterText = if (primary != null) "" else legacyText
    // The pending count rides on the cover; entries without chapter identities fall back to the discovered count.
    val badgeCount = if (primary != null) pending.size else item.count.coerceAtLeast(0)
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val continueLabel = stringResource(R.string.continue_reading)
    val continueIcon = painterResource(id = R.drawable.ic_read)
    var expanded by remember(item.key) { mutableStateOf(false) }
    val presentation = if (primary == null) null else chapterPresentation(ordered, pending, primary, onReadChapter)
    FeedTimelineCard(
        supportingContent = presentation?.let { shown ->
            {
                FeedChapterToggle(
                    summary = shown.summary,
                    expandable = shown.lines.isNotEmpty(),
                    expanded = expanded,
                    onToggle = {
                        expanded = !expanded
                        if (expanded) onDetailsViewed()
                    },
                    expandLabel = stringResource(R.string.update_chapter_details),
                    isNew = item.isNew,
                )
            }
        },
        belowContent = presentation?.takeIf { it.lines.isNotEmpty() }?.let { shown ->
            { FeedChapterList(shown.lines, expanded, continueIcon, stringResource(R.string.read)) }
        },
        title = item.title,
        chapterText = chapterText,
        isNew = item.isNew,
        isRead = !item.isNew && item.pendingCount == 0,
        onClick = { onClick(coverBounds.currentBounds()) },
        modifier = modifier,
        screenPadding = AppLayoutTokens.screenHorizontalPadding,
        coverModifier = Modifier
            .onGloballyPositioned { coordinates -> coverBounds.updateCoordinates(coordinates) }
            .then(
                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            rememberSharedContentState(key = sharedElementKey),
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    }
                } else Modifier,
            ),
        isSelected = isSelected,
        selectedIcon = painterResource(id = R.drawable.ic_check),
        timelineLabel = timelineLabel,
        onLongClick = onLongClick,
        continueAction = onContinueReading?.let {
            FeedContinueAction(continueIcon, continueLabel, it, hint = stringResource(R.string._continue))
        },
        cover = {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onSuccess = onImageSuccess,
            )
            if (badgeCount > 0) {
                ContentCardBadgePill(
                    tone = ContentCardBadgeTone.COUNTER,
                    isIosStyle = isIosStyle,
                    metrics = badgeMetrics,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(badgeMetrics.outerPadding * 0.6f),
                ) { colors ->
                    ContentCardBadgeText(badgeCount.toString(), colors.content, badgeMetrics)
                }
            }
            if (item.manga.isNsfw()) {
                ContentCardNsfwBadge(
                    metrics = badgeMetrics,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(badgeMetrics.outerPadding * 0.6f),
                )
            }
        },
    )
}

/** What the third line says and the chapters it unfolds into. */
private class ChapterPresentation(val summary: String, val lines: List<FeedChapterLine>)

@Composable
private fun chapterPresentation(
    ordered: List<UpdateChapter>,
    pending: List<UpdateChapter>,
    primary: UpdateChapter,
    onReadChapter: ((chapterId: Long) -> Unit)?,
): ChapterPresentation? {
    val locale = LocalContext.current.resources.configuration.locales[0]
    val zone = ZoneId.systemDefault()
    val shortDate = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale) }
    // Sources publish dates, not times of day, so the unfolded list shows no clock time.
    val longDate = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    fun format(value: Long, formatter: DateTimeFormatter) =
        Instant.ofEpochMilli(value).atZone(zone).format(formatter)
    // The chapters themselves are the summary: the unread range, or the one chapter with its date when known.
    val summary = when {
        pending.size > 1 -> "${pending.first().title} – ${pending.last().title}"
        pending.size == 1 -> pending.single().title +
            (pending.single().publishedAt.takeIf { it > 0 }?.let { " · " + format(it, shortDate) }.orEmpty())
        else -> stringResource(R.string.update_read_completed) + " · " + primary.title
    }
    // The unfolded list is every chapter of the entry, the one shown above included, so each can be opened.
    val lines = if (ordered.size > 1) {
        ordered.map { chapter ->
            FeedChapterLine(
                title = chapter.title,
                date = chapter.publishedAt.takeIf { it > 0 }?.let { format(it, longDate) },
                isRead = chapter.readAt > 0,
                onClick = onReadChapter?.let { open -> { open(chapter.chapterId) } },
            )
        }
    } else {
        emptyList()
    }
    return ChapterPresentation(summary, lines)
}
