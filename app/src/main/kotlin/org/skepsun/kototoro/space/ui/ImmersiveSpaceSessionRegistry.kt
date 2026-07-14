package org.skepsun.kototoro.space.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.skepsun.kototoro.space.domain.SpaceId
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImmersiveSpaceSessionRegistry @Inject constructor() {

	private val sessions = mutableMapOf<SpaceId, WeakReference<Activity>>()

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
		context.startActivity(
			Intent(context, activity::class.java)
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
