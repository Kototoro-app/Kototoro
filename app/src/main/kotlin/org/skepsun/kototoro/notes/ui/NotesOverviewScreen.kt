package org.skepsun.kototoro.notes.ui

import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.artworkAwareContainerColor
import org.skepsun.kototoro.notes.domain.BookNotesSummary

@Composable
fun NotesOverviewScreen(
    uiState: NotesUiState,
    onBookClick: (Long) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleNsfwFilter: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var searchBarVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
    ) {
        // Top Header: the shared summary header of the top-level list pages.
        org.skepsun.kototoro.core.ui.compose.PageSummaryHeader(
            iconRes = R.drawable.ic_bookmark,
            title = stringResource(R.string.notes),
            subtitle = stringResource(
                R.string.book_notes_overview_summary,
                uiState.totalNotesCount,
                uiState.totalBooksCount,
            ),
        ) {
            if (!uiState.isGlobalNsfwDisabled && onToggleNsfwFilter != null) {
                org.skepsun.kototoro.core.ui.compose.PageSummaryAction(
                    text = stringResource(R.string.nsfw),
                    icon = if (uiState.isNsfwFiltered) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    onClick = onToggleNsfwFilter,
                    emphasized = uiState.isNsfwFiltered,
                )
            }
            org.skepsun.kototoro.core.ui.compose.PageSummaryAction(
                text = null,
                icon = if (searchBarVisible) Icons.Default.Close else Icons.Default.Search,
                contentDescription = stringResource(R.string.search),
                onClick = { searchBarVisible = !searchBarVisible },
            )
        }
        if (searchBarVisible) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppLayoutTokens.screenHorizontalPadding, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.book_notes_overview_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }

        // Empty state
        if (uiState.filteredSummaries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(
                            if (uiState.searchQuery.isBlank()) {
                                R.string.book_notes_overview_empty
                            } else {
                                R.string.book_notes_overview_no_matches
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.book_notes_overview_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Book cards list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.filteredSummaries, key = { it.mangaId }) { summary ->
                    BookNotesOverviewCard(
                        summary = summary,
                        onClick = { onBookClick(summary.mangaId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookNotesOverviewCard(
    summary: BookNotesSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isArtworkBackground = LocalBackgroundStyle.current.usesArtworkBackdrop

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.artworkAwareContainerColor(),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isArtworkBackground) 0.dp else 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${summary.totalCount}",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.book_notes_overview_count_suffix),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = stringResource(R.string.book_notes_overview_book_title, summary.title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = summary.readingProgressText ?: stringResource(R.string.status_reading),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AsyncImage(
                model = summary.coverUrl,
                contentDescription = summary.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 54.dp, height = 76.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}
