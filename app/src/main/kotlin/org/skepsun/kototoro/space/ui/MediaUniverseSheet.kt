package org.skepsun.kototoro.space.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.parsers.model.Content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaUniverseSheet(
	state: MediaUniverseUiState,
	onDismiss: () -> Unit,
	onContentClick: (Content) -> Unit,
) {
	if (!state.visible) return
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		containerColor = Color.Transparent,
		tonalElevation = 0.dp,
		shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
		dragHandle = null,
	) {
		KototoroSheetSurface(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 8.dp),
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(bottom = 24.dp),
			) {
				SheetDragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
				Text(
					text = stringResource(R.string.media_universe_title),
					style = MaterialTheme.typography.titleLarge,
					modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
				)
				when {
					state.loading -> CircularProgressIndicator(
						modifier = Modifier
							.size(32.dp)
							.align(Alignment.CenterHorizontally),
					)
					state.items.isEmpty() -> Text(
						text = stringResource(R.string.media_universe_empty),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
					)
					else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
						items(
							items = state.items,
							key = { item -> item.content.id },
						) { item ->
							MediaUniverseRow(item = item, onClick = { onContentClick(item.content) })
						}
					}
				}
			}
		}
	}
}

@Composable
internal fun MediaUniverseRow(
	item: MediaUniverseItem,
	onClick: () -> Unit,
) {
	val content = item.content
	val context = LocalContext.current
	val sourceTitle = rememberResolvedSourceTitle(content.source)
	val imageRequest = remember(content.id, content.coverUrl) {
		ImageRequest.Builder(context)
			.data(content.coverUrl)
			.apply { mangaExtra(content) }
			.build()
	}
	val membership = when {
		item.inHistory && item.inFavorites -> stringResource(R.string.media_universe_history_and_favorites)
		item.inHistory -> stringResource(R.string.history)
		else -> stringResource(R.string.favourites)
	}
	ListItem(
		headlineContent = {
			Text(content.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
		},
		supportingContent = {
			Text(
				text = stringResource(R.string.media_universe_item_summary, sourceTitle, membership),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		},
		leadingContent = {
			AsyncImage(
				model = imageRequest,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.size(width = 48.dp, height = 64.dp)
					.background(MaterialTheme.colorScheme.surfaceVariant),
			)
		},
		modifier = Modifier.clickable(onClick = onClick),
	)
}
