package org.skepsun.kototoro.bookmarks.ui.compose

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.bookmarks.domain.extractNovelBookmarkPreview
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.ui.compose.DetailsBookmarkCard
import org.skepsun.kototoro.core.ui.compose.DetailsBookmarkText
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.artworkAwareContainerColor
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.File

/**
 * One saved position as a card, for both Android surfaces that list bookmarks: the details tab and the top-level
 * bookmarks page. The drawing is the shared [DetailsBookmarkCard] (the Windows details pane draws the same component);
 * this wrapper only knows what Android adds to it — the saved page snapshot on disk, Coil for the thumbnail, and the
 * novel excerpt Android stores in `imageUrl`.
 *
 * [pageLabel] is the corner label the image branch draws. The details tab shows the concrete page ("P.7"); the
 * top-level page shows the chapter the position belongs to, which is what it loses otherwise, because its sections are
 * only grouped per work.
 */
@Composable
fun BookmarkCard(
    bookmark: Bookmark,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    pageLabel: String = "P.${bookmark.page + 1}",
) {
    val context = LocalContext.current
    val isArtworkBackground = LocalBackgroundStyle.current.usesArtworkBackdrop
    val snapshotFile = remember(bookmark) { bookmarkSnapshotFile(context, bookmark) }
    val imageModel = remember(bookmark, snapshotFile) {
        when {
            snapshotFile.exists() && snapshotFile.length() > 0 -> snapshotFile.toUri().toString()
            bookmark.imageUrl.startsWith("file://") -> bookmark.imageUrl
            else -> bookmark.toContentPage()
        }
    }

    val contentType = bookmark.manga.source.getContentType()
    val isNovel = contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL
    val previewText = remember(bookmark.imageUrl, isNovel) {
        if (isNovel) extractNovelBookmarkPreview(bookmark.imageUrl) else ""
    }
    DetailsBookmarkCard(
        selected = isSelected,
        percent = bookmark.percent,
        onClick = onClick,
        onLongClick = onLongClick,
        selectedIcon = rememberVectorPainter(Icons.Default.CheckCircle),
        modifier = modifier,
        pageLabel = pageLabel,
        text = if (isNovel) {
            DetailsBookmarkText(
                title = bookmark.chapterTitle.orEmpty().ifBlank {
                    stringResource(R.string.bookmark_position, bookmark.page + 1)
                },
                excerpt = previewText.ifBlank { stringResource(R.string.bookmark_preview_unavailable) },
            )
        } else {
            null
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant.artworkAwareContainerColor(),
        elevation = if (isArtworkBackground) 0.dp else 1.dp,
        image = {
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

/** Android keeps a page snapshot per saved position; the card prefers it over the remote page image. */
private fun bookmarkSnapshotFile(context: Context, bookmark: Bookmark): File =
    File(context.filesDir, "bookmarks/manga_${bookmark.manga.id}_chapter_${bookmark.chapterId}_page_${bookmark.page}.jpg")
