package org.skepsun.kototoro.video.performance

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

enum class DevicePerformanceTier {
    LOW,
    MID,
    HIGH,
}

data class DevicePerformanceInfo(
    val tier: DevicePerformanceTier,
    val score: Int,
    val totalRamMb: Long,
    val cpuCores: Int,
    val isLowRamDevice: Boolean,
    val isTv: Boolean,
    val is32Bit: Boolean,
)

object DevicePerformanceClassifier {

    fun classify(context: Context): DevicePerformanceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val totalRamMb = memoryInfo.totalMem / 1024L / 1024L
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val isLowRamDevice = activityManager.isLowRamDevice
        val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val is32Bit = Build.SUPPORTED_64_BIT_ABIS.isEmpty()

        var score = 0
        if (isLowRamDevice) score += 3
        if (totalRamMb in 1..3072) score += 2
        if (cpuCores <= 4) score += 1
        if (is32Bit) score += 1
        if (isTv) score += 1

        val tier = when {
            score >= 4 -> DevicePerformanceTier.LOW
            score >= 2 -> DevicePerformanceTier.MID
            else -> DevicePerformanceTier.HIGH
        }

        return DevicePerformanceInfo(
            tier = tier,
            score = score,
            totalRamMb = totalRamMb,
            cpuCores = cpuCores,
            isLowRamDevice = isLowRamDevice,
            isTv = isTv,
            is32Bit = is32Bit,
        )
    }
}
