package org.skepsun.kototoro.scrobbling.common.ui.config

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScrobblerConfigScreen(
	title: String,
	user: ScrobblerUser?,
	items: List<ListModel>,
	isLoading: Boolean,
	onNavigateUp: () -> Unit,
	onAvatarClick: () -> Unit,
	onRefresh: () -> Unit,
	onItemClick: (ScrobblingInfo) -> Unit,
	onContentBound: (ScrobblingInfo) -> Unit,
) {
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	Scaffold(
		topBar = {
			MediumTopAppBar(
				title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
				navigationIcon = {
					IconButton(onClick = onNavigateUp) {
						Icon(
							Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.back),
						)
					}
				},
				actions = {
					UserAvatar(user = user, onClick = onAvatarClick)
				},
				scrollBehavior = scrollBehavior,
			)
		},
		containerColor = MaterialTheme.colorScheme.background,
		contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
	) { contentPadding ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(contentPadding),
		) {
			KototoroPullToRefreshBox(
				isRefreshing = isLoading,
				onRefresh = onRefresh,
				indicatorTopInset = PaddingValues(top = 8.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				LazyColumn(
					modifier = Modifier
						.fillMaxSize()
						.navigationBarsPadding()
						.nestedScroll(scrollBehavior.nestedScrollConnection),
					contentPadding = PaddingValues(
						start = dimensionResource(R.dimen.list_spacing_normal),
						end = dimensionResource(R.dimen.list_spacing_normal),
						top = dimensionResource(R.dimen.list_spacing_normal),
						bottom = dimensionResource(R.dimen.list_spacing_normal),
					),
					verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.list_spacing_normal)),
				) {
					items(
						items = items,
						key = { item -> itemKey(item) },
						contentType = { item -> item::class },
					) { item ->
						when (item) {
							is ScrobblingStatus -> ScrobblingStatusHeader(item)
							is ScrobblingInfo -> ScrobblingContentRow(item, onItemClick, onContentBound)
							is EmptyState -> EmptyScrobblingState(item)
						}
					}
				}
			}
			if (isLoading && items.isEmpty()) {
				CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
			}
		}
	}
}

@Composable
private fun UserAvatar(user: ScrobblerUser?, onClick: () -> Unit) {
	val fallback = user?.service?.iconResId ?: androidx.appcompat.R.drawable.abc_ic_menu_overflow_material
	IconButton(onClick = onClick) {
		Box(
			modifier = Modifier
				.size(28.dp)
				.clip(CircleShape)
				.background(MaterialTheme.colorScheme.surfaceContainerHighest),
			contentAlignment = Alignment.Center,
		) {
			if (user?.avatar.isNullOrBlank()) {
				Icon(
					painter = rememberSafePainter(fallback),
					contentDescription = null,
					modifier = Modifier.size(22.dp),
				)
			} else {
				AsyncImage(
					model = user.avatar,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
					error = rememberSafePainter(fallback),
					fallback = rememberSafePainter(fallback),
				)
			}
		}
	}
}

@Composable
private fun ScrobblingStatusHeader(status: ScrobblingStatus) {
	Text(
		text = stringArrayResource(R.array.scrobbling_statuses).getOrElse(status.ordinal) { "" },
		style = MaterialTheme.typography.titleSmall,
		modifier = Modifier.padding(dimensionResource(R.dimen.grid_spacing)),
	)
}

@Composable
private fun ScrobblingContentRow(
	item: ScrobblingInfo,
	onClick: (ScrobblingInfo) -> Unit,
	onBind: (ScrobblingInfo) -> Unit,
) {
	LaunchedEffect(item) {
		onBind(item)
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(4.dp))
			.clickable { onClick(item) }
			.padding(dimensionResource(R.dimen.list_spacing)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		AsyncImage(
			model = item.coverUrl.takeIf(String::isNotBlank),
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.size(42.dp)
				.clip(RoundedCornerShape(4.dp)),
			placeholder = rememberSafePainter(R.drawable.ic_placeholder),
			error = rememberSafePainter(R.drawable.ic_placeholder),
			fallback = rememberSafePainter(R.drawable.ic_placeholder),
		)
		Column(
			modifier = Modifier
				.padding(start = 16.dp)
				.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(
				text = item.title,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			RatingStars(rating = item.rating)
		}
	}
}

@Composable
private fun RatingStars(rating: Float) {
	Row {
		repeat(5) { index ->
			Icon(
				imageVector = Icons.Filled.Star,
				contentDescription = null,
				modifier = Modifier.size(12.dp),
				tint = MaterialTheme.colorScheme.primary.copy(
					alpha = if (index < rating.coerceIn(0f, 1f) * 5f) 1f else 0.25f,
				),
			)
		}
	}
}

@Composable
private fun EmptyScrobblingState(item: EmptyState) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 32.dp, vertical = 64.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(
			text = (item.textPrimaryText ?: stringResource(item.textPrimary)).toString(),
			style = MaterialTheme.typography.titleLarge,
			textAlign = androidx.compose.ui.text.style.TextAlign.Center,
		)
		if (item.textSecondary != 0 || item.textSecondaryText != null) {
			Text(
				text = (item.textSecondaryText ?: stringResource(item.textSecondary)).toString(),
				style = MaterialTheme.typography.bodyMedium,
				textAlign = androidx.compose.ui.text.style.TextAlign.Center,
			)
		}
	}
}

private fun itemKey(item: ListModel): String = when (item) {
	is ScrobblingStatus -> "status:${item.ordinal}"
	is ScrobblingInfo -> "info:${item.scrobbler.id}:${item.targetId}:${item.mangaId}:${item.mediaType}"
	is EmptyState -> "empty"
	else -> item.hashCode().toString()
}

@Preview(showBackground = true)
@Composable
private fun ScrobblerConfigScreenPreview() {
	MaterialTheme {
		ScrobblerConfigScreen(
			title = "AniList",
			user = ScrobblerUser(1, "user", null, ScrobblerService.ANILIST),
			items = listOf(
				ScrobblingStatus.READING,
				ScrobblingInfo(
					scrobbler = ScrobblerService.ANILIST,
					entityId = null,
					preferredLocalMangaId = null,
					mangaId = 1,
					targetId = 2,
					status = ScrobblingStatus.READING,
					chapter = 1,
					comment = null,
					rating = .8f,
					title = "Sample title",
					coverUrl = "",
					description = null,
					externalUrl = "",
				),
			),
			isLoading = false,
			onNavigateUp = {},
			onAvatarClick = {},
			onRefresh = {},
			onItemClick = {},
			onContentBound = {},
		)
	}
}
