package org.skepsun.kototoro.core.parser.tvbox

import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.atomic.AtomicBoolean

internal object TVBoxHiddenApiCompat {

	private const val TAG = "TVBoxJarRuntime"
	private val activityLookupEnabled = AtomicBoolean(false)

	fun enableActivityLookup() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !activityLookupEnabled.compareAndSet(false, true)) {
			return
		}
		val enabled = runCatching {
			HiddenApiBypass.addHiddenApiExemptions(
				"Landroid/app/ActivityThread;",
				"Landroid/app/ActivityThread\$ActivityClientRecord;",
			)
		}.onFailure {
			Log.w(TAG, "Unable to enable TVBox ActivityThread compatibility", it)
		}.getOrDefault(false)
		if (enabled) {
			Log.i(TAG, "Enabled scoped TVBox ActivityThread compatibility")
		} else {
			activityLookupEnabled.set(false)
			Log.w(TAG, "TVBox ActivityThread compatibility was rejected by ART")
		}
	}
}
