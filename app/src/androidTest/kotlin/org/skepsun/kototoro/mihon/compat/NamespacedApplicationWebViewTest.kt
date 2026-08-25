package org.skepsun.kototoro.mihon.compat

import android.app.Application
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class NamespacedApplicationWebViewTest {

    @Test
    fun namespacedApplicationCanCreateWebView() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val context = NamespacedApplication(application)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WebView(context).destroy()
        }
    }
}
