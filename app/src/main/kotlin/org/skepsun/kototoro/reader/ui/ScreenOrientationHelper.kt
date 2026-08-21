package org.skepsun.kototoro.reader.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject

@ActivityScoped
class ScreenOrientationHelper @Inject constructor(
    private val activity: Activity,
    private val settings: AppSettings,
) {

    /**
     * Android 17 (targetSdk 37) ignores orientation locks and resizeability restrictions on
     * large screens (>= 600dp smallest width). Mirror that behavior on every API level so
     * large-screen windows always resize-driven layouts instead of `requestedOrientation`.
     */
    private val isLargeScreen: Boolean
        get() {
            val config = activity.resources.configuration
            return minOf(config.screenWidthDp, config.screenHeightDp) >= 600
        }

    val isAutoRotationEnabled: Boolean
        get() = Settings.System.getInt(
            activity.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0,
        ) == 1

    var isLandscape: Boolean
        get() = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        set(value) {
            if (isLargeScreen) return
            activity.requestedOrientation = if (value) {
                if (settings.videoLandscapeSensorEnabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            }
        }

    var isLocked: Boolean
        get() = activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED
        set(value) {
            if (isLargeScreen) return
            activity.requestedOrientation = if (value) {
                ActivityInfo.SCREEN_ORIENTATION_LOCKED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

    fun applySettings() {
        if (isLargeScreen) return
        if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            // https://developer.android.com/reference/android/R.attr.html#screenOrientation
            activity.requestedOrientation = settings.readerScreenOrientation
        }
    }

    fun observeAutoOrientation() = callbackFlow {
        val observer = object : ContentObserver(Handler(activity.mainLooper)) {
            override fun onChange(selfChange: Boolean) {
                trySendBlocking(isAutoRotationEnabled)
            }
        }
        activity.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, observer,
        )
        awaitClose {
            activity.contentResolver.unregisterContentObserver(observer)
        }
    }.onStart {
        emit(isAutoRotationEnabled)
    }.distinctUntilChanged()
        .conflate()

    fun toggleScreenOrientation(): Boolean = if (isAutoRotationEnabled) {
        val newValue = !isLocked
        isLocked = newValue
        true
    } else {
        isLandscape = !isLandscape
        false
    }
}
