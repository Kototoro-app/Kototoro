package org.skepsun.kototoro.core.image

/** Budget for one animation's displayed and queued RGB frames; codec working memory is additional. */
data class AvifAnimationPolicy(
    val memoryBudgetBytes: Long,
    val allowDownsampling: Boolean = false,
) {
    init {
        require(memoryBudgetBytes > 0)
    }
}

/** Stable device capabilities, rather than momentary free RAM, determine the default. */
data class AvifAnimationDeviceProfile(
    val totalMemoryBytes: Long,
    val heapLimitBytes: Long,
    val isLowRam: Boolean,
) {
    val maxMemoryLimitMb: Int
        get() = minOf(512L, heapLimitBytes / MIB / 2, totalMemoryBytes / MIB / 16)
            .coerceAtLeast(16).toInt()

    val recommendedMemoryLimitMb: Int
        get() {
            val (divisor, ceiling) = when {
                isLowRam -> 12 to 32
                totalMemoryBytes < 6L * 1024 * MIB -> 6 to 96
                // Reported RAM excludes reserved memory; this covers nominal 12/16 GB devices.
                totalMemoryBytes >= 10L * 1024 * MIB -> 2 to 256
                else -> 4 to 256
            }
            val limit = minOf(heapLimitBytes / MIB / divisor, ceiling.toLong(), maxMemoryLimitMb.toLong())
            return (limit / 16 * 16).toInt().coerceAtLeast(16)
        }

    /** Zero means automatic, including after restoring settings onto a different device. */
    fun resolvePolicy(memoryLimitMb: Int, allowDownsampling: Boolean): AvifAnimationPolicy {
        val limit = if (memoryLimitMb <= 0) recommendedMemoryLimitMb else memoryLimitMb.coerceIn(16, maxMemoryLimitMb)
        return AvifAnimationPolicy(limit * MIB, allowDownsampling)
    }
}

private const val MIB = 1024L * 1024
