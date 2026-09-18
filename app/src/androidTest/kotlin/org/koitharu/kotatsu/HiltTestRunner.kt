package org.skepsun.kototoro

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }

    override fun onStart() {
        // The target package may have persisted WorkManager jobs from a previous app run, while
        // HiltTestApplication intentionally does not provide the production worker factory.
        // Initialize the minimal test configuration before those jobs can launch a service.
        runCatching {
            WorkManager.initialize(
                targetContext,
                Configuration.Builder().build(),
            )
        }
        runCatching {
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.executeShellCommand("appops set ${targetContext.packageName} 10021 allow")
            automation.executeShellCommand("appops set ${context.packageName} 10021 allow")
            automation.executeShellCommand("appops set ${targetContext.packageName} SYSTEM_ALERT_WINDOW allow")
            automation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            automation.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            automation.executeShellCommand("wm dismiss-keyguard")
        }
        super.onStart()
    }
}
