package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.parsers.model.ContentChapter

internal sealed interface NovelChapterListItem {
	val key: String
	data class Header(val title: String) : NovelChapterListItem { override val key = "header:$title" }
	data class Chapter(val chapter: ContentChapter, val originalIndex: Int) : NovelChapterListItem {
		override val key = "chapter:${chapter.id}:$originalIndex"
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeNovelChaptersSheet(
	chapters: List<ContentChapter>,
	currentIndex: Int,
	onDismiss: () -> Unit,
	onChapterSelected: (Int) -> Unit,
) {
	var reversed by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	val items = remember(chapters, reversed, query) { buildChapterItems(chapters, reversed, query) }
	val currentPosition = items.indexOfFirst {
		it is NovelChapterListItem.Chapter && it.originalIndex == currentIndex
	}.coerceAtLeast(0)
	val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentPosition)
	LaunchedEffect(reversed, query) {
		if (query.isBlank()) listState.scrollToItem(currentPosition)
	}
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		modifier = Modifier.fillMaxHeight(),
	) {
		BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
				val contentWidth = if (maxWidth >= 720.dp) 680.dp else maxWidth
				Column(
					verticalArrangement = Arrangement.spacedBy(10.dp),
					modifier = Modifier
						.widthIn(max = contentWidth)
						.align(Alignment.TopCenter)
						.padding(horizontal = 16.dp),
				) {
						Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
						Column(modifier = Modifier.weight(1f)) {
							Text(stringResource(R.string.chapters), style = MaterialTheme.typography.titleLarge)
							Text(
								stringResource(R.string.novel_chapters_count, chapters.size),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						IconButton(onClick = { reversed = !reversed }) {
							Icon(painterResource(R.drawable.ic_sort_desc), stringResource(R.string.reverse_order))
						}
					}
					OutlinedTextField(
						value = query,
						onValueChange = { query = it },
						label = { Text(stringResource(R.string.search_chapters)) },
						singleLine = true,
						modifier = Modifier.fillMaxWidth(),
					)
					LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
						items(items, key = NovelChapterListItem::key) { item ->
							when (item) {
								is NovelChapterListItem.Header -> {
									Text(
										item.title,
										style = MaterialTheme.typography.titleSmall,
										fontWeight = FontWeight.SemiBold,
										color = MaterialTheme.colorScheme.primary,
										modifier = Modifier
											.fillMaxWidth()
											.padding(horizontal = 16.dp, vertical = 10.dp),
									)
								}
								is NovelChapterListItem.Chapter -> {
									val selected = item.originalIndex == currentIndex
									ListItem(
										headlineContent = {
											Text(
												item.chapter.title ?: stringResource(R.string.unnamed_chapter),
												maxLines = 2,
												overflow = TextOverflow.Ellipsis,
												fontWeight = if (selected) {
													FontWeight.SemiBold
												} else {
													FontWeight.Normal
												},
											)
										},
										leadingContent = if (selected) {
											{ Icon(painterResource(R.drawable.ic_current_chapter), null) }
										} else {
											null
										},
										colors = ListItemDefaults.colors(
											containerColor = if (selected) {
												MaterialTheme.colorScheme.secondaryContainer
											} else {
												MaterialTheme.colorScheme.surfaceContainerLow
											},
										),
										modifier = Modifier.clickable { onChapterSelected(item.originalIndex) },
									)
									HorizontalDivider()
								}
							}
						}
					}
				}
		}
	}
}

@Composable
internal fun ComposeNovelChaptersPanel(
	chapters: List<ContentChapter>,
	currentIndex: Int,
	onChapterSelected: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	var reversed by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	val items = remember(chapters, reversed, query) { buildChapterItems(chapters, reversed, query) }
	val currentPosition = items.indexOfFirst {
		it is NovelChapterListItem.Chapter && it.originalIndex == currentIndex
	}.coerceAtLeast(0)
	val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentPosition)
	LaunchedEffect(reversed, query) {
		if (query.isBlank()) listState.scrollToItem(currentPosition)
	}
	Column(
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 12.dp, vertical = 10.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.weight(1f)) {
				Text(stringResource(R.string.chapters), style = MaterialTheme.typography.titleMedium)
				Text(
					stringResource(R.string.novel_chapters_count, chapters.size),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			IconButton(onClick = { reversed = !reversed }) {
				Icon(painterResource(R.drawable.ic_sort_desc), stringResource(R.string.reverse_order))
			}
		}
		OutlinedTextField(
			value = query,
			onValueChange = { query = it },
			label = { Text(stringResource(R.string.search_chapters)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
			items(items, key = NovelChapterListItem::key) { item ->
				when (item) {
					is NovelChapterListItem.Header -> Text(
						item.title,
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.primary,
						modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
					)
					is NovelChapterListItem.Chapter -> {
						val selected = item.originalIndex == currentIndex
						ListItem(
							headlineContent = {
								Text(
									item.chapter.title ?: stringResource(R.string.unnamed_chapter),
									maxLines = 2,
									overflow = TextOverflow.Ellipsis,
									fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
								)
							},
							leadingContent = if (selected) {
								{ Icon(painterResource(R.drawable.ic_current_chapter), null) }
							} else {
								null
							},
							colors = ListItemDefaults.colors(
								containerColor = if (selected) {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
								} else {
									androidx.compose.ui.graphics.Color.Transparent
								},
							),
							modifier = Modifier.clickable { onChapterSelected(item.originalIndex) },
						)
						HorizontalDivider()
					}
				}
			}
		}
	}
}

internal fun buildChapterItems(
	chapters: List<ContentChapter>,
	reversed: Boolean,
	query: String,
): List<NovelChapterListItem> {
	val indexed = chapters.withIndex().let { if (reversed) it.reversed() else it }
	val filtered = indexed.filter { (_, chapter) ->
		query.isBlank() || listOf(chapter.title, chapter.branch, chapter.scanlator)
			.any { it?.contains(query, ignoreCase = true) == true }
	}
	val result = mutableListOf<NovelChapterListItem>()
	var previousGroup: String? = null
	filtered.forEach { (index, chapter) ->
		val group = chapter.branch?.takeIf(String::isNotBlank)
			?: chapter.scanlator?.takeIf { it.isNotBlank() && chapter.source == LocalNovelSource }
			.orEmpty()
		if (group.isNotEmpty() && group != previousGroup) result += NovelChapterListItem.Header(group)
		result += NovelChapterListItem.Chapter(chapter, index)
		previousGroup = group
	}
	return result
}
