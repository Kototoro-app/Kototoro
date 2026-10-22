package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.skepsun.kototoro.bookmarks.domain.parseNovelBookmarkPreview
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.desktop.runtime.DesktopBookmark

/** Cards stay small enough that a page decode never competes with the reader's own page budget. */
private const val THUMBNAIL_PIXELS = 400_000L
private const val THUMBNAIL_DIMENSION = 1024

/** Android stores a chapter bookmark under the chapter id, without a page of its own. */
private fun DesktopBookmark.isChapterOnly() = pageId == chapterId

@Composable
private fun bookmarkLabel(bookmark: DesktopBookmark): String = if (bookmark.isChapterOnly()) {
    desktopText("details.chapterBookmarkLabel")
} else {
    desktopText("common.pageOf", bookmark.page + 1, (bookmark.percent * 100).toInt())
}

/**
 * Android shows the saved positions of a work as page thumbnails in a details tab. The same list here
 * opens from the details panes, so a bookmark can be revisited without entering the reader first.
 */
@Composable
internal fun DesktopDetailsBookmarks(controller: DesktopController, state: DesktopAppState,
    modifier: Modifier = Modifier) {
    val content = state.content ?: return
    val bookmarks = remember(state.bookmarks, content.id) { state.bookmarks.filter { it.contentId == content.id } }
    val enabled = !state.busy
    val pageReader = DesktopReaderKind.of(content) == DesktopReaderKind.PAGES
    Column(modifier.fillMaxSize().testTag("details-bookmarks"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(desktopText("reader.bookmarksCount", bookmarks.size), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.subtitle1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Muted)
            TextButton(controller::clearBookmarks, enabled = enabled && bookmarks.isNotEmpty(),
                modifier = Modifier.testTag("details-bookmarks-clear")) {
                Text(desktopText("details.bookmarksClear"))
            }
        }
        if (bookmarks.isEmpty()) {
            Text(desktopText("reader.bookmarksEmpty"), color = Muted, fontSize = 13.sp)
            return@Column
        }
        if (!pageReader) {
            // A novel position has text, not a page image: Android's card shows its excerpt instead.
            val previews = remember(state.bookmarks) {
                state.bookmarks.associate { it.pageId to parseNovelBookmarkPreview(it.preview) }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("details-bookmarks-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bookmarks, key = { it.pageId }) { bookmark ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colors.surface,
                        modifier = Modifier.testTag("details-bookmark:${bookmark.pageId}")) {
                        Column(Modifier.fillMaxWidth().clickable(enabled = enabled) {
                            controller.openDetailsBookmark(bookmark)
                        }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(content.chapters?.firstOrNull { it.id == bookmark.chapterId }?.title
                                ?: desktopText("reader.chapterMissing"), maxLines = 2,
                                overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            if (!bookmark.isChapterOnly()) {
                                Text(previews[bookmark.pageId].orEmpty(), color = Muted, fontSize = 12.sp,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(bookmarkLabel(bookmark), color = Accent, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = { controller.removeBookmark(bookmark) }, enabled = enabled,
                                    modifier = Modifier.testTag("bookmark-delete:${bookmark.pageId}")) {
                                    Text(desktopText("details.bookmarkDelete"), fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize().testTag("details-bookmarks-grid"),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            items(bookmarks, key = { it.pageId }) { bookmark ->
                BookmarkCard(controller, content, bookmark, enabled)
            }
        }
    }
}

@Composable
private fun BookmarkCard(controller: DesktopController, content: SourceContent, bookmark: DesktopBookmark,
    enabled: Boolean) {
    val chapter = content.chapters?.firstOrNull { it.id == bookmark.chapterId }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colors.surface,
        modifier = Modifier.testTag("details-bookmark:${bookmark.pageId}")) {
        Column(Modifier.fillMaxWidth().clickable(enabled = enabled && chapter != null) {
            controller.openDetailsBookmark(bookmark)
        }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp))
                .background(Accent.copy(alpha = .06f)).testTag("bookmark-thumb:${bookmark.pageId}")) {
                BookmarkThumbnail(controller, content, chapter, bookmark, Modifier.fillMaxSize())
            }
            Text(chapter?.title ?: desktopText("reader.chapterMissing"), maxLines = 2,
                overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bookmarkLabel(bookmark), color = Accent, fontSize = 12.sp,
                    modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { controller.removeBookmark(bookmark) }, enabled = enabled,
                    modifier = Modifier.testTag("bookmark-delete:${bookmark.pageId}")) {
                    Text(desktopText("details.bookmarkDelete"), fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

/** The saved page image, fetched through the reader's own image pipeline so the store is shared. */
@Composable
private fun BookmarkThumbnail(controller: DesktopController, content: SourceContent, chapter: SourceChapter?,
    bookmark: DesktopBookmark, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, content.id, chapter?.id, bookmark.pageId, bookmark.page) {
        value = null
        if (chapter == null) return@produceState
        var unpublished: Bitmap? = null
        try {
            val page = try {
                // The source answers on its own worker; a details card must never block composition.
                withContext(DesktopDispatchers.io) {
                    controller.session.bookmarkImage(content.id, chapter, bookmark.page)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@produceState
            val decoded = withContext(DesktopDispatchers.decode) {
                DesktopImageDecoder.decode(page.path, THUMBNAIL_PIXELS, THUMBNAIL_DIMENSION)
                    .also { unpublished = it }
            }
            value = decoded.asComposeImageBitmap()
            unpublished = null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A source that no longer serves the page keeps the empty plate: the card still opens the position.
        } finally {
            unpublished?.close()
        }
    }
    image?.let {
        Image(it, null, contentScale = ContentScale.Crop,
            modifier = modifier.testTag("bookmark-thumb-image:${bookmark.pageId}"))
    }
}
