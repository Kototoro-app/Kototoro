package org.skepsun.kototoro.core.network.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudFlareDetectionTest {

    @Test
    fun `normal Cloudflare background script is not a challenge selector`() {
        assertFalse(CF_CHALLENGE_SELECTOR.contains("challenge-platform"))
        assertTrue(CF_CHALLENGE_SELECTOR.contains("#challenge-stage"))
        assertTrue(CF_CHALLENGE_SELECTOR.contains("#turnstile-wrapper"))
        assertTrue(CF_CHALLENGE_SELECTOR.contains("iframe[src*='challenges.cloudflare.com']"))
        assertTrue(CF_CHALLENGE_SELECTOR.contains("input[name='cf-turnstile-response']"))
    }

    @Test
    fun `parses WebView callback values conservatively`() {
        assertEquals(CloudFlarePageState.OK, parseCloudFlarePageState("\"ok\""))
        assertEquals(CloudFlarePageState.ERROR, parseCloudFlarePageState("\"error\""))
        assertEquals(CloudFlarePageState.INTERACTIVE, parseCloudFlarePageState("\"interactive\""))
        assertEquals(CloudFlarePageState.WAIT, parseCloudFlarePageState("\"wait\""))
        assertEquals(CloudFlarePageState.WAIT, parseCloudFlarePageState(null))
        assertEquals(CloudFlarePageState.WAIT, parseCloudFlarePageState("unexpected"))
    }
}
