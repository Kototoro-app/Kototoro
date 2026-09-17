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
        super.onStart()
    }
}
