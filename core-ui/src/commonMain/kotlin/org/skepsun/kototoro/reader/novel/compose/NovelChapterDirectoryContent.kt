package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import org.skepsun.kototoro.reader.ui.compose.design.currentReaderPanelColors

@Immutable
data class NovelChapterDirectoryLabels(val search: String, val clear: String, val reverse: String, val unnamed: String)

@Composable
fun NovelChapterLocateButton(description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag("novel-directory-locate")) {
        Icon(imageVector = ReaderPanelIcons.CurrentChapter, contentDescription = description)
    }
}

@Composable
fun NovelChapterDirectoryContent(
    chapters: List<NovelChapterDirectoryEntry>,
    labels: NovelChapterDirectoryLabels,
    volumeTitle: (Int) -> String,
    currentIndex: Int,
    onChapterSelected: (Int) -> Unit,
    locateRequest: Int = 0,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = currentReaderPanelColors()
    var reversed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val items = remember(chapters, reversed, query, volumeTitle) {
        buildNovelChapterDirectoryItems(chapters, reversed, query, volumeTitle)
    }
    val currentPosition = novelDirectoryPositionForCurrent(items, currentIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentPosition.coerceAtLeast(0))
    LaunchedEffect(locateRequest) {
        if (locateRequest > 0) query = ""
    }
    LaunchedEffect(reversed, query, locateRequest, chapters, currentIndex) {
        if (items.isNotEmpty()) {
            val position = if (query.isBlank()) currentPosition else 0
            if (position >= 0) listState.scrollToItem(position)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("novel-directory-content")
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = dragModifier.fillMaxWidth(),
        ) {
            SharedNovelReaderSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = labels.search,
                clearContentDescription = labels.clear,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { reversed = !reversed },
                modifier = Modifier.testTag("novel-directory-reverse").semantics { selected = reversed },
            ) {
                Icon(
                    imageVector = ReaderPanelIcons.SortDesc,
                    contentDescription = labels.reverse,
                    tint = if (reversed) colors.accent else colors.contentSecondary,
                )
            }
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("novel-directory-list"),
        ) {
            items(items, key = NovelChapterDirectoryItem::key) { item ->
                when (item) {
                    is NovelChapterDirectoryItem.Header -> {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    is NovelChapterDirectoryItem.Chapter -> {
                        NovelChapterRow(
                            number = item.originalIndex + 1,
                            title = item.chapter.title ?: labels.unnamed,
                            state = novelChapterReadState(item.originalIndex, currentIndex),
                            onClick = { onChapterSelected(item.originalIndex) },
                            enabled = enabled,
                            modifier = Modifier.testTag(
                                "novel-directory-chapter:${item.chapter.id}:${item.originalIndex}",
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** A light row: read chapters fade, the current one gets a tinted background and an accent bar. */
@Composable
private fun NovelChapterRow(
    number: Int,
    title: String,
    state: NovelChapterReadState,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    val colors = currentReaderPanelColors()
    val current = state == NovelChapterReadState.CURRENT
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .semantics { selected = current }
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (current) colors.selectedContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .alpha(if (state == NovelChapterReadState.READ) 0.6f else 1f),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(if (current) colors.accent else Color.Transparent, RoundedCornerShape(2.dp)),
        )
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.contentSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(40.dp)
                .padding(end = 12.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        )
    }
}

@Composable
fun SharedNovelReaderSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    clearContentDescription: String? = null,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
        ),
        modifier = modifier.heightIn(min = 46.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .padding(start = 12.dp, end = 4.dp),
        ) {
            Icon(
                imageVector = ReaderPanelIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .testTag("novel-search-query")
                    .padding(horizontal = 8.dp),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(36.dp).testTag("novel-search-clear"),
                ) {
                    Icon(
                        imageVector = ReaderPanelIcons.TtsClose,
                        contentDescription = clearContentDescription,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
