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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
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
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.reader.ui.EmbeddedReaderCockpitState
import org.skepsun.kototoro.space.domain.BuiltInSpaces
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

internal data class SpaceCockpitLayoutSpec(
	val workspaceScale: Float,
	val isLandscape: Boolean,
) {
	val workbenchFraction: Float
		get() = 1f - workspaceScale
}

internal fun resolveSpaceCockpitLayoutSpec(
	availableWidth: Dp,
	availableHeight: Dp,
): SpaceCockpitLayoutSpec = SpaceCockpitLayoutSpec(
	workspaceScale = when {
		availableWidth >= 840.dp -> 0.88f
		availableWidth > availableHeight -> 0.80f
		else -> 0.82f
	},
	isLandscape = availableWidth > availableHeight,
)

/**
 * The Cockpit owns one continuous, opaque Material surface. It deliberately avoids Haze and
 * Backdrop so the L-shaped workspace stays visually stable across interface styles.
 */
@Composable
internal fun SpaceCockpitMaterialLayer(
	layoutSpec: SpaceCockpitLayoutSpec,
	modifier: Modifier = Modifier,
) {
	val shape = remember(layoutSpec.workspaceScale) {
		GenericShape { size, _ ->
			val contentRight = size.width * layoutSpec.workspaceScale
			val contentTop = size.height * layoutSpec.workbenchFraction
			moveTo(0f, 0f)
			lineTo(size.width, 0f)
			lineTo(size.width, size.height)
			lineTo(contentRight, size.height)
			lineTo(contentRight, contentTop)
			lineTo(0f, contentTop)
			close()
		}
	}
	Surface(
		modifier = modifier,
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
	) {}
}

@Composable
internal fun ImmersiveCockpitCommandBar(
	isLandscape: Boolean,
	onHome: () -> Unit,
	onSpaces: () -> Unit,
	onUnpin: () -> Unit,
) {
	Surface(
		modifier = Modifier.fillMaxSize(),
		color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
	) {
		val content: @Composable () -> Unit = {
			CockpitCommandButton(R.drawable.ic_home, R.string.home, onHome)
			CockpitCommandButton(R.drawable.ic_list, R.string.space_switcher_title, onSpaces)
			CockpitCommandButton(R.drawable.ic_unpin, R.string.unpin, onUnpin)
		}
		if (isLandscape) {
			Row(
				modifier = Modifier.fillMaxSize().statusBarsPadding(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) { content() }
		} else {
			LazyColumn(
				modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.Bottom,
			) { item { content() } }
		}
	}
}

@Composable
internal fun CockpitCommandRail(
	pageContext: CockpitPageContext,
	commands: List<CockpitCommand>,
	isLandscape: Boolean,
	modifier: Modifier = Modifier,
) {
	if (isLandscape) {
		LazyRow(
			modifier = modifier.fillMaxSize(),
			contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			items(commands, key = { "${pageContext.name}:${it.id}" }) { command ->
				CockpitContextCommandButton(command)
			}
		}
	} else {
		LazyColumn(
			modifier = modifier.fillMaxSize(),
			contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
			verticalArrangement = Arrangement.Bottom,
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			items(commands, key = { "${pageContext.name}:${it.id}" }) { command ->
				CockpitContextCommandButton(command)
			}
		}
	}
}

@Composable
internal fun CockpitReaderProgressBar(
	state: EmbeddedReaderCockpitState,
	onSeek: (Int) -> Unit,
	onBack: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val pageCount = state.pageCount.coerceAtLeast(1)
	var pendingPage by remember(state.pageCount) { mutableStateOf<Float?>(null) }
	val displayedPage = pendingPage ?: state.page.toFloat().coerceIn(0f, (pageCount - 1).toFloat())
	Row(
		modifier = modifier
			.background(MaterialTheme.colorScheme.surfaceContainerHigh)
			.padding(horizontal = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
			Icon(
				painter = painterResource(R.drawable.ic_arrow_forward),
				contentDescription = stringResource(R.string.back),
				modifier = Modifier.graphicsLayer { rotationZ = 180f },
			)
		}
		Text(
			text = "${displayedPage.roundToInt() + 1} / $pageCount",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
		)
		Slider(
			value = displayedPage,
			onValueChange = { pendingPage = it },
			onValueChangeFinished = {
				pendingPage?.roundToInt()?.let(onSeek)
				pendingPage = null
			},
			valueRange = 0f..(pageCount - 1).toFloat().coerceAtLeast(1f),
			enabled = state.pageCount > 1,
			modifier = Modifier.weight(1f),
		)
	}
}

private enum class CockpitShelfPage {
	HISTORY,
	UPDATES,
	RECOMMENDATIONS,
}

/**
 * Compact content shelf above the command rail. Every page is backed by local observable data and
 * opening it never initiates an update check or cover network request.
 */
@Composable
internal fun CockpitContentShelf(
	activeSpaceId: SpaceId,
	historyItems: Map<SpaceId, List<SpaceResumeItem>>,
	updateItems: List<SpaceUpdateItem>,
	recommendationItems: Map<SpaceId, List<Content>>,
	onOpenUpdate: (Content) -> Unit,
	isLandscape: Boolean,
	modifier: Modifier = Modifier,
) {
	val activeHistory = historyItems[activeSpaceId].orEmpty()
	val activeUpdates = updateItems.filter { it.spaceId == activeSpaceId }
	val activeRecommendations = recommendationItems[activeSpaceId].orEmpty()
	val pages = remember { CockpitShelfPage.entries }
	val pagerState = rememberPagerState(pageCount = { pages.size })
	Column(modifier = modifier) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 8.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
		) {
			pages.forEachIndexed { index, _ ->
				Box(
					Modifier
						.size(if (pagerState.currentPage == index) 6.dp else 4.dp)
						.background(
							if (pagerState.currentPage == index) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
							},
							CircleShape,
						),
				)
			}
		}
		HorizontalPager(
			state = pagerState,
			modifier = Modifier.fillMaxSize(),
			key = { pages[it].name },
		) { pageIndex ->
			val shelfItems = when (pages[pageIndex]) {
				CockpitShelfPage.HISTORY -> activeHistory.map { item ->
					CockpitShelfEntry(
						id = cockpitShelfContentKey(item.spaceId, item.content),
						title = item.title,
						content = item.content,
						enabled = item.canResume,
						onClick = { onOpenUpdate(item.content) },
					)
				}
				CockpitShelfPage.UPDATES -> activeUpdates.map { item ->
					CockpitShelfEntry(
						id = cockpitShelfContentKey(item.spaceId, item.content),
						title = item.title,
						content = item.content,
						enabled = true,
						onClick = { onOpenUpdate(item.content) },
					)
				}
				CockpitShelfPage.RECOMMENDATIONS -> activeRecommendations.map { content ->
						CockpitShelfEntry(
							id = cockpitShelfContentKey(activeSpaceId, content),
							title = content.title,
							content = content,
							enabled = true,
							onClick = { onOpenUpdate(content) },
						)
				}
			}
			if (isLandscape) {
				LazyRow(
					modifier = Modifier.fillMaxSize(),
					contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					items(shelfItems, key = { "${pages[pageIndex].name}:${it.id}" }) { item ->
						CockpitShelfCard(item, Modifier.width(72.dp).fillMaxHeight())
					}
				}
			} else {
				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					items(shelfItems, key = { "${pages[pageIndex].name}:${it.id}" }) { item ->
						CockpitShelfCard(item, Modifier.fillMaxWidth().height(76.dp))
					}
				}
			}
		}
	}
}

internal fun cockpitShelfContentKey(spaceId: SpaceId, content: Content): String =
	cockpitShelfContentKey(spaceId, content.source.name, content.id)

internal fun cockpitShelfContentKey(spaceId: SpaceId, sourceName: String, contentId: Long): String =
	"${spaceId.value}:$sourceName:$contentId"

private data class CockpitShelfEntry(
	val id: String,
	val title: String,
	val content: Content,
	val enabled: Boolean,
	val onClick: () -> Unit,
)

@Composable
private fun CockpitShelfCard(
	item: CockpitShelfEntry,
	modifier: Modifier,
) {
	val coverRequest = rememberWorkbenchCoverRequest(item.content)
	Surface(
		onClick = item.onClick,
		enabled = item.enabled,
		modifier = modifier,
		shape = RoundedCornerShape(12.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
	) {
		Box(Modifier.fillMaxSize()) {
			if (coverRequest != null) {
				AsyncImage(
					model = coverRequest,
					contentDescription = item.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			}
			Box(
				Modifier
					.fillMaxSize()
					.background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.76f)))),
			)
			Text(
				text = item.title,
				style = MaterialTheme.typography.labelSmall,
				color = Color.White,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
			)
		}
	}
}

@Composable
private fun CockpitContextCommandButton(command: CockpitCommand) {
	IconButton(
		onClick = command.onClick,
		enabled = command.enabled,
		modifier = Modifier
			.size(64.dp)
			.semantics { selected = command.selected },
	) {
		Icon(
			painter = painterResource(command.iconRes),
			contentDescription = stringResource(command.titleRes),
			tint = if (command.selected) {
				MaterialTheme.colorScheme.primary
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
			modifier = Modifier.graphicsLayer { rotationZ = command.iconRotationDegrees },
		)
	}
}

@Composable
private fun CockpitCommandButton(
	iconRes: Int,
	labelRes: Int,
	onClick: () -> Unit,
) {
	IconButton(
		onClick = onClick,
		modifier = Modifier.size(64.dp),
	) {
		Icon(
			painter = painterResource(iconRes),
			contentDescription = stringResource(labelRes),
			tint = MaterialTheme.colorScheme.onSurface,
		)
	}
}

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
	onPin: () -> Unit,
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
	val overlayVisible = state.workbenchMode == SpaceWorkbenchMode.OVERLAY
	BackHandler(enabled = overlayVisible, onBack = onDismiss)
	AnimatedVisibility(
		visible = overlayVisible,
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
					Row(
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = stringResource(R.string.space_workbench_title),
							style = MaterialTheme.typography.titleSmall,
							modifier = Modifier.padding(start = 6.dp).weight(1f),
						)
						IconButton(onClick = onPin) {
							Icon(
								painter = painterResource(R.drawable.ic_pin),
								contentDescription = stringResource(R.string.pin),
							)
						}
					}
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

/**
 * Persistent top edge of the L-shaped Cockpit. It deliberately uses semantic previews instead of
 * live page snapshots so pinning the workbench never mounts another navigation host.
 */
@Composable
internal fun SpaceCockpitTopStrip(
	state: SpaceUiState,
	resumeItems: Map<SpaceId, SpaceResumeItem>,
	onUnpin: () -> Unit,
	onSelectSpace: (SpaceId) -> Unit,
	modifier: Modifier = Modifier,
	materialBacked: Boolean = false,
) {
	Surface(
		modifier = modifier,
		shape = RectangleShape,
		color = if (materialBacked) Color.Transparent else {
			MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)
		},
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
	) {
		Row(
			modifier = Modifier
				.fillMaxSize()
				.statusBarsPadding()
				.padding(start = 12.dp, end = 8.dp, bottom = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			LazyRow(
				modifier = Modifier.weight(1f),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				items(state.spaces, key = { it.id.value }) { context ->
					val selected = context.id == state.activeSpaceId
					val resumeItem = resumeItems[context.id]
					val coverRequest = rememberWorkbenchCoverRequest(resumeItem)
					Surface(
						onClick = {
							if (!selected && !state.switchInProgress) onSelectSpace(context.id)
						},
						modifier = Modifier
							.width(88.dp)
							.height(80.dp),
						shape = RoundedCornerShape(18.dp),
						color = MaterialTheme.colorScheme.surfaceContainerHigh,
						border = if (selected) {
							androidx.compose.foundation.BorderStroke(
								1.5.dp,
								MaterialTheme.colorScheme.primary,
							)
						} else {
							null
						},
					) {
						Box(
							modifier = Modifier.fillMaxSize(),
							contentAlignment = Alignment.Center,
						) {
							SpaceSwitcherIcon(
								activeSpaceId = context.id,
								activeSpace = context,
								modifier = Modifier
									.size(30.dp)
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
							Box(
								modifier = Modifier
									.matchParentSize()
									.background(
										Brush.verticalGradient(
											listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
										),
									),
							)
							Column(
								modifier = Modifier
									.align(Alignment.BottomStart)
									.padding(horizontal = 8.dp, vertical = 7.dp),
							) {
								Text(
									text = cockpitSpaceDisplayLabel(context),
									style = MaterialTheme.typography.labelLarge,
									color = Color.White,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = resumeItem?.title
										?: stringResource(R.string.space_workbench_empty_preview),
									style = MaterialTheme.typography.labelSmall,
									color = Color.White.copy(alpha = 0.78f),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
						}
					}
				}
			}
			IconButton(onClick = onUnpin) {
				Icon(
					painter = painterResource(R.drawable.ic_unpin),
					contentDescription = stringResource(R.string.unpin),
				)
			}
		}
	}
}

@Composable
private fun cockpitSpaceDisplayLabel(context: SpaceContext): String = context.title ?: when (context.id) {
	BuiltInSpaces.Manga -> stringResource(R.string.manga)
	BuiltInSpaces.Novel -> stringResource(R.string.novel)
	BuiltInSpaces.Anime -> stringResource(R.string.video)
	else -> spaceDisplayLabel(context.id, context)
}

@Composable
internal fun SpaceCockpitSideStrip(
	state: SpaceUiState,
	resumeItems: Map<SpaceId, SpaceResumeItem>,
	onUnpin: () -> Unit,
	onSelectSpace: (SpaceId) -> Unit,
	modifier: Modifier = Modifier,
	materialBacked: Boolean = false,
) {
	Surface(
		modifier = modifier,
		shape = RectangleShape,
		color = if (materialBacked) Color.Transparent else {
			MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)
		},
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
	) {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.statusBarsPadding()
				.navigationBarsPadding()
				.padding(horizontal = 8.dp, vertical = 10.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			IconButton(onClick = onUnpin) {
				Icon(
					painter = painterResource(R.drawable.ic_unpin),
					contentDescription = stringResource(R.string.unpin),
				)
			}
			LazyColumn(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				items(state.spaces, key = { it.id.value }) { context ->
					val selected = context.id == state.activeSpaceId
					val coverRequest = rememberWorkbenchCoverRequest(resumeItems[context.id])
					Surface(
						onClick = {
							if (!selected && !state.switchInProgress) onSelectSpace(context.id)
						},
						modifier = Modifier.fillMaxWidth().height(92.dp),
						shape = RoundedCornerShape(16.dp),
						color = MaterialTheme.colorScheme.surfaceContainerHigh,
						border = if (selected) {
							androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
						} else {
							null
						},
					) {
						Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
							SpaceSwitcherIcon(
								activeSpaceId = context.id,
								activeSpace = context,
								modifier = Modifier.size(30.dp).graphicsLayer { alpha = 0.42f },
							)
							if (coverRequest != null) {
								AsyncImage(
									model = coverRequest,
									contentDescription = null,
									contentScale = ContentScale.Crop,
									modifier = Modifier.matchParentSize(),
								)
							}
							Box(
								Modifier.matchParentSize().background(
									Brush.verticalGradient(
										listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
									),
								),
							)
							Text(
								text = cockpitSpaceDisplayLabel(context),
								style = MaterialTheme.typography.labelLarge,
								color = Color.White,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
							)
						}
					}
				}
			}
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
	val coverRequest = rememberWorkbenchCoverRequest(resumeItem)
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
private fun rememberWorkbenchCoverRequest(resumeItem: SpaceResumeItem?): ImageRequest? {
	return rememberWorkbenchCoverRequest(resumeItem?.content)
}

@Composable
private fun rememberWorkbenchCoverRequest(content: Content?): ImageRequest? {
	val localContext = LocalContext.current
	val coverUrl = content?.coverUrl?.takeIf { it.isNotBlank() }
	return remember(localContext, content?.id, coverUrl) {
		content?.takeIf { coverUrl != null }?.let {
			val cacheKey = contentCoverCacheKey(it, coverUrl)
			ImageRequest.Builder(localContext)
				.data(coverUrl)
				.memoryCacheKey(cacheKey)
				.diskCacheKey(cacheKey)
				.apply { mangaExtra(it) }
				.diskCachePolicy(CachePolicy.READ_ONLY)
				.networkCachePolicy(CachePolicy.DISABLED)
				.build()
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
