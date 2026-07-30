package org.skepsun.kototoro.space.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.core.ui.theme.isDarkTheme
import javax.inject.Inject
import javax.inject.Singleton

enum class SpaceTransitionPhase {
	IDLE,
	COVERING,
	COVERED,
	REVEALING,
}

@Immutable
data class SpaceTransitionState(
	val phase: SpaceTransitionPhase = SpaceTransitionPhase.IDLE,
	val fromSpaceId: SpaceId? = null,
	val targetSpaceId: SpaceId? = null,
	val animated: Boolean = true,
	val showOnTarget: Boolean = true,
) {
	val isVisible: Boolean
		get() = phase != SpaceTransitionPhase.IDLE
}

internal fun isSpaceCurtainRevealHost(
	targetSpaceId: SpaceId?,
	hostSpaceId: SpaceId?,
	activeSpaceId: SpaceId,
): Boolean = targetSpaceId != null && targetSpaceId == hostSpaceId && targetSpaceId == activeSpaceId

@Singleton
class SpaceTransitionCurtainController @Inject constructor() {
	private val coverMutex = Mutex()
	private val revealMutex = Mutex()
	private val mutableState = MutableStateFlow(SpaceTransitionState())
	val state: StateFlow<SpaceTransitionState> = mutableState.asStateFlow()

	suspend fun cover(
		from: SpaceId,
		target: SpaceId,
		animated: Boolean,
		showOnTarget: Boolean = true,
	): Boolean {
		if (from == target || mutableState.value.isVisible) return false
		return coverMutex.withLock {
			if (from == target || mutableState.value.isVisible) return@withLock false
			mutableState.value = SpaceTransitionState(
				phase = SpaceTransitionPhase.COVERING,
				fromSpaceId = from,
				targetSpaceId = target,
				animated = animated,
				showOnTarget = showOnTarget,
			)
			try {
				state.first { it.targetSpaceId != target || it.phase != SpaceTransitionPhase.COVERING }
				mutableState.value.targetSpaceId == target &&
					mutableState.value.phase == SpaceTransitionPhase.COVERED
			} catch (error: Throwable) {
				cancel(target)
				throw error
			}
		}
	}

	suspend fun reveal(target: SpaceId) = revealMutex.withLock {
		val current = mutableState.value
		if (
			current.targetSpaceId != target ||
			current.phase == SpaceTransitionPhase.IDLE ||
			current.phase == SpaceTransitionPhase.REVEALING
		) return@withLock
		mutableState.value = current.copy(phase = SpaceTransitionPhase.REVEALING)
		try {
			state.first { it.targetSpaceId != target || it.phase != SpaceTransitionPhase.REVEALING }
		} finally {
			cancel(target)
		}
	}

	fun markCovered(target: SpaceId) {
		val current = mutableState.value
		if (current.targetSpaceId == target && current.phase == SpaceTransitionPhase.COVERING) {
			mutableState.value = current.copy(phase = SpaceTransitionPhase.COVERED)
		}
	}

	fun markRevealFinished(target: SpaceId) {
		val current = mutableState.value
		if (current.targetSpaceId == target && current.phase == SpaceTransitionPhase.REVEALING) {
			mutableState.value = SpaceTransitionState()
		}
	}

	fun cancel(target: SpaceId) {
		if (mutableState.value.targetSpaceId == target) {
			mutableState.value = SpaceTransitionState()
		}
	}
}

@Composable
fun SpaceTransitionCurtain(
	state: SpaceTransitionState,
	spaces: List<SpaceContext>,
	modifier: Modifier = Modifier,
	allowReveal: Boolean = true,
	isTargetHost: Boolean = allowReveal,
	onCoverFinished: (SpaceId) -> Unit = {},
	onRevealFinished: (SpaceId) -> Unit = {},
) {
	if (!state.isVisible) return
	val hideOnTargetHost = isTargetHost && !state.showOnTarget
	val initialAlpha = when (state.phase) {
		SpaceTransitionPhase.COVERED,
		SpaceTransitionPhase.REVEALING,
		-> if (hideOnTargetHost) 0f else 1f
		else -> 0f
	}
	val alpha = remember { Animatable(initialAlpha) }
	LaunchedEffect(
		state.phase,
		state.targetSpaceId,
		state.animated,
		isTargetHost,
		allowReveal,
		state.showOnTarget,
	) {
		val target = state.targetSpaceId ?: return@LaunchedEffect
		when (state.phase) {
			SpaceTransitionPhase.COVERING -> {
				if (state.animated) {
					alpha.animateTo(1f, tween(SpaceMotion.CurtainCoverMillis))
				} else {
					alpha.snapTo(1f)
				}
				androidx.compose.runtime.withFrameNanos { }
				onCoverFinished(target)
			}
			SpaceTransitionPhase.COVERED -> alpha.snapTo(if (hideOnTargetHost) 0f else 1f)
			SpaceTransitionPhase.REVEALING -> {
				if (hideOnTargetHost) {
					alpha.snapTo(0f)
					if (allowReveal) {
						androidx.compose.runtime.withFrameNanos { }
						onRevealFinished(target)
					}
				} else if (!allowReveal) {
					alpha.snapTo(1f)
				} else {
					if (state.animated) {
						alpha.animateTo(0f, tween(SpaceMotion.CurtainRevealMillis))
					} else {
						alpha.snapTo(0f)
					}
					androidx.compose.runtime.withFrameNanos { }
					onRevealFinished(target)
				}
			}
			SpaceTransitionPhase.IDLE -> alpha.snapTo(0f)
		}
	}
	val from = spaces.firstOrNull { it.id == state.fromSpaceId }
	val target = spaces.firstOrNull { it.id == state.targetSpaceId }
	val fromLabel = state.fromSpaceId?.let { spaceDisplayLabel(it, from) }.orEmpty()
	val targetLabel = state.targetSpaceId?.let { spaceDisplayLabel(it, target) }.orEmpty()
	val description = "$fromLabel → $targetLabel"
	val colorScheme = MaterialTheme.colorScheme
	val isDarkTheme = colorScheme.isDarkTheme()
	val curtainContentColor = if (isDarkTheme) Color.White else colorScheme.onSurface
	Box(
		modifier = modifier
			.fillMaxSize()
			.alpha(alpha.value)
			.background(colorScheme.surface)
			.pointerInput(Unit) {
				awaitPointerEventScope {
					while (true) awaitPointerEvent()
				}
			}
			.semantics { contentDescription = description },
		contentAlignment = Alignment.Center,
	) {
		CompositionLocalProvider(LocalContentColor provides curtainContentColor) {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier.padding(32.dp),
			) {
				Row(
					horizontalArrangement = Arrangement.spacedBy(16.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					state.fromSpaceId?.let { SpaceSwitcherIcon(activeSpaceId = it, activeSpace = from) }
					Text(text = "→", style = MaterialTheme.typography.headlineSmall)
					state.targetSpaceId?.let { SpaceSwitcherIcon(activeSpaceId = it, activeSpace = target) }
				}
				Text(text = description, style = MaterialTheme.typography.titleMedium)
			}
		}
	}
}
