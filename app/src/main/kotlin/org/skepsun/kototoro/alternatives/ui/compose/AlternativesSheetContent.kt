package org.skepsun.kototoro.alternatives.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.alternatives.ui.ContentAlternativeModel
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.list.ui.model.ButtonFooter
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingFooter
import org.skepsun.kototoro.list.ui.model.LoadingState
import kotlin.math.sign

@Composable
fun AlternativesSheetContent(
    items: List<ListModel>,
    onItemClick: (ContentAlternativeModel) -> Unit,
    onSourceClick: (ContentAlternativeModel) -> Unit,
    onMigrateClick: (ContentAlternativeModel) -> Unit,
    onRetry: () -> Unit,
    onContinueSearch: () -> Unit,
) {
    AlternativesList(
        items = items,
        onItemClick = onItemClick,
        onSourceClick = onSourceClick,
        onMigrateClick = onMigrateClick,
        onRetry = onRetry,
        onContinueSearch = onContinueSearch,
    )
}

@Composable
private fun AlternativesList(
    items: List<ListModel>,
    onItemClick: (ContentAlternativeModel) -> Unit,
    onSourceClick: (ContentAlternativeModel) -> Unit,
    onMigrateClick: (ContentAlternativeModel) -> Unit,
    onRetry: () -> Unit,
    onContinueSearch: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(rememberNestedScrollInteropConnection()),
    ) {
        item(key = "hint") {
            Text(
                text = stringResource(R.string.alternatives_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(
            items = items,
            key = { item ->
                when (item) {
                    is ContentAlternativeModel -> item.manga.id
                    is LoadingState -> "loading"
                    is EmptyState -> "empty"
                    is LoadingFooter -> "loading_footer"
                    is ButtonFooter -> "button_footer"
                    else -> item.hashCode()
                }
            },
        ) { item ->
            when (item) {
                is ContentAlternativeModel -> AlternativeItem(
                    model = item,
                    onClick = { onItemClick(item) },
                    onSourceClick = { onSourceClick(item) },
                    onMigrateClick = { onMigrateClick(item) },
                )
                is LoadingState -> LoadingContent()
                is EmptyState -> EmptyContent(item, onRetry)
                is LoadingFooter -> LoadingFooterContent()
                is ButtonFooter -> ButtonFooterContent(item, onContinueSearch)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlternativeItem(
    model: ContentAlternativeModel,
    onClick: () -> Unit,
    onSourceClick: () -> Unit,
    onMigrateClick: () -> Unit,
) {
    val context = LocalContext.current
    val manga = model.manga
    val coverRequest = remember(manga.coverUrl, manga.id, manga.source) {
        ImageRequest.Builder(context)
            .data(manga.coverUrl)
            .mangaExtra(manga)
            .crossfade(true)
            .build()
    }
    val sourceTitle = remember(manga.source) { manga.source.getTitle(context) }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onMigrateClick,
                ),
        ) {
            AsyncImage(
                model = coverRequest,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(98.dp)
                    .aspectRatio(13f / 18f)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp),
            ) {
                Text(
                    text = model.mangaModel.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                ChaptersDiffText(model)
                Spacer(modifier = Modifier.height(4.dp))
                AssistChip(
                    onClick = onSourceClick,
                    label = { Text(sourceTitle) },
                    leadingIcon = {
                        ContentSourceIcon(
                            source = manga.source,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(
                        onClick = onMigrateClick,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_replace),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.migrate),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChaptersDiffText(model: ContentAlternativeModel) {
    val chaptersText = if (model.chaptersCount > 0) {
        pluralStringResource(R.plurals.chapters, model.chaptersCount, model.chaptersCount)
    } else {
        stringResource(R.string.no_chapters)
    }
    val diffSign = model.chaptersDiff.sign
    val annotated = buildAnnotatedString {
        append(chaptersText)
        when (diffSign) {
            -1 -> {
                withStyle(
                    MaterialTheme.typography.bodySmall.toSpanStyle().copy(
                        color = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    append("  ▼ ${model.chaptersDiff}")
                }
            }
            1 -> {
                withStyle(
                    MaterialTheme.typography.bodySmall.toSpanStyle().copy(
                        color = MaterialTheme.colorScheme.tertiary,
                    ),
                ) {
                    append("  ▲ +${model.chaptersDiff}")
                }
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(state: EmptyState, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(state.icon),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = state.textPrimaryText?.toString() ?: stringResource(state.textPrimary),
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.textSecondary != 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.textSecondaryText?.toString() ?: stringResource(state.textSecondary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.actionStringRes != 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(stringResource(state.actionStringRes))
            }
        }
    }
}

@Composable
private fun LoadingFooterContent() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ButtonFooterContent(footer: ButtonFooter, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        FilledTonalButton(onClick = onClick) {
            Text(stringResource(footer.textResId))
        }
    }
}
