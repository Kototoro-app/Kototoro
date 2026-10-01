package org.skepsun.kototoro.migration.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.chaptersCount
import org.skepsun.kototoro.migration.ui.migrationTitle
import org.skepsun.kototoro.migration.ui.rememberCoverRequest
import org.skepsun.kototoro.migration.domain.MatchCandidate
import org.skepsun.kototoro.parsers.model.Content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationCandidatesSheet(
    item: MigrationItemState,
    onSelect: (MatchCandidate) -> Unit,
    onSearch: (String) -> Unit,
    onLoadDetails: (MatchCandidate) -> Unit,
    onOpen: (Content) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(item.origin.id) { mutableStateOf(item.origin.title) }
    val focusManager = LocalFocusManager.current
    val search = {
        focusManager.clearFocus()
        onSearch(query)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxHeight(0.85f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.migration_candidates_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.migration_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                trailingIcon = {
                    IconButton(onClick = { search() }, enabled = query.isNotBlank()) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.migration_action_search))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            val searching = item.status == MigrationItemStatus.SEARCHING
            if (searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (item.candidates.isEmpty()) {
                Text(
                    stringResource(R.string.migration_candidates_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        LazyColumn {
            items(item.candidates, key = { it.content.id }) { candidate ->
                MigrationCandidateRow(
                    candidate = item.candidateWithDetails(candidate),
                    selected = candidate.content.id == item.target?.id,
                    onSelect = onSelect,
                    onLoadDetails = onLoadDetails,
                    onOpen = onOpen,
                )
            }
        }
    }
}

/** Shared by the inline preview and the full candidate list. Opening details never selects a match. */
@Composable
internal fun MigrationCandidateRow(
    candidate: MatchCandidate,
    selected: Boolean,
    onSelect: (MatchCandidate) -> Unit,
    onLoadDetails: (MatchCandidate) -> Unit,
    onOpen: (Content) -> Unit,
) {
    val content = candidate.content
    val context = LocalContext.current
    LaunchedEffect(content.id) {
        if (content.chapters == null) onLoadDetails(candidate)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(candidate) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = { onSelect(candidate) })
        AsyncImage(
            model = rememberCoverRequest(content),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(40.dp).aspectRatio(13f / 18f).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(content.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val source = content.source.migrationTitle(context)
            Text(
                text = if (content.chapters == null) {
                    "$source · ${stringResource(R.string.unknown)}"
                } else {
                    stringResource(R.string.migration_chapters, source, content.chaptersCount())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onOpen(content) }) {
            Icon(painterResource(R.drawable.ic_info_outline), contentDescription = stringResource(R.string.details))
        }
    }
}
