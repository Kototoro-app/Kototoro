package org.skepsun.kototoro.space.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot

private val WorkbenchRailShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
private val WorkbenchCardShape = RoundedCornerShape(20.dp)

internal data class SpaceWorkbenchLayoutSpec(
	val railWidth: Dp,
	val coverHeight: Dp,
	val showCovers: Boolean,
)

internal fun resolveSpaceWorkbenchLayoutSpec(
	availableWidth: Dp,
	availableHeight: Dp,
): SpaceWorkbenchLayoutSpec {
	val railWidth = when {
		availableWidth >= 840.dp -> 240.dp
		availableWidth >= 600.dp -> 188.dp
		else -> 132.dp
	}
	val coverHeight = when {
		availableWidth >= 840.dp -> 128.dp
		availableWidth >= 600.dp -> 108.dp
		else -> 92.dp
	}
	return SpaceWorkbenchLayoutSpec(
		railWidth = railWidth,
		coverHeight = coverHeight,
		showCovers = availableHeight >= 520.dp,
	)
}

/**
 * A lightweight, One Step-inspired overview. Only the active Space remains composed; inactive
 * Spaces are represented by semantic session previews so the workbench does not keep multiple
 * navigation hosts, readers, or players alive.
 */
@Composable
internal fun SpaceWorkbench(
	state: SpaceUiState,
	resumeItems: Map<SpaceId, SpaceResumeItem>,
	sessions: Map<SpaceId, SpaceSessionSnapshot> = emptyMap(),
	gestureState: SpaceWorkbenchGestureState,
	settleAnimationDurationMillis: Int = 180,
	onDismiss: () -> Unit,
	onSelectSpace: (SpaceId) -> Unit,
	modifier: Modifier = Modifier,
) {
	val dismissDescription = stringResource(R.string.space_workbench_dismiss)
	val cardBounds = remember { mutableStateMapOf<SpaceId, Rect>() }
	var overlayBounds by remember { mutableStateOf<Rect?>(null) }
	val resolvedHoveredSpaceId = resolveSpaceWorkbenchDropTarget(
		dragPosition = gestureState.dragPosition.takeIf {
			gestureState.phase == SpaceWorkbenchDragPhase.DRAGGING
		},
		orderedSpaceIds = state.spaces.map { it.id },
		cardBounds = cardBounds,
	)
	val displayedHoveredSpaceId = if (gestureState.phase == SpaceWorkbenchDragPhase.SETTLING) {
		gestureState.hoveredSpaceId
	} else {
		resolvedHoveredSpaceId
	}
	val resolvedHoveredBounds = resolvedHoveredSpaceId?.let(cardBounds::get)
	val hapticFeedback = LocalHapticFeedback.current
	LaunchedEffect(resolvedHoveredSpaceId, resolvedHoveredBounds) {
		gestureState.updateHoveredSpace(
			spaceId = resolvedHoveredSpaceId,
			center = resolvedHoveredBounds?.center,
		)
	}
	LaunchedEffect(resolvedHoveredSpaceId) {
		if (resolvedHoveredSpaceId != null) {
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
		BoxWithConstraints(
			modifier = Modifier
				.fillMaxSize()
				.onGloballyPositioned { coordinates -> overlayBounds = coordinates.boundsInRoot() },
		) {
			val layoutSpec = remember(maxWidth, maxHeight) {
				resolveSpaceWorkbenchLayoutSpec(maxWidth, maxHeight)
			}
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
					.width(layoutSpec.railWidth)
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
								hovered = context.id == displayedHoveredSpaceId,
								resumeItem = resumeItems[context.id],
								session = sessions[context.id],
								layoutSpec = layoutSpec,
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
			SpaceWorkbenchDragProxy(
				gestureState = gestureState,
				activeSpace = state.spaces.firstOrNull { it.id == state.activeSpaceId },
				overlayBounds = overlayBounds,
				settleAnimationDurationMillis = settleAnimationDurationMillis,
				onSettled = { outcome ->
					when (outcome) {
						SpaceWorkbenchDropOutcome.Dismiss -> onDismiss()
						is SpaceWorkbenchDropOutcome.Select -> onSelectSpace(outcome.spaceId)
					}
				},
			)
		}
	}
}

@Composable
private fun SpaceWorkbenchDragProxy(
	gestureState: SpaceWorkbenchGestureState,
	activeSpace: SpaceContext?,
	overlayBounds: Rect?,
	settleAnimationDurationMillis: Int,
	onSettled: (SpaceWorkbenchDropOutcome) -> Unit,
) {
	val density = LocalDensity.current
	val proxySize = 58.dp
	val proxyRadiusPx = with(density) { proxySize.toPx() / 2f }
	val animatedX = remember { Animatable(0f) }
	val animatedY = remember { Animatable(0f) }
	var proxyReady by remember { mutableStateOf(false) }
	val proxyPosition = gestureState.proxyPosition
	val phase = gestureState.phase
	val proxyScale by animateFloatAsState(
		targetValue = if (gestureState.hoveredSpaceId != null) 1.12f else 1f,
		animationSpec = if (settleAnimationDurationMillis > 0) tween(durationMillis = 120) else snap(),
		label = "space_workbench_drag_proxy_scale",
	)
	LaunchedEffect(phase, proxyPosition, overlayBounds) {
		val position = proxyPosition
		if (phase == SpaceWorkbenchDragPhase.IDLE || position == null) {
			proxyReady = false
			return@LaunchedEffect
		}
		val rootOffset = overlayBounds?.topLeft ?: Offset.Zero
		val localPosition = position - rootOffset
		when (phase) {
			SpaceWorkbenchDragPhase.IDLE -> Unit
			SpaceWorkbenchDragPhase.DRAGGING -> {
				animatedX.snapTo(localPosition.x)
				animatedY.snapTo(localPosition.y)
				proxyReady = true
			}
			SpaceWorkbenchDragPhase.SETTLING -> {
				if (!proxyReady) {
					animatedX.snapTo(localPosition.x)
					animatedY.snapTo(localPosition.y)
					proxyReady = true
				}
				if (settleAnimationDurationMillis > 0) {
					coroutineScope {
						launch {
							animatedX.animateTo(
								targetValue = localPosition.x,
								animationSpec = tween(settleAnimationDurationMillis),
							)
						}
						launch {
							animatedY.animateTo(
								targetValue = localPosition.y,
								animationSpec = tween(settleAnimationDurationMillis),
							)
						}
					}
				} else {
					animatedX.snapTo(localPosition.x)
					animatedY.snapTo(localPosition.y)
				}
				gestureState.completeSettling()?.let(onSettled)
			}
		}
	}
	if (phase != SpaceWorkbenchDragPhase.IDLE && proxyReady && activeSpace != null) {
		Surface(
			modifier = Modifier
				.offset {
					IntOffset(
						x = (animatedX.value - proxyRadiusPx).roundToInt(),
						y = (animatedY.value - proxyRadiusPx).roundToInt(),
					)
				}
				.size(proxySize)
				.graphicsLayer {
					scaleX = proxyScale
					scaleY = proxyScale
				}
				.clearAndSetSemantics { },
			shape = CircleShape,
			color = MaterialTheme.colorScheme.primaryContainer,
			tonalElevation = 10.dp,
			shadowElevation = 14.dp,
		) {
			Box(contentAlignment = Alignment.Center) {
				SpaceSwitcherIcon(
					activeSpaceId = activeSpace.id,
					activeSpace = activeSpace,
					modifier = Modifier.size(28.dp),
				)
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
	layoutSpec: SpaceWorkbenchLayoutSpec,
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
				.diskCachePolicy(CachePolicy.READ_ONLY)
				.networkCachePolicy(CachePolicy.DISABLED)
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
			if (layoutSpec.showCovers) {
				SpaceWorkbenchCoverPreview(
					context = context,
					coverRequest = coverRequest,
					height = layoutSpec.coverHeight,
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

@Composable
private fun SpaceWorkbenchCoverPreview(
	context: SpaceContext,
	coverRequest: ImageRequest?,
	height: Dp,
) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(height)
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant),
		contentAlignment = Alignment.Center,
	) {
		SpaceSwitcherIcon(
			activeSpaceId = context.id,
			activeSpace = context,
			modifier = Modifier
				.size(32.dp)
				.graphicsLayer { alpha = 0.42f }
				.clearAndSetSemantics { },
		)
		if (coverRequest != null) {
			AsyncImage(
				model = coverRequest,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.matchParentSize(),
			)
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
