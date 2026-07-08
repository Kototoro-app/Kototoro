package org.skepsun.kototoro.main.ui.protect

import android.app.Application
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import org.acra.dialog.CrashReportDialog
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.DefaultActivityLifecycleCallbacks
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppProtectHelper @Inject constructor(
	@ApplicationContext context: Context,
	private val settings: AppSettings,
) : DefaultActivityLifecycleCallbacks,
	ComponentCallbacks2 {

	private var isUnlocked = settings.appPassword.isNullOrEmpty()
	private var isProtecting = false

	init {
		(context.applicationContext as Application).registerComponentCallbacks(this)
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		protectIfNeeded(activity)
	}

	private fun protectIfNeeded(activity: Activity) {
		val shouldProtect = !isUnlocked &&
			!isProtecting &&
			activity !is ProtectActivity &&
			activity !is CrashReportDialog
		if (shouldProtect) {
			isProtecting = true
			val sourceIntent = Intent(activity, activity.javaClass)
			activity.intent?.let {
				sourceIntent.putExtras(it)
				sourceIntent.action = it.action
				sourceIntent.setDataAndType(it.data, it.type)
			}
			activity.startActivity(ProtectActivity.newIntent(activity, sourceIntent))
			activity.finishAfterTransition()
		}
	}

	override fun onActivityResumed(activity: Activity) {
		protectIfNeeded(activity)
	}

	fun unlock() {
		isProtecting = false
		isUnlocked = true
	}

	override fun onTrimMemory(level: Int) {
		if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
			restoreLock()
		}
	}

	override fun onConfigurationChanged(newConfig: Configuration) = Unit

	@Suppress("OVERRIDE_DEPRECATION")
	override fun onLowMemory() = Unit

	private fun restoreLock() {
		isProtecting = false
		isUnlocked = settings.appPassword.isNullOrEmpty()
	}
}
