package org.skepsun.kototoro.core.nav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.R
import org.skepsun.kototoro.browser.BrowserActivity
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.TestContentSource

@RunWith(AndroidJUnit4::class)
class AppRouterCloudFlareIntentTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun cloudFlareResolveIntent_includesClearanceCookieContractAndNavigationMetadata() {
        val intent = AppRouter.cloudFlareResolveIntent(
            context,
            cloudFlareException("https://reader.example.com/protected/chapter?token=secret"),
        )
        val challengeUrl = "https://reader.example.com/"

        assertEquals(BrowserActivity::class.java.name, intent.component?.className)
        assertEquals(challengeUrl, intent.dataString)
        assertEquals(challengeUrl, intent.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_URL))
        assertEquals("cf_clearance", intent.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_NAME))
        assertEquals(TestContentSource.name, intent.getStringExtra(AppRouter.KEY_SOURCE))
        assertEquals("test-user-agent", intent.getStringExtra(AppRouter.KEY_USER_AGENT))
        assertEquals(
            context.getString(R.string.open_in_reader_browser),
            intent.getStringExtra(AppRouter.KEY_TITLE),
        )
    }

    @Test
    fun cloudFlareResolveIntent_usesRootDomainForAssetDataAndCookieUrl() {
        val intent = AppRouter.cloudFlareResolveIntent(
            context,
            cloudFlareException("https://cdn.example.com/covers/chapter.webp?token=secret"),
        )
        val challengeUrl = "https://example.com/"

        assertEquals(challengeUrl, intent.dataString)
        assertEquals(challengeUrl, intent.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_URL))
        assertEquals("cf_clearance", intent.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_NAME))
    }

    private fun cloudFlareException(url: String) = CloudFlareProtectedException(
        url = url,
        source = TestContentSource,
        headers = Headers.headersOf("User-Agent", "test-user-agent"),
    )
}
