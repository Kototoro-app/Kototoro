package org.skepsun.kototoro.details.ui.pager.chapters.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.details.ui.model.ChapterListItem

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChapterListCard(
	item: ChapterListItem,
	isSelected: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val context = LocalContext.current
	val titleColor = when {
		item.isCurrent -> MaterialTheme.colorScheme.primary
		item.isUnread -> MaterialTheme.colorScheme.onSurface
		else -> MaterialTheme.colorScheme.onSurfaceVariant
	}
	val alphaFactor = if (item.isUnread || item.isCurrent) 1.0f else 0.6f
	val titleWeight = if (item.isCurrent) androidx.compose.ui.text.font.FontWeight.Bold else null
	
	Row(
		modifier = modifier
			.fillMaxWidth()
			.then(
				if (isSelected) {
					Modifier.background(
						color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
						shape = RoundedCornerShape(8.dp),
					)
				} else {
					Modifier
				},
			)
			.combinedClickable(
				onClick = onClick,
				onLongClick = onLongClick
			)
			.heightIn(min = 56.dp)
			.padding(horizontal = 16.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		if (item.isCurrent) {
			Icon(
				painter = painterResource(id = R.drawable.ic_current_chapter),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.padding(end = 8.dp).size(16.dp)
			)
		}
		Column(
			modifier = Modifier
				.weight(1f)
				.alpha(alphaFactor)
		) {
			Text(
				text = item.getTitle(context.resources),
				style = MaterialTheme.typography.bodyLarge,
				fontWeight = titleWeight,
				color = titleColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			val desc = item.description
			if (!desc.isNullOrBlank()) {
				Text(
					text = desc,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 2.dp)
				)
			}
		}

		if (item.isBookmarked) {
			Icon(
				painter = painterResource(id = R.drawable.ic_bookmark),
				contentDescription = "Bookmarked",
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier
					.padding(start = 8.dp)
					.size(24.dp)
			)
		}

		if (item.isDownloaded) {
			Icon(
				painter = painterResource(id = R.drawable.ic_storage),
				contentDescription = "Downloaded",
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier
					.padding(start = 8.dp)
					.size(24.dp)
			)
		}
	}
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChapterGridCard(
	item: ChapterListItem,
	isSelected: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val context = LocalContext.current
	val titleColor = if (item.isUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
	val alphaFactor = if (item.isUnread) 1.0f else 0.6f
	val containerColor = when {
		isSelected -> MaterialTheme.colorScheme.primaryContainer
		else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
	}

	Card(
		modifier = modifier
			.fillMaxWidth()
			.aspectRatio(1f) // 1:1 ratio
			.alpha(alphaFactor)
			.combinedClickable(
				onClick = onClick,
				onLongClick = onLongClick
			),
		shape = RoundedCornerShape(8.dp),
		colors = CardDefaults.cardColors(
			containerColor = containerColor
		),
		border = if (!item.isUnread && !isSelected) null else CardDefaults.outlinedCardBorder()
	) {
		Box(modifier = Modifier.fillMaxSize()) {
			Text(
				text = item.getTitle(context.resources),
				style = MaterialTheme.typography.titleMedium,
				color = titleColor,
				maxLines = 1,
				textAlign = TextAlign.Center,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.align(Alignment.Center)
			)

			Row(
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(6.dp),
				horizontalArrangement = Arrangement.spacedBy(4.dp)
			) {
				if (item.isDownloaded) {
					Icon(
						painter = painterResource(id = R.drawable.ic_save_ok),
						contentDescription = "Downloaded",
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(14.dp)
					)
				}
				if (item.isBookmarked) {
					Icon(
						painter = painterResource(id = R.drawable.ic_bookmark),
						contentDescription = "Bookmarked",
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(14.dp)
					)
				}
			}

			if (item.isNew) {
				Icon(
					painter = painterResource(id = R.drawable.ic_new),
					contentDescription = "New",
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.align(Alignment.TopEnd)
						.padding(10.dp)
						.size(8.dp)
				)
			}

			if (item.isCurrent) {
				Icon(
					painter = painterResource(id = R.drawable.ic_current_chapter),
					contentDescription = "Current",
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.align(Alignment.TopStart)
						.padding(6.dp)
						.size(16.dp)
				)
			}
		}
	}
}
