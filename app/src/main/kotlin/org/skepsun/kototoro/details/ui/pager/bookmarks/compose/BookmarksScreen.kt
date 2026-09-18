package org.skepsun.kototoro.details.ui.pager.bookmarks.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.bookmarks.domain.extractNovelBookmarkPreview
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneNestedScrollConnection
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.artworkAwareContainerColor
import org.skepsun.kototoro.list.ui.model.ListHeader
import java.io.File

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookmarkCard(
    bookmark: Bookmark,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isArtworkBackground = LocalBackgroundStyle.current.usesArtworkBackdrop
    val snapshotFile = remember(bookmark) {
        File(context.filesDir, "bookmarks/manga_${bookmark.manga.id}_chapter_${bookmark.chapterId}_page_${bookmark.page}.jpg")
    }
    val imageModel = remember(bookmark, snapshotFile) {
        when {
            snapshotFile.exists() && snapshotFile.length() > 0 -> snapshotFile.toUri().toString()
            bookmark.imageUrl.startsWith("file://") -> bookmark.imageUrl
            else -> bookmark.toContentPage()
        }
    }

    val contentType = bookmark.manga.source.getContentType()
    val isNovel = contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.artworkAwareContainerColor(),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isArtworkBackground) 0.dp else 1.dp),
        border = if (isSelected) BorderStroke(4.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isNovel) {
                val previewText = remember(bookmark.imageUrl) {
                    extractNovelBookmarkPreview(bookmark.imageUrl)
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                ) {
                    Text(
                        text = bookmark.chapterTitle.orEmpty().ifBlank {
                            stringResource(R.string.bookmark_position, bookmark.page + 1)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = previewText.ifBlank { stringResource(R.string.bookmark_preview_unavailable) },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                AsyncImage(
                    model = imageModel,
                    contentDescription = "Bookmark Thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (bookmark.percent > 0) {
                CircularProgressIndicator(
                    progress = { bookmark.percent },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Black.copy(alpha = 0.5f),
                )
            }

            if (!isNovel) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                ) {
                    Text(
                        text = "P.${bookmark.page + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            if (isSelected) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(48.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun BookmarksScreen(
    items: List<org.skepsun.kototoro.list.ui.model.ListModel>,
    gridMinSize: Dp,
    selectedItemIds: Set<Long>,
    detailsPaneState: DetailsPaneState? = null,
    onItemClick: (Bookmark) -> Unit,
    onItemLongClick: (Bookmark) -> Unit,
    onSelectionActionClick: (Int) -> Unit,
    onClearSelection: () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current

    val gridState = rememberLazyGridState()
    val paneNestedScrollConnection = rememberDetailsPaneNestedScrollConnection(
        state = detailsPaneState,
        canChildScrollBackward = { gridState.canScrollBackward },
    )
    val paneNestedScrollModifier = remember(paneNestedScrollConnection) {
        if (paneNestedScrollConnection != null) {
            Modifier.nestedScroll(paneNestedScrollConnection)
        } else {
            Modifier
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Text(
                text = "No bookmarks",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = gridMinSize),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .then(paneNestedScrollModifier),
            ) {
                items.forEach { item ->
                    when (item) {
                        is ListHeader -> {
                            item(
                                key = "header_${item.hashCode()}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                val context = LocalContext.current
                                Text(
                                    text = item.getText(context)?.toString().orEmpty(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                )
                            }
                        }
                        is Bookmark -> {
                            item(key = item.pageId) {
                                BookmarkCard(
                                    bookmark = item,
                                    isSelected = selectedItemIds.contains(item.pageId),
                                    onClick = {
                                        if (selectedItemIds.isNotEmpty()) {
                                            hapticFeedback.performSelectionHapticFeedback()
                                        }
                                        onItemClick(item)
                                    },
                                    onLongClick = { onItemLongClick(item) },
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = selectedItemIds.isNotEmpty(),
            enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(16.dp).windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClearSelection) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                        }
                        Text(
                            text = "${selectedItemIds.size}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Row {
                        IconButton(onClick = { onSelectionActionClick(R.id.action_delete) }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }
}
