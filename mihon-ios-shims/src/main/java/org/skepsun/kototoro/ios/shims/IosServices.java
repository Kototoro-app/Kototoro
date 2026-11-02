package org.skepsun.kototoro.ios.shims;

import android.app.ActivityManager;

/**
 * The adapter compiles against Android's public API, where ActivityManager's constructor is hidden.
 */
public final class IosServices {
    private IosServices() {}

    public static Object activityManager() {
        return new ActivityManager();
    }

    public static Object displayManager() {
        return new android.hardware.display.DisplayManager();
    }
}
