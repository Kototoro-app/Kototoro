package org.skepsun.kototoro.space.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.space.domain.SpaceId
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImmersiveSpaceSessionRegistry @Inject constructor() {

	private val sessions = mutableMapOf<SpaceId, WeakReference<Activity>>()
	private val mutableMainTransitionSuppressionTarget = MutableStateFlow<SpaceId?>(null)
	val mainTransitionSuppressionTarget: StateFlow<SpaceId?> =
		mutableMainTransitionSuppressionTarget.asStateFlow()

	@MainThread
	fun suppressMainTransitionTo(spaceId: SpaceId) {
		mutableMainTransitionSuppressionTarget.value = spaceId
	}

	@MainThread
	fun completeMainTransitionSuppression(spaceId: SpaceId) {
		if (mutableMainTransitionSuppressionTarget.value == spaceId) {
			mutableMainTransitionSuppressionTarget.value = null
		}
	}

	@MainThread
	fun register(spaceId: SpaceId, activity: Activity) {
		sessions[spaceId] = WeakReference(activity)
		(activity as? LifecycleOwner)?.lifecycle?.addObserver(
			object : DefaultLifecycleObserver {
				override fun onDestroy(owner: LifecycleOwner) {
					if (sessions[spaceId]?.get() === activity) {
						sessions.remove(spaceId)
					}
				}
			},
		)
	}

	@MainThread
	fun hasActiveSession(spaceId: SpaceId): Boolean = activeActivity(spaceId) != null

	@MainThread
	fun restore(spaceId: SpaceId, context: Context): Boolean {
		val activity = activeActivity(spaceId) ?: return false
		val callerTaskId = (context as? Activity)?.taskId
		if (activity.taskId != callerTaskId) {
			val appTask = context.getSystemService(ActivityManager::class.java).appTasks
				.firstOrNull { it.taskInfo.taskId == activity.taskId }
				?: return false
			appTask.moveToFront()
			return true
		}
		context.startActivity(
			Intent(activity.intent)
				.setClass(context, activity::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
		)
		return true
	}

	private fun activeActivity(spaceId: SpaceId): Activity? {
		val activity = sessions[spaceId]?.get()
		if (activity == null || activity.isFinishing || activity.isDestroyed) {
			sessions.remove(spaceId)
			return null
		}
		return activity
	}
}
