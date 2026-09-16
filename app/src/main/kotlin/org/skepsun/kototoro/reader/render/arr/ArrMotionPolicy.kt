package org.skepsun.kototoro.reader.render.arr

import android.os.Build
import android.view.View
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import org.skepsun.kototoro.reader.core.ViewportMotion

/**
 * Target refresh rate preference requested by reader motion telemetry.
 */
enum class RefreshRatePreference {
    HIGH,    // Typically 120Hz/144Hz for dragging or high-speed fling
    NORMAL,  // Typically 60Hz for low-speed smooth scrolling
    LOW,     // Idle / power saving (e.g. 30Hz or down to 1Hz LTPO)
}

/**
 * Pure policy resolving ARR (Adaptive Refresh Rate) preference from [ViewportMotion] (ADR 0002).
 */
object ArrMotionPolicy {
    const val HIGH_SPEED_THRESHOLD_PX_PER_SEC = 300f

    fun resolvePreference(motion: ViewportMotion): RefreshRatePreference {
        return when {
            motion.isDragging -> RefreshRatePreference.HIGH
            motion.speed >= HIGH_SPEED_THRESHOLD_PX_PER_SEC -> RefreshRatePreference.HIGH
            motion.speed > 0f -> RefreshRatePreference.NORMAL
            else -> RefreshRatePreference.LOW
        }
    }
}

/**
 * Platform helper to apply ARR preferences to Android [View] on supported Android versions (Android 15+ / API 35).
 */
object AdaptiveRefreshRateHelper {

    fun applyPreference(view: View, motion: ViewportMotion) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val pref = ArrMotionPolicy.resolvePreference(motion)
            Api35Impl.applyFrameRate(view, pref, motion.speed)
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private object Api35Impl {
        @DoNotInline
        fun applyFrameRate(view: View, preference: RefreshRatePreference, speed: Float) {
            val rateCategory = when (preference) {
                RefreshRatePreference.HIGH -> View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
                RefreshRatePreference.NORMAL -> View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL
                RefreshRatePreference.LOW -> View.REQUESTED_FRAME_RATE_CATEGORY_LOW
            }
            view.setRequestedFrameRate(rateCategory)
            view.setFrameContentVelocity(speed)
        }
    }
}
