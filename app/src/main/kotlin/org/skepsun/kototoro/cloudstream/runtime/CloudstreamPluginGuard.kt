package org.skepsun.kototoro.cloudstream.runtime

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Keeps Cloudstream plugins from ending the app. Cloudstream accepts both, being a plugin host only; Kototoro is not:
 * - **Crashes.** Android ends the process on any uncaught error. One whose stack runs through a loaded plugin's
 *   classes is logged and dropped instead: on a background thread that thread ends, on the main thread the message
 *   loop goes on. Every other error crashes as before.
 * - **Restarts.** Plugin settings screens restart the app to apply what was saved: the launch intent with
 *   `Intent.makeRestartActivityTask`, then `Runtime.exit(0)` (CNC Verse; most settings screens do). Such a start from
 *   a plugin's code throws [PluginRestartRequested] before the exit runs, and the plugin is loaded again instead.
 *
 * Installed once per process, by the app's runtime manager.
 */
internal object CloudstreamPluginGuard {

	/** Thrown from a plugin's restart of the app; the plugin is reloaded instead. */
	class PluginRestartRequested(val plugin: String) : RuntimeException("Cloudstream plugin $plugin asked to restart the app")

	@Volatile
	private var pluginOf: (Throwable) -> String? = { null }

	@Volatile
	private var onRestartRequested: (String) -> Unit = {}

	@Volatile
	private var installed = false

	/**
	 * @param pluginOf the archive name of the loaded plugin whose code is on an error's stack, if any
	 * @param onRestartRequested reloads that plugin; called off the caller's stack, before its restart throws
	 */
	@Synchronized
	fun install(pluginOf: (Throwable) -> String?, onRestartRequested: (String) -> Unit) {
		if (installed) return
		installed = true
		this.pluginOf = pluginOf
		this.onRestartRequested = onRestartRequested

		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			if (thread === Looper.getMainLooper().thread || !isHandled(error, thread.name)) {
				previous?.uncaughtException(thread, error)
			}
		}
		// An error leaving Looper.loop() ends the main thread, so run the loop from inside a catch: messages then run
		// in this nested loop for the life of the process, and what it does not handle is thrown on as before.
		Handler(Looper.getMainLooper()).post {
			while (true) {
				try {
					Looper.loop()
				} catch (error: Throwable) {
					if (!isHandled(error, Looper.getMainLooper().thread.name)) throw error
				}
			}
		}
	}

	/** Stops a plugin's restart of the app; call from `startActivity` of the application and the activities. */
	fun checkRestart(context: Context, intent: Intent?) {
		if (!installed || !isRestart(context, intent)) return
		val plugin = pluginOf(Throwable()) ?: return
		// Told before it's thrown: a plugin catching every Throwable around its restart would hide it otherwise.
		Handler(Looper.getMainLooper()).post { onRestartRequested(plugin) }
		throw PluginRestartRequested(plugin)
	}

	/** Whether [intent] restarts this app: one of its own components, as a new task clearing the old one. */
	private fun isRestart(context: Context, intent: Intent?): Boolean {
		val component = intent?.component ?: return false
		return component.packageName == context.packageName && intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0
	}

	private fun isHandled(error: Throwable, threadName: String): Boolean {
		val restart = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)
			.filterIsInstance<PluginRestartRequested>()
			.firstOrNull()
		if (restart != null) return true
		val plugin = runCatching { pluginOf(error) }.getOrNull() ?: return false
		Log.e(TAG, "Cloudstream plugin $plugin crashed on thread $threadName; ignored", error)
		return true
	}

	private const val TAG = "CloudstreamGuard"
	private const val MAX_CAUSE_DEPTH = 8
}
