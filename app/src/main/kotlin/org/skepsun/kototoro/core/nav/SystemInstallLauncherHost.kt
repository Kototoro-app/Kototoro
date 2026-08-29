package org.skepsun.kototoro.core.nav

import android.content.Intent

/**
 * Hosts that can show the wizard and dispatch SYSTEM-mode APK installs.
 *
 * SYSTEM-mode package installs must be launched from an Activity via
 * [androidx.activity.result.ActivityResultLauncher], and the active install
 * session is unblocked by [org.skepsun.kototoro.extensions.install.ExtensionInstallService.onInstallerActivityReturned].
 * Activities that open the welcome wizard implement this so the wizard can
 * launch the system installer without owning a launcher itself.
 */
interface SystemInstallLauncherHost {

    fun launchSystemInstall(intent: Intent)
}
