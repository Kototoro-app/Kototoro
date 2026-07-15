package org.skepsun.kototoro.space.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceProgressFlusher
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.domain.SpaceSwitchAvailability
import org.skepsun.kototoro.space.domain.SpaceSwitchCoordinator
import org.skepsun.kototoro.space.domain.SpaceSwitchOrigin
import org.skepsun.kototoro.space.domain.SpaceSwitchResult
import javax.inject.Inject

class SpaceSwitcherDelegate @Inject constructor(
	private val coordinator: SpaceSwitchCoordinator,
	private val spaceRepository: SpaceRepository,
	private val featureFlagsRepository: SpaceFeatureFlagsRepository,
	private val resumeStateSource: SpaceResumeStateSource,
	private val mediaUniverseStateSource: MediaUniverseStateSource,
	private val immersiveSessionRegistry: ImmersiveSpaceSessionRegistry,
) {
	private var activity: AppCompatActivity? = null
	private var snackbarAnchor: View? = null
	private var origin = SpaceSwitchOrigin.READER
	private var availabilityProvider: () -> SpaceSwitchAvailability = { SpaceSwitchAvailability.UNAVAILABLE }
	private var progressFlusher = SpaceProgressFlusher {}
	private var onMediaUniverseContentClick: (org.skepsun.kototoro.parsers.model.Content) -> Unit = {}
	private var featureEnabled = false
	private var controlsVisible = false
	private val fabs = LinkedHashSet<ExtendedFloatingActionButton>()
	private var switcherOverlay: ComposeView? = null

	fun bind(
		activity: AppCompatActivity,
		snackbarAnchor: View,
		origin: SpaceSwitchOrigin,
		availabilityProvider: () -> SpaceSwitchAvailability,
		progressFlusher: SpaceProgressFlusher,
		onMediaUniverseContentClick: (org.skepsun.kototoro.parsers.model.Content) -> Unit = {},
	) {
		this.activity = activity
		this.snackbarAnchor = snackbarAnchor
		this.origin = origin
		this.availabilityProvider = availabilityProvider
		this.progressFlusher = progressFlusher
		this.onMediaUniverseContentClick = onMediaUniverseContentClick
		immersiveSessionRegistry.register(spaceRepository.activeSpace.value, activity)
		featureEnabled = featureFlagsRepository.flags.value.effectiveImmersiveSwitchEnabled
		activity.lifecycleScope.launch {
			activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
				combine(
					featureFlagsRepository.flags,
					spaceRepository.activeSpace,
					coordinator.state,
				) { flags, activeSpace, switchState ->
					Triple(flags.effectiveImmersiveSwitchEnabled, activeSpace, switchState.inProgress)
				}.collect { (enabled, activeSpace, inProgress) ->
					featureEnabled = enabled
					refreshMenuItems(activeSpace, inProgress)
				}
			}
		}
	}

	fun installFab(fab: ExtendedFloatingActionButton) {
		fabs += fab
		fab.shrink()
		fab.setOnClickListener { showSwitcher() }
		refreshMenuItems(spaceRepository.activeSpace.value, coordinator.state.value.inProgress)
	}

	fun setControlsVisible(visible: Boolean) {
		controlsVisible = visible
		refreshMenuItems(spaceRepository.activeSpace.value, coordinator.state.value.inProgress)
	}

	fun invalidateAvailability() {
		refreshMenuItems(spaceRepository.activeSpace.value, coordinator.state.value.inProgress)
	}

	private fun showSwitcher() {
		val activity = activity ?: return
		if (!featureEnabled || availabilityProvider() == SpaceSwitchAvailability.UNAVAILABLE) return
		if (switcherOverlay != null) return
		mediaUniverseStateSource.loadIfNeeded()
		val overlay = ComposeView(activity).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
		}
		switcherOverlay = overlay
		activity.addContentView(
			overlay,
			ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			),
		)
			overlay.setContent {
			KototoroTheme {
				val activeSpaceId by spaceRepository.activeSpace.collectAsState()
				val switchState by coordinator.state.collectAsState()
				val resumeFlow = remember(resumeStateSource) { resumeStateSource.observe() }
				val resumeState by resumeFlow.collectAsState(initial = SpaceResumeUiState())
				val mediaContentState by mediaUniverseStateSource.state.collectAsState()
				SpaceSwitcherSheet(
					state = SpaceUiState(
						activeSpaceId = activeSpaceId,
						switcherVisible = true,
						switchInProgress = switchState.inProgress,
						switcherEnabled = true,
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
					mediaUniverseState = MediaUniverseUiState(
						visible = true,
						loading = mediaContentState.loading,
						items = mediaContentState.items,
					),
					onMediaUniverseContentClick = { content ->
						dismissSwitcher()
						onMediaUniverseContentClick(content)
					},
				)
			}
		}
	}

	private fun requestSwitch(target: SpaceId, resumeReading: Boolean = false) {
		val activity = activity ?: return
		activity.lifecycleScope.launch {
			when (val result = coordinator.requestSwitch(
				target = target,
				origin = origin,
				availability = availabilityProvider(),
				progressFlusher = progressFlusher,
			)) {
				is SpaceSwitchResult.Success -> {
					dismissSwitcher()
					returnToMain(activity, result.targetSpaceId, resumeReading)
				}
				is SpaceSwitchResult.AlreadyActive -> Unit
				is SpaceSwitchResult.Failed -> showMessage(R.string.space_switch_failed)
				SpaceSwitchResult.ConfirmationRequired,
				SpaceSwitchResult.Unavailable -> showMessage(R.string.space_switch_unavailable)
			}
		}
	}

	private fun dismissSwitcher() {
		val overlay = switcherOverlay ?: return
		switcherOverlay = null
		overlay.disposeComposition()
		(overlay.parent as? ViewGroup)?.removeView(overlay)
	}

	private fun refreshMenuItems(activeSpaceId: SpaceId, inProgress: Boolean) {
		val available = availabilityProvider() != SpaceSwitchAvailability.UNAVAILABLE
		val context = activity ?: return
		val shouldShowFab = featureEnabled && available && controlsVisible
		fabs.forEach { target ->
			target.isEnabled = !inProgress
			target.icon = ContextCompat.getDrawable(context, activeSpaceId.iconRes())
			target.shrink()
			target.contentDescription = context.getString(
				R.string.space_switcher_content_description,
				context.getString(activeSpaceId.labelRes()),
			)
			if (shouldShowFab) {
				target.show()
			} else {
				target.hide()
			}
		}
	}

	private fun showMessage(messageRes: Int) {
		snackbarAnchor?.let { Snackbar.make(it, messageRes, Snackbar.LENGTH_LONG).show() }
	}

	private fun returnToMain(
		activity: AppCompatActivity,
		targetSpaceId: SpaceId,
		resumeReading: Boolean,
	) {
		val intent = Intent(activity, MainActivity::class.java)
			.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
		resumeSpaceExtraValue(targetSpaceId, resumeReading)?.let { spaceId ->
			intent.putExtra(MainActivity.EXTRA_RESUME_SPACE_ID, spaceId)
		}
		activity.startActivity(
			intent,
		)
	}
}

internal fun resumeSpaceExtraValue(targetSpaceId: SpaceId, resumeReading: Boolean): String? =
	targetSpaceId.value.takeIf { resumeReading }

private fun SpaceId.iconRes(): Int = when (this) {
	BuiltInSpaces.Novel -> R.drawable.ic_content_novel
	BuiltInSpaces.Anime -> R.drawable.ic_content_video
	else -> R.drawable.ic_content_manga
}

private fun SpaceId.labelRes(): Int = when (this) {
	BuiltInSpaces.Novel -> R.string.space_novel
	BuiltInSpaces.Anime -> R.string.space_anime
	else -> R.string.space_manga
}

private fun org.skepsun.kototoro.space.domain.SpaceContext.labelRes(): Int = id.labelRes()
