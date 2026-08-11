package com.lagradost.cloudstream3

import android.app.Activity
import androidx.annotation.MainThread
import java.lang.ref.WeakReference

/** Minimal activity bridge for plugins compiled against the full Cloudstream application. */
object CommonActivity {
	private var activityRef: WeakReference<Activity>? = null

	val activity: Activity?
		get() = activityRef?.get()

	@MainThread
	fun setActivityInstance(newActivity: Activity?) {
		activityRef = newActivity?.let(::WeakReference)
	}
}
