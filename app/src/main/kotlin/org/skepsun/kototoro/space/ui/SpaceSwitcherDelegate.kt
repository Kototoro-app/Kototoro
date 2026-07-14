package org.skepsun.kototoro.space.ui

import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
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
import java.util.WeakHashMap

class SpaceSwitcherDelegate @Inject constructor(
	private val coordinator: SpaceSwitchCoordinator,
	private val spaceRepository: SpaceRepository,
	private val featureFlagsRepository: SpaceFeatureFlagsRepository,
) {
	private val menuItems = WeakHashMap<Toolbar, MenuItem>()
	private var activity: AppCompatActivity? = null
	private var snackbarAnchor: View? = null
	private var origin = SpaceSwitchOrigin.READER
	private var availabilityProvider: () -> SpaceSwitchAvailability = { SpaceSwitchAvailability.UNAVAILABLE }
	private var progressFlusher = SpaceProgressFlusher {}
	private var onSwitchComplete: (SpaceId) -> Unit = {}
	private var featureEnabled = false

	fun bind(
		activity: AppCompatActivity,
		snackbarAnchor: View,
		origin: SpaceSwitchOrigin,
		availabilityProvider: () -> SpaceSwitchAvailability,
		progressFlusher: SpaceProgressFlusher,
		onSwitchComplete: (SpaceId) -> Unit = { returnToMain(activity) },
	) {
		this.activity = activity
		this.snackbarAnchor = snackbarAnchor
		this.origin = origin
		this.availabilityProvider = availabilityProvider
		this.progressFlusher = progressFlusher
		this.onSwitchComplete = onSwitchComplete
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

	fun install(toolbar: Toolbar) {
		val item = toolbar.menu.findItem(R.id.action_space_switcher) ?: toolbar.menu.add(
			Menu.NONE,
			R.id.action_space_switcher,
			Menu.FIRST,
			R.string.space_switcher_title,
		).apply {
			setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		}
		menuItems[toolbar] = item
		refreshMenuItems(spaceRepository.activeSpace.value, coordinator.state.value.inProgress)
	}

	fun onMenuItemSelected(item: MenuItem): Boolean {
		if (item.itemId != R.id.action_space_switcher) return false
		showSwitcher()
		return true
	}

	fun invalidateAvailability() {
		refreshMenuItems(spaceRepository.activeSpace.value, coordinator.state.value.inProgress)
	}

	private fun showSwitcher() {
		val activity = activity ?: return
		if (!featureEnabled || availabilityProvider() == SpaceSwitchAvailability.UNAVAILABLE) return
		val contexts = BuiltInSpaces.contexts
		val labels = contexts.map { context -> activity.getString(context.labelRes()) }.toTypedArray()
		val selected = contexts.indexOfFirst { it.id == spaceRepository.activeSpace.value }
		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(R.string.space_switcher_title)
			.setSingleChoiceItems(labels, selected) { dialog, which ->
				dialog.dismiss()
				val target = contexts[which].id
				activity.lifecycleScope.launch {
					when (val result = coordinator.requestSwitch(
						target = target,
						origin = origin,
						availability = availabilityProvider(),
						progressFlusher = progressFlusher,
					)) {
						is SpaceSwitchResult.Success -> onSwitchComplete(result.targetSpaceId)
						is SpaceSwitchResult.AlreadyActive -> Unit
						is SpaceSwitchResult.Failed -> showMessage(R.string.space_switch_failed)
						SpaceSwitchResult.ConfirmationRequired,
						SpaceSwitchResult.Unavailable -> showMessage(R.string.space_switch_unavailable)
					}
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.create()
		dialog.show()
	}

	private fun refreshMenuItems(activeSpaceId: SpaceId, inProgress: Boolean) {
		val available = availabilityProvider() != SpaceSwitchAvailability.UNAVAILABLE
		val context = activity ?: return
		menuItems.values.forEach { item ->
			item.isVisible = featureEnabled && available
			item.isEnabled = !inProgress
			item.icon = ContextCompat.getDrawable(context, activeSpaceId.iconRes())
			item.title = context.getString(
				R.string.space_switcher_content_description,
				context.getString(activeSpaceId.labelRes()),
			)
		}
	}

	private fun showMessage(messageRes: Int) {
		snackbarAnchor?.let { Snackbar.make(it, messageRes, Snackbar.LENGTH_LONG).show() }
	}

	private fun returnToMain(activity: AppCompatActivity) {
		activity.startActivity(
			Intent(activity, MainActivity::class.java).addFlags(
				Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
			),
		)
		activity.finish()
	}
}

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
