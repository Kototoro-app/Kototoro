package org.skepsun.kototoro.space.ui

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.SpaceSwitcherPosition
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.animatorDurationScale
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceProgressFlusher
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.domain.SpaceSwitchAvailability
import org.skepsun.kototoro.space.domain.SpaceSwitchCoordinator
import org.skepsun.kototoro.space.domain.SpaceSwitchOrigin
import org.skepsun.kototoro.space.domain.SpaceSwitchResult
import javax.inject.Inject

internal const val EXTRA_IMMERSIVE_SESSION_SPACE_ID =
	"org.skepsun.kototoro.extra.IMMERSIVE_SESSION_SPACE_ID"

class SpaceSwitcherDelegate @Inject constructor(
	private val coordinator: SpaceSwitchCoordinator,
	private val spaceRepository: SpaceRepository,
	private val featureFlagsRepository: SpaceFeatureFlagsRepository,
	private val catalogRepository: SpaceCatalogRepository,
	private val resumeStateSource: SpaceResumeStateSource,
	private val immersiveSessionRegistry: ImmersiveSpaceSessionRegistry,
	private val settings: AppSettings,
	private val transitionController: SpaceTransitionCurtainController,
) {
	private var activity: FragmentActivity? = null
	private var snackbarAnchor: View? = null
	private var origin = SpaceSwitchOrigin.READER
	private var availabilityProvider: () -> SpaceSwitchAvailability = { SpaceSwitchAvailability.UNAVAILABLE }
	private var progressFlusher = SpaceProgressFlusher {}
	private var featureEnabled = false
	private var controlsVisible = false
	private var composeFabVisible by mutableStateOf(false)
	private var composeFabEnabled by mutableStateOf(true)
	private var switcherVisible by mutableStateOf(false)
	private var sessionSpaceId: SpaceId? = null
	private var pendingRevealTarget: SpaceId? = null
	private var transitionDrawn = CompletableDeferred<Unit>()
	private val snackbarHostState = SnackbarHostState()

	fun bind(
		activity: FragmentActivity,
		snackbarAnchor: View,
		origin: SpaceSwitchOrigin,
		availabilityProvider: () -> SpaceSwitchAvailability,
		progressFlusher: SpaceProgressFlusher,
	) {
		this.activity = activity
		this.snackbarAnchor = snackbarAnchor
		this.origin = origin
		this.availabilityProvider = availabilityProvider
		this.progressFlusher = progressFlusher
		ImmersiveSpaceSwitcherTransition.consumeOrigin(activity.intent)
		val sessionSpaceId = immersiveSessionSpaceId(
			rawSpaceId = activity.intent.getStringExtra(EXTRA_IMMERSIVE_SESSION_SPACE_ID),
			fallback = spaceRepository.activeSpace.value,
		)
		this.sessionSpaceId = sessionSpaceId
		immersiveSessionRegistry.register(sessionSpaceId, activity)
		activity.lifecycle.addObserver(
			object : DefaultLifecycleObserver {
					override fun onResume(owner: LifecycleOwner) {
						val immersiveSwitchEnabled =
							featureFlagsRepository.flags.value.effectiveImmersiveSwitchEnabled
						val shouldRestore = shouldRestoreImmersiveSpaceOnResume(
							sessionSpaceId = sessionSpaceId,
							activeSpaceId = spaceRepository.activeSpace.value,
							immersiveSwitchEnabled = immersiveSwitchEnabled,
						switchInProgress = coordinator.state.value.inProgress,
						transitionSuppressionTarget = immersiveSessionRegistry.mainTransitionSuppressionTarget.value,
					)
					if (shouldRestore) {
						activity.lifecycleScope.launch {
							runCatching { spaceRepository.activate(sessionSpaceId) }
						}
					}
				}

					override fun onDestroy(owner: LifecycleOwner) {
					dismissSwitcher()
				}
			},
		)
		featureEnabled = featureFlagsRepository.flags.value.effectiveImmersiveSwitchEnabled
		activity.lifecycleScope.launch {
			activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
				combine(
					featureFlagsRepository.flags,
					spaceRepository.activeSpace,
					coordinator.state,
					catalogRepository.spaces,
					transitionController.state,
				) { flags, activeSpace, switchState, _, transitionState ->
					SwitcherChromeState(
						flags.effectiveImmersiveSwitchEnabled,
						activeSpace,
						switchState.inProgress || transitionState.isVisible,
					)
				}.collect { state ->
					featureEnabled = state.enabled
					refreshMenuItems(state.activeSpace, state.inProgress)
				}
			}
		}
	}

	@Composable
	fun Fab(modifier: Modifier = Modifier) {
		if (!composeFabVisible) return
		val activeSpaceId by spaceRepository.activeSpace.collectAsState()
		val spaces by catalogRepository.spaces.collectAsState()
		val position by settings.observeAsState(AppSettings.KEY_SPACE_SWITCHER_POSITION) {
			spaceSwitcherPosition
		}
		Box(modifier = modifier.fillMaxSize()) {
			SpaceSidekickHandle(
				state = SpaceUiState(
					activeSpaceId = activeSpaceId,
					switcherEnabled = true,
					spaces = spaces,
				),
				onOpen = { if (composeFabEnabled) showSwitcher() },
				position = position,
				modifier = Modifier
					.align(position.handleAlignment())
					.then(
						if (position.isCentered()) {
							Modifier
						} else {
							Modifier.statusBarsPadding().padding(top = 72.dp)
						},
					),
			)
		}
	}

	@Suppress("UNUSED_PARAMETER")
	fun setControlsVisible(
		visible: Boolean,
		hideWithControlsTransition: Boolean = false,
	) {
		controlsVisible = visible
		refreshMenuItems(
			spaceRepository.activeSpace.value,
			coordinator.state.value.inProgress || transitionController.state.value.isVisible,
		)
	}

	fun invalidateAvailability() {
		refreshMenuItems(
			spaceRepository.activeSpace.value,
			coordinator.state.value.inProgress || transitionController.state.value.isVisible,
		)
	}

	private fun showSwitcher() {
		activity ?: return
		if (!featureEnabled || availabilityProvider() == SpaceSwitchAvailability.UNAVAILABLE) return
		if (switcherVisible) return
		switcherVisible = true
	}

	@Composable
	fun Overlays(modifier: Modifier = Modifier) {
		val transitionState by transitionController.state.collectAsState()
		val spaces by catalogRepository.spaces.collectAsState()
		val activeSpaceId by spaceRepository.activeSpace.collectAsState()
		val position by settings.observeAsState(AppSettings.KEY_SPACE_SWITCHER_POSITION) {
			spaceSwitcherPosition
		}
		LaunchedEffect(transitionState.isVisible) {
			if (!transitionState.isVisible) pendingRevealTarget = null
		}
		Box(modifier = modifier.fillMaxSize()) {
			if (switcherVisible) {
				val activity = activity ?: return@Box
				val switchState by coordinator.state.collectAsState()
				val resumeFlow = remember(resumeStateSource) { resumeStateSource.observe() }
				val resumeState by resumeFlow.collectAsState(initial = SpaceResumeUiState())
				SpaceSidekick(
					state = SpaceUiState(
						activeSpaceId = activeSpaceId,
						switcherVisible = true,
						switchInProgress = switchState.inProgress || transitionState.isVisible,
						switcherEnabled = true,
						spaces = spaces,
					),
					onAction = { action ->
						when (action) {
							SpaceAction.DismissSwitcher -> dismissSwitcher()
							SpaceAction.OpenSwitcher -> Unit
							is SpaceAction.SelectSpace -> requestSwitch(action.spaceId)
						}
					},
					resumeItems = resumeState.items,
					onResume = { target ->
						if (target == spaceRepository.activeSpace.value) {
							dismissSwitcher()
							returnToMain(activity, target, resumeReading = true)
						} else {
							requestSwitch(target, resumeReading = true)
						}
					},
					visible = false,
					position = position,
					modifier = Modifier.fillMaxSize(),
				)
			}
			if (transitionState.isVisible) {
				SpaceTransitionCurtain(
					state = transitionState,
					spaces = spaces,
					isTargetHost = transitionState.targetSpaceId == sessionSpaceId,
					allowReveal = isSpaceCurtainRevealHost(
						targetSpaceId = transitionState.targetSpaceId,
						hostSpaceId = sessionSpaceId,
						activeSpaceId = activeSpaceId,
					),
					onCoverFinished = transitionController::markCovered,
					onRevealFinished = transitionController::markRevealFinished,
				)
				LaunchedEffect(transitionState) {
					androidx.compose.runtime.withFrameNanos { }
					if (!transitionDrawn.isCompleted) transitionDrawn.complete(Unit)
					val target = transitionState.targetSpaceId
					if (transitionState.phase == SpaceTransitionPhase.COVERED &&
						target != null && target == sessionSpaceId && target == spaceRepository.activeSpace.value &&
						pendingRevealTarget != target
					) {
						pendingRevealTarget = target
						transitionController.reveal(target)
					}
				}
			}
			SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
		}
	}

	private fun requestSwitch(target: SpaceId, resumeReading: Boolean = false) {
		val activity = activity ?: return
		activity.lifecycleScope.launch {
			val activeSpaceId = spaceRepository.activeSpace.value
			if (activeSpaceId == target || transitionController.state.value.isVisible) return@launch
			immersiveSessionRegistry.suppressMainTransitionTo(target)
			try {
				val animated = !settings.isReducedVisualEffectsEnabled && activity.animatorDurationScale > 0f
				transitionDrawn = CompletableDeferred()
				val covered = transitionController.cover(
					from = activeSpaceId,
					target = target,
					animated = animated,
					showOnTarget = false,
				)
				if (!covered) {
					immersiveSessionRegistry.completeMainTransitionSuppression(target)
					return@launch
				}
				awaitTransitionCurtainDraw()
				dismissSwitcher()
				when (val result = coordinator.requestSwitch(
					target = target,
					origin = origin,
					availability = availabilityProvider(),
					progressFlusher = progressFlusher,
				)) {
					is SpaceSwitchResult.Success -> {
						if (!immersiveSessionRegistry.restore(
							result.targetSpaceId,
							activity,
							suppressAnimation = true,
						)) {
							returnToMain(activity, result.targetSpaceId, resumeReading)
						}
					}
					is SpaceSwitchResult.AlreadyActive -> {
						immersiveSessionRegistry.completeMainTransitionSuppression(target)
						transitionController.reveal(target)
					}
					is SpaceSwitchResult.Failed -> {
						immersiveSessionRegistry.completeMainTransitionSuppression(target)
						transitionController.reveal(target)
						showMessage(R.string.space_switch_failed)
					}
					SpaceSwitchResult.ConfirmationRequired,
					SpaceSwitchResult.Unavailable -> {
						immersiveSessionRegistry.completeMainTransitionSuppression(target)
						transitionController.reveal(target)
						showMessage(R.string.space_switch_unavailable)
					}
				}
			} catch (error: CancellationException) {
				immersiveSessionRegistry.completeMainTransitionSuppression(target)
				transitionController.cancel(target)
				throw error
			}
		}
	}

	private fun dismissSwitcher() {
		if (!switcherVisible) return
		switcherVisible = false
	}

	private suspend fun awaitTransitionCurtainDraw() {
		transitionDrawn.await()
	}

	@Suppress("UNUSED_PARAMETER")
	private fun refreshMenuItems(activeSpaceId: SpaceId, inProgress: Boolean) {
		val available = availabilityProvider() != SpaceSwitchAvailability.UNAVAILABLE
		activity ?: return
		val shouldShowFab = featureEnabled && available && controlsVisible
		composeFabVisible = shouldShowFab
		composeFabEnabled = !inProgress
	}

	private fun showMessage(messageRes: Int) {
		val activity = activity ?: return
		activity.lifecycleScope.launch {
			snackbarHostState.showSnackbar(activity.getString(messageRes), duration = SnackbarDuration.Long)
		}
	}

	private fun returnToMain(
		activity: FragmentActivity,
		targetSpaceId: SpaceId,
		resumeReading: Boolean,
	) {
		val intent = Intent(activity, MainActivity::class.java)
			.addFlags(mainReturnActivityFlags())
			.putExtra(MainActivity.EXTRA_RESTORE_IMMERSIVE_SPACE_ID, targetSpaceId.value)
		resumeSpaceExtraValue(targetSpaceId, resumeReading)?.let { spaceId ->
			intent.putExtra(MainActivity.EXTRA_RESUME_SPACE_ID, spaceId)
		}
		val activityManager = activity.getSystemService(ActivityManager::class.java)
		val mainTask = activityManager.appTasks.firstOrNull { task ->
			task.taskInfo.baseIntent.component?.className == MainActivity::class.java.name
		}
		val options = ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle()
		if (mainTask != null) {
			mainTask.startActivity(activity, intent, options)
		} else {
			activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
		}
	}

}

private fun SpaceSwitcherPosition.isCentered(): Boolean =
	this == SpaceSwitcherPosition.CENTER_LEFT || this == SpaceSwitcherPosition.CENTER_RIGHT

private fun SpaceSwitcherPosition.handleAlignment(): Alignment = when (this) {
	SpaceSwitcherPosition.TOP_LEFT -> Alignment.TopStart
	SpaceSwitcherPosition.TOP_RIGHT -> Alignment.TopEnd
	SpaceSwitcherPosition.CENTER_LEFT -> Alignment.CenterStart
	SpaceSwitcherPosition.CENTER_RIGHT -> Alignment.CenterEnd
}

private data class SwitcherChromeState(
	val enabled: Boolean,
	val activeSpace: SpaceId,
	val inProgress: Boolean,
)

internal fun resumeSpaceExtraValue(targetSpaceId: SpaceId, resumeReading: Boolean): String? =
	targetSpaceId.value.takeIf { resumeReading }

internal fun mainReturnActivityFlags(): Int =
	Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
		Intent.FLAG_ACTIVITY_SINGLE_TOP or
		Intent.FLAG_ACTIVITY_NO_ANIMATION

internal fun immersiveSessionSpaceId(rawSpaceId: String?, fallback: SpaceId): SpaceId =
	rawSpaceId?.takeIf(String::isNotBlank)?.let(::SpaceId) ?: fallback

internal fun shouldRestoreImmersiveSpaceOnResume(
	sessionSpaceId: SpaceId,
	activeSpaceId: SpaceId,
	immersiveSwitchEnabled: Boolean,
	switchInProgress: Boolean,
	transitionSuppressionTarget: SpaceId?,
): Boolean = immersiveSwitchEnabled &&
	!switchInProgress &&
	sessionSpaceId != activeSpaceId &&
	transitionSuppressionTarget != sessionSpaceId
