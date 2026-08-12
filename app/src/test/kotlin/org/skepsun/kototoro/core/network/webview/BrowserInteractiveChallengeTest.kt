package org.skepsun.kototoro.core.network.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BrowserInteractiveChallengeTest {
	@Test
	fun `challenge id is unique per event`() {
		val first = BrowserInteractiveChallenge(
			sessionId = "session",
			origin = "https://kagane.to",
			requestUrl = "https://kagane.to/api/v2/search/series",
			method = "POST",
			displayUrl = "https://kagane.to/api/v2/search/series",
		)
		val second = first.copy(challengeId = java.util.UUID.randomUUID().toString())

		assertEquals("session", first.sessionId)
		assertEquals("POST", first.method)
		assertNotEquals(first.challengeId, second.challengeId)
	}

	@Test
	fun `challenge starts pending and terminal states are explicit`() {
		val challenge = BrowserInteractiveChallenge(
			sessionId = "session",
			origin = "https://kagane.to",
			requestUrl = "https://kagane.to/api/v2/search/series",
			method = "POST",
			displayUrl = "https://kagane.to/api/v2/search/series",
		)

		assertEquals(BrowserInteractiveChallengeState.PENDING, challenge.state)
		val attached = challenge.transitionTo(BrowserInteractiveChallengeState.ATTACHED)
		val resolved = attached.transitionTo(BrowserInteractiveChallengeState.RESOLVED)

		assertEquals(BrowserInteractiveChallengeState.ATTACHED, attached.state)
		assertEquals(true, resolved.isTerminal)
		assertThrows(IllegalStateException::class.java) {
			resolved.transitionTo(BrowserInteractiveChallengeState.ATTACHED)
		}
	}
}
