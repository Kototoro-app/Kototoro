package org.skepsun.kototoro.reader.novel.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.reader.novel.NovelReaderThemePreset
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingColor
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class NovelMarkingsTab(@StringRes val labelRes: Int) {
    ALL(R.string.novel_marking_tab_all),
    HIGHLIGHTS(R.string.novel_marking_tab_highlights),
    ANNOTATIONS(R.string.novel_marking_tab_annotations),
    BOOKMARKS(R.string.novel_marking_tab_bookmarks),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeNovelMarkingsSheet(
    bookmarks: List<Bookmark> = emptyList(),
    markings: List<NovelMarkingEntity>,
    chapters: List<ContentChapter> = emptyList(),
    bookTitle: String = "",
    themePreset: NovelReaderThemePreset = NovelReaderThemePreset.PAPER,
    onDismiss: () -> Unit,
    onEditNote: (NovelMarkingEntity) -> Unit,
    onDelete: (NovelMarkingEntity) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit = {},
    onOpenBookmark: (Bookmark) -> Unit = {},
    onJumpToMarking: (NovelMarkingEntity) -> Unit = {},
) {
    val palette = novelReaderPalette(themePreset, isSystemInDarkTheme())
    val sheetColor = Color(palette.backgroundColor)
    val sheetContentColor = Color(palette.textColor)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.92f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetColor,
        contentColor = sheetContentColor,
        scrimColor = Color.Black.copy(alpha = if (palette.isDark) 0.58f else 0.42f),
        tonalElevation = 0.dp,
    ) {
        val readerColors = MaterialTheme.colorScheme.copy(
            primary = Color(palette.chromeTextColor),
            onPrimary = sheetColor,
            primaryContainer = Color(palette.placeholderColor),
            onPrimaryContainer = Color(palette.textColor),
            secondaryContainer = Color(palette.highlightColor),
            onSecondaryContainer = Color(palette.textColor),
            background = sheetColor,
            surface = sheetColor,
            surfaceVariant = Color(palette.placeholderColor),
            onSurface = sheetContentColor,
            onSurfaceVariant = Color(palette.secondaryTextColor),
        )
        MaterialTheme(colorScheme = readerColors) {
            ComposeNovelNotesContent(
                bookmarks = bookmarks,
                markings = markings,
                chapters = chapters,
                bookTitle = bookTitle,
                onDismiss = onDismiss,
                onJumpToMarking = onJumpToMarking,
                onOpenBookmark = onOpenBookmark,
                onEditNote = onEditNote,
                onDelete = onDelete,
                onDeleteBookmark = onDeleteBookmark,
                showTitle = true,
            )
        }
    }
}

@Composable
internal fun ComposeNovelNotesContent(
    bookmarks: List<Bookmark> = emptyList(),
    markings: List<NovelMarkingEntity> = emptyList(),
    chapters: List<ContentChapter> = emptyList(),
    bookTitle: String = "",
    onDismiss: () -> Unit = {},
    onJumpToMarking: (NovelMarkingEntity) -> Unit = {},
    onOpenBookmark: (Bookmark) -> Unit = {},
    onEditNote: (NovelMarkingEntity) -> Unit = {},
    onDelete: (NovelMarkingEntity) -> Unit = {},
    onDeleteBookmark: (Bookmark) -> Unit = {},
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    var selectedTab by remember { mutableStateOf(NovelMarkingsTab.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var sortByChapter by remember { mutableStateOf(true) }

    val annotationCount = remember(markings) { markings.count { !it.note.isNullOrBlank() } }
    val highlightCount = remember(markings) { markings.size }
    val bookmarkCount = remember(bookmarks) { bookmarks.size }

    val filteredMarkings = remember(markings, selectedTab, searchQuery) {
        val base = when (selectedTab) {
            NovelMarkingsTab.ALL, NovelMarkingsTab.HIGHLIGHTS -> markings
            NovelMarkingsTab.ANNOTATIONS -> markings.filter { !it.note.isNullOrBlank() }
            NovelMarkingsTab.BOOKMARKS -> emptyList()
        }
        if (searchQuery.isBlank()) {
            base
        } else {
            base.filter {
                it.selectedText.contains(searchQuery, ignoreCase = true) ||
                    it.note?.contains(searchQuery, ignoreCase = true) == true
            }
        }
    }
    val filteredBookmarks = remember(bookmarks, selectedTab, searchQuery) {
        if (selectedTab != NovelMarkingsTab.ALL && selectedTab != NovelMarkingsTab.BOOKMARKS) {
            emptyList()
        } else if (searchQuery.isBlank()) {
            bookmarks
        } else {
            bookmarks.filter { it.imageUrl.contains(searchQuery, ignoreCase = true) }
        }
    }
    val displayedMarkings = remember(filteredMarkings, sortByChapter) {
        if (sortByChapter) filteredMarkings else filteredMarkings.sortedByDescending { it.updatedAt }
    }
    val displayedBookmarks = remember(filteredBookmarks, sortByChapter) {
        if (sortByChapter) filteredBookmarks else filteredBookmarks.sortedByDescending { it.createdAt }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (showTitle) 0.dp else 8.dp),
    ) {
        if (showTitle) {
            Row(
                modifier = dragModifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.novel_markings_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = stringResource(
                            R.string.novel_reader_notes_counts,
                            highlightCount,
                            annotationCount,
                            bookmarkCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }
            if (bookTitle.isNotBlank()) {
                NovelNotesBookSummary(
                    title = bookTitle,
                    itemCount = highlightCount + bookmarkCount,
                    modifier = dragModifier,
                )
            }
        } else {
            Text(
                text = stringResource(
                    R.string.novel_reader_notes_counts,
                            highlightCount,
                            annotationCount,
                            bookmarkCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragModifier,
            )
        }

        Row(
            modifier = dragModifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedTab == NovelMarkingsTab.ALL,
                onClick = { selectedTab = NovelMarkingsTab.ALL },
                label = {
                    Text("${stringResource(NovelMarkingsTab.ALL.labelRes)} (${highlightCount + bookmarkCount})")
                },
                shape = RoundedCornerShape(10.dp),
            )
            FilterChip(
                selected = selectedTab == NovelMarkingsTab.HIGHLIGHTS,
                onClick = { selectedTab = NovelMarkingsTab.HIGHLIGHTS },
                label = { Text("${stringResource(NovelMarkingsTab.HIGHLIGHTS.labelRes)} ($highlightCount)") },
                shape = RoundedCornerShape(10.dp),
            )
            FilterChip(
                selected = selectedTab == NovelMarkingsTab.ANNOTATIONS,
                onClick = { selectedTab = NovelMarkingsTab.ANNOTATIONS },
                label = { Text("${stringResource(NovelMarkingsTab.ANNOTATIONS.labelRes)} ($annotationCount)") },
                shape = RoundedCornerShape(10.dp),
            )
            FilterChip(
                selected = selectedTab == NovelMarkingsTab.BOOKMARKS,
                onClick = { selectedTab = NovelMarkingsTab.BOOKMARKS },
                label = { Text("${stringResource(NovelMarkingsTab.BOOKMARKS.labelRes)} ($bookmarkCount)") },
                shape = RoundedCornerShape(10.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = dragModifier.fillMaxWidth(),
        ) {
            NovelReaderSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = stringResource(R.string.novel_reader_notes_search_hint),
                clearContentDescription = stringResource(R.string.clear),
            )
            IconButton(onClick = { sortByChapter = !sortByChapter }) {
                Icon(
                    painter = painterResource(R.drawable.ic_sort_desc),
                    contentDescription = stringResource(
                        if (sortByChapter) R.string.novel_reader_notes_sort_time else R.string.novel_reader_notes_sort_chapter,
                    ),
                    tint = if (sortByChapter) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            if (displayedBookmarks.isNotEmpty()) {
                item(key = "bookmarks_header") {
                    Text(
                        text = stringResource(R.string.bookmarks),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
                items(displayedBookmarks, key = { "bookmark:${it.pageId}" }) { bookmark ->
                    NovelBookmarkCard(
                        bookmark = bookmark,
                        chapterName = chapters.firstOrNull { it.id == bookmark.chapterId }?.title
                            ?: bookmark.chapterTitle,
                        onOpen = {
                            onDismiss()
                            onOpenBookmark(bookmark)
                        },
                        onDelete = { onDeleteBookmark(bookmark) },
                    )
                }
            }

            if (displayedMarkings.isNotEmpty()) {
                if (sortByChapter) {
                    displayedMarkings.groupBy { it.chapterIndex }.forEach { (chapterIndex, chapterMarkings) ->
                        item(key = "chapter_header:$chapterIndex") {
                            val chapterTitle = chapters.getOrNull(chapterIndex)?.title
                            Text(
                                text = chapterTitle?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.novel_marking_chapter, chapterIndex + 1),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(chapterMarkings, key = { "marking:${it.id}" }) { marking ->
                            NovelMarkingCard(
                                marking = marking,
                                chapterName = chapters.getOrNull(marking.chapterIndex)?.title,
                                onOpen = {
                                    onDismiss()
                                    onJumpToMarking(marking)
                                },
                                onEdit = { onEditNote(marking) },
                                onDelete = { onDelete(marking) },
                            )
                        }
                    }
                } else {
                    item(key = "recent_markings_header") {
                        Text(
                            text = stringResource(R.string.novel_reader_notes_sort_time),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                    items(displayedMarkings, key = { "marking:${it.id}" }) { marking ->
                        NovelMarkingCard(
                            marking = marking,
                            chapterName = chapters.getOrNull(marking.chapterIndex)?.title,
                            onOpen = {
                                onDismiss()
                                onJumpToMarking(marking)
                            },
                            onEdit = { onEditNote(marking) },
                            onDelete = { onDelete(marking) },
                        )
                    }
                }
            }

            if (displayedBookmarks.isEmpty() && displayedMarkings.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    ) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) {
                                stringResource(R.string.novel_reader_no_matching_notes)
                            } else {
                                stringResource(R.string.novel_markings_empty)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelNotesBookSummary(
    title: String,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.novel_markings_summary, itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_book_page),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun NovelBookmarkCard(
    bookmark: Bookmark,
    chapterName: String?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val positionText = stringResource(R.string.bookmark_position, bookmark.page + 1)
    val previewText = remember(bookmark.imageUrl) {
        org.skepsun.kototoro.bookmarks.domain.extractNovelBookmarkPreview(bookmark.imageUrl)
    }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = previewText.ifBlank { positionText },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.delete),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = chapterName?.takeIf { it.isNotBlank() } ?: positionText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = formatNovelDate(Date.from(bookmark.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NovelMarkingCard(
    marking: NovelMarkingEntity,
    chapterName: String?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val markingColor = NovelMarkingColor.fromId(marking.color)
    val location = chapterName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.novel_marking_chapter, marking.chapterIndex + 1)
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(48.dp)
                        .clip(CircleShape)
                        .background(markingColor.lineColor),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = marking.selectedText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!marking.note.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = marking.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = location,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatNovelDate(Date(marking.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.novel_marking_edit_note),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.novel_marking_delete),
                    )
                }
            }
        }
    }
}

private fun formatNovelDate(date: Date): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(date)
}

/**
 * Dedicated BottomSheet for writing or editing thoughts on selected text or an existing marking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelNoteEditorSheet(
    quoteText: String,
    initialNote: String,
    title: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var noteDraft by remember { mutableStateOf(initialNote) }
    var quoteExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.85f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = { onSave(noteDraft.trim()) },
                        enabled = noteDraft.trim().isNotBlank() || initialNote.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }

            // Quote Box
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { quoteExpanded = !quoteExpanded },
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = quoteText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (quoteExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Note Input Field
            OutlinedTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it },
                placeholder = { Text(stringResource(R.string.novel_selection_note_hint)) },
                minLines = 5,
                maxLines = 10,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Detailed sheet for viewing a full thought and jump to origin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelNoteDetailSheet(
    marking: NovelMarkingEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onExcerpt: () -> Unit,
    onJumpToText: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.75f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.novel_marking_detail_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }

            // Original Quote
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(14.dp)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = marking.selectedText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Thought text
            if (!marking.note.isNullOrBlank()) {
                Text(
                    text = marking.note,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Time & chapter
            val dateStr = remember(marking.updatedAt) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(marking.updatedAt))
            }
            Text(
                text = stringResource(
                    R.string.novel_marking_detail_subtitle,
                    marking.chapterIndex + 1,
                    dateStr,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )

            HorizontalDivider()

            // Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        onDismiss()
                        onJumpToText()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.novel_marking_locate_original))
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onExcerpt()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.novel_marking_create_excerpt))
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onEdit()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.edit))
                }
            }

            TextButton(
                onClick = {
                    onDismiss()
                    onDelete()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
