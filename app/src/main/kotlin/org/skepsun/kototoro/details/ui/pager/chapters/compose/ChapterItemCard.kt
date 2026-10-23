package org.skepsun.kototoro.details.ui.pager.chapters.compose

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvHorizontalEdgeGuard
import org.skepsun.kototoro.core.ui.chapters.ChapterCardData
import org.skepsun.kototoro.core.ui.chapters.ChapterGridCard as SharedChapterGridCard
import org.skepsun.kototoro.core.ui.chapters.ChapterListCard as SharedChapterListCard
import org.skepsun.kototoro.details.ui.model.ChapterListItem

/** Android resource and TV-focus adapters for the shared chapter cards. */
@Composable
fun ChapterListCard(
    item: ChapterListItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    /** Shows a download button at the end of the row; null hides it. */
    onDownload: (() -> Unit)? = null,
    /** Shows a translate button at the end of the row; null hides it. */
    onTranslate: (() -> Unit)? = null,
) = SharedChapterListCard(
    item = item.cardData(),
    isSelected = isSelected,
    onClick = onClick,
    onLongClick = onLongClick,
    focusRequester = focusRequester,
    modifier = modifier,
    // The row's click target stops before the trailing buttons, so on a television RIGHT steps from
    // the title onto the download / translate buttons instead of leaving the panel.
    focusModifier = Modifier.tvFocusable(shape = RoundedCornerShape(8.dp), borderWidth = 2.dp, addFocusTarget = false),
    actions = if (onDownload != null || onTranslate != null) {
        {
            // Whatever the row ends with has nothing to its right. Without the guard RIGHT scrolls
            // the tab pager onto the next tab instead of simply stopping at the last button.
            val translateEndsTheRow = onDownload == null
            onTranslate?.let { translate ->
                IconButton(
                    onClick = translate,
                    modifier = Modifier.size(40.dp)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false)
                        .then(if (translateEndsTheRow) Modifier.tvHorizontalEdgeGuard(blockRight = true) else Modifier),
                ) {
                    val progress = item.translationProgress
                    if (progress != null && !item.isTranslated) {
                        // A translation is in flight (or a chapter was left half translated): show how far it got.
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_translate),
                            contentDescription = stringResource(R.string.translate_chapter),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            onDownload?.let { download ->
                IconButton(
                    onClick = download,
                    modifier = Modifier.size(40.dp)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false)
                        .tvHorizontalEdgeGuard(blockRight = true),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = stringResource(R.string.download),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    } else {
        null
    },
)

@Composable
fun ChapterGridCard(
    item: ChapterListItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) = SharedChapterGridCard(
    item = item.cardData(),
    isSelected = isSelected,
    onClick = onClick,
    onLongClick = onLongClick,
    focusRequester = focusRequester,
    modifier = modifier,
    focusModifier = Modifier.tvFocusable(shape = RoundedCornerShape(8.dp), borderWidth = 3.dp, addFocusTarget = false),
)

@Composable
private fun ChapterListItem.cardData() = ChapterCardData(
    title = getTitle(LocalContext.current.resources),
    description = description,
    isCurrent = isCurrent,
    isUnread = isUnread,
    isBookmarked = isBookmarked,
    isDownloaded = isDownloaded,
    isNew = isNew,
    isTranslated = isTranslated,
)
