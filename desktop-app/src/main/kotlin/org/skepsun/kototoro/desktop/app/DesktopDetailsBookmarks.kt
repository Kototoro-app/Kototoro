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
import androidx.compose.material3.*
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
import org.skepsun.kototoro.desktop.runtime.isChapterBookmark
import org.skepsun.kototoro.core.ui.compose.DetailsBookmarkCard
import org.skepsun.kototoro.core.ui.compose.DetailsBookmarkText
import androidx.compose.ui.res.painterResource

/** Cards stay small enough that a page decode never competes with the reader's own page budget. */
private const val THUMBNAIL_PIXELS = 400_000L
private const val THUMBNAIL_DIMENSION = 1024

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
    // Android enters multi-select by long-pressing a card; the header switch does the same with a mouse.
    var selecting by remember(content.id) { mutableStateOf(false) }
    var selection by remember(content.id) { mutableStateOf(emptySet<Long>()) }
    // A stable key: mapping the list on every recomposition would restart the effect and never settle.
    val bookmarkIds = remember(bookmarks) { bookmarks.mapTo(LinkedHashSet()) { it.pageId } }
    LaunchedEffect(bookmarkIds) {
        if (selection.isNotEmpty()) selection = selection.intersect(bookmarkIds)
        if (bookmarkIds.isEmpty()) {
            selecting = false
            selection = emptySet()
        }
    }
    val toggle: (Long) -> Unit = { pageId ->
        selection = if (pageId in selection) selection - pageId else selection + pageId
    }
    Column(modifier.fillMaxSize().testTag("details-bookmarks"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selecting) desktopText("details.bookmarksSelectedCount", selection.size)
            else desktopText("reader.bookmarksCount", bookmarks.size),
                modifier = Modifier.weight(1f).testTag("details-bookmarks-count"),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Muted)
            TextButton({
                selecting = !selecting
                if (!selecting) selection = emptySet()
            }, enabled = enabled && bookmarks.isNotEmpty(),
                modifier = Modifier.testTag("details-bookmarks-select")) {
                if (selecting) {
                    Text(desktopText("details.cancelSelection"))
                } else {
                    Text(desktopText("details.selectBookmarks"))
                }
            }
            TextButton(controller::clearBookmarks, enabled = enabled && !selecting && bookmarks.isNotEmpty(),
                modifier = Modifier.testTag("details-bookmarks-clear")) {
                Text(desktopText("details.bookmarksClear"))
            }
        }
        if (selecting) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ selection = bookmarks.mapTo(HashSet()) { it.pageId } },
                    enabled = enabled && selection.size < bookmarks.size,
                    modifier = Modifier.testTag("details-bookmarks-select-all")) {
                    Text(desktopText("details.selectAll"))
                }
                TextButton({ selection = emptySet() }, enabled = enabled && selection.isNotEmpty(),
                    modifier = Modifier.testTag("details-bookmarks-select-none")) {
                    Text(desktopText("downloads.selectNone"))
                }
                OutlinedButton({
                    controller.removeBookmarks(selection)
                    selecting = false
                    selection = emptySet()
                }, enabled = enabled && selection.isNotEmpty(),
                    modifier = Modifier.testTag("details-bookmarks-select-delete")) {
                    Text(desktopText("details.bookmarksDeleteSelected", selection.size))
                }
            }
        }
        if (bookmarks.isEmpty()) {
            Text(desktopText("reader.bookmarksEmpty"), color = Muted, fontSize = 13.sp)
            return@Column
        }
        // Android's bookmarks tab: one grid of 0.7 cards for page images and novel excerpts alike; a click opens the
        // position, a long press starts selecting, and while selecting a click toggles the card.
        val previews = remember(state.bookmarks) {
            state.bookmarks.associate { it.pageId to parseNovelBookmarkPreview(it.preview) }
        }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize().testTag("details-bookmarks-grid"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            items(bookmarks, key = { it.pageId }) { bookmark ->
                val chapter = content.chapters?.firstOrNull { it.id == bookmark.chapterId }
                val chapterTitle = chapter?.title ?: AndroidStrings.format("bookmark_position", bookmark.page + 1)
                DetailsBookmarkCard(
                    selected = bookmark.pageId in selection,
                    percent = bookmark.percent,
                    onClick = {
                        if (selecting) toggle(bookmark.pageId)
                        else if (chapter != null) controller.openDetailsBookmark(bookmark)
                    },
                    onLongClick = {
                        selecting = true
                        toggle(bookmark.pageId)
                    },
                    selectedIcon = painterResource("icons/ic_check.svg"),
                    enabled = enabled,
                    modifier = Modifier.testTag("details-bookmark:${bookmark.pageId}"),
                    pageLabel = if (bookmark.isChapterBookmark) null else "P.${bookmark.page + 1}",
                    text = if (pageReader) null else DetailsBookmarkText(chapterTitle,
                        previews[bookmark.pageId].orEmpty().ifBlank {
                            if (bookmark.isChapterBookmark) desktopBookmarkLabel(bookmark)
                            else AndroidStrings["bookmark_preview_unavailable"]
                        }),
                    image = { BookmarkThumbnail(controller, content, chapter, bookmark, Modifier.fillMaxSize()) },
                )
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
