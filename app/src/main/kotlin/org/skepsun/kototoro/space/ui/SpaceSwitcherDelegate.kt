package org.skepsun.kototoro.space.ui

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.transition.Slide
import androidx.transition.TransitionSet
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.animatorDurationScale
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.space.domain.BuiltInSpaces
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
import kotlin.coroutines.resume

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
	private var activity: AppCompatActivity? = null
	private var snackbarAnchor: View? = null
	private var origin = SpaceSwitchOrigin.READER
	private var availabilityProvider: () -> SpaceSwitchAvailability = { SpaceSwitchAvailability.UNAVAILABLE }
	private var progressFlusher = SpaceProgressFlusher {}
	private var featureEnabled = false
	private var controlsVisible = false
	private var hideWithControlsTransition = false
	private var launchOrigin: android.graphics.PointF? = null
	private val fabs = LinkedHashSet<ExtendedFloatingActionButton>()
	private val fabOverlays = LinkedHashMap<ExtendedFloatingActionButton, ComposeView>()
	private var switcherOverlay: ComposeView? = null
	private var transitionOverlay: ComposeView? = null
	private var sessionSpaceId: SpaceId? = null
	private var pendingRevealTarget: SpaceId? = null

	fun bind(
		activity: AppCompatActivity,
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
		launchOrigin = ImmersiveSpaceSwitcherTransition.consumeOrigin(activity.intent)
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
					dismissTransitionOverlay()
					fabOverlays.keys.toList().forEach(::removeFabOverlay)
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
		activity.lifecycleScope.launch {
			activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
				transitionController.state.collect(::updateTransitionOverlay)
			}
		}
	}

	fun installFab(fab: ExtendedFloatingActionButton) {
		fabs += fab
		fab.shrink()
		fab.setOnClickListener { showSwitcher() }
		fab.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncFabOverlay(fab) }
		refreshMenuItems(
			spaceRepository.activeSpace.value,
			coordinator.state.value.inProgress || transitionController.state.value.isVisible,
		)
	}

	fun setControlsVisible(
		visible: Boolean,
		hideWithControlsTransition: Boolean = false,
	) {
		controlsVisible = visible
		this.hideWithControlsTransition = !visible && hideWithControlsTransition
		refreshMenuItems(
			spaceRepository.activeSpace.value,
			coordinator.state.value.inProgress || transitionController.state.value.isVisible,
		)
	}

	fun addControlsHideTransition(transition: TransitionSet) {
		fabs.forEach { target ->
			transition.addTransition(
				Slide(android.view.Gravity.BOTTOM)
					.addTarget(target)
					.setDuration(
						target.resources.getInteger(android.R.integer.config_shortAnimTime).toLong(),
					),
			)
		}
	}

	fun invalidateAvailability() {
		refreshMenuItems(
			spaceRepository.activeSpace.value,
			coordinator.state.value.inProgress || transitionController.state.value.isVisible,
		)
	}

	private fun showSwitcher() {
		val activity = activity ?: return
		if (!featureEnabled || availabilityProvider() == SpaceSwitchAvailability.UNAVAILABLE) return
		if (switcherOverlay != null) return
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
				val transitionState by transitionController.state.collectAsState()
				val resumeFlow = remember(resumeStateSource) { resumeStateSource.observe() }
				val resumeState by resumeFlow.collectAsState(initial = SpaceResumeUiState())
				SpaceSwitcherSheet(
					state = SpaceUiState(
						activeSpaceId = activeSpaceId,
						switcherVisible = true,
						switchInProgress = switchState.inProgress || transitionState.isVisible,
						switcherEnabled = true,
						spaces = catalogRepository.spaces.value,
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
				)
			}
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
				updateTransitionOverlay(transitionController.state.value)
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
		val overlay = switcherOverlay ?: return
		switcherOverlay = null
		overlay.disposeComposition()
		(overlay.parent as? ViewGroup)?.removeView(overlay)
	}

	private fun updateTransitionOverlay(state: SpaceTransitionState) {
		if (!state.isVisible) {
			dismissTransitionOverlay()
			return
		}
		val activity = activity ?: return
		val overlay = transitionOverlay ?: ComposeView(activity).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
			activity.addContentView(
				this,
				ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT,
				),
			)
			setContent {
				KototoroTheme {
					val transitionState by transitionController.state.collectAsState()
					val spaces by catalogRepository.spaces.collectAsState()
					val activeSpaceId by spaceRepository.activeSpace.collectAsState()
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
				}
			}
		}.also { transitionOverlay = it }
		overlay.bringToFront()
		val target = state.targetSpaceId
		if (
			state.phase == SpaceTransitionPhase.COVERED &&
			target != null &&
			target == sessionSpaceId &&
			target == spaceRepository.activeSpace.value &&
			pendingRevealTarget != target
		) {
			pendingRevealTarget = target
			overlay.doOnPreDraw {
				activity.lifecycleScope.launch { transitionController.reveal(target) }
			}
		}
	}

	private fun dismissTransitionOverlay() {
		pendingRevealTarget = null
		val overlay = transitionOverlay ?: return
		transitionOverlay = null
		overlay.disposeComposition()
		(overlay.parent as? ViewGroup)?.removeView(overlay)
	}

	private suspend fun awaitTransitionCurtainDraw() {
		val overlay = transitionOverlay ?: return
		suspendCancellableCoroutine { continuation ->
			overlay.doOnPreDraw {
				if (continuation.isActive) continuation.resume(Unit)
			}
		}
	}

	private fun refreshMenuItems(activeSpaceId: SpaceId, inProgress: Boolean) {
		val available = availabilityProvider() != SpaceSwitchAvailability.UNAVAILABLE
		val context = activity ?: return
		val shouldShowFab = featureEnabled && available && controlsVisible
		val activeSpace = catalogRepository.find(activeSpaceId)
		fabs.forEach { target ->
			val isIosStyle = settings.interfaceStyle == org.skepsun.kototoro.core.prefs.InterfaceStyle.IOS
			target.isEnabled = !inProgress
			target.icon = activeSpace?.customMonogram()?.let { monogram ->
				SpaceMonogramDrawable(context, monogram)
			} ?: ContextCompat.getDrawable(context, activeSpace?.kind?.iconRes() ?: activeSpaceId.iconRes())
			target.shrink()
			target.contentDescription = context.getString(
				R.string.space_switcher_content_description,
				activeSpace?.title ?: context.getString(activeSpaceId.labelRes()),
			)
			applyFabStyle(target, isIosStyle)
			if (shouldShowFab) {
				target.bringToFront()
				target.show()
				animateFromLaunchOrigin(target)
			} else if (hideWithControlsTransition) {
				target.visibility = View.GONE
			} else {
				target.hide()
			}
			syncFabOverlay(target)
		}
	}

	private fun applyFabStyle(fab: ExtendedFloatingActionButton, isIosStyle: Boolean) {
		if (isIosStyle) {
			fab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
			fab.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
			fab.elevation = 0f
			ensureFabOverlay(fab)
		} else {
			removeFabOverlay(fab)
		}
	}

	private fun ensureFabOverlay(fab: ExtendedFloatingActionButton) {
		if (fabOverlays.containsKey(fab)) return
		val activity = activity ?: return
		val parent = fab.parent as? ViewGroup ?: return
		val overlay = ComposeView(activity).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
			isClickable = false
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			setContent {
				KototoroTheme {
					val activeSpaceId by spaceRepository.activeSpace.collectAsState()
					val spaces by catalogRepository.spaces.collectAsState()
					ViewSpaceSwitcherFab(
						activeSpaceId = activeSpaceId,
						activeSpace = spaces.firstOrNull { it.id == activeSpaceId },
					)
				}
			}
		}
		parent.addView(overlay)
		fabOverlays[fab] = overlay
		syncFabOverlay(fab)
	}

	private fun syncFabOverlay(fab: ExtendedFloatingActionButton) {
		val overlay = fabOverlays[fab] ?: return
		val parent = fab.parent as? ViewGroup ?: return
		if (overlay.parent !== parent) return
		overlay.layoutParams = overlay.layoutParams.apply {
			width = fab.width
			height = fab.height
		}
		overlay.x = fab.left.toFloat()
		overlay.y = fab.top.toFloat()
		overlay.visibility = fab.visibility
		overlay.alpha = fab.alpha
		fab.bringToFront()
	}

	private fun removeFabOverlay(fab: ExtendedFloatingActionButton) {
		val overlay = fabOverlays.remove(fab) ?: return
		overlay.disposeComposition()
		(overlay.parent as? ViewGroup)?.removeView(overlay)
	}

	private fun animateFromLaunchOrigin(target: ExtendedFloatingActionButton) {
		val origin = launchOrigin ?: return
		if (!target.isLaidOut) {
			target.doOnLayout { animateFromLaunchOrigin(target) }
			return
		}
		launchOrigin = null
		val location = IntArray(2)
		target.getLocationOnScreen(location)
		val targetCenterX = location[0] + target.width / 2f
		val targetCenterY = location[1] + target.height / 2f
		target.animate().cancel()
		target.translationX = origin.x - targetCenterX
		target.translationY = origin.y - targetCenterY
		target.animate()
			.translationX(0f)
			.translationY(0f)
			.setDuration(target.resources.getInteger(android.R.integer.config_mediumAnimTime).toLong())
			.setInterpolator(AnimationUtils.loadInterpolator(target.context, android.R.interpolator.fast_out_slow_in))
			.start()
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
			.addFlags(
				Intent.FLAG_ACTIVITY_CLEAR_TOP or
					Intent.FLAG_ACTIVITY_SINGLE_TOP or
					Intent.FLAG_ACTIVITY_NO_ANIMATION,
			)
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

private data class SwitcherChromeState(
	val enabled: Boolean,
	val activeSpace: SpaceId,
	val inProgress: Boolean,
)

internal fun resumeSpaceExtraValue(targetSpaceId: SpaceId, resumeReading: Boolean): String? =
	targetSpaceId.value.takeIf { resumeReading }

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

private fun SpaceId.iconRes(): Int = when (this) {
	BuiltInSpaces.Novel -> R.drawable.ic_content_novel
	BuiltInSpaces.Anime -> R.drawable.ic_content_video
	else -> R.drawable.ic_content_manga
}

private fun org.skepsun.kototoro.space.domain.SpaceKind.iconRes(): Int = when (this) {
	org.skepsun.kototoro.space.domain.SpaceKind.NOVEL -> R.drawable.ic_content_novel
	org.skepsun.kototoro.space.domain.SpaceKind.ANIME -> R.drawable.ic_content_video
	org.skepsun.kototoro.space.domain.SpaceKind.MANGA -> R.drawable.ic_content_manga
}

private fun SpaceId.labelRes(): Int = when (this) {
	BuiltInSpaces.Novel -> R.string.space_novel
	BuiltInSpaces.Anime -> R.string.space_anime
	else -> R.string.space_manga
}

private class SpaceMonogramDrawable(context: Context, private val monogram: String) : Drawable() {
	private val size = (24f * context.resources.displayMetrics.density).toInt()
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		textAlign = Paint.Align.CENTER
		textSize = TypedValue.applyDimension(
			TypedValue.COMPLEX_UNIT_SP,
			18f,
			context.resources.displayMetrics,
		)
		typeface = Typeface.DEFAULT_BOLD
	}

	override fun draw(canvas: Canvas) {
		val baseline = bounds.exactCenterY() - (paint.ascent() + paint.descent()) / 2f
		canvas.drawText(monogram, bounds.exactCenterX(), baseline, paint)
	}

	override fun setAlpha(alpha: Int) {
		paint.alpha = alpha
		invalidateSelf()
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		paint.colorFilter = colorFilter
		invalidateSelf()
	}

	override fun setTint(tintColor: Int) {
		paint.color = tintColor
		invalidateSelf()
	}

	@Deprecated("Deprecated in Android")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	override fun getIntrinsicWidth(): Int = size
	override fun getIntrinsicHeight(): Int = size
}
