package org.skepsun.kototoro.space.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot

private val WorkbenchRailWidth = 132.dp
private val WorkbenchRailShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
private val WorkbenchCardShape = RoundedCornerShape(20.dp)

/**
 * A lightweight, One Step-inspired overview. Only the active Space remains composed; inactive
 * Spaces are represented by semantic session previews so the workbench does not keep multiple
 * navigation hosts, readers, or players alive.
 */
@Composable
fun SpaceWorkbench(
	state: SpaceUiState,
	resumeItems: Map<SpaceId, SpaceResumeItem>,
	sessions: Map<SpaceId, SpaceSessionSnapshot> = emptyMap(),
	dragPosition: Offset? = null,
	onDismiss: () -> Unit,
	onSelectSpace: (SpaceId) -> Unit,
	onHoveredSpaceChanged: (SpaceId?) -> Unit = {},
	modifier: Modifier = Modifier,
) {
	val dismissDescription = stringResource(R.string.space_workbench_dismiss)
	val cardBounds = remember { mutableStateMapOf<SpaceId, Rect>() }
	val hoveredSpaceId = resolveSpaceWorkbenchDropTarget(
		dragPosition = dragPosition,
		orderedSpaceIds = state.spaces.map { it.id },
		cardBounds = cardBounds,
	)
	val hapticFeedback = LocalHapticFeedback.current
	LaunchedEffect(hoveredSpaceId) {
		onHoveredSpaceChanged(hoveredSpaceId)
		if (hoveredSpaceId != null) {
			hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
		}
	}
	BackHandler(enabled = state.workbenchVisible, onBack = onDismiss)
	AnimatedVisibility(
		visible = state.workbenchVisible,
		enter = fadeIn(),
		exit = fadeOut(),
		modifier = modifier,
	) {
		Box(modifier = Modifier.fillMaxSize()) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color.Black.copy(alpha = 0.24f))
					.clickable(
						interactionSource = remember { MutableInteractionSource() },
						indication = null,
						role = Role.Button,
						onClick = onDismiss,
					)
					.semantics { contentDescription = dismissDescription },
			)
			Surface(
				modifier = Modifier
					.align(Alignment.CenterEnd)
					.width(WorkbenchRailWidth)
					.fillMaxHeight()
					.windowInsetsPadding(WindowInsets.safeDrawing),
				shape = WorkbenchRailShape,
				color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
				tonalElevation = 8.dp,
				shadowElevation = 12.dp,
			) {
				Column(
					modifier = Modifier.padding(horizontal = 10.dp, vertical = 16.dp),
					verticalArrangement = Arrangement.spacedBy(12.dp),
				) {
					Text(
						text = stringResource(R.string.space_workbench_title),
						style = MaterialTheme.typography.titleSmall,
						modifier = Modifier.padding(horizontal = 6.dp),
					)
					LazyColumn(
						modifier = Modifier.weight(1f),
						verticalArrangement = Arrangement.spacedBy(10.dp),
					) {
						items(state.spaces, key = { it.id.value }) { context ->
							SpaceWorkbenchCard(
								context = context,
								selected = context.id == state.activeSpaceId,
								hovered = context.id == hoveredSpaceId,
								resumeItem = resumeItems[context.id],
								session = sessions[context.id],
								enabled = !state.switchInProgress,
								onBoundsChanged = { bounds ->
									if (cardBounds[context.id] != bounds) {
										cardBounds[context.id] = bounds
									}
								},
								onClick = {
									if (context.id == state.activeSpaceId) {
										onDismiss()
									} else {
										onSelectSpace(context.id)
									}
								},
							)
						}
					}
					if (state.switchInProgress) {
						CircularProgressIndicator(
							modifier = Modifier.align(Alignment.CenterHorizontally).size(28.dp),
							strokeWidth = 3.dp,
						)
					}
				}
			}
		}
	}
}

internal fun resolveSpaceWorkbenchDropTarget(
	dragPosition: Offset?,
	orderedSpaceIds: List<SpaceId>,
	cardBounds: Map<SpaceId, Rect>,
): SpaceId? {
	if (dragPosition == null) return null
	return orderedSpaceIds.firstOrNull { spaceId ->
		cardBounds[spaceId]?.contains(dragPosition) == true
	}
}

@Composable
private fun SpaceWorkbenchCard(
	context: SpaceContext,
	selected: Boolean,
	hovered: Boolean,
	resumeItem: SpaceResumeItem?,
	session: SpaceSessionSnapshot?,
	enabled: Boolean,
	onBoundsChanged: (Rect) -> Unit,
	onClick: () -> Unit,
) {
	val localContext = LocalContext.current
	val coverUrl = resumeItem?.content?.coverUrl?.takeIf { it.isNotBlank() }
	val coverRequest = remember(localContext, resumeItem?.content?.id, coverUrl) {
		resumeItem?.content?.takeIf { coverUrl != null }?.let { content ->
			ImageRequest.Builder(localContext)
				.data(coverUrl)
				.apply { mangaExtra(content) }
				.build()
		}
	}
	val location = session?.toWorkbenchLocation()
	val scale by animateFloatAsState(
		targetValue = if (hovered) 1.045f else 1f,
		animationSpec = tween(durationMillis = 120),
		label = "space_workbench_card_scale",
	)
	val borderColor = when {
		hovered -> MaterialTheme.colorScheme.tertiary
		selected -> MaterialTheme.colorScheme.primary
		else -> Color.Transparent
	}
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.onGloballyPositioned { coordinates -> onBoundsChanged(coordinates.boundsInRoot()) }
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.border(1.5.dp, borderColor, WorkbenchCardShape)
			.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
		shape = WorkbenchCardShape,
		color = when {
			hovered -> MaterialTheme.colorScheme.tertiaryContainer
			selected -> MaterialTheme.colorScheme.primaryContainer
			else -> MaterialTheme.colorScheme.surfaceContainerHigh
		},
	) {
		Column(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			if (coverRequest != null) {
				AsyncImage(
					model = coverRequest,
					contentDescription = resumeItem?.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier
						.fillMaxWidth()
						.height(92.dp)
						.clip(RoundedCornerShape(12.dp))
						.background(MaterialTheme.colorScheme.surfaceVariant),
				)
			}
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				SpaceSwitcherIcon(
					activeSpaceId = context.id,
					activeSpace = context,
					modifier = Modifier.size(22.dp),
				)
				Text(
					text = spaceDisplayLabel(context.id, context),
					style = MaterialTheme.typography.labelLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Text(
				text = resumeItem?.title ?: stringResource(R.string.space_workbench_empty_preview),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			when (location) {
				SpaceWorkbenchLocation.Details -> Text(
					text = stringResource(R.string.space_workbench_location_details),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.primary,
					maxLines = 1,
				)
				is SpaceWorkbenchLocation.ContentList -> Text(
					text = stringResource(
						R.string.space_workbench_location_source,
						location.sourceName,
					),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.primary,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				null -> Unit
			}
		}
	}
}

internal sealed interface SpaceWorkbenchLocation {
	data object Details : SpaceWorkbenchLocation
	data class ContentList(val sourceName: String) : SpaceWorkbenchLocation
}

internal fun SpaceSessionSnapshot.toWorkbenchLocation(): SpaceWorkbenchLocation? = when (val route = resumeRoute) {
	is SpaceRouteSnapshot.WorkDetails -> SpaceWorkbenchLocation.Details
	is SpaceRouteSnapshot.ContentList -> SpaceWorkbenchLocation.ContentList(route.sourceName)
	is SpaceRouteSnapshot.TopLevel,
	null -> null
}
