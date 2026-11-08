package org.skepsun.kototoro.cloudstream.desktop

/** A plugin asked to end the process; on Windows that would close Kototoro, so it is refused. */
class PluginExitRequested(call: String) : RuntimeException("Cloudstream plugin called $call; Kototoro keeps running")

/**
 * Where a converted plugin's process exits are sent instead. Plugins restart Cloudstream to apply their settings:
 * the launch intent, then `Runtime.exit(0)` (46 of 444 surveyed plugins call it). On Android the host stops the
 * restart before the exit runs; on Windows the exit itself is rewritten ([PluginBytecodeRewriter]) to throw.
 */
object PluginExits {
	@JvmStatic
	fun exit(@Suppress("UNUSED_PARAMETER") status: Int): Unit = throw PluginExitRequested("System.exit")

	@JvmStatic
	fun exit(@Suppress("UNUSED_PARAMETER") runtime: Runtime, @Suppress("UNUSED_PARAMETER") status: Int): Unit =
		throw PluginExitRequested("Runtime.exit")

	@JvmStatic
	fun halt(@Suppress("UNUSED_PARAMETER") runtime: Runtime, @Suppress("UNUSED_PARAMETER") status: Int): Unit =
		throw PluginExitRequested("Runtime.halt")

	@JvmStatic
	fun exitProcess(@Suppress("UNUSED_PARAMETER") status: Int): Void = throw PluginExitRequested("exitProcess")

	@JvmStatic
	fun killProcess(@Suppress("UNUSED_PARAMETER") pid: Int): Unit = throw PluginExitRequested("Process.killProcess")
}
