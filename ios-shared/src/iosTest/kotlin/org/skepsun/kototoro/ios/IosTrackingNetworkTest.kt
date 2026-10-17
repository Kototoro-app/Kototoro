package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.skepsun.kototoro.tracking.discovery.PublicTrackingApi
import org.skepsun.kototoro.tracking.discovery.PublicTrackingSite
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in integration probe; it never sends account credentials or modifies a remote list. */
class IosTrackingNetworkTest {
    @Test
    fun publicBangumiSearchUsesTheNativeTransport() {
        if (NSProcessInfo.processInfo.environment["KOTOTORO_TRACKING_ONLINE"] != "1") return
        runBlocking {
            val items = withTimeout(8_000) {
                val base = NSProcessInfo.processInfo.environment["KOTOTORO_TRACKING_BANGUMI_API_BASE"] as? String
                    ?: "https://api.bgm.tv"
                PublicTrackingApi(IosTracking.client, bangumiBaseUrl = base)
                    .search(PublicTrackingSite.BANGUMI, "葬送的芙莉莲", "MANGA")
            }
            assertTrue(items.isNotEmpty(), "The real native catalog transport must return the requested work")
        }
    }
}
