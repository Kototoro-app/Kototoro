package org.skepsun.kototoro.core.network.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudFlareDetectionTest {
	@Test
	fun `document readiness requires stable completed non challenge page`() {
		val tracker = BrowserDocumentReadinessTracker(quietWindowMs = 1_500L)

		assertFalse(tracker.observe(CloudFlarePageState.LOADING, "complete", "https://example.com", 4, 0L))
		assertFalse(tracker.observe(CloudFlarePageState.NORMAL, "complete", "https://example.com", 5, 500L))
		assertFalse(tracker.observe(CloudFlarePageState.NORMAL, "complete", "https://example.com", 5, 1_500L))
		assertTrue(tracker.observe(CloudFlarePageState.NORMAL, "complete", "https://example.com", 5, 3_000L))
	}

	@Test
	fun `document readiness resets when resources or challenge navigation changes`() {
		val tracker = BrowserDocumentReadinessTracker(quietWindowMs = 1_000L)

		assertFalse(tracker.observe(CloudFlarePageState.NORMAL, "complete", "https://example.com", 2, 0L))
		assertFalse(tracker.observe(CloudFlarePageState.NORMAL, "complete", "https://example.com", 3, 1_000L))
		assertFalse(
			tracker.observe(
				CloudFlarePageState.NORMAL,
				"complete",
				"https://example.com?__cf_chl_rt_tk=token",
				3,
				2_000L,
			),
		)
		assertFalse(tracker.observe(CloudFlarePageState.NORMAL, "complete", "https://example.com", 3, 2_500L))
		assertTrue(tracker.observe(CloudFlarePageState.NORMAL, "complete", "https://example.com", 3, 3_500L))
	}

	@Test
	fun `managed challenge is not a stable document`() {
		val tracker = BrowserDocumentReadinessTracker(quietWindowMs = 1_000L)

		assertFalse(tracker.observe(CloudFlarePageState.MANAGED_CHALLENGE, "complete", "https://example.com", 1, 0L))
		assertFalse(tracker.observe(CloudFlarePageState.MANAGED_CHALLENGE, "complete", "https://example.com", 1, 2_000L))
	}

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
        assertEquals(CloudFlarePageState.NORMAL, parseCloudFlarePageState("\"normal\""))
        assertEquals(CloudFlarePageState.HARD_BLOCK, parseCloudFlarePageState("\"hard_block\""))
        assertEquals(CloudFlarePageState.INTERACTIVE_CHALLENGE, parseCloudFlarePageState("\"interactive\""))
        assertEquals(CloudFlarePageState.MANAGED_CHALLENGE, parseCloudFlarePageState("\"managed\""))
        assertEquals(CloudFlarePageState.LOADING, parseCloudFlarePageState("\"loading\""))
        assertEquals(CloudFlarePageState.LOADING, parseCloudFlarePageState(null))
        assertEquals(CloudFlarePageState.LOADING, parseCloudFlarePageState("unexpected"))
    }
}
