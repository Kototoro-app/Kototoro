package org.skepsun.kototoro.cloudstream.desktop

import android.app.ActivityManager
import sun.reflect.ReflectionFactory
import java.lang.management.ManagementFactory

/**
 * `ActivityManager.MemoryInfo` as plugins read it on Windows. Plugins size their work by the device's memory
 * (StreamPlay picks its concurrency from `totalMem` when it loads), and the compatibility runtime's class is a stub
 * whose constructor throws, so converted plugins use this class instead ([PluginBytecodeRewriter]): same public fields.
 */
class DesktopMemoryInfo {
	@JvmField var advertisedMem: Long = 0

	@JvmField var availMem: Long = 0

	@JvmField var totalMem: Long = 0

	@JvmField var threshold: Long = 0

	@JvmField var lowMemory: Boolean = false
}

/** What a plugin's `ActivityManager` calls reach on Windows, with the machine's own figures. */
object DesktopActivityManager {
	/**
	 * The `activity` system service a plugin asks its context for. Its class is a stub whose constructor throws, so it
	 * is made without running it; the calls plugins make on it are rewritten to this object.
	 */
	fun service(): ActivityManager = ReflectionFactory.getReflectionFactory()
		.newConstructorForSerialization(ActivityManager::class.java, Any::class.java.getDeclaredConstructor())
		.newInstance() as ActivityManager

	/** `ActivityManager.getMemoryInfo(info)`: physical memory as Android reports it, low below a tenth free. */
	@JvmStatic
	fun getMemoryInfo(@Suppress("UNUSED_PARAMETER") manager: ActivityManager, info: DesktopMemoryInfo) {
		val system = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
		val total = system?.totalMemorySize ?: Runtime.getRuntime().maxMemory()
		val free = system?.freeMemorySize ?: Runtime.getRuntime().freeMemory()
		info.totalMem = total
		info.advertisedMem = total
		info.availMem = free
		info.threshold = total / LOW_MEMORY_DIVISOR
		info.lowMemory = free < info.threshold
	}

	private const val LOW_MEMORY_DIVISOR = 10
}
