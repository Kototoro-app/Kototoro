package org.skepsun.kototoro.core.network.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BrowserChallengeContextTest {
	@Test
	fun `post challenge navigates the visible resolver to the origin root`() {
		val context = BrowserChallengeContext.create(
			requestUrl = "https://kagane.to/api/v2/search/series?page=0",
			method = "POST",
			responseHtml = "challenge",
		)!!

		assertEquals("https://kagane.to", context.origin)
		assertEquals("https://kagane.to/", context.navigationUrl)
	}

	@Test
	fun `challenge diagnostic snippet is bounded`() {
		val html = "x".repeat(BrowserChallengeContext.MAX_HTML_SNIPPET_CHARS + 1)

		val context = BrowserChallengeContext.create("https://example.com/api", "GET", html)!!

		assertEquals(BrowserChallengeContext.MAX_HTML_SNIPPET_CHARS, context.responseHtmlSnippet.length)
		assertEquals("https://example.com/api", context.navigationUrl)
	}

	@Test
	fun `unsupported method is rejected`() {
		assertNull(BrowserChallengeContext.create("https://example.com/api", "PUT", "challenge"))
	}

	@Test
	fun `existing clearance before challenge is not resolution evidence`() {
		val tracker = BrowserChallengeResolutionTracker()

		assertNull(tracker.observe(CloudFlarePageState.OK, hasClearance = true, clearanceChanged = false))
	}

	@Test
	fun `challenge followed by normal page and clearance is resolution evidence`() {
		val tracker = BrowserChallengeResolutionTracker()

		assertNull(tracker.observe(CloudFlarePageState.INTERACTIVE, hasClearance = true, clearanceChanged = false))
		assertEquals(
			BrowserResolutionEvidence.CHALLENGE_FLOW_REACHED_NORMAL_PAGE,
			tracker.observe(CloudFlarePageState.OK, hasClearance = true, clearanceChanged = false),
		)
	}

	@Test
	fun `transient wait followed by ok with old clearance is not resolution evidence`() {
		val tracker = BrowserChallengeResolutionTracker()

		assertNull(tracker.observe(CloudFlarePageState.WAIT, hasClearance = true, clearanceChanged = false))
		assertNull(tracker.observe(CloudFlarePageState.OK, hasClearance = true, clearanceChanged = false))
	}

	@Test
	fun `cloudflare token navigation followed by ok with existing clearance is evidence`() {
		val tracker = BrowserChallengeResolutionTracker()

		assertNull(
			tracker.observe(
				CloudFlarePageState.WAIT,
				hasClearance = true,
				clearanceChanged = false,
				currentUrl = "https://kagane.to/api?__cf_chl_rt_tk=token",
			),
		)
		assertEquals(
			BrowserResolutionEvidence.CHALLENGE_FLOW_REACHED_NORMAL_PAGE,
			tracker.observe(
				CloudFlarePageState.OK,
				hasClearance = true,
				clearanceChanged = false,
				currentUrl = "https://kagane.to/api",
			),
		)
	}

	@Test
	fun `post challenge does not accept plain wait followed by ok`() {
		val tracker = BrowserChallengeResolutionTracker()

		assertNull(
			tracker.observe(
				CloudFlarePageState.WAIT,
				hasClearance = true,
				clearanceChanged = false,
				requiresInteractiveResolution = true,
			),
		)
		assertNull(
			tracker.observe(
				CloudFlarePageState.OK,
				hasClearance = true,
				clearanceChanged = false,
				requiresInteractiveResolution = true,
			),
		)
	}

	@Test
	fun `post challenge accepts normal page without requiring a clearance cookie`() {
		val tracker = BrowserChallengeResolutionTracker()

		assertNull(
			tracker.observe(
				CloudFlarePageState.INTERACTIVE,
				hasClearance = false,
				clearanceChanged = false,
				requiresInteractiveResolution = true,
			),
		)
		assertEquals(
			BrowserResolutionEvidence.CHALLENGE_FLOW_REACHED_NORMAL_PAGE,
			tracker.observe(
				CloudFlarePageState.OK,
				hasClearance = false,
				clearanceChanged = false,
				requiresInteractiveResolution = true,
			),
		)
	}

	@Test
	fun `post challenge accepts token navigation when interactive DOM state is missed`() {
		val tracker = BrowserChallengeResolutionTracker()

		assertNull(
			tracker.observe(
				CloudFlarePageState.WAIT,
				hasClearance = true,
				clearanceChanged = false,
				currentUrl = "https://kagane.to/api?__cf_chl_rt_tk=token",
				requiresInteractiveResolution = true,
			),
		)
		assertEquals(
			BrowserResolutionEvidence.CHALLENGE_FLOW_REACHED_NORMAL_PAGE,
			tracker.observe(
				CloudFlarePageState.OK,
				hasClearance = false,
				clearanceChanged = false,
				currentUrl = "https://kagane.to/api",
				requiresInteractiveResolution = true,
			),
		)
	}

	@Test
	fun `get challenge may resolve via GET challenge navigation or new clearance`() {
		val tracker = BrowserChallengeResolutionTracker()

		assertNull(
			tracker.observe(
				CloudFlarePageState.WAIT,
				hasClearance = true,
				clearanceChanged = false,
				currentUrl = "https://kagane.to/api?__cf_chl_rt_tk=token",
			),
		)
		assertEquals(
			BrowserResolutionEvidence.CHALLENGE_FLOW_REACHED_NORMAL_PAGE,
			tracker.observe(
				CloudFlarePageState.OK,
				hasClearance = true,
				clearanceChanged = false,
				currentUrl = "https://kagane.to/api",
			),
		)
	}
}
