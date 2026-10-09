package org.skepsun.kototoro.search.ui.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/**
 * @param filterSummary names of the active filters, trailing the filter chip so the row says what is
 * narrowed without a second chip row; tapping one opens the same filter sheet.
 * @param scopeLabel when set, a leading chip that toggles between searching every content source and
 * only the page the search was opened from ("In Favourites"). It is a scope, not a destination, so it
 * lives with the other search options instead of being a tab pair of its own.
 * @param scopeSelected whether the search is currently limited to that page. The source-search options
 * (advanced search, filters) do not apply to a page, so only the scope chip remains then.
 */
@Composable
fun SearchToolsRow(
    advancedExpanded: Boolean,
    onAdvancedClick: () -> Unit,
    hasActiveFilters: Boolean,
    onFiltersClick: () -> Unit,
    modifier: Modifier = Modifier,
    filterSummary: List<String> = emptyList(),
    scopeLabel: String? = null,
    scopeSelected: Boolean = false,
    onScopeClick: () -> Unit = {},
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scopeLabel != null) {
            SearchCompactChip(
                text = scopeLabel,
                selected = scopeSelected,
                onClick = onScopeClick,
                leadingIcon = if (scopeSelected) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
        if (scopeLabel == null || !scopeSelected) {
            SearchCompactChip(
                text = stringResource(R.string.advanced_search),
                selected = advancedExpanded,
                onClick = onAdvancedClick,
                leadingIcon = {
                    Icon(
                        imageVector = if (advancedExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            SearchCompactChip(
                text = stringResource(R.string.filter),
                selected = hasActiveFilters,
                onClick = onFiltersClick,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_filter_menu),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            filterSummary.forEach { label ->
                SearchCompactChip(text = label, onClick = onFiltersClick)
            }
        }
    }
}

/**
 * A low-profile chip for search tools and suggestions: 30dp visual height with a 40dp touch target,
 * so rows of chips stay light without becoming hard to hit.
 */
@Composable
fun SearchCompactChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(SearchCompactChipHeight / 2),
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
        Surface(
            onClick = onClick,
            modifier = modifier.tvFocusable(shape = shape, addFocusTarget = false),
            shape = shape,
            color = if (selected) colorScheme.secondaryContainer else colorScheme.surfaceContainerHigh,
            contentColor = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .height(SearchCompactChipHeight)
                    .padding(start = if (leadingIcon != null) 8.dp else 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcon?.invoke()
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val SearchCompactChipHeight = 30.dp

@Composable
fun SearchAdvancedFields(
    title: String,
    onTitleChange: (String) -> Unit,
    tags: String,
    onTagsChange: (String) -> Unit,
    author: String,
    onAuthorChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val nextField = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.title)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = nextField,
            )
            OutlinedTextField(
                value = tags,
                onValueChange = onTagsChange,
                label = { Text(stringResource(R.string.tags)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = nextField,
            )
            OutlinedTextField(
                value = author,
                onValueChange = onAuthorChange,
                label = { Text(stringResource(R.string.author)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
        }
    }
}

@Composable
fun SearchFeedbackCard(
    message: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    isError: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false),
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

/** Shown above a library list while the overlay's page tab is filtering it. */
@Composable
fun LibrarySearchBanner(
    query: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.library_search_active, query),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onClear,
                modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
            ) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = stringResource(R.string.clear),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
