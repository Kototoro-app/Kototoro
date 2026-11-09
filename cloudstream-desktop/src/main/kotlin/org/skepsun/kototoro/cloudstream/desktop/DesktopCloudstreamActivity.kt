package org.skepsun.kototoro.cloudstream.desktop

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import com.lagradost.cloudstream3.MainActivity
import sun.reflect.ReflectionFactory

/**
 * What Windows hands a plugin's `load(context)`: Cloudstream passes its `MainActivity` there, and plugins cast to it or
 * to `AppCompatActivity` (CNC Verse), which a plain compatibility Context fails. It is that activity over the
 * compatibility Context: every Context call goes to [base].
 *
 * The compatibility runtime's activity classes are stubs whose constructors throw, so the instance is made without
 * running them (as deserialization does) and given its base directly, and the stubbed overrides of
 * `ContextThemeWrapper` and `Activity` are routed back to the real `ContextWrapper` behaviour.
 */
internal class DesktopCloudstreamActivity private constructor() : MainActivity() {

	override fun getAssets(): AssetManager = baseContext.assets

	override fun getResources(): Resources = baseContext.resources

	override fun setTheme(resid: Int) = baseContext.setTheme(resid)

	override fun getTheme(): Resources.Theme = baseContext.theme

	// Plugins size their work by the device's memory (StreamPlay); the other services stay the compatibility Context's.
	override fun getSystemService(name: String): Any? =
		if (name == Context.ACTIVITY_SERVICE) DesktopActivityManager.service() else baseContext.getSystemService(name)

	override fun startActivity(intent: Intent?) = baseContext.startActivity(intent)

	override fun startActivity(intent: Intent?, options: Bundle?) = baseContext.startActivity(intent, options)

	companion object {
		fun over(base: Context): DesktopCloudstreamActivity {
			val constructor = ReflectionFactory.getReflectionFactory().newConstructorForSerialization(
				DesktopCloudstreamActivity::class.java,
				Any::class.java.getDeclaredConstructor(),
			)
			val activity = constructor.newInstance() as DesktopCloudstreamActivity
			// `Activity` stubs `attachBaseContext` too, so the base is set where `ContextWrapper` keeps it.
			ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(activity, base)
			return activity
		}
	}
}
